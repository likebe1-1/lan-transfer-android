package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 单个远端文件的多通道并行下载（JSch 版）。
 *
 * 背景：JSch 的 ChannelSftp 单条通道的吞吐受窗口/包大小限制（本地最大包 32 KiB，不可调），
 * 单个大文件走一条通道经常跑不满局域网带宽。这里把同一个远端文件按 [chunkSize] 切成若干段，
 * 用一个独立 SSH [Session] 并行开 N 条 [ChannelSftp]，每条通道用
 * get(src, dst, monitor, RESUME, skip) 从自己的 offset 开始读，只读本段长度，
 * 再把数据按 offset 随机写入本地文件的对应位置。
 *
 * 关键技术结论（已按 JSch 0.2.26 源码核实并在真实 SFTP 走线测试中验证）：
 * - get(..., RESUME, skip)：JSch 的 _get 只在 mode == RESUME 时把 skip 计入起始 offset，
 *   所以分段读必须传 [ChannelSftp.RESUME]，否则 skip 被忽略、每段都从 0 开始读；
 * - 读到本段末尾即停止：不需要抛异常/关闭通道这种粗暴手段。JSch 在
 *   SftpProgressMonitor.count() 返回 false 时会 skip 掉当前包剩余数据、跳出读取循环、
 *   正常发 SSH_FXP_CLOSE 并返回——属于协议上的优雅停止，不会产生协议错误或通道泄漏；
 * - get(..., RESUME, skip) 在读取前会先回调一次 count(skip) 做进度补偿，
 *   本类的 [ChunkStopMonitor] 会忽略这第一次计数，避免把偏移当成已传输数据；
 * - 最后一次 dst.write 可能越过本段末尾（最多一个 SFTP 数据包）：[ChunkOutputStream]
 *   会截断写入，超出部分丢弃，由相邻段自己写入，因此不会互相覆盖。
 *
 * 本地写入选择 [OrderedRangeWriter]（RandomAccessFile + 全局锁）而不是「分段临时文件后合并」：
 * 前者只需一个目标文件、内存与磁盘峰值都是 1 份文件大小，失败时也能直接看到已写区间；
 * 后者需要 N 份临时文件再串行合并，额外占用一份磁盘空间和一次全量拷贝。
 *
 * 小文件（小于 [SftpChunkPlanner.MIN_MULTICHANNEL_SIZE]）或显式 channels = 1 时走单通道，
 * 避免多通道握手与调度开销。
 *
 * 使用约定：
 * - [download] 会自行建立/关闭 SSH Session 与全部 Channel，可对同一实例多次调用；
 * - 同一实例同一时刻只支持一个进行中的 [download]（内部只跟踪单个 Session/写入器），
 *   并发下载请各用一个实例；
 * - 取消：任意线程调用 [cancel] 或 [close]，下载线程会在当前数据包边界停止并抛
 *   [TransferCancelledException]；[cancel] 同时断开 Session 以唤醒可能阻塞在 socket 读上的线程；
 * - 阻塞实现，[download] 内部切到 [Dispatchers.IO]。
 */
class SftpMultichannel(
    val profile: ServerProfile,
    val chunkSize: Long = SftpChunkPlanner.DEFAULT_CHUNK_SIZE,
    val channels: Int = SftpChunkPlanner.DEFAULT_CHANNELS
) : Closeable {

    init {
        require(chunkSize > 0L) { "分块大小必须为正数：" + chunkSize }
        require(channels >= 1) { "通道数必须为正数：" + channels }
    }

    @Volatile
    private var cancelled = false

    @Volatile
    private var closed = false

    @Volatile
    private var session: Session? = null

    @Volatile
    private var activeWriter: OrderedRangeWriter? = null

    private val openChannels = ConcurrentLinkedQueue<ChannelSftp>()

    /**
     * 并行下载 [remotePath] 到本地 [target]。
     *
     * 远端文件大小由 SFTP stat 获取后生成分块计划；目标文件会被预置为远端大小并按 offset 写入。
     * 若下载失败/取消，目标文件保留已写入的内容（调用方可自行删除或续传）。
     *
     * @param onProgress 已写入本地的总字节数回调。回调在写锁内串行触发，数值单调递增。
     * @return 下载统计（文件大小、分块数、实际使用的通道数、耗时）
     */
    suspend fun download(
        remotePath: String,
        target: File,
        onProgress: (Long) -> Unit = {}
    ): MultichannelDownloadResult = withContext(Dispatchers.IO) {
        downloadBlocking(remotePath, target, onProgress)
    }

    /** 取消进行中的下载：标记取消、唤醒读阻塞并让各通道在本段边界优雅停止。 */
    fun cancel() {
        cancelled = true
        activeWriter?.cancel()
        openChannels.forEach { channel -> runCatching { channel.disconnect() } }
        runCatching { session?.disconnect() }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelled = true
        activeWriter?.cancel()
        openChannels.forEach { channel -> runCatching { channel.disconnect() } }
        runCatching { session?.disconnect() }
        activeWriter?.let { writer -> runCatching { writer.close() } }
        activeWriter = null
    }

    // ------------------------------------------------------------ 下载主流程

    private fun downloadBlocking(
        remotePath: String,
        target: File,
        onProgress: (Long) -> Unit
    ): MultichannelDownloadResult {
        checkNotCancelled()
        val startedAtNanos = System.nanoTime()
        val remote = RemotePath.normalize(remotePath)
        val sshSession = connectSession()
        val channelList = ArrayList<ChannelSftp>()
        var writer: OrderedRangeWriter? = null
        try {
            val probe = openChannel(sshSession)
            channelList.add(probe)
            val fileSize = statSize(probe, remote)
            if (fileSize == 0L) {
                prepareEmptyTarget(target)
                return MultichannelDownloadResult(
                    fileSize = 0L,
                    chunkCount = 0,
                    usedChannels = 0,
                    elapsedMillis = elapsedMillis(startedAtNanos)
                )
            }

            val plan = SftpChunkPlanner.plan(fileSize, chunkSize, channels)
            val currentWriter = OrderedRangeWriter(target, fileSize, plan.size)
            currentWriter.onProgress = onProgress
            writer = currentWriter
            activeWriter = currentWriter

            val workerCount = minOf(plan.size, SftpChunkPlanner.effectiveChannels(channels))
            while (channelList.size < workerCount) {
                checkNotCancelled()
                channelList.add(openChannel(sshSession))
            }

            runWorkers(remote, plan, channelList, workerCount, currentWriter)

            if (cancelled || currentWriter.isCancelled) throw TransferCancelledException()
            if (!currentWriter.allChunksComplete()) {
                throw ConnectionException(
                    "多通道下载未完成：「" + remote + "」已完成 " +
                        currentWriter.completedChunkCount() + "/" + plan.size + " 段"
                )
            }
            currentWriter.close()
            writer = null
            activeWriter = null
            return MultichannelDownloadResult(
                fileSize = fileSize,
                chunkCount = plan.size,
                usedChannels = workerCount,
                elapsedMillis = elapsedMillis(startedAtNanos)
            )
        } finally {
            runCatching { writer?.close() }
            activeWriter = null
            channelList.forEach { channel -> runCatching { channel.disconnect() } }
            openChannels.removeAll(channelList.toSet())
            runCatching { sshSession.disconnect() }
            session = null
        }
    }

    /** 多个 worker 共享一个分块游标（动态调度），每条 worker 独占一条已连接的 SFTP 通道。 */
    private fun runWorkers(
        remote: String,
        plan: List<SftpChunk>,
        channelList: List<ChannelSftp>,
        workerCount: Int,
        writer: OrderedRangeWriter
    ) {
        val nextChunk = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val pool: ExecutorService = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "sftp-multichannel-reader").apply { isDaemon = true }
        }
        val futures = ArrayList<Future<*>>(workerCount)
        try {
            for (workerIndex in 0 until workerCount) {
                val channel = channelList[workerIndex]
                futures.add(
                    pool.submit {
                        while (true) {
                            if (cancelled || writer.isCancelled || failure.get() != null) break
                            val index = nextChunk.getAndIncrement()
                            if (index >= plan.size) break
                            try {
                                downloadChunk(channel, remote, plan[index], writer)
                            } catch (e: Throwable) {
                                // 记录第一个失败原因并让其余 worker 在下个数据包边界停下
                                failure.compareAndSet(null, e)
                                writer.cancel()
                                break
                            }
                        }
                    }
                )
            }
            futures.forEach { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    failure.compareAndSet(null, e.cause ?: e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failure.compareAndSet(null, e)
                    writer.cancel()
                }
            }
        } finally {
            pool.shutdown()
            runCatching { pool.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        if (cancelled) throw TransferCancelledException()
        failure.get()?.let { error -> throw toTransferException(error, remote) }
    }

    /** 下载单个分块：从 chunk.offset 开始读，累计读满 chunk.length 后由 monitor 优雅停止。 */
    private fun downloadChunk(
        channel: ChannelSftp,
        remote: String,
        chunk: SftpChunk,
        writer: OrderedRangeWriter
    ) {
        if (chunk.length == 0L) {
            writer.markChunkComplete(chunk.index)
            return
        }
        val sink = ChunkOutputStream(writer, chunk)
        val monitor = ChunkStopMonitor(chunk, sink, cancelledProvider = { cancelled })
        try {
            // 仅 RESUME 模式会让 JSch 把 skip 计入起始 offset；跳过 JSch 在读取前的 count(skip) 补偿
            channel.get(remote, sink, monitor, ChannelSftp.RESUME, chunk.offset)
        } catch (e: SftpException) {
            if (cancelled || writer.isCancelled) throw TransferCancelledException()
            throw sftpFailure("下载文件", remote, e)
        } catch (e: IOException) {
            if (cancelled || writer.isCancelled) throw TransferCancelledException()
            throw ioFailure("下载文件", remote, e)
        } catch (e: JSchException) {
            if (cancelled || writer.isCancelled) throw TransferCancelledException()
            throw operationFailure("下载文件", remote, e)
        }
        if (cancelled || writer.isCancelled) throw TransferCancelledException()
        if (sink.writtenBytes != chunk.length) {
            throw ConnectionException(
                "分块下载不完整：「" + remote + "」段 " + chunk.index +
                    " 期望 " + chunk.length + " 字节，实际 " + sink.writtenBytes + " 字节"
            )
        }
        writer.markChunkComplete(chunk.index)
    }

    // ------------------------------------------------------------ SSH 连接

    private fun checkNotCancelled() {
        if (cancelled || closed) throw TransferCancelledException()
    }

    private fun connectSession(): Session {
        val pinnedHostKey = profile.pinnedSshHostKeySha256
            ?.takeIf { SshHostKeyPinning.isValidFingerprint(it) }
        val jsch = JSch().apply {
            if (pinnedHostKey != null) {
                // 快速传文件：只接受发送方广告的主机密钥指纹，防止中间人冒充
                hostKeyRepository = SshHostKeyPinning.PinnedRepository(pinnedHostKey)
            }
        }
        val created = try {
            jsch.getSession(profile.username, profile.host, profile.port).apply {
                setPassword(profile.password)
                // 有指纹时严格校验；无指纹（普通服务器）沿用首次信任
                setConfig("StrictHostKeyChecking", if (pinnedHostKey != null) "yes" else "no")
                setConfig("PreferredAuthentications", "password")
                setTimeout(READ_TIMEOUT_MS)
                connect(CONNECT_TIMEOUT_MS)
            }
        } catch (e: JSchException) {
            throw connectFailure(e)
        }
        session = created
        return created
    }

    private fun openChannel(sshSession: Session): ChannelSftp {
        val channel = try {
            (sshSession.openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_TIMEOUT_MS) }
        } catch (e: JSchException) {
            throw operationFailure("打开 SFTP 通道", null, e)
        }
        openChannels.add(channel)
        return channel
    }

    private fun statSize(channel: ChannelSftp, remote: String): Long {
        val attributes = try {
            channel.stat(remote)
        } catch (e: SftpException) {
            throw sftpFailure("获取文件信息", remote, e)
        }
        if (attributes.isDir) {
            throw RemotePathException("下载失败：「" + remote + "」是目录，不是文件")
        }
        return attributes.getSize()
    }

    private fun prepareEmptyTarget(target: File) {
        try {
            target.absoluteFile.parentFile?.mkdirs()
            if (target.exists() && target.length() != 0L) {
                RandomAccessFile(target, "rw").use { file -> file.setLength(0L) }
            } else if (!target.exists()) {
                target.createNewFile()
            }
        } catch (e: IOException) {
            throw RemotePathException(
                "无法创建本地目标文件：「" + target.absolutePath + "」：" + (e.message ?: "IO 错误"),
                e
            )
        }
    }

    // ------------------------------------------------------------ 异常映射

    private fun toTransferException(error: Throwable, remote: String): LanTransferException = when (error) {
        is LanTransferException -> error
        is SftpException -> sftpFailure("下载文件", remote, error)
        is IOException -> ioFailure("下载文件", remote, error)
        is JSchException -> operationFailure("下载文件", remote, error)
        else -> ConnectionException(
            "多通道下载失败：「" + remote + "」：" + (error.message ?: error.javaClass.simpleName),
            error
        )
    }

    private fun connectFailure(e: JSchException): LanTransferException {
        if (cancelled || closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        val cause = e.cause
        return when {
            cause is java.net.UnknownHostException ->
                ConnectionException("无法解析主机地址「" + profile.host + "」", e)
            cause is java.net.ConnectException ||
                message.contains("Connection refused", ignoreCase = true) ||
                message.contains("connect failed", ignoreCase = true) ->
                ConnectionException(
                    "无法连接到 " + profile.endpoint + "：" + message.ifBlank { "连接被拒绝" },
                    e
                )
            cause is java.net.SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                ConnectionException("连接 " + profile.endpoint + " 超时", e)
            isAuthenticationFailure(message) ->
                AuthenticationException("认证失败，请检查用户名或密码（" + profile.endpoint + "）", e)
            else -> ConnectionException(
                "SSH 连接失败（" + profile.endpoint + "）：" + message.ifBlank { e.javaClass.simpleName },
                e
            )
        }
    }

    private fun operationFailure(action: String, path: String?, e: JSchException): LanTransferException {
        if (cancelled || closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        return when {
            isAuthenticationFailure(message) -> AuthenticationException("认证失败：服务器拒绝登录", e)
            message.contains("session is down", ignoreCase = true) ||
                message.contains("session is not connected", ignoreCase = true) ||
                message.contains("channel is not opened", ignoreCase = true) ||
                message.contains("connection is closed", ignoreCase = true) ->
                ConnectionException(action + "失败：SSH 连接已断开", e)
            else -> ConnectionException(
                action + "失败" + path?.let { "（" + it + "）" }.orEmpty() +
                    "：" + message.ifBlank { e.javaClass.simpleName },
                e
            )
        }
    }

    private fun ioFailure(action: String, path: String?, e: IOException): LanTransferException {
        if (cancelled || closed) return TransferCancelledException()
        return ConnectionException(
            action + "失败" + path?.let { "（" + it + "）" }.orEmpty() +
                "：" + (e.message ?: "SSH 连接中断"),
            e
        )
    }

    private fun sftpFailure(action: String, path: String?, e: SftpException): LanTransferException {
        if (cancelled || closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        return when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE ->
                RemotePathException(action + "失败：「" + path + "」不存在", e)
            ChannelSftp.SSH_FX_PERMISSION_DENIED ->
                RemotePathException(action + "失败：没有权限访问「" + path + "」", e)
            ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST ->
                ConnectionException(action + "失败：SSH 连接已断开", e)
            else -> RemotePathException(
                action + "失败：「" + path + "」" + (if (message.isBlank()) "" else "（" + message + "）"),
                e
            )
        }
    }

    private fun isAuthenticationFailure(message: String): Boolean =
        message.contains("Auth fail", ignoreCase = true) ||
            message.contains("Auth cancel", ignoreCase = true) ||
            message.contains("USERAUTH", ignoreCase = true) ||
            message.contains("incorrect password", ignoreCase = true) ||
            message.contains("no more authentication methods", ignoreCase = true)

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000L

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val WORKER_SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}

/** 单次多通道下载的结果统计。 */
data class MultichannelDownloadResult(
    /** 远端文件大小（字节）。 */
    val fileSize: Long,
    /** 实际生成的分块数量。 */
    val chunkCount: Int,
    /** 实际使用的并行通道数（小文件/单通道模式下为 1）。 */
    val usedChannels: Int,
    /** 本次下载耗时（毫秒）。 */
    val elapsedMillis: Long
)

/** 单个分块在远端文件中的区间：[offset, offset + length)。 */
data class SftpChunk(
    /** 分块序号，从 0 开始。 */
    val index: Int,
    /** 远端文件内起始偏移。 */
    val offset: Long,
    /** 分块长度（字节）。 */
    val length: Long
) {
    /** 结束偏移（不含）。 */
    val endExclusive: Long get() = offset + length
}

/**
 * 分块计划（纯逻辑、可单测）。
 *
 * 规则：
 * - fileSize == 0 返回空列表（空文件无需分块）；
 * - 请求通道数 <= 1、文件小于 [MIN_MULTICHANNEL_SIZE]（2 MiB）或不超过一个 [DEFAULT_CHUNK_SIZE] 时，
 *   返回一个整文件分块，走单通道；
 * - 否则按 [chunkSize] 顺序切块，最后一块可能更短；块数可能多于通道数，
 *   由 N 个 worker 通过共享游标动态领取，保证大文件也能按固定粒度并行。
 *
 * 通道数上限归一化为 1..[MAX_CHANNELS]：JSch 每条通道都有独立窗口与线程开销，
 * 局域网单文件下载 3~4 条已能打满带宽，更多通道收益递减。
 */
object SftpChunkPlanner {

    /** 默认分块大小：4 MiB。 */
    const val DEFAULT_CHUNK_SIZE: Long = 4L * 1024L * 1024L

    /** 默认并行通道数。 */
    const val DEFAULT_CHANNELS: Int = 3

    /** 小于该大小的文件走单通道，避免多通道握手/调度开销。 */
    const val MIN_MULTICHANNEL_SIZE: Long = 2L * 1024L * 1024L

    /** 多通道并行度上限。 */
    const val MAX_CHANNELS: Int = 4

    /** 请求的通道数归一化到 1..[MAX_CHANNELS]。 */
    fun effectiveChannels(requested: Int): Int = when {
        requested <= 1 -> 1
        requested > MAX_CHANNELS -> MAX_CHANNELS
        else -> requested
    }

    /** 生成分块计划；非法参数抛 [IllegalArgumentException]。 */
    fun plan(
        fileSize: Long,
        chunkSize: Long = DEFAULT_CHUNK_SIZE,
        channels: Int = DEFAULT_CHANNELS
    ): List<SftpChunk> {
        require(fileSize >= 0L) { "文件大小不能为负数：" + fileSize }
        require(chunkSize > 0L) { "分块大小必须为正数：" + chunkSize }
        require(channels >= 1) { "通道数必须为正数：" + channels }
        if (fileSize == 0L) return emptyList()

        val effective = effectiveChannels(channels)
        if (effective == 1 || fileSize < MIN_MULTICHANNEL_SIZE || fileSize <= chunkSize) {
            return listOf(SftpChunk(index = 0, offset = 0L, length = fileSize))
        }

        val chunks = ArrayList<SftpChunk>()
        var offset = 0L
        var index = 0
        while (offset < fileSize) {
            val length = minOf(chunkSize, fileSize - offset)
            chunks.add(SftpChunk(index = index, offset = offset, length = length))
            offset += length
            index++
        }
        return chunks
    }
}

/**
 * 有序随机写器：同一个本地文件被多个下载线程按 offset 并发写入。
 *
 * - RandomAccessFile + 单一锁保证 seek + write 原子，避免不同分块交错写坏数据；
 * - 预先 setLength(远端大小)，因此任何 offset 都可直接写；
 * - 记录每段完成状态，全部完成后 [allChunksComplete] 为 true（用于判定下载成功）；
 * - [cancel] 后所有写入抛 [TransferCancelledException]，配合 monitor 停止读取；
 * - [onProgress] 在锁内串行回调，保证进度数值单调递增。
 */
internal class OrderedRangeWriter(
    file: File,
    expectedSize: Long,
    chunkCount: Int
) : Closeable {

    private val raf: RandomAccessFile
    private val lock = Any()
    private val completed: BooleanArray
    private var written = 0L
    private var closed = false

    @Volatile
    private var cancelled = false

    /** 已写入本地的总字节数（含并发写入）。 */
    val progress: Long get() = synchronized(lock) { written }

    val isCancelled: Boolean get() = cancelled

    /** 进度回调（累计字节数）；在写锁内调用，必须快速返回。 */
    @Volatile
    var onProgress: ((Long) -> Unit)? = null

    init {
        require(expectedSize >= 0L) { "目标文件大小不能为负数：" + expectedSize }
        require(chunkCount >= 0) { "分块数量不能为负数：" + chunkCount }
        try {
            file.absoluteFile.parentFile?.mkdirs()
            raf = RandomAccessFile(file, "rw")
            raf.setLength(expectedSize)
        } catch (e: IOException) {
            throw RemotePathException(
                "无法创建本地目标文件：「" + file.absolutePath + "」：" + (e.message ?: "IO 错误"),
                e
            )
        }
        completed = BooleanArray(chunkCount)
    }

    /**
     * 把 [buffer] 从 [bufferOffset] 起写 [length] 字节到本地文件 [offset] 处。
     *
     * @throws TransferCancelledException 已取消或已关闭
     * @throws IOException 底层写入失败
     */
    fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            ensureWritableLocked()
            raf.seek(offset)
            raf.write(buffer, bufferOffset, length)
            written += length
            onProgress?.invoke(written)
        }
    }

    /** 标记某段完整写入；取消后不再记录（失败路径由调用方处理）。 */
    fun markChunkComplete(index: Int) {
        synchronized(lock) {
            if (index in completed.indices) completed[index] = true
        }
    }

    fun isChunkComplete(index: Int): Boolean = synchronized(lock) {
        index in completed.indices && completed[index]
    }

    fun completedChunkCount(): Int = synchronized(lock) { completed.count { it } }

    fun allChunksComplete(): Boolean = synchronized(lock) { completed.all { it } }

    fun cancel() {
        cancelled = true
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { raf.fd.sync() }
            runCatching { raf.close() }
        }
    }

    private fun ensureWritableLocked() {
        if (cancelled) throw TransferCancelledException()
        if (closed) throw IOException("目标文件已关闭，无法继续写入")
    }
}

/**
 * 分块输出流：把 JSch 顺序推送的数据写到本段在本地文件的 offset 上。
 *
 * JSch 在 monitor 返回 false 前已经写出的最后一个数据包可能越过本段末尾，
 * 这里截断到 [chunk] 长度并丢弃多余字节，避免覆盖相邻分块的数据。
 */
private class ChunkOutputStream(
    private val writer: OrderedRangeWriter,
    private val chunk: SftpChunk
) : OutputStream() {

    /** 本段实际写入的字节数。 */
    @Volatile
    var writtenBytes: Long = 0L
        private set

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val remaining = chunk.length - writtenBytes
        if (remaining <= 0L) return
        val toWrite = if (len.toLong() <= remaining) len else remaining.toInt()
        writer.writeAt(chunk.offset + writtenBytes, b, off, toWrite)
        writtenBytes += toWrite
    }
}

/**
 * 分块停止监视器：累计到本段长度（或已取消）时返回 false，让 JSch 正常关闭远端句柄并返回。
 *
 * 注意 JSch 在 get(..., RESUME, skip) 读取数据前会先回调一次 count(skip)（进度补偿），
 * 这里忽略第一次计数，只统计真正的数据字节。
 */
private class ChunkStopMonitor(
    private val chunk: SftpChunk,
    private val sink: ChunkOutputStream,
    private val cancelledProvider: () -> Boolean
) : SftpProgressMonitor {

    private var skipReported = false

    override fun init(op: Int, src: String?, dest: String?, max: Long) {
        skipReported = false
    }

    override fun count(count: Long): Boolean {
        if (!skipReported) {
            // RESUME 模式下的 skip 补偿回调，不是真实传输数据
            skipReported = true
            return true
        }
        if (cancelledProvider()) return false
        return sink.writtenBytes < chunk.length
    }

    override fun end() = Unit
}

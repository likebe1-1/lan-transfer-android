package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClient.OpenMode
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.concurrent.TimeoutException

/**
 * 基于 Apache MINA SSHD 的 SFTP 传输引擎（JSch 之外的提速路径）。
 *
 * 背景：JSch 的 ChannelSftp 把单条通道的本地最大 SFTP 数据包硬编码为 32 KiB，
 * 且应用侧无法调整 SSH 通道窗口；单连接大文件吞吐因此长期上不去。MINA SSHD 的 SFTP 客户端
 * 允许在建立会话前通过 CoreModuleProperties / SftpModuleProperties 调整：
 *
 * - [CoreModuleProperties.WINDOW_SIZE]（默认 4 MiB）：SSH 通道的接收窗口。MINA 的流水线读
 *   SftpInputStreamAsync 会按 windowSize / packetSize 预先发出多个 SSH_FXP_READ 请求，
 *   窗口越大，在途请求越多，越能掩盖局域网 RTT；
 * - [CoreModuleProperties.MAX_PACKET_SIZE]（随 [packetSize] 设置）：本端通道单包上限；
 * - [SftpModuleProperties.COPY_BUF_SIZE] / [SftpModuleProperties.WRITE_CHUNK_SIZE]：内部拷贝与
 *   随机写（续传上传）的 SFTP 包尺寸，均取 [packetSize]；
 * - 下载时直接以 [packetSize] 作为 SftpClient.read(path, bufferSize, ...) 的读请求长度，
 *   上传时以 [packetSize] 作为 SftpClient.write(path, bufferSize, ...) 的写缓冲长度。
 *
 * 能力：
 * - 密码认证；[ServerProfile.pinnedSshHostKeySha256] 非空时只接受指纹一致的主机密钥
 *   （复用 [SshHostKeyPinning] 的 SHA-256(SSH 公钥线格式 blob) 算法，正确通过、错误拒绝）；
 * - 顺序流式下载/上传，进度回调为「已累计完成的字节数」；
 * - 断点续传：SFTP 支持随机读写，[downloadPartial] 从指定远端偏移开始读，
 *   [uploadPartial] 校验远端已有字节数后从该偏移继续写（与 JSch 版 RESUME 语义一致：
 *   调用方传入完整源流，本类负责跳过 [startOffset] 前缀）；
 * - 错误统一映射到 [ConnectionException] / [AuthenticationException] /
 *   [RemotePathException] / [TransferCancelledException]；
 * - [close] 幂等，并会把进行中的读写转换成 [TransferCancelledException]。
 *
 * 线程模型：同一实例同一时刻只支持一个传输；阻塞操作全部切到 [Dispatchers.IO]。
 * 本类保持纯 JVM（不依赖 Android API），可在 JVM 单元测试中真实走线。
 *
 * @param profile 连接参数（SFTP 协议；主机密钥指纹可选）
 * @param packetSize 单个 SFTP 数据包/读请求大小，默认 [DEFAULT_PACKET_SIZE]（64 KiB）
 * @param windowSize SSH 通道接收窗口，默认 [DEFAULT_WINDOW_SIZE]（4 MiB）；决定下载流水线深度
 */
class MinaSftpTransfer(
    val profile: ServerProfile,
    val packetSize: Int = DEFAULT_PACKET_SIZE,
    val windowSize: Long = DEFAULT_WINDOW_SIZE
) : Closeable {

    init {
        require(packetSize >= MIN_PACKET_SIZE) { "SFTP 包大小不能小于 " + MIN_PACKET_SIZE + " 字节：" + packetSize }
        require(packetSize <= MAX_PACKET_SIZE_LIMIT) {
            "SFTP 包大小不能大于服务端保护上限 " + MAX_PACKET_SIZE_LIMIT + " 字节：" + packetSize
        }
        require(windowSize >= packetSize.toLong()) {
            "SSH 通道窗口（" + windowSize + "）应不小于 SFTP 包大小（" + packetSize + "）"
        }
    }

    /** SFTP 支持按偏移读写，因此始终支持断点续传。 */
    val supportsResume: Boolean
        get() = true

    @Volatile
    private var closed = false

    @Volatile
    private var client: SshClient? = null

    @Volatile
    private var session: ClientSession? = null

    @Volatile
    private var sftpClient: SftpClient? = null

    @Volatile
    private var expectedHostKeyFingerprint: String? = null

    /** 最近一次握手看到的主机密钥指纹（诊断用；指纹固定失败时便于在错误信息里展示实际值）。 */
    @Volatile
    var lastSeenHostKeyFingerprint: String? = null
        private set

    /** 当前是否已连接且 SFTP 通道可用。 */
    val isConnected: Boolean
        get() = !closed && session?.isAuthenticated == true && sftpClient?.isOpen == true

    // ------------------------------------------------------------ 连接

    /** 建立 SSH 会话与 SFTP 通道；重复调用是幂等的。 */
    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        connectBlocking()
    }

    private fun connectBlocking() {
        if (closed) throw TransferCancelledException()
        if (isConnected) return

        val pinned = profile.pinnedSshHostKeySha256
            ?.takeIf { SshHostKeyPinning.isValidFingerprint(it) }
            ?.let { SshHostKeyPinning.normalizeFingerprint(it) }
        expectedHostKeyFingerprint = pinned
        lastSeenHostKeyFingerprint = null

        val sshClient = SshClient.setUpDefaultClient()
        try {
            configureClient(sshClient, pinned)
            client = sshClient
            sshClient.start()

            val connectedSession = try {
                sshClient.connect(profile.username, profile.host, profile.port)
                    .verify(CONNECT_TIMEOUT_MS.toLong())
                    .session
            } catch (e: IOException) {
                throw mapConnectFailure(e)
            }
            session = connectedSession
            connectedSession.addPasswordIdentity(profile.password)
            try {
                connectedSession.auth().verify(CONNECT_TIMEOUT_MS.toLong())
            } catch (e: IOException) {
                throw mapConnectFailure(e)
            }

            val clientInstance = try {
                SftpClientFactory.instance()
                    .createSftpClient(connectedSession, SFTP_PROTOCOL_VERSION)
            } catch (e: IOException) {
                throw mapOperationFailure("打开 SFTP 通道", null, e)
            }
            sftpClient = clientInstance
        } catch (e: Throwable) {
            sftpClient = null
            session = null
            client = null
            runCatching { sshClient.stop() }
            if (closed) throw TransferCancelledException()
            throw when (e) {
                is LanTransferException -> e
                is IOException -> mapConnectFailure(e)
                else -> ConnectionException(
                    "SSH 连接失败（" + profile.endpoint + "）：" + (e.message ?: e.javaClass.simpleName),
                    e
                )
            }
        }
    }

    /** 在建立会话前把包大小/窗口写入 MINA 的客户端配置。 */
    private fun configureClient(sshClient: SshClient, pinnedFingerprint: String?) {
        CoreModuleProperties.WINDOW_SIZE.set(sshClient, windowSize)
        CoreModuleProperties.MAX_PACKET_SIZE.set(sshClient, packetSize.toLong())
        SftpModuleProperties.COPY_BUF_SIZE.set(sshClient, packetSize)
        SftpModuleProperties.WRITE_CHUNK_SIZE.set(sshClient, packetSize)
        if (pinnedFingerprint != null) {
            sshClient.serverKeyVerifier = PinnedServerKeyVerifier(pinnedFingerprint)
        }
    }

    /**
     * 只接受指定 SHA-256 指纹的主机密钥。
     *
     * 指纹算法与 JSch 版一致：SHA-256(SSH 公钥线格式 blob) 的小写十六进制，
     * 因此发送方通过二维码/广播下发的指纹可直接复用。
     */
    private inner class PinnedServerKeyVerifier(
        private val expectedFingerprint: String
    ) : ServerKeyVerifier {
        override fun verifyServerKey(
            clientSession: ClientSession?,
            remoteAddress: SocketAddress?,
            serverKey: PublicKey?
        ): Boolean {
            if (serverKey == null) return false
            val fingerprint = try {
                sshPublicKeySha256(serverKey)
            } catch (e: Exception) {
                null
            }
            lastSeenHostKeyFingerprint = fingerprint
            return fingerprint == expectedFingerprint
        }
    }

    // ------------------------------------------------------------ 下载

    /** 下载远端文件到 [sink]；[onProgress] 参数为已累计传输字节数。 */
    suspend fun download(
        remotePath: String,
        sink: OutputStream,
        onProgress: (Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        downloadBlocking(RemotePath.normalize(remotePath), sink, 0L, onProgress)
    }

    /**
     * 断点续传下载：从远端 [startOffset] 字节处继续写入 [sink]。
     *
     * [onProgress] 会从 [startOffset] 开始累计，末值等于远端文件大小；
     * 远端文件在 [startOffset] 之前被截断时抛 [RemotePathException]。
     */
    suspend fun downloadPartial(
        remotePath: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        if (startOffset < 0L) throw IllegalArgumentException("续传偏移不能为负数：" + startOffset)
        downloadBlocking(RemotePath.normalize(remotePath), sink, startOffset, onProgress)
    }

    private fun downloadBlocking(
        remote: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        val sftp = requireSftp()
        try {
            val remoteSize = statSize(sftp, remote)
            if (startOffset > remoteSize) {
                throw RemotePathException(
                    "断点续传下载失败：「" + remote + "」本地已下载 " + startOffset +
                        " 字节，超过远端文件大小 " + remoteSize + " 字节"
                )
            }
            onProgress(startOffset)
            if (startOffset == remoteSize) return

            sftp.read(remote, packetSize, OpenMode.Read).use { input ->
                if (startOffset > 0L) skipFully(input, startOffset, remote)
                val buffer = ByteArray(packetSize)
                var total = startOffset
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
                sink.flush()
                if (total < remoteSize) {
                    throw ConnectionException(
                        "下载未完成：「" + remote + "」期望 " + remoteSize + " 字节，实际收到 " + total + " 字节"
                    )
                }
            }
        } catch (e: SftpException) {
            throw mapSftpFailure("下载文件", remote, e)
        } catch (e: IOException) {
            throw mapOperationFailure("下载文件", remote, e)
        }
    }

    // ------------------------------------------------------------ 上传

    /** 上传 [source] 到远端 [remotePath]（覆盖写）；[sizeHint] 为源数据长度，未知时传 -1。 */
    suspend fun upload(
        source: InputStream,
        remotePath: String,
        sizeHint: Long = -1L,
        onProgress: (Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        uploadBlocking(source, RemotePath.normalize(remotePath), startOffset = 0L, onProgress = onProgress)
    }

    /**
     * 断点续传上传：远端已存在 [startOffset] 字节，从该偏移继续写入。
     *
     * 与 JSch 版 RESUME 语义一致：[source] 是完整源数据，本类会先跳过 [startOffset] 前缀；
     * 写之前校验远端大小与 [startOffset] 一致，否则抛 [IOException] 提示重新上传。
     */
    suspend fun uploadPartial(
        source: InputStream,
        remotePath: String,
        sizeHint: Long = -1L,
        startOffset: Long = 0L,
        onProgress: (Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        if (startOffset < 0L) throw IllegalArgumentException("续传偏移不能为负数：" + startOffset)
        uploadBlocking(source, RemotePath.normalize(remotePath), startOffset, onProgress)
    }

    private fun uploadBlocking(
        source: InputStream,
        remote: String,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        val sftp = requireSftp()
        if (startOffset > 0L) {
            // 续传前置校验放在异常映射之外：远端大小与偏移不一致属于调用方可恢复的业务错误，
            // 保持与 JSch 版一致的 IOException 契约，不要被下面的网络异常映射吞成 ConnectionException。
            val remoteSize = try {
                statSize(sftp, remote)
            } catch (e: SftpException) {
                throw mapSftpFailure("上传文件", remote, e)
            } catch (e: IOException) {
                throw mapOperationFailure("上传文件", remote, e)
            }
            if (remoteSize != startOffset) {
                val actual = if (remoteSize < 0L) "未知（文件不存在或不是普通文件）" else remoteSize.toString() + " 字节"
                throw IOException(
                    "无法续传上传「" + remote + "」：远端已有 " + actual +
                        "，与续传偏移 " + startOffset + " 字节不一致，请重新上传"
                )
            }
        }
        try {
            if (startOffset == 0L) {
                writeFromStart(sftp, source, remote, onProgress)
            } else {
                appendFromOffset(sftp, source, remote, startOffset, onProgress)
            }
        } catch (e: SftpException) {
            throw mapSftpFailure("上传文件", remote, e)
        } catch (e: IOException) {
            throw mapOperationFailure("上传文件", remote, e)
        }
    }

    /** 全量上传：MINA 的写流会流水线发送多个 SSH_FXP_WRITE，并在 close 时等待全部应答。 */
    private fun writeFromStart(
        sftp: SftpClient,
        source: InputStream,
        remote: String,
        onProgress: (Long) -> Unit
    ) {
        onProgress(0L)
        sftp.write(
            remote,
            packetSize,
            OpenMode.Write,
            OpenMode.Create,
            OpenMode.Truncate
        ).use { output ->
            val buffer = ByteArray(packetSize)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                total += read
                onProgress(total)
            }
            output.flush()
        }
    }

    /** 续传上传：从该偏移随机写剩余内容（跳过源流的相同前缀）。前置校验由调用方完成。 */
    private fun appendFromOffset(
        sftp: SftpClient,
        source: InputStream,
        remote: String,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        skipFully(source, startOffset, remote)
        onProgress(startOffset)
        sftp.open(remote, OpenMode.Write).use { handle ->
            val buffer = ByteArray(packetSize)
            var total = startOffset
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sftp.write(handle, total, buffer, 0, read)
                total += read
                onProgress(total)
            }
        }
    }

    // ------------------------------------------------------------ 元数据

    /** 远端文件大小（字节）；不存在、是目录或不是普通文件时返回 -1。 */
    suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        val remote = RemotePath.normalize(path)
        val sftp = requireSftp()
        val attributes = try {
            sftp.stat(remote)
        } catch (e: SftpException) {
            if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE) return@withContext -1L
            throw mapSftpFailure("获取文件大小", remote, e)
        }
        if (attributes.isDirectory) -1L else attributes.size
    }

    // ------------------------------------------------------------ 生命周期

    /** 关闭 SFTP 通道与 SSH 会话；幂等，可跨线程调用以取消进行中的传输。 */
    override fun close() {
        if (closed) return
        closed = true
        val currentSftp = sftpClient
        sftpClient = null
        val currentSession = session
        session = null
        val currentClient = client
        client = null
        runCatching { currentSftp?.close() }
        runCatching {
            currentSession?.disconnect(SshConstants.SSH2_DISCONNECT_BY_APPLICATION, "LanLink closed")
        }
        runCatching { currentClient?.stop() }
    }

    // ------------------------------------------------------------ 测试/诊断钩子

    /** 实际写入 MINA 客户端的通道窗口（未连接时为 null）。 */
    internal fun resolvedWindowSize(): Long? = client?.let { CoreModuleProperties.WINDOW_SIZE.getRequired(it) }

    /** 实际写入 MINA 客户端的通道单包上限（未连接时为 null）。 */
    internal fun resolvedMaxPacketSize(): Long? =
        client?.let { CoreModuleProperties.MAX_PACKET_SIZE.getRequired(it) }

    /** 实际写入 MINA 客户端的 SFTP 拷贝缓冲大小（未连接时为 null）。 */
    internal fun resolvedCopyBufferSize(): Int? =
        client?.let { SftpModuleProperties.COPY_BUF_SIZE.getRequired(it) }

    /** 实际写入 MINA 客户端的随机写 SFTP 包大小（未连接时为 null）。 */
    internal fun resolvedWriteChunkSize(): Int? =
        client?.let { SftpModuleProperties.WRITE_CHUNK_SIZE.getRequired(it) }

    // ------------------------------------------------------------ 内部工具

    private fun requireSftp(): SftpClient {
        if (closed) throw TransferCancelledException()
        val current = sftpClient
        if (current == null || !current.isOpen || session?.isAuthenticated != true) {
            throw ConnectionException("尚未连接到服务器（" + profile.endpoint + "），请先调用 connect()")
        }
        return current
    }

    private fun statSize(sftp: SftpClient, remote: String): Long {
        val attributes = sftp.stat(remote)
        if (attributes.isDirectory) {
            throw RemotePathException("操作失败：「" + remote + "」是目录，不是普通文件")
        }
        return attributes.size
    }

    /** 跳过输入流前 [offset] 字节；SFTP 随机读场景由虚拟 skip 完成，不产生额外网络往返。 */
    private fun skipFully(input: InputStream, offset: Long, remote: String) {
        var remaining = offset
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining -= 1L
            } else {
                throw ConnectionException("续传失败：「" + remote + "」源数据在前 " + offset + " 字节处提前结束")
            }
        }
    }

    // ------------------------------------------------------------ 异常映射

    private fun mapConnectFailure(e: Throwable): LanTransferException {
        if (closed) return TransferCancelledException()
        val chain = causeChain(e)
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        val actualFingerprint = lastSeenHostKeyFingerprint
        val expectedFingerprint = expectedHostKeyFingerprint
        return when {
            (actualFingerprint != null && expectedFingerprint != null && actualFingerprint != expectedFingerprint) ||
                text.contains("server key", ignoreCase = true) ||
                text.contains("did not validate", ignoreCase = true) ||
                text.contains("host key", ignoreCase = true) ->
                ConnectionException(
                    "SSH 主机密钥校验失败（" + profile.endpoint + "）：指纹不匹配" +
                        "（期望 " + (expectedFingerprint ?: "未知") +
                        "，实际 " + (actualFingerprint ?: "未知") + "），可能存在中间人攻击",
                    e
                )
            chain.any { it is UnknownHostException } ->
                ConnectionException("无法解析主机地址「" + profile.host + "」", e)
            chain.any { it is ConnectException || it is NoRouteToHostException } ||
                text.contains("connection refused", ignoreCase = true) ||
                text.contains("connect failed", ignoreCase = true) ->
                ConnectionException(
                    "无法连接到 " + profile.endpoint + "：" +
                        (e.message?.takeIf { it.isNotBlank() } ?: "连接被拒绝"),
                    e
                )
            chain.any { it is SocketTimeoutException || it is TimeoutException } ||
                text.contains("timeout", ignoreCase = true) ||
                text.contains("timed out", ignoreCase = true) ->
                ConnectionException("连接 " + profile.endpoint + " 超时", e)
            isAuthenticationFailure(text) ->
                AuthenticationException("认证失败，请检查用户名或密码（" + profile.endpoint + "）", e)
            else -> ConnectionException(
                "SSH 连接失败（" + profile.endpoint + "）：" +
                    (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName),
                e
            )
        }
    }

    private fun mapOperationFailure(action: String, path: String?, e: IOException): LanTransferException {
        if (closed) return TransferCancelledException()
        if (e is SftpException) return mapSftpFailure(action, path, e)
        val text = causeChain(e).mapNotNull { it.message }.joinToString(" | ")
        return when {
            isAuthenticationFailure(text) -> AuthenticationException("认证失败：服务器拒绝登录", e)
            text.contains("session is closed", ignoreCase = true) ||
                text.contains("connection is closed", ignoreCase = true) ||
                text.contains("channel is closed", ignoreCase = true) ||
                text.contains("connection reset", ignoreCase = true) ->
                ConnectionException(action + "失败：SSH 连接已断开", e)
            else -> ConnectionException(
                action + "失败" + path?.let { "（" + it + "）" }.orEmpty() +
                    "：" + (e.message ?: "SSH 连接中断"),
                e
            )
        }
    }

    private fun mapSftpFailure(action: String, path: String?, e: SftpException): LanTransferException {
        if (closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        return when (e.status) {
            SftpConstants.SSH_FX_NO_SUCH_FILE ->
                RemotePathException(action + "失败：「" + path + "」不存在", e)
            SftpConstants.SSH_FX_PERMISSION_DENIED ->
                RemotePathException(action + "失败：没有权限访问「" + path + "」", e)
            SftpConstants.SSH_FX_NO_CONNECTION,
            SftpConstants.SSH_FX_CONNECTION_LOST ->
                ConnectionException(action + "失败：SSH 连接已断开", e)
            else -> RemotePathException(
                action + "失败：「" + path + "」" + (if (message.isBlank()) "" else "（" + message + "）"),
                e
            )
        }
    }

    private fun isAuthenticationFailure(text: String): Boolean =
        text.contains("auth fail", ignoreCase = true) ||
            text.contains("authentication", ignoreCase = true) ||
            text.contains("userauth", ignoreCase = true) ||
            text.contains("incorrect password", ignoreCase = true) ||
            text.contains("no more authentication methods", ignoreCase = true)

    private fun causeChain(error: Throwable): List<Throwable> {
        val chain = ArrayList<Throwable>(4)
        var current: Throwable? = error
        while (current != null && chain.size < MAX_CAUSE_DEPTH) {
            chain.add(current)
            current = current.cause
        }
        return chain
    }

    companion object {
        /** 默认 SFTP 包/读请求大小：64 KiB（JSch 硬编码为 32 KiB）。 */
        const val DEFAULT_PACKET_SIZE = 64 * 1024

        /** 默认 SSH 通道窗口：4 MiB；下载流水线深度约为 windowSize / packetSize = 64 个在途请求。 */
        const val DEFAULT_WINDOW_SIZE = 4L * 1024L * 1024L

        /** 最小包大小：低于 8 KiB 时包头部开销与请求数过大，没有提速意义。 */
        const val MIN_PACKET_SIZE = 8 * 1024

        /** 包大小上限：对齐 MINA SFTP 服务端默认的 MAX_WRITEDATA_PACKET_LENGTH（256 KiB）。 */
        const val MAX_PACKET_SIZE_LIMIT = 256 * 1024

        /** SFTP 协议版本：与 JSch / OpenSSH 一致固定在 v3，避免不同服务端 v6 扩展行为差异。 */
        internal const val SFTP_PROTOCOL_VERSION = 3

        private const val CONNECT_TIMEOUT_MS = 15_000

        /** 20 层足够覆盖 MINA 的 SshException -> TimeoutException/ConnectException 包装链。 */
        private const val MAX_CAUSE_DEPTH = 20
    }
}

/**
 * 计算 SSH 公钥的 SHA-256 指纹（小写十六进制）。
 *
 * 先按 SSH 公钥线格式编码（key-type 字符串 + 算法相关字段），
 * 与 JSch HostKey.key 的字节序列一致，再复用 [SshHostKeyPinning.sha256Hex] 计算摘要，
 * 保证 MINA 客户端与发送方广告的指纹可以互相校验。
 */
internal fun sshPublicKeySha256(publicKey: PublicKey): String {
    val buffer = ByteArrayBuffer(512)
    buffer.putRawPublicKey(publicKey)
    return SshHostKeyPinning.sha256Hex(buffer.getCompactData())
}

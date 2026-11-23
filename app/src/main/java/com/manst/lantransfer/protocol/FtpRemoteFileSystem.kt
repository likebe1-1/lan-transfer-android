package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * FTP / FTPS 远程文件系统实现（Apache Commons Net）。
 *
 * 设计要点：
 * - FTP 使用 [FTPClient]，FTPS 使用 [FTPSClient]；显式 TLS 通过 AUTH TLS 升级控制连接，隐式 TLS 直接加密；
 * - 控制连接与数据连接均使用二进制模式、UTF-8 编码；
 * - 目录列表优先使用 MLSD（RFC 3659），服务器不支持或返回错误时回退 LIST，由 Commons Net 的 Unix
 *   列表解析器处理；
 * - 被动模式为默认，[ServerProfile.passiveMode] 为 false 时使用主动模式；
 * - 数据连接使用 128 KiB 的流缓冲（setBufferSize）与数据 socket 收发缓冲，
 *   兼顾局域网吞吐与 Android 内存占用（128/256 KiB 实测对照见 [DATA_TRANSFER_BUFFER_SIZE] 注释与
 *   FtpDataBufferTuningTest#dataTransferBuffer128Vs256KiBEndToEndBenchmark）；
 * - TCP_NODELAY：FTPClient 没有直接 API，数据 socket 由 `_socketFactory_` 创建且只暴露
 *   SO_RCVBUF/SO_SNDBUF 配置；理论上可用自定义 SocketFactory 设置 TCP_NODELAY，但批量数据连接
 *   （128 KiB 的整块写）不需要关闭 Nagle，实测也没有收益（见 FtpDataBufferTuningTest 的 TCP_NODELAY
 *   对照用例），因此不启用、也不使用反射；
 * - [ServerProfile.allowInsecureTls] 为 true 时信任任意服务端证书（局域网自签证书常见），为 false 时使用
 *   JVM 默认信任库做严格校验；
 * - 全部阻塞调用都放在 [Dispatchers.IO] 上执行，类本身是纯 JVM 实现（不依赖 Android API）。
 *
 * 线程模型：同一实例不支持多线程并发操作（上层「一任务一连接」保证），唯一允许跨线程调用的是
 * [close]——它会把传输中出现的 IO 异常转换成 [TransferCancelledException]。
 */
class FtpRemoteFileSystem(override val profile: ServerProfile) : RemoteFileSystem {

    private val usesTls: Boolean = profile.protocol == TransferProtocol.FTPS

    private val ftp: FTPClient = createClient()

    @Volatile
    private var closed = false

    private var connected = false

    private fun createClient(): FTPClient {
        if (!usesTls) return FTPClient()
        val implicitTls = profile.ftpsMode == FtpsMode.IMPLICIT
        val sslContext = createSslContext(
            allowInsecureTls = profile.allowInsecureTls,
            pinnedSha256 = profile.pinnedCertificateSha256,
            endpoint = profile.endpoint
        )
        return FTPSClient(implicitTls, sslContext).apply {
            // 显式模式下使用 AUTH TLS（RFC 4217），具体 TLS 版本由 JDK 协商
            setAuthValue("TLS")
        }
    }

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        connectBlocking()
    }

    private fun connectBlocking() {
        if (closed) throw TransferCancelledException()
        if (connected && ftp.isConnected) return

        ftp.controlEncoding = CONTROL_ENCODING
        ftp.connectTimeout = CONNECT_TIMEOUT_MS
        ftp.defaultTimeout = READ_TIMEOUT_MS
        ftp.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
        // 数据连接缓冲区调优（配置型 setter，connect 前后调用均可，这里放在建连前保证一致）。
        // Commons Net 3.11.1 实际语义：
        // - setBufferSize 只影响数据流包装：retrieveFile/storeFile/appendFile 内部用 bufferSize 包裹
        //   数据连接流（FTPClient#getBufferedInputStream/OutputStream），默认 0 时仅 8 KiB；
        // - setReceiveBufferSize/setSendBufferSize 是 SocketClient 对「控制连接」的 SO_RCVBUF/SO_SNDBUF，
        //   在 _connect() 内、socket.connect() 之前读取，控制连接只传输少量命令，调大无吞吐收益，故不设置；
        // - 数据连接的 SO_RCVBUF/SO_SNDBUF 要用 FTPClient 专用的
        //   setReceieveDataSocketBufferSize/setSendDataSocketBufferSize（注意 setter 拼写为 Receieve），
        //   FTPClient 与 FTPSClient 的 _openDataConnection_ 都会在创建数据 socket 时应用。
        applyDataTransferBufferTuning(ftp)
        try {
            ftp.connect(profile.host, profile.port)
        } catch (e: IOException) {
            throw connectFailure(e)
        }
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            val reply = ftp.replyString?.trim().orEmpty()
            disconnectQuietly()
            throw ConnectionException("服务器拒绝连接（${profile.endpoint}）：${reply.ifBlank { "无应答" }}")
        }

        // 必须先完成登录：多数服务端（含 MockFtpServer）对未登录会话的 TYPE/PBSZ/PROT 返回 530
        val user = profile.username.ifBlank { ANONYMOUS_USER }
        val password = if (user == ANONYMOUS_USER && profile.password.isEmpty()) {
            ANONYMOUS_PASSWORD
        } else {
            profile.password
        }
        val loggedIn = try {
            ftp.login(user, password)
        } catch (e: IOException) {
            disconnectQuietly()
            if (closed) throw TransferCancelledException()
            throw AuthenticationException("认证失败，请检查用户名或密码（${profile.endpoint}）", e)
        }
        if (!loggedIn) {
            val reply = ftp.replyString?.trim().orEmpty()
            disconnectQuietly()
            throw AuthenticationException(
                "认证失败，请检查用户名或密码（服务器回复：${reply.ifBlank { ftp.replyCode.toString() }}）"
            )
        }

        try {
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                val reply = ftp.replyString?.trim().orEmpty()
                disconnectQuietly()
                throw ConnectionException("服务器不支持二进制传输模式：${reply.ifBlank { "无应答" }}")
            }
            if (usesTls) {
                // FTPS 在数据连接前协商保护级别：PBSZ 0 不限制块大小，PROT P 加密数据连接
                val ftps = ftp as FTPSClient
                ftps.execPBSZ(0L)
                ftps.execPROT("P")
            }
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            disconnectQuietly()
            if (closed) throw TransferCancelledException()
            val message = e.message ?: "TLS 握手异常"
            throw ConnectionException(
                if (usesTls) "FTPS 加密协商失败（${profile.endpoint}）：$message"
                else "FTP 初始化失败（${profile.endpoint}）：$message",
                e
            )
        }

        if (profile.passiveMode) ftp.enterLocalPassiveMode() else ftp.enterLocalActiveMode()
        connected = true
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(path)
        try {
            listBlocking(client, remote).mapNotNull { file -> file.toRemoteEntry(remote) }
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("列出目录", remote, e)
        }
    }

    private fun listBlocking(client: FTPClient, remote: String): List<FTPFile> {
        // 优先 MLSD：成功时空目录返回空数组且 replyCode 为 2xx；服务器不支持时 replyCode 为 5xx
        val mlist = try {
            client.mlistDir(remote)
        } catch (e: IOException) {
            if (closed) throw TransferCancelledException()
            null
        }
        if (mlist != null && FTPReply.isPositiveCompletion(client.replyCode)) {
            return mlist.toList()
        }

        // 回退 LIST（Unix 格式，由 Commons Net 自动识别解析器）
        val listed = client.listFiles(remote)?.toList().orEmpty()
        if (listed.isEmpty() && !FTPReply.isPositiveCompletion(client.replyCode)) {
            throw replyFailure("列出目录", remote)
        }
        return listed
    }

    private fun FTPFile.toRemoteEntry(basePath: String): RemoteEntry? {
        val entryName = name ?: return null
        if (entryName == "." || entryName == "..") return null
        val permissions = rawListing
            ?.takeIf { it.length >= 10 && (it[0] == 'd' || it[0] == '-' || it[0] == 'l') }
            ?.substring(0, 10)
            .orEmpty()
        return RemoteEntry(
            name = entryName,
            path = RemotePath.join(basePath, entryName),
            isDirectory = isDirectory,
            isSymlink = isSymbolicLink,
            size = if (isDirectory) 0L else size,
            modifiedAt = timestamp?.timeInMillis ?: 0L,
            permissions = permissions
        )
    }

    /** FTP/FTPS 支持 REST 偏移续传下载与 APPE 追加续传上传。 */
    override val supportsResume: Boolean
        get() = true

    override suspend fun download(
        remotePath: String,
        sink: OutputStream,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(remotePath)
        try {
            downloadBlocking(client, remote, sink, 0L, onProgress)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("下载文件", remote, e)
        }
    }

    override suspend fun downloadPartial(
        remotePath: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (startOffset < 0L) throw IllegalArgumentException("续传偏移不能为负数：" + startOffset)
        val client = requireConnected()
        val remote = RemotePath.normalize(remotePath)
        try {
            downloadBlocking(client, remote, sink, startOffset, onProgress)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("下载文件", remote, e)
        }
    }

    /**
     * 下载主体：startOffset > 0 时通过 Commons Net 的 restart offset（FTP REST 命令）从远端指定偏移读取，
     * 进度回调返回 startOffset + 本次已读字节数。REST 只对紧随其后的传输命令生效，完成后必须复位。
     */
    private fun downloadBlocking(
        client: FTPClient,
        remote: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        val progressSink = ProgressOutputStream(sink, onProgress, startOffset)
        val restartApplied = startOffset > 0L
        try {
            if (restartApplied) client.setRestartOffset(startOffset)
            if (!client.retrieveFile(remote, progressSink)) {
                throw replyFailure("下载文件", remote)
            }
            progressSink.flush()
        } finally {
            if (restartApplied) client.setRestartOffset(0L)
        }
    }

    override suspend fun upload(
        source: InputStream,
        remotePath: String,
        sizeHint: Long,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(remotePath)
        try {
            uploadBlocking(client, source, remote, sizeHint, 0L, onProgress)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("上传文件", remote, e)
        }
    }

    override suspend fun uploadPartial(
        source: InputStream,
        remotePath: String,
        sizeHint: Long,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (startOffset < 0L) throw IllegalArgumentException("续传偏移不能为负数：" + startOffset)
        val client = requireConnected()
        val remote = RemotePath.normalize(remotePath)
        if (startOffset == 0L) {
            try {
                uploadBlocking(client, source, remote, sizeHint, 0L, onProgress)
            } catch (e: LanTransferException) {
                throw e
            } catch (e: IOException) {
                throw operationFailure("上传文件", remote, e)
            }
            return@withContext
        }

        // 续传上传前必须确认远端已存在的字节数与调用方给出的偏移一致，否则追加会损坏文件
        val remoteSize = try {
            readRemoteSize(client, remote)
        } catch (e: IOException) {
            throw operationFailure("获取文件大小", remote, e)
        }
        if (remoteSize != startOffset) {
            val actual = if (remoteSize < 0L) "未知（文件不存在或服务器不支持 SIZE）" else remoteSize.toString() + " 字节"
            throw IOException(
                "无法续传上传「" + remote + "」：远端已有 " + actual +
                    "，与续传偏移 " + startOffset + " 字节不一致，请重新上传"
            )
        }
        // 调用方传入的是完整文件流，这里跳过已上传的前缀
        skipFully(source, startOffset)
        try {
            uploadBlocking(client, source, remote, sizeHint, startOffset, onProgress)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("上传文件", remote, e)
        }
    }

    /**
     * 上传主体：startOffset 为 0 时用 STOR 全量覆盖；大于 0 时用 APPE 追加剩余字节，
     * 进度回调从 startOffset 起累计（调用方传入的是完整文件流，已在上层跳过前缀）。
     *
     * 上传方向的 App 侧拷贝缓冲：Commons Net 的 storeFile/appendFile 内部按 [FTPClient.getBufferSize]
     * （即 [DATA_TRANSFER_BUFFER_SIZE] = 128 KiB）分配拷贝缓冲、并用同尺寸 BufferedOutputStream 包装
     * 数据连接输出流，因此「Source → FTP 输出」的读写块大小与下载方向一致，不需要额外再包一层
     * 小缓冲（再包只会多一次内存拷贝）。该行为由 FtpDataBufferTuningTest 的
     * uploadCopyBufferUsesDataTransferBufferSize / ftpsUploadCopyBufferMatchesDataTransferBufferSize /
     * resumeUploadAppendUsesDataTransferBufferSizeAndKeepsAppeSemantics 用例断言真实 read 请求大小，
     * 避免后续 Commons Net 升级悄悄退回 8 KiB 默认值。
     */
    private fun uploadBlocking(
        client: FTPClient,
        source: InputStream,
        remote: String,
        sizeHint: Long,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        val remainingHint = if (sizeHint > startOffset) sizeHint - startOffset else -1L
        // ALLO 只是空间预分配提示，服务器拒绝时继续传输
        if (remainingHint > 0L) {
            try {
                client.allocate(remainingHint)
            } catch (e: IOException) {
                if (closed) throw TransferCancelledException()
            }
        }
        val progressSource = ProgressInputStream(source, onProgress, startOffset)
        val uploaded = if (startOffset > 0L) {
            client.appendFile(remote, progressSource)
        } else {
            client.storeFile(remote, progressSource)
        }
        if (!uploaded) throw replyFailure("上传文件", remote)
    }

    override suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(path)
        try {
            readRemoteSize(client, remote)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("获取文件大小", remote, e)
        }
    }

    /** SIZE 命令；文件不存在、是目录或服务器不支持 SIZE 时返回 -1（契约语义）。 */
    private fun readRemoteSize(client: FTPClient, remote: String): Long {
        val raw = client.getSize(remote) ?: return -1L
        return raw.trim().toLongOrNull()?.takeIf { it >= 0L } ?: -1L
    }

    /**
     * 跳过输入流前 count 字节（续传上传时跳过已上传前缀）；来源不足时抛 IOException。
     *
     * 先尝试 [InputStream.skip]（文件/MediaStore 的 ParcelFileDescriptor 流通常是真正的 seek，代价 O(1)）；
     * 部分 ContentResolver/SAF 提供方（如云盘、管道流）的 skip 恒返回 0，此时必须用与数据传输一致的
     * [DATA_TRANSFER_BUFFER_SIZE]（128 KiB）缓冲读取来消费前缀。不能退化成单字节 read()：
     * 续传偏移可能达到 GiB 级，逐字节消费会把「续传上传」拖成不可用（该路径属于上传 App 侧拷贝）。
     */
    private fun skipFully(source: InputStream, count: Long) {
        var remaining = count
        var scratch: ByteArray? = null
        while (remaining > 0L) {
            val skipped = source.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            if (scratch == null) scratch = ByteArray(DATA_TRANSFER_BUFFER_SIZE)
            val buffer = scratch
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.read(buffer, 0, want)
            if (read < 0) {
                throw IOException("本地文件长度不足：还需跳过 " + remaining + " 字节（续传偏移 " + count + " 字节），请重新上传")
            }
            if (read > 0) remaining -= read
        }
    }

    override suspend fun mkdir(path: String): Unit = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(path)
        try {
            if (client.makeDirectory(remote)) return@withContext
            // 契约要求「已存在视为成功」：MKD 对已存在目录返回 550，这里用 CWD 探测确认
            if (isExistingDirectory(client, remote)) return@withContext
            throw replyFailure("创建目录", remote)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("创建目录", remote, e)
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Unit = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(path)
        val action = if (isDirectory) "删除目录" else "删除文件"
        try {
            val deleted = if (isDirectory) client.removeDirectory(remote) else client.deleteFile(remote)
            if (!deleted) throw replyFailure(action, remote)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure(action, remote, e)
        }
    }

    override suspend fun rename(fromPath: String, toPath: String): Unit = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val from = RemotePath.normalize(fromPath)
        val to = RemotePath.normalize(toPath)
        try {
            if (!client.rename(from, to)) throw replyFailure("重命名", "$from -> $to")
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("重命名", from, e)
        }
    }

    override fun close() {
        closed = true
        connected = false
        disconnectQuietly()
    }

    // ---------------------------------------------------------------- 内部工具

    private fun requireConnected(): FTPClient {
        if (closed) throw TransferCancelledException()
        if (!connected || !ftp.isConnected) {
            throw ConnectionException("尚未连接到服务器（${profile.endpoint}），请先调用 connect()")
        }
        return ftp
    }

    /** 探测远端路径是否为已存在的目录，用于兼容 MKD 的「目录已存在」报错。 */
    private fun isExistingDirectory(client: FTPClient, remote: String): Boolean {
        if (closed) throw TransferCancelledException()
        val previous = try {
            client.printWorkingDirectory()
        } catch (e: IOException) {
            if (closed) throw TransferCancelledException()
            null
        }
        return try {
            if (client.changeWorkingDirectory(remote)) {
                if (previous != null) client.changeWorkingDirectory(previous)
                true
            } else {
                false
            }
        } catch (e: IOException) {
            if (closed) throw TransferCancelledException()
            false
        }
    }

    private fun disconnectQuietly() {
        try {
            if (ftp.isConnected) ftp.disconnect()
        } catch (e: IOException) {
            // 连接即将丢弃，关闭阶段的 IO 异常不影响调用方
        }
    }

    /** 连接阶段异常：区分 DNS、拒绝连接、超时与 TLS 握手失败。 */
    private fun connectFailure(e: IOException): LanTransferException {
        if (closed) return TransferCancelledException()
        return when {
            e is UnknownHostException -> ConnectionException("无法解析主机地址「${profile.host}」", e)
            e is SocketTimeoutException -> ConnectionException("连接 ${profile.endpoint} 超时", e)
            e is SSLException -> ConnectionException(
                "TLS 握手失败（${profile.endpoint}）：${e.message ?: "证书不被信任"}", e
            )
            else -> ConnectionException(
                "无法连接到 ${profile.endpoint}：${e.message ?: e.javaClass.simpleName}", e
            )
        }
    }

    /** 已连接状态下发生 IO 异常时的映射：优先按 FTP 应答码区分路径错误与连接错误。 */
    private fun operationFailure(action: String, path: String?, e: IOException): LanTransferException {
        if (closed) return TransferCancelledException()
        val replyCode = if (ftp.isConnected) ftp.replyCode else -1
        return when {
            e is FTPConnectionClosedException -> ConnectionException("${action}失败：服务器已断开连接", e)
            e is SocketTimeoutException -> ConnectionException("${action}超时：${path ?: profile.endpoint}", e)
            e is SSLException -> ConnectionException("${action}失败：TLS 连接异常（${e.message}）", e)
            e is SocketException -> ConnectionException("${action}失败：网络错误（${e.message ?: "连接中断"}）", e)
            replyCode == 530 -> AuthenticationException("认证失败：服务器要求重新登录", e)
            replyCode == 421 || replyCode == 425 || replyCode == 426 -> ConnectionException(
                "${action}失败：${ftp.replyString?.trim().orEmpty().ifBlank { "数据连接不可用" }}", e
            )
            replyCode in 500..599 -> RemotePathException(pathMessage(action, path, ftp.replyString), e)
            else -> ConnectionException("${action}失败：${e.message ?: "连接中断"}", e)
        }
    }

    /** 服务器以否定应答拒绝操作时的异常（例如 550 路径不存在）。 */
    private fun replyFailure(action: String, path: String?): LanTransferException {
        val replyCode = ftp.replyCode
        val reply = ftp.replyString?.trim().orEmpty()
        return when {
            replyCode == 530 -> AuthenticationException("认证失败：$reply")
            replyCode == 421 || replyCode == 425 || replyCode == 426 ->
                ConnectionException("${action}失败：${reply.ifBlank { "数据连接不可用" }}")
            else -> RemotePathException(pathMessage(action, path, reply))
        }
    }

    private fun pathMessage(action: String, path: String?, reply: String?): String {
        val target = if (path.isNullOrBlank()) "" else "「$path」"
        val detail = reply?.trim().orEmpty()
        return if (detail.isBlank()) {
            "${action}失败$target（服务器未返回错误详情）"
        } else {
            "${action}失败$target（服务器回复：$detail）"
        }
    }

    /** 上传方向的进度输入流：只统计已读字节，不关闭调用方传入的流。 */
    private class ProgressInputStream(
        private val delegate: InputStream,
        private val onProgress: (Long) -> Unit,
        initialTotal: Long = 0L
    ) : InputStream() {
        private var total = initialTotal

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) {
                total += 1
                onProgress(total)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) {
                total += count
                onProgress(total)
            }
            return count
        }

        override fun available(): Int = delegate.available()

        override fun close() {
            // 调用方拥有输入流的生命周期；传输结束时不在此关闭
        }
    }

    /** 下载方向的进度输出流：只统计已写字节，不关闭调用方传入的流。 */
    private class ProgressOutputStream(
        private val delegate: OutputStream,
        private val onProgress: (Long) -> Unit,
        initialTotal: Long = 0L
    ) : OutputStream() {
        private var total = initialTotal

        override fun write(value: Int) {
            delegate.write(value)
            total += 1
            onProgress(total)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            total += length.toLong()
            onProgress(total)
        }

        override fun flush() = delegate.flush()

        override fun close() {
            // 调用方拥有输出流的生命周期；只保证数据落盘
            delegate.flush()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val CONTROL_ENCODING = "UTF-8"
        const val ANONYMOUS_USER = "anonymous"
        const val ANONYMOUS_PASSWORD = "anonymous@lantransfer"

        /**
         * 构造 FTPS 使用的 SSLContext。
         *
         * 优先级：
         * 1. [pinnedSha256] 非空 → 只信任该指纹的证书（快速传文件等自签名场景，防中间人）；
         * 2. [allowInsecureTls] 为 true → 信任任意证书（兼容旧配置，会打印安全警告）；
         * 3. 否则 → JVM 默认信任库（严格校验）。
         */
        fun createSslContext(
            allowInsecureTls: Boolean,
            pinnedSha256: String? = null,
            endpoint: String = "未知目标"
        ): SSLContext {
            val pin = com.manst.lantransfer.core.CertificatePinning.normalizeFingerprint(pinnedSha256)
            if (com.manst.lantransfer.core.CertificatePinning.isValidFingerprint(pin)) {
                return com.manst.lantransfer.core.CertificatePinning.createSslContext(pin)
            }
            if (!allowInsecureTls) return SSLContext.getDefault()
            System.err.println(
                "[LanLink][安全] 使用「信任任意证书」模式连接 " + endpoint +
                    "：TLS 只加密不认证，请仅在完全可信的局域网中使用"
            )
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            return context
        }
    }
}

/**
 * FTP/FTPS 数据连接缓冲区大小：128 KiB，用于数据流包装与数据 socket 的 SO_RCVBUF/SO_SNDBUF。
 *
 * 选择依据：
 * - Commons Net 默认不为数据流指定缓冲（内部退化为 8 KiB），在局域网千兆/Wi-Fi 下 syscall 与
 *   TLS 记录层开销会明显限制吞吐；128 KiB 足以覆盖多个 TCP MSS 与多份 TLS 记录；
 * - 128 KiB vs 256 KiB 的真实端到端对照（8 MiB 上传 + 下载，正反两遍顺序消除 JIT/缓存偏差，
 *   见 FtpDataBufferTuningTest#dataTransferBuffer128Vs256KiBEndToEndBenchmark）：
 *   · FTP 128→256：两次采样下载吞吐比 0.96×–0.98×、上传 0.83×–1.03×；
 *   · FTPS 128→256：两次采样下载 0.96×–1.00×、上传 1.13×–1.16×；
 *   · 方向并不完全一致（FTPS 上传时快时慢），没有稳定收益。
 * - 结论：该常量同时作用于数据流缓冲、数据 socket 收发缓冲，每条连接内存约 3 × 128 KiB，
 *   默认 5 路并发下改 256 KiB 会多占约 1.9 MiB。回环测试是 CPU-bound、真实局域网是网络-bound，
 *   收益会进一步缩小，因此保留 128 KiB，对 Android 低端设备与并行传输更友好；
 *   若后续有真机 Wi-Fi 的 FTPS 专项数据，可再按协议分别调参。
 */
internal const val DATA_TRANSFER_BUFFER_SIZE = 128 * 1024

/**
 * 为 FTP/FTPS 数据连接应用缓冲区配置。
 *
 * 依据 Commons Net 3.11.1 源码核实：
 * - [FTPClient.setBufferSize] 仅影响 retrieveFile/storeFile/appendFile 等数据流包装
 *   （getBufferedInputStream/getBufferedOutputStream 用该值创建包装流），对控制连接与目录列表缓冲无影响；
 * - [FTPClient.setReceieveDataSocketBufferSize]（setter 拼写为 Receieve，官方保留）与
 *   [FTPClient.setSendDataSocketBufferSize] 在每条数据连接创建时设置 SO_RCVBUF/SO_SNDBUF，
 *   普通 [FTPClient] 与 [FTPSClient]（覆写的 _openDataConnection_）都会应用，FTPS 加密数据连接同样生效；
 * - 这些 setter 只保存配置，与 connect 时机无关，但必须在数据传输前调用。
 *
 * 注意：SocketClient.setReceiveBufferSize/setSendBufferSize 作用于控制连接，
 * 且只在 connect() 内部创建 socket 时读取；控制连接仅传输少量命令，调大没有吞吐收益，故不在此设置。
 *
 * [bufferSize] 只为端到端缓冲对照测试（128 KiB vs 256 KiB）保留参数化能力，生产路径始终使用
 * [DATA_TRANSFER_BUFFER_SIZE]；不要把它接到运行时配置上，避免同一传输链路出现多种缓冲尺寸。
 *
 * TCP_NODELAY：FTPClient/FTPSClient 没有任何直接的 TCP_NODELAY setter，数据 socket 由
 * `_socketFactory_` 创建（FTPClient 与 FTPSClient 的 `_openDataConnection_` 都是先
 * `_socketFactory_.createSocket()` 再 connect）。Commons Net 只在该 socket 上设置
 * SO_RCVBUF/SO_SNDBUF，因此「能否设置 TCP_NODELAY」完全取决于自定义 SocketFactory；
 * 这是公开 API，不需要反射。但数据连接是批量整块写，关闭 Nagle 没有稳定收益（对照与可行性见
 * FtpDataBufferTuningTest#dataTransferBuffer128Vs256KiBEndToEndBenchmark 与
 * #ftpDataSocketTcpNoDelayIsFeasibleViaPublicSocketFactory），反而会改变小包合并行为，
 * 故生产路径保持系统默认（Nagle 开启），不引入自定义 socket 工厂。
 */
internal fun applyDataTransferBufferTuning(client: FTPClient, bufferSize: Int = DATA_TRANSFER_BUFFER_SIZE) {
    require(bufferSize > 0) { "数据连接缓冲区必须为正数：" + bufferSize }
    client.setBufferSize(bufferSize)
    client.setReceieveDataSocketBufferSize(bufferSize)
    client.setSendDataSocketBufferSize(bufferSize)
}

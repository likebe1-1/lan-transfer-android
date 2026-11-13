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
        val sslContext = createSslContext(profile.allowInsecureTls)
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

    override suspend fun download(
        remotePath: String,
        sink: OutputStream,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val client = requireConnected()
        val remote = RemotePath.normalize(remotePath)
        try {
            val progressSink = ProgressOutputStream(sink, onProgress)
            if (!client.retrieveFile(remote, progressSink)) {
                throw replyFailure("下载文件", remote)
            }
            progressSink.flush()
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("下载文件", remote, e)
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
            // ALLO 只是空间预分配提示，服务器拒绝时继续传输
            if (sizeHint > 0L) {
                try {
                    client.allocate(sizeHint)
                } catch (e: IOException) {
                    if (closed) throw TransferCancelledException()
                }
            }
            val progressSource = ProgressInputStream(source, onProgress)
            if (!client.storeFile(remote, progressSource)) {
                throw replyFailure("上传文件", remote)
            }
        } catch (e: LanTransferException) {
            throw e
        } catch (e: IOException) {
            throw operationFailure("上传文件", remote, e)
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
        private val onProgress: (Long) -> Unit
    ) : InputStream() {
        private var total = 0L

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
        private val onProgress: (Long) -> Unit
    ) : OutputStream() {
        private var total = 0L

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
         * [allowInsecureTls] 为 true 时信任任意服务端证书（局域网自签证书场景），否则使用 JVM 默认信任库。
         */
        fun createSslContext(allowInsecureTls: Boolean): SSLContext {
            if (!allowInsecureTls) return SSLContext.getDefault()
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

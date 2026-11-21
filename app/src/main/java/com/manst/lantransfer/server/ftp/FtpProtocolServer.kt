package com.manst.lantransfer.server.ftp

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.server.ProtocolServer
import com.manst.lantransfer.server.ServerAuthGuard
import com.manst.lantransfer.server.ServerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext

/**
 * 自实现的 FTP / FTPS 服务端（纯 JVM，被动模式）。
 *
 * 设计要点：
 * - 监听 `0.0.0.0:<binding.port>`，每个控制连接一个守护线程，支持多客户端并发；
 * - FTP 与 FTPS 共用同一套会话实现：FTPS 通过 [com.manst.lantransfer.core.FtpsMode.EXPLICIT]
 *   （AUTH TLS 升级控制连接）或 [com.manst.lantransfer.core.FtpsMode.IMPLICIT]（accept 后立即 TLS）
 *   开启，数据通道由 PROT P / PROT C 决定是否加密；
 * - 自签名证书在首次启动 FTPS 时由 BouncyCastle 生成，进程内缓存（见 [FtpServerTls]）；
 * - [start] 返回时控制端口已处于监听状态（bind 完成后才启动 accept 线程），[stop] 幂等，
 *   关闭监听端口与全部会话线程。
 */
class FtpProtocolServer(private val transferProtocol: TransferProtocol) : ProtocolServer {

    init {
        require(transferProtocol.isFtp) {
            "FtpProtocolServer 只支持 FTP/FTPS，当前协议为 ${transferProtocol.label}"
        }
    }

    override val protocol: TransferProtocol
        get() = transferProtocol

    /** 当前服务的认证限流器引用（与 FtpServerContext 中为同一实例）。 */
    @Volatile
    private var authGuard: ServerAuthGuard? = null

    override fun pruneAuthGuards() {
        authGuard?.prune()
    }

    private val lifecycleLock = Any()
    private val sessions = CopyOnWriteArrayList<FtpSession>()

    @Volatile
    private var running = false

    private var controlServer: ServerSocket? = null
    private var acceptorThread: Thread? = null
    private var currentBinding: ServerBinding? = null

    override suspend fun start(binding: ServerBinding) = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            check(!running) {
                "${transferProtocol.label} 服务端已在运行（端口 ${currentBinding?.port}）"
            }
            if (binding.port !in 1..65535) {
                throw IOException("${transferProtocol.label} 服务端口 ${binding.port} 无效：必须在 1-65535 之间")
            }
            val shareRoot = prepareShareRoot(binding)
            val serverContext = buildServerContext(binding, shareRoot)
            val server = bindControlSocket(binding.port)

            controlServer = server
            currentBinding = binding
            running = true
            val worker = Thread({ acceptLoop(server, serverContext) }, "ftp-acceptor-${binding.port}").apply {
                isDaemon = true
            }
            acceptorThread = worker
            worker.start()
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        var server: ServerSocket? = null
        var acceptor: Thread? = null
        var activeSessions: List<FtpSession> = emptyList()
        var wasRunning = false

        synchronized(lifecycleLock) {
            if (running || controlServer != null) {
                wasRunning = true
                running = false
                server = controlServer
                controlServer = null
                acceptor = acceptorThread
                acceptorThread = null
                activeSessions = sessions.toList()
                sessions.clear()
                currentBinding = null
            }
        }
        if (!wasRunning) return@withContext

        try {
            server?.close()
        } catch (e: IOException) {
            // 监听端口关闭失败不影响后续清理
        }
        activeSessions.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                // 单个会话清理失败不阻塞其他会话
            }
        }
        activeSessions.forEach { it.awaitTermination(STOP_JOIN_TIMEOUT_MS) }
        try {
            acceptor?.join(STOP_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun acceptLoop(server: ServerSocket, serverContext: FtpServerContext) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!running) break else continue
            }
            val session = FtpSession(socket, serverContext)
            sessions.add(session)
            val worker = Thread({ runSession(session) }, "ftp-session-${socket.port}").apply { isDaemon = true }
            session.attachThread(worker)
            worker.start()
        }
    }

    private fun runSession(session: FtpSession) {
        try {
            session.run()
        } catch (e: Exception) {
            // FtpSession.run 内部已尽力回复错误并关闭连接，这里只保证线程正常结束
        } finally {
            sessions.remove(session)
            try {
                session.close()
            } catch (e: Exception) {
                // 会话关闭失败不再上报
            }
        }
    }

    private fun prepareShareRoot(binding: ServerBinding): File {
        val root = binding.shareRoot.absoluteFile
        if (!root.exists() && !root.mkdirs() && !root.exists()) {
            throw IOException("${transferProtocol.label} 共享目录不可用：${root.absolutePath}")
        }
        if (!root.isDirectory) {
            throw IOException("${transferProtocol.label} 共享目录不是目录：${root.absolutePath}")
        }
        return try {
            root.canonicalFile
        } catch (e: IOException) {
            throw IOException("${transferProtocol.label} 共享目录解析失败：${root.absolutePath}", e)
        }
    }

    private fun buildServerContext(binding: ServerBinding, shareRoot: File): FtpServerContext {
        val ftps = transferProtocol == TransferProtocol.FTPS
        val sslContext: SSLContext? = if (ftps) {
            try {
                FtpServerTls.sharedContext()
            } catch (e: Exception) {
                throw IOException(
                    "${transferProtocol.label} 自签名证书生成失败：${e.message ?: e.javaClass.simpleName}",
                    e
                )
            }
        } else {
            null
        }
        val guard = ServerAuthGuard()
        authGuard = guard
        return FtpServerContext(
            protocol = transferProtocol,
            shareRoot = shareRoot,
            username = binding.username,
            password = binding.password,
            explicitTls = ftps && binding.ftpsMode == FtpsMode.EXPLICIT,
            implicitTls = ftps && binding.ftpsMode == FtpsMode.IMPLICIT,
            sslContext = sslContext,
            passiveBindAddress = InetAddress.getByName(BIND_ADDRESS),
            authGuard = guard
        )
    }

    private fun bindControlSocket(port: Int): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(BIND_ADDRESS), port), LISTEN_BACKLOG)
            }
        } catch (e: IOException) {
            throw IOException(
                "${transferProtocol.label} 服务端口 $port 监听失败：${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    private companion object {
        const val BIND_ADDRESS = "0.0.0.0"
        const val LISTEN_BACKLOG = 64
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}

/**
 * 一个 FTP/FTPS 监听实例的不可变运行参数，被所有会话线程共享。
 */
internal data class FtpServerContext(
    val protocol: TransferProtocol,
    val shareRoot: File,
    val username: String,
    val password: String,
    /** 显式 TLS（FTPS + EXPLICIT）：允许 AUTH TLS 升级控制连接。 */
    val explicitTls: Boolean,
    /** 隐式 TLS（FTPS + IMPLICIT）：accept 后立即 TLS 握手。 */
    val implicitTls: Boolean,
    /** FTPS 时非空；FTP 时为 null。 */
    val sslContext: SSLContext?,
    /** 被动模式数据端口的监听地址（与 0.0.0.0 控制端口同网卡）。 */
    val passiveBindAddress: InetAddress,
    /** 服务端认证失败限流：同一来源多次失败后临时封禁（防止接收码被暴力枚举）。 */
    val authGuard: ServerAuthGuard
) {
    val tlsEnabled: Boolean get() = protocol == TransferProtocol.FTPS && sslContext != null

    val displayName: String
        get() = if (protocol == TransferProtocol.FTPS) "LanTransfer FTPS Server" else "LanTransfer FTP Server"
}

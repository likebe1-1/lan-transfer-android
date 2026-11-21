package com.manst.lantransfer.server

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.TransferProtocol
import java.io.File

/** 协议服务端启动参数。 */
data class ServerBinding(
    val port: Int,
    val shareRoot: File,
    val username: String,
    val password: String,
    val ftpsMode: FtpsMode = FtpsMode.EXPLICIT
)

/**
 * 单个协议的服务端实现。
 *
 * 约定：
 * - 纯 JVM 实现（便于 JVM 单测），不直接依赖 Activity；
 * - [start] 返回时端口必须已处于监听状态，失败抛出带中文 message 的异常；
 * - [stop] 幂等，必须释放端口、线程与 TLS 上下文；
 * - 监听地址为 0.0.0.0（同一局域网内的其他设备可访问）。
 */
interface ProtocolServer {
    val protocol: TransferProtocol

    suspend fun start(binding: ServerBinding)

    suspend fun stop()

    /** 清理认证限流器的过期记录（默认无操作；长驻服务实现该方法供管理器定期调用）。 */
    fun pruneAuthGuards() {}
}

/**
 * SFTP 与 SCP 共用同一个 SSH 监听端口，因此由同一个组件负责，
 * 根据开关是否注册 SFTP 子系统与 SCP 命令工厂。
 */
interface SshServerComponent {
    suspend fun start(binding: ServerBinding, enableSftp: Boolean, enableScp: Boolean)

    suspend fun stop()

    /** 清理认证限流器的过期记录（默认无操作；长驻服务实现该方法供管理器定期调用）。 */
    fun pruneAuthGuards() {}
}

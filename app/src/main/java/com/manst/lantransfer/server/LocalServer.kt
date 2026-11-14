package com.manst.lantransfer.server

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.flow.StateFlow

/** 本机服务器的一条协议监听状态。 */
data class ServerEndpointState(
    val protocol: TransferProtocol,
    val port: Int,
    val running: Boolean,
    val error: String? = null
)

/** 本机服务器配置（同时用于持久化）。 */
data class LocalServerConfig(
    val enableFtp: Boolean = true,
    val enableFtps: Boolean = true,
    val enableSftp: Boolean = true,
    val enableScp: Boolean = true,
    val ftpPort: Int = 2121,
    val ftpsPort: Int = 2122,
    val sshPort: Int = 2222,
    val username: String = "android",
    val password: String = "",
    val ftpsMode: FtpsMode = FtpsMode.EXPLICIT,
    /** 覆盖服务端共享根目录（快捷传输会话使用临时目录）；null 表示使用 ShareRepository 的共享目录。 */
    val shareRootOverride: String? = null
) {
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (username.isBlank()) errors += "用户名不能为空"
        if (password.isBlank()) errors += "密码不能为空"
        if (username.contains(':')) errors += "用户名不能包含冒号"
        if (username.any { it.isWhitespace() }) errors += "用户名不能包含空格"
        val enabledPorts = buildList {
            if (enableFtp) add("FTP" to ftpPort)
            if (enableFtps) add("FTPS" to ftpsPort)
            if (enableSftp || enableScp) add("SFTP/SCP" to sshPort)
        }
        if (enabledPorts.isEmpty()) errors += "至少启用一种协议"
        for ((label, port) in enabledPorts) {
            if (port !in 1024..65535) errors += label + " 端口需在 1024-65535 之间"
        }
        val grouped = enabledPorts.groupBy { it.second }.filterValues { it.size > 1 }
        if (grouped.isNotEmpty()) {
            val detail = grouped.entries.joinToString("；") { (port, list) ->
                list.joinToString("/") { it.first } + " 都使用端口 " + port
            }
            errors += "不同协议的端口不能重复：" + detail
        }
        return errors
    }
}

data class LocalServerState(
    val phase: Phase = Phase.STOPPED,
    val config: LocalServerConfig = LocalServerConfig(),
    val endpoints: List<ServerEndpointState> = emptyList(),
    val shareDirectoryPath: String = "",
    val startedAt: Long = 0L,
    val lastError: String? = null
) {
    enum class Phase { STOPPED, STARTING, RUNNING }

    val isRunning: Boolean get() = phase == Phase.RUNNING

    fun endpoint(protocol: TransferProtocol): ServerEndpointState? =
        endpoints.firstOrNull { it.protocol == protocol }
}

/**
 * 本机协议服务器管理器：一个开关同时启动 FTP / FTPS / SFTP / SCP 四种服务端，
 * 对外暴露 [com.manst.lantransfer.data.ShareRepository] 的共享目录。
 *
 * 实现要求：
 * - [start] 为幂等友好的阻塞调用（内部切 IO 线程），任一协议启动失败时其余协议继续启动；
 * - 全部协议失败时返回失败并回到 STOPPED；
 * - 运行期间通过前台服务保活；
 * - [stop] 释放所有端口并停止前台服务。
 */
interface LocalServerManager {
    val state: StateFlow<LocalServerState>

    suspend fun start(config: LocalServerConfig): Result<Unit>

    suspend fun stop()

    fun validate(config: LocalServerConfig): List<String>
}
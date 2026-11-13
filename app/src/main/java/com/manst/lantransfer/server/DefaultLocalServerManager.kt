package com.manst.lantransfer.server

import android.content.Context
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.ShareRepository
import com.manst.lantransfer.server.ftp.FtpProtocolServer
import com.manst.lantransfer.server.ssh.SshProtocolServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本机服务器默认实现：把 FTP / FTPS / SFTP / SCP 四个服务端串联起来，
 * 共享同一个共享目录（[ShareRepository]），局域网内其他设备用任意一种协议即可连接。
 *
 * 设计取舍：
 * - 任意协议启动失败不影响其他协议，失败信息通过 [LocalServerState.endpoints] 暴露；
 * - 全部协议失败才回到 STOPPED 并返回失败；
 * - 有时间窗口内运行则通过 [LocalServerService] 前台服务保活。
 */
class DefaultLocalServerManager(
    private val context: Context,
    private val shareRepository: ShareRepository
) : LocalServerManager {

    private val lifecycleMutex = Mutex()

    private val sshServer: SshServerComponent = SshProtocolServer()

    private var ftpServer: ProtocolServer? = null
    private var ftpsServer: ProtocolServer? = null

    private val mutableState = MutableStateFlow(
        LocalServerState(shareDirectoryPath = shareRepository.directoryPath)
    )

    override val state: StateFlow<LocalServerState> = mutableState.asStateFlow()

    override fun validate(config: LocalServerConfig): List<String> = config.validationErrors()

    override suspend fun start(config: LocalServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (mutableState.value.phase != LocalServerState.Phase.STOPPED) {
                return@withLock Result.failure(IllegalStateException("本机服务器已在运行"))
            }
            val configErrors = config.validationErrors()
            if (configErrors.isNotEmpty()) {
                return@withLock Result.failure(IllegalArgumentException(configErrors.joinToString("；")))
            }

            val shareRoot = shareRepository.directory().apply { mkdirs() }
            mutableState.value = LocalServerState(
                phase = LocalServerState.Phase.STARTING,
                config = config,
                endpoints = emptyList(),
                shareDirectoryPath = shareRoot.absolutePath
            )
            runCatching { LocalServerService.start(context) }

            val endpoints = mutableListOf<ServerEndpointState>()
            val failures = mutableListOf<String>()

            if (config.enableFtp) {
                val binding = ServerBinding(config.ftpPort, shareRoot, config.username, config.password)
                val server = FtpProtocolServer(TransferProtocol.FTP)
                try {
                    server.start(binding)
                    ftpServer = server
                    endpoints += ServerEndpointState(TransferProtocol.FTP, config.ftpPort, true)
                } catch (e: Exception) {
                    runCatching { server.stop() }
                    val message = e.message ?: "启动失败"
                    endpoints += ServerEndpointState(TransferProtocol.FTP, config.ftpPort, false, message)
                    failures += "FTP：$message"
                }
            }
            if (config.enableFtps) {
                val binding = ServerBinding(
                    port = config.ftpsPort,
                    shareRoot = shareRoot,
                    username = config.username,
                    password = config.password,
                    ftpsMode = config.ftpsMode
                )
                val server = FtpProtocolServer(TransferProtocol.FTPS)
                try {
                    server.start(binding)
                    ftpsServer = server
                    endpoints += ServerEndpointState(TransferProtocol.FTPS, config.ftpsPort, true)
                } catch (e: Exception) {
                    runCatching { server.stop() }
                    val message = e.message ?: "启动失败"
                    endpoints += ServerEndpointState(TransferProtocol.FTPS, config.ftpsPort, false, message)
                    failures += "FTPS：$message"
                }
            }
            if (config.enableSftp || config.enableScp) {
                val binding = ServerBinding(
                    port = config.sshPort,
                    shareRoot = shareRoot,
                    username = config.username,
                    password = config.password
                )
                try {
                    sshServer.start(binding, config.enableSftp, config.enableScp)
                    if (config.enableSftp) {
                        endpoints += ServerEndpointState(TransferProtocol.SFTP, config.sshPort, true)
                    }
                    if (config.enableScp) {
                        endpoints += ServerEndpointState(TransferProtocol.SCP, config.sshPort, true)
                    }
                } catch (e: Exception) {
                    val message = e.message ?: "SSH 服务端启动失败"
                    if (config.enableSftp) {
                        endpoints += ServerEndpointState(TransferProtocol.SFTP, config.sshPort, false, message)
                    }
                    if (config.enableScp) {
                        endpoints += ServerEndpointState(TransferProtocol.SCP, config.sshPort, false, message)
                    }
                    failures += "SFTP/SCP：$message"
                }
            }

            val anyRunning = endpoints.any { it.running }
            val lastError = failures.joinToString("；").ifBlank { null }
            mutableState.value = LocalServerState(
                phase = if (anyRunning) LocalServerState.Phase.RUNNING else LocalServerState.Phase.STOPPED,
                config = config,
                endpoints = endpoints,
                shareDirectoryPath = shareRoot.absolutePath,
                startedAt = if (anyRunning) System.currentTimeMillis() else 0L,
                lastError = lastError
            )
            if (anyRunning) {
                Result.success(Unit)
            } else {
                runCatching { LocalServerService.stop(context) }
                Result.failure(IllegalStateException(lastError ?: "所有协议启动失败"))
            }
        }
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (mutableState.value.phase == LocalServerState.Phase.STOPPED) {
                return@withLock
            }
            runCatching { ftpServer?.stop() }
            ftpServer = null
            runCatching { ftpsServer?.stop() }
            ftpsServer = null
            runCatching { sshServer.stop() }
            runCatching { LocalServerService.stop(context) }
            mutableState.value = mutableState.value.copy(
                phase = LocalServerState.Phase.STOPPED,
                endpoints = emptyList(),
                startedAt = 0L
            )
        }
    }
}

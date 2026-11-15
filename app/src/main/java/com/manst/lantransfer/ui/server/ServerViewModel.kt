package com.manst.lantransfer.ui.server

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.LocalServerConfigStore
import com.manst.lantransfer.data.ShareFile
import com.manst.lantransfer.data.ShareRepository
import com.manst.lantransfer.server.LanAddress
import com.manst.lantransfer.server.LocalServerConfig
import com.manst.lantransfer.server.LocalServerManager
import com.manst.lantransfer.server.LocalServerState
import com.manst.lantransfer.server.ServerEndpointState
import com.manst.lantransfer.ui.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本机服务器设置表单（端口用字符串保存，便于输入过程中校验）。 */
data class ServerConfigForm(
    val enableFtp: Boolean = true,
    val enableFtps: Boolean = true,
    val enableSftp: Boolean = true,
    val enableScp: Boolean = true,
    val ftpPort: String = "2121",
    val ftpsPort: String = "2122",
    val sshPort: String = "2222",
    val username: String = "android",
    val password: String = "",
    val ftpsMode: FtpsMode = FtpsMode.EXPLICIT
) {
    val anyProtocolEnabled: Boolean get() = enableFtp || enableFtps || enableSftp || enableScp
}

/** 本机服务器页状态。 */
data class ServerUiState(
    val phase: LocalServerState.Phase = LocalServerState.Phase.STOPPED,
    val endpoints: List<ServerEndpointState> = emptyList(),
    val lastError: String? = null,
    val startedAt: Long = 0L,
    val shareDirectoryPath: String = "",
    val addresses: List<String> = emptyList(),
    val sharedFiles: List<ShareFile> = emptyList(),
    val isLoadingFiles: Boolean = false,
    val isImporting: Boolean = false,
    val isStopping: Boolean = false,
    val form: ServerConfigForm = ServerConfigForm(),
    val usernameError: String? = null,
    val passwordError: String? = null,
    val ftpPortError: String? = null,
    val ftpsPortError: String? = null,
    val sshPortError: String? = null,
    val protocolError: String? = null
) {
    val isRunning: Boolean get() = phase == LocalServerState.Phase.RUNNING
    val isStarting: Boolean get() = phase == LocalServerState.Phase.STARTING
    val isStopped: Boolean get() = phase == LocalServerState.Phase.STOPPED
    val canEditConfig: Boolean get() = isStopped && !isStopping
    val isSwitching: Boolean get() = isStarting || isStopping

    fun endpointOf(protocol: TransferProtocol): ServerEndpointState? =
        endpoints.firstOrNull { it.protocol == protocol }
}

/**
 * 本机服务器页 ViewModel：把 [LocalServerManager] 的运行状态、[ShareRepository] 的共享文件
 * 与可编辑的配置表单聚合成一个 UI 状态，并负责配置的自动保存。
 *
 * 注意：页面销毁（onCleared）不会停止服务器，服务器由前台服务保活。
 */
class ServerViewModel(
    private val localServerManager: LocalServerManager,
    private val shareRepository: ShareRepository,
    private val localServerConfigStore: LocalServerConfigStore
) : ViewModel() {

    private val initialConfig: LocalServerConfig =
        localServerConfigStore.load() ?: localServerManager.state.value.config

    private val _uiState = MutableStateFlow(
        ServerUiState(
            form = initialConfig.toForm(),
            shareDirectoryPath = shareRepository.directoryPath
        )
    )
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 配置防抖保存使用独立作用域：页面退出时最后几次修改也能落盘。 */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var startInFlight = false
    private var silentRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            localServerManager.state.collect { serverState ->
                _uiState.update {
                    it.copy(
                        phase = serverState.phase,
                        endpoints = serverState.endpoints,
                        lastError = serverState.lastError,
                        startedAt = serverState.startedAt,
                        shareDirectoryPath = serverState.shareDirectoryPath
                            .ifBlank { shareRepository.directoryPath }
                    )
                }
            }
        }
        viewModelScope.launch {
            shareRepository.files.collect { files ->
                _uiState.update { it.copy(sharedFiles = files) }
            }
        }
        viewModelScope.launch {
            updateAddresses()
            doRefreshSharedFiles(showError = false)
        }
    }

    // ------------------------------------------------------------------ 配置编辑

    fun onProtocolEnabledChange(protocol: TransferProtocol, enabled: Boolean) {
        if (!_uiState.value.canEditConfig) return
        val form = _uiState.value.form
        if (!enabled) {
            val enabledCount = listOf(form.enableFtp, form.enableFtps, form.enableSftp, form.enableScp)
                .count { it }
            if (enabledCount <= 1) {
                _messages.trySend("至少需要启用一种协议")
                return
            }
        }
        val updated = when (protocol) {
            TransferProtocol.FTP -> form.copy(enableFtp = enabled)
            TransferProtocol.FTPS -> form.copy(enableFtps = enabled)
            TransferProtocol.SFTP -> form.copy(enableSftp = enabled)
            TransferProtocol.SCP -> form.copy(enableScp = enabled)
        }
        _uiState.update { it.copy(form = updated, protocolError = null) }
        scheduleConfigSave()
    }

    fun onFtpPortChange(value: String) {
        if (!_uiState.value.canEditConfig) return
        _uiState.update { it.copy(form = it.form.copy(ftpPort = digitsOnly(value)), ftpPortError = null) }
        scheduleConfigSave()
    }

    fun onFtpsPortChange(value: String) {
        if (!_uiState.value.canEditConfig) return
        _uiState.update { it.copy(form = it.form.copy(ftpsPort = digitsOnly(value)), ftpsPortError = null) }
        scheduleConfigSave()
    }

    fun onSshPortChange(value: String) {
        if (!_uiState.value.canEditConfig) return
        _uiState.update { it.copy(form = it.form.copy(sshPort = digitsOnly(value)), sshPortError = null) }
        scheduleConfigSave()
    }

    fun onUsernameChange(value: String) {
        if (!_uiState.value.canEditConfig) return
        _uiState.update { it.copy(form = it.form.copy(username = value), usernameError = null) }
        scheduleConfigSave()
    }

    fun onPasswordChange(value: String) {
        if (!_uiState.value.canEditConfig) return
        _uiState.update { it.copy(form = it.form.copy(password = value), passwordError = null) }
        scheduleConfigSave()
    }

    fun onFtpsModeChange(mode: FtpsMode) {
        if (!_uiState.value.canEditConfig) return
        _uiState.update { it.copy(form = it.form.copy(ftpsMode = mode)) }
        scheduleConfigSave()
    }

    // ------------------------------------------------------------------ 服务器开关

    /** 校验配置并启动四种协议服务端；失败时用 Snackbar 展示具体原因。 */
    fun startServer() {
        if (!_uiState.value.isStopped || startInFlight) return
        val validation = validateForm()
        val config = validation.config
        if (config == null) {
            _messages.trySend(validation.errors.joinToString("；"))
            return
        }
        startInFlight = true
        viewModelScope.launch {
            try {
                saveConfigQuietly(config)
                val result = try {
                    localServerManager.start(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                result.onSuccess {
                    updateAddresses()
                    doRefreshSharedFiles(showError = false)
                    _messages.send("服务器已启动，其他设备可通过局域网访问")
                }.onFailure { error ->
                    _messages.send("启动失败：${error.toUserMessage()}")
                }
            } finally {
                startInFlight = false
            }
        }
    }

    fun stopServer() {
        if (_uiState.value.isStopped) return
        viewModelScope.launch {
            _uiState.update { it.copy(isStopping = true) }
            try {
                localServerManager.stop()
                _messages.send("服务器已停止")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("停止失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isStopping = false) }
                updateAddresses()
            }
        }
    }

    // ------------------------------------------------------------------ 访问地址

    /** 重新读取本机 IPv4 地址（Wi-Fi 状态变化后可手动刷新）。 */
    fun refreshAddresses() {
        viewModelScope.launch { updateAddresses() }
    }

    // ------------------------------------------------------------------ 共享文件

    fun refreshSharedFiles() {
        viewModelScope.launch { doRefreshSharedFiles(showError = true) }
    }

    /**
     * 页面可见期间的静默自动刷新：不显示刷新按钮 loading、不弹错误提示，
     * 已有刷新在进行时直接跳过，避免与手动刷新/上一次自动刷新叠加。
     */
    fun refreshSharedFilesSilently() {
        if (_uiState.value.isLoadingFiles) return
        if (silentRefreshJob?.isActive == true) return
        silentRefreshJob = viewModelScope.launch {
            doRefreshSharedFiles(showError = false, showLoading = false)
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            try {
                val result = shareRepository.importFromUris(uris)
                val message = buildString {
                    append("已导入 ${result.imported.size} 个文件")
                    if (result.failed.isNotEmpty()) {
                        append("，${result.failed.size} 个失败")
                    }
                }
                _messages.send(message)
                doRefreshSharedFiles(showError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("导入失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun exportSharedFile(file: ShareFile) {
        viewModelScope.launch {
            try {
                shareRepository.exportToDownloads(file.name)
                    .onSuccess { savedName -> _messages.send("已导出到系统下载目录：$savedName") }
                    .onFailure { error -> _messages.send("导出失败：${error.toUserMessage()}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("导出失败：${e.toUserMessage()}")
            }
        }
    }

    fun deleteSharedFile(file: ShareFile) {
        viewModelScope.launch {
            try {
                if (shareRepository.delete(file.name)) {
                    _messages.send("已删除「${file.name}」")
                    doRefreshSharedFiles(showError = false)
                } else {
                    _messages.send("删除失败：文件不存在或无法删除")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("删除失败：${e.toUserMessage()}")
            }
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        // 页面退出时补一次配置保存（服务器本身继续由前台服务保活，不在这里停止）。
        val config = configForPersistence()
        saveScope.launch { saveConfigQuietly(config) }
        super.onCleared()
    }

    // ------------------------------------------------------------------ 内部实现

    private data class ValidationResult(val config: LocalServerConfig?, val errors: List<String>)

    /**
     * 校验表单：端口必须是数字，其余规则（范围、端口冲突、帐号）复用 [LocalServerManager.validate]，
     * 并把管理器的错误信息映射回对应的输入框。
     */
    private fun validateForm(): ValidationResult {
        val form = _uiState.value.form
        val fallback = localServerManager.state.value.config
        val errors = mutableListOf<String>()

        fun parsePort(enabled: Boolean, text: String, label: String, fallbackPort: Int): Int {
            if (!enabled) return text.trim().toIntOrNull() ?: fallbackPort
            val value = text.trim().toIntOrNull()
            if (value == null) {
                errors += "${label}端口必须是数字"
                return fallbackPort
            }
            return value
        }

        val ftpPort = parsePort(form.enableFtp, form.ftpPort, "FTP", fallback.ftpPort)
        val ftpsPort = parsePort(form.enableFtps, form.ftpsPort, "FTPS", fallback.ftpsPort)
        val sshPort = parsePort(form.enableSftp || form.enableScp, form.sshPort, "SSH", fallback.sshPort)

        val config = LocalServerConfig(
            enableFtp = form.enableFtp,
            enableFtps = form.enableFtps,
            enableSftp = form.enableSftp,
            enableScp = form.enableScp,
            ftpPort = ftpPort,
            ftpsPort = ftpsPort,
            sshPort = sshPort,
            username = form.username.trim(),
            password = form.password,
            ftpsMode = form.ftpsMode
        )
        val allErrors = (errors + localServerManager.validate(config)).distinct()

        _uiState.update {
            it.copy(
                usernameError = allErrors.firstOrNull { message -> message.startsWith("用户名") },
                passwordError = allErrors.firstOrNull { message -> message.startsWith("密码") },
                ftpPortError = allErrors.firstOrNull { message -> message.startsWith("FTP") },
                ftpsPortError = allErrors.firstOrNull { message -> message.startsWith("FTPS") },
                sshPortError = allErrors.firstOrNull { message -> message.startsWith("SFTP/SCP") },
                protocolError = allErrors.firstOrNull { message -> message.startsWith("至少启用") }
            )
        }
        return if (allErrors.isEmpty()) ValidationResult(config, emptyList())
        else ValidationResult(null, allErrors)
    }

    private fun configForPersistence(): LocalServerConfig {
        val form = _uiState.value.form
        val fallback = localServerManager.state.value.config
        return LocalServerConfig(
            enableFtp = form.enableFtp,
            enableFtps = form.enableFtps,
            enableSftp = form.enableSftp,
            enableScp = form.enableScp,
            ftpPort = form.ftpPort.trim().toIntOrNull() ?: fallback.ftpPort,
            ftpsPort = form.ftpsPort.trim().toIntOrNull() ?: fallback.ftpsPort,
            sshPort = form.sshPort.trim().toIntOrNull() ?: fallback.sshPort,
            username = form.username.trim(),
            password = form.password,
            ftpsMode = form.ftpsMode
        )
    }

    private fun scheduleConfigSave() {
        if (!_uiState.value.canEditConfig) return
        saveJob?.cancel()
        val config = configForPersistence()
        saveJob = saveScope.launch {
            delay(CONFIG_SAVE_DEBOUNCE_MS)
            saveConfigQuietly(config)
        }
    }

    private suspend fun saveConfigQuietly(config: LocalServerConfig) {
        try {
            localServerConfigStore.save(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "保存本机服务器配置失败", e)
        }
    }

    private suspend fun updateAddresses() {
        val addresses = withContext(Dispatchers.IO) { LanAddress.localIpv4Addresses() }
        _uiState.update { it.copy(addresses = addresses) }
    }

    private suspend fun doRefreshSharedFiles(showError: Boolean, showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.update { it.copy(isLoadingFiles = true) }
        }
        try {
            shareRepository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (showError) {
                _messages.send("刷新共享文件失败：${e.toUserMessage()}")
            } else {
                Log.w(TAG, "刷新共享文件失败", e)
            }
        } finally {
            if (showLoading) {
                _uiState.update { it.copy(isLoadingFiles = false) }
            }
        }
    }

    private fun digitsOnly(value: String): String = value.filter { it.isDigit() }.take(5)

    private companion object {
        const val TAG = "ServerViewModel"
        const val CONFIG_SAVE_DEBOUNCE_MS = 350L
    }
}

/** 把持久化配置转成表单字符串。 */
private fun LocalServerConfig.toForm(): ServerConfigForm = ServerConfigForm(
    enableFtp = enableFtp,
    enableFtps = enableFtps,
    enableSftp = enableSftp,
    enableScp = enableScp,
    ftpPort = ftpPort.toString(),
    ftpsPort = ftpsPort.toString(),
    sshPort = sshPort.toString(),
    username = username,
    password = password,
    ftpsMode = ftpsMode
)
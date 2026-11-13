package com.manst.lantransfer.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.protocol.RemoteFileSystem
import com.manst.lantransfer.protocol.RemoteFileSystems
import com.manst.lantransfer.ui.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 服务器编辑页状态（表单值 + 内联校验错误 + 测试/保存中的忙碌标记）。 */
data class ServerEditorUiState(
    val isNew: Boolean = true,
    val name: String = "",
    val protocol: TransferProtocol = TransferProtocol.SFTP,
    val host: String = "",
    val port: String = TransferProtocol.SFTP.defaultPort.toString(),
    val username: String = "",
    val password: String = "",
    val initialPath: String = RemotePath.ROOT,
    val ftpsMode: FtpsMode = FtpsMode.EXPLICIT,
    val passiveMode: Boolean = true,
    val allowInsecureTls: Boolean = true,
    val nameError: String? = null,
    val hostError: String? = null,
    val portError: String? = null,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false
) {
    val showFtpsOptions: Boolean get() = protocol == TransferProtocol.FTPS
    val showPassiveOption: Boolean get() = protocol.isFtp
    val hasValidationError: Boolean
        get() = nameError != null || hostError != null || portError != null
}

/** 编辑页只向界面发出“保存完成”这种导航事件，避免把导航逻辑塞进 ViewModel。 */
sealed interface ServerEditorEvent {
    data object Saved : ServerEditorEvent
}

class ServerEditorViewModel(
    private val serverRepository: ServerRepository,
    private val profileId: String?
) : ViewModel() {

    private val existingProfile: ServerProfile? = profileId?.let { serverRepository.find(it) }

    private val _uiState = MutableStateFlow(createInitialState(existingProfile))
    val uiState: StateFlow<ServerEditorUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _events = Channel<ServerEditorEvent>(Channel.BUFFERED)
    val events: Flow<ServerEditorEvent> = _events.receiveAsFlow()

    private var activeConnection: RemoteFileSystem? = null
    private var testJob: Job? = null

    init {
        if (profileId != null && existingProfile == null) {
            _messages.trySend("未找到该服务器配置，已切换为新增模式")
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, nameError = null) }

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value, hostError = null) }

    fun onPortChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(port = digits, portError = null) }
    }

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun onInitialPathChange(value: String) = _uiState.update { it.copy(initialPath = value) }

    fun onPassiveModeChange(value: Boolean) = _uiState.update { it.copy(passiveMode = value) }

    fun onAllowInsecureTlsChange(value: Boolean) = _uiState.update { it.copy(allowInsecureTls = value) }

    /** 切换协议时端口联动到该协议的默认端口；隐式 FTPS 使用 990。 */
    fun onProtocolChange(protocol: TransferProtocol) {
        _uiState.update { state ->
            val port = if (protocol == TransferProtocol.FTPS && state.ftpsMode == FtpsMode.IMPLICIT) {
                IMPLICIT_FTPS_PORT
            } else {
                protocol.defaultPort.toString()
            }
            state.copy(protocol = protocol, port = port, portError = null)
        }
    }

    /** 切换 FTPS 加密模式时同步常见的端口（显式 21 / 隐式 990）。 */
    fun onFtpsModeChange(mode: FtpsMode) {
        _uiState.update { state ->
            val port = when {
                mode == FtpsMode.IMPLICIT && (state.port == "21" || state.port.isBlank()) -> IMPLICIT_FTPS_PORT
                mode == FtpsMode.EXPLICIT && state.port == IMPLICIT_FTPS_PORT -> "21"
                else -> state.port
            }
            state.copy(ftpsMode = mode, port = port, portError = null)
        }
    }

    /** 连接并列出根目录，用于「测试连接」。连接使用 IO 调度器，离开页面时关闭。 */
    fun testConnection() {
        val validated = validateForm()
        if (validated.hasValidationError) {
            _messages.trySend("请先修正标红的输入项")
            return
        }
        if (validated.isTesting || validated.isSaving) return

        _uiState.update { it.copy(isTesting = true) }
        val profile = buildProfile()
        testJob?.cancel()
        testJob = viewModelScope.launch {
            try {
                val entryCount = withContext(Dispatchers.IO) { connectAndListRoot(profile) }
                _messages.send("连接成功：根目录共 $entryCount 项")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("连接失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isTesting = false) }
            }
        }
    }

    /** 保存配置（upsert）；成功后通知界面返回。 */
    fun save() {
        val validated = validateForm()
        if (validated.hasValidationError) {
            _messages.trySend("请先修正标红的输入项")
            return
        }
        if (validated.isSaving || validated.isTesting) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                serverRepository.save(buildProfile())
                _events.send(ServerEditorEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false) }
                _messages.send("保存失败：${e.toUserMessage()}")
            }
        }
    }

    override fun onCleared() {
        testJob?.cancel()
        closeActiveConnection()
        super.onCleared()
    }

    private fun createInitialState(existing: ServerProfile?): ServerEditorUiState {
        if (existing == null) return ServerEditorUiState()
        return ServerEditorUiState(
            isNew = false,
            name = existing.name,
            protocol = existing.protocol,
            host = existing.host,
            port = existing.port.toString(),
            username = existing.username,
            password = existing.password,
            initialPath = existing.initialPath,
            ftpsMode = existing.ftpsMode,
            passiveMode = existing.passiveMode,
            allowInsecureTls = existing.allowInsecureTls
        )
    }

    private fun validateForm(): ServerEditorUiState {
        val current = _uiState.value
        val nameError = if (current.name.isBlank()) "请输入服务器名称" else null
        val host = current.host.trim()
        val hostError = when {
            host.isEmpty() -> "请输入主机地址"
            host.contains(' ') -> "主机地址不能包含空格"
            host.contains('/') -> "主机地址只需填写 IP 或域名"
            else -> null
        }
        val portValue = current.port.toIntOrNull()
        val portError = when {
            current.port.isBlank() -> "请输入端口"
            portValue == null -> "端口必须是数字"
            portValue !in 1..65535 -> "端口需在 1-65535 之间"
            else -> null
        }
        val validated = current.copy(nameError = nameError, hostError = hostError, portError = portError)
        _uiState.value = validated
        return validated
    }

    private fun buildProfile(): ServerProfile {
        val current = _uiState.value
        val existing = existingProfile
        return ServerProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = current.name.trim(),
            protocol = current.protocol,
            host = current.host.trim(),
            port = current.port.toIntOrNull() ?: current.protocol.defaultPort,
            username = current.username.trim(),
            password = current.password,
            initialPath = normalizeInitialPath(current.initialPath),
            ftpsMode = current.ftpsMode,
            passiveMode = current.passiveMode,
            allowInsecureTls = current.allowInsecureTls,
            lastUsedAt = existing?.lastUsedAt ?: 0L
        )
    }

    private fun normalizeInitialPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return RemotePath.ROOT
        val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return RemotePath.normalize(absolute)
    }

    private suspend fun connectAndListRoot(profile: ServerProfile): Int = withContext(Dispatchers.IO) {
        val fileSystem = RemoteFileSystems.create(profile)
        activeConnection = fileSystem
        try {
            fileSystem.connect()
            fileSystem.list(RemotePath.ROOT).size
        } finally {
            closeActiveConnection()
        }
    }

    private fun closeActiveConnection() {
        val connection = activeConnection ?: return
        activeConnection = null
        try {
            connection.close()
        } catch (_: Exception) {
            // 关闭连接时的异常不影响页面状态，忽略即可。
        }
    }

    private companion object {
        const val IMPLICIT_FTPS_PORT = "990"
    }
}

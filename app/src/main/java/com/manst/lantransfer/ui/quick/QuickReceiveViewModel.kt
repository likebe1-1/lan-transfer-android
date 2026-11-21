package com.manst.lantransfer.ui.quick

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.QuickJoinTarget
import com.manst.lantransfer.quick.QuickReceiveManager
import com.manst.lantransfer.quick.QuickReceivePhase
import com.manst.lantransfer.quick.QuickReceiveState
import com.manst.lantransfer.quick.QuickSessionCode
import com.manst.lantransfer.quick.QuickSessionInfo
import com.manst.lantransfer.quick.QuickTransferDefaults
import com.manst.lantransfer.ui.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 接收端快捷传文件页状态（含手动输入的本地表单）。 */
data class QuickReceiveUiState(
    val receive: QuickReceiveState = QuickReceiveState(),
    val isJoining: Boolean = false,
    val isLeaving: Boolean = false,
    val host: String = "",
    val code: String = "",
    val protocol: TransferProtocol? = null,
    val hostError: String? = null,
    val codeError: String? = null,
    val lastTarget: QuickJoinTarget? = null
) {
    val isConnected: Boolean get() = receive.isConnected
    val isDiscovering: Boolean get() = receive.phase == QuickReceivePhase.SCANNING
    val isTransferring: Boolean get() = receive.phase == QuickReceivePhase.TRANSFERRING
    val isFailed: Boolean get() = receive.phase == QuickReceivePhase.FAILED
    val activeProtocolLabel: String get() = receive.activeProtocol?.label ?: ""
    val joinedDeviceName: String
        get() = receive.joined?.deviceName?.takeIf { it.isNotBlank() }
            ?: receive.joined?.primaryHost
            ?: "对方设备"
}

/**
 * 接收端 ViewModel：局域网发现、手动加入、下载/互传等动作全部委托给 [QuickReceiveManager]，
 * 界面只消费其 StateFlow。进入页面自动开始发现，离开页面停止发现并断开可能残留的连接。
 */
class QuickReceiveViewModel(
    private val quickReceiveManager: QuickReceiveManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickReceiveUiState(receive = quickReceiveManager.state.value))
    val uiState: StateFlow<QuickReceiveUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotifiedMessage: String? = null
    private var lastNotifiedError: String? = null

    init {
        viewModelScope.launch {
            quickReceiveManager.state.collect { receiveState ->
                _uiState.update { it.copy(receive = receiveState) }
                if (receiveState.message != lastNotifiedMessage) {
                    lastNotifiedMessage = receiveState.message
                    receiveState.message?.takeIf { it.isNotBlank() }?.let { _messages.trySend(it) }
                }
                if (receiveState.error != lastNotifiedError) {
                    lastNotifiedError = receiveState.error
                    receiveState.error?.takeIf { it.isNotBlank() }?.let { _messages.trySend("错误：$it") }
                }
            }
        }
        startDiscoveryQuietly(userInitiated = false)
    }

    // ------------------------------------------------------------------ 手动表单

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value, hostError = null) }

    fun onCodeChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(QuickTransferDefaults.CODE_LENGTH)
        _uiState.update { it.copy(code = digits, codeError = null) }
    }

    fun onProtocolChange(protocol: TransferProtocol?) = _uiState.update { it.copy(protocol = protocol) }

    /** 手动输入 IP + 接收码加入；格式不合法时内联报错。 */
    fun joinManual() {
        if (_uiState.value.isJoining) return
        val current = _uiState.value
        val host = current.host.trim()
        val normalizedCode = QuickSessionCode.normalize(current.code)
        val hostError = when {
            host.isBlank() -> "请输入对方 IP 地址"
            host.contains(' ') -> "IP 地址不能包含空格"
            else -> null
        }
        val codeError = if (normalizedCode == null) "请输入接收码（8 位数字）" else null
        _uiState.update { it.copy(hostError = hostError, codeError = codeError) }
        if (hostError != null || normalizedCode == null) {
            _messages.trySend("请先填写对方 IP 和接收码（8 位数字）")
            return
        }
        joinTarget(
            QuickJoinTarget(
                code = normalizedCode,
                host = host,
                protocol = current.protocol
            )
        )
    }

    /**
     * 点击附近设备后（广播不再携带接收码）用用户输入的接收码连接；
     * 协议自动选择，加密优先。
     */
    fun joinDiscovered(info: QuickSessionInfo, code: String) {
        if (_uiState.value.isJoining || _uiState.value.isConnected) return
        val host = info.primaryHost
        if (host.isBlank()) {
            _messages.trySend("该设备没有可用的局域网地址")
            return
        }
        val normalizedCode = QuickSessionCode.normalize(code)
        if (normalizedCode == null) {
            _messages.trySend("请输入接收码（8 位数字）")
            return
        }
        joinTarget(
            QuickJoinTarget(
                code = normalizedCode,
                host = host,
                deviceName = info.deviceName,
                protocol = null,
                ftpPort = info.ftpPort,
                ftpsPort = info.ftpsPort,
                sshPort = info.sshPort,
                ftpsFingerprintSha256 = info.ftpsFingerprintSha256
            )
        )
    }

    /** 扫码解析出完整会话信息（含接收码）后直接连接（协议自动选择，加密优先）。 */
    fun joinDiscovered(info: QuickSessionInfo) {
        if (_uiState.value.isJoining || _uiState.value.isConnected) return
        val host = info.primaryHost
        if (host.isBlank()) {
            _messages.trySend("该设备没有可用的局域网地址")
            return
        }
        joinTarget(
            QuickJoinTarget(
                code = info.code,
                host = host,
                deviceName = info.deviceName,
                protocol = null,
                ftpPort = info.ftpPort,
                ftpsPort = info.ftpsPort,
                sshPort = info.sshPort
            )
        )
    }

    /** 扫码解析出的会话信息：与点击附近设备一致，协议自动选择（加密优先）。 */
    fun joinScanned(info: QuickSessionInfo) = joinDiscovered(info)

    /** 用上次的目标重试连接（连接失败后的重试按钮）。 */
    fun retryJoin() {
        val target = _uiState.value.lastTarget
        if (target == null) {
            _messages.trySend("请重新选择设备或输入 IP 与接收码")
            return
        }
        joinTarget(target)
    }

    fun restartDiscovery() = startDiscoveryQuietly(userInitiated = true)

    // ------------------------------------------------------------------ 连接内操作

    fun downloadAll() {
        if (_uiState.value.isTransferring || _uiState.value.isJoining) return
        viewModelScope.launch {
            try {
                quickReceiveManager.downloadAll()
                    .onFailure { error -> _messages.send("接收失败：${error.toUserMessage()}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("接收失败：${e.toUserMessage()}")
            }
        }
    }

    fun download(entry: RemoteEntry) {
        if (_uiState.value.isTransferring) return
        viewModelScope.launch {
            try {
                quickReceiveManager.download(entry.path)
                    .onFailure { error -> _messages.send("接收失败：${error.toUserMessage()}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("接收失败：${e.toUserMessage()}")
            }
        }
    }

    /** 重试失败清单里的某个文件（按展示名在对方文件列表中定位）。 */
    fun retryFailed(displayName: String) {
        val entry = _uiState.value.receive.remoteFiles.firstOrNull { it.name == displayName }
        if (entry == null) {
            _messages.trySend("找不到文件「$displayName」，请重新连接后再试")
            return
        }
        download(entry)
    }

    /** 互传：把自己的文件上传给对方。 */
    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val limited = uris.take(QuickTransferDefaults.MAX_FILES)
        viewModelScope.launch {
            try {
                quickReceiveManager.sendFiles(limited)
                    .onFailure { error -> _messages.send("发送失败：${error.toUserMessage()}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("发送失败：${e.toUserMessage()}")
            }
        }
    }

    fun leave() {
        if (_uiState.value.isLeaving) return
        _uiState.update { it.copy(isLeaving = true) }
        viewModelScope.launch {
            try {
                quickReceiveManager.leave()
                _messages.send("已断开连接")
                startDiscoveryQuietly(userInitiated = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("断开连接失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isLeaving = false) }
            }
        }
    }

    override fun onCleared() {
        try {
            quickReceiveManager.stopDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "停止局域网发现失败", e)
        }
        val receiveState = _uiState.value.receive
        if (receiveState.isConnected || receiveState.phase == QuickReceivePhase.CONNECTING) {
            cleanupScope.launch {
                try {
                    quickReceiveManager.leave()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "页面退出时断开连接失败", e)
                }
            }
        }
        super.onCleared()
    }

    // ------------------------------------------------------------------ 内部实现

    private fun joinTarget(target: QuickJoinTarget) {
        if (_uiState.value.isJoining) return
        _uiState.update { it.copy(isJoining = true, lastTarget = target) }
        viewModelScope.launch {
            try {
                quickReceiveManager.join(target)
                    .onSuccess { _messages.send("已连接，正在读取对方文件") }
                    .onFailure { error -> _messages.send("连接失败：${error.toUserMessage()}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("连接失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isJoining = false) }
            }
        }
    }

    private fun startDiscoveryQuietly(userInitiated: Boolean) {
        try {
            quickReceiveManager.stopDiscovery()
            quickReceiveManager.startDiscovery()
            if (userInitiated) _messages.trySend("正在重新搜索附近设备…")
        } catch (e: Exception) {
            Log.w(TAG, "开始局域网发现失败", e)
            if (userInitiated) _messages.trySend("开始搜索失败：${e.toUserMessage()}")
        }
    }

    private companion object {
        const val TAG = "QuickReceiveViewModel"
    }
}
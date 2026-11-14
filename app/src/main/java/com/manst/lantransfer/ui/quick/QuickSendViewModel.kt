package com.manst.lantransfer.ui.quick

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.quick.QuickSendManager
import com.manst.lantransfer.quick.QuickSendPhase
import com.manst.lantransfer.quick.QuickSendState
import com.manst.lantransfer.ui.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** 发送端快捷传文件页状态。 */
data class QuickSendUiState(
    val session: QuickSendState = QuickSendState(),
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val lastSelectedCount: Int = 0
) {
    val isActive: Boolean get() = session.phase == QuickSendPhase.WAITING
    val isPreparing: Boolean get() = session.phase == QuickSendPhase.PREPARING || isStarting
    val isStoppingPhase: Boolean get() = session.phase == QuickSendPhase.STOPPING || isStopping
    val codeDisplay: String get() = formatSessionCode(session.code)
}

/**
 * 发送端 ViewModel：状态完全来自 [QuickSendManager]；
 * 页面可见时每 2 秒刷新一次会话目录（对方上传的文件会出现在「收到的文件」里）。
 */
class QuickSendViewModel(
    private val quickSendManager: QuickSendManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickSendUiState(session = quickSendManager.state.value))
    val uiState: StateFlow<QuickSendUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var lastSelectedUris: List<Uri> = emptyList()

    /** 页面退出时用于兜底结束会话（viewModelScope 会被取消，需独立作用域）。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            quickSendManager.state.collect { sendState ->
                _uiState.update { it.copy(session = sendState) }
            }
        }
    }

    /** 选择文件后开启会话（页面已限制最多 200 个文件）。 */
    fun startSession(uris: List<Uri>) {
        if (uris.isEmpty() || _uiState.value.isStarting) return
        lastSelectedUris = uris
        _uiState.update { it.copy(isStarting = true, lastSelectedCount = uris.size) }
        viewModelScope.launch {
            try {
                quickSendManager.startSession(uris)
                    .onSuccess {
                        _messages.send("会话已开启，请让对方输入接收码")
                    }
                    .onFailure { error ->
                        _messages.send("开启会话失败：${error.toUserMessage()}")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("开启会话失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isStarting = false) }
            }
        }
    }

    /** 用上次选择的文件重试开启会话。 */
    fun retryStart() {
        if (lastSelectedUris.isEmpty()) {
            _messages.trySend("请重新选择要发送的文件")
            return
        }
        startSession(lastSelectedUris)
    }

    /** 结束会话（不退出页面）。 */
    fun stopSession() {
        if (_uiState.value.isStopping) return
        _uiState.update { it.copy(isStopping = true) }
        viewModelScope.launch {
            try {
                quickSendManager.stopSession()
                _messages.send("会话已结束")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("结束会话失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isStopping = false) }
            }
        }
    }

    /** 页面可见期间的轮询循环（由界面用 repeatOnLifecycle 驱动）。 */
    suspend fun pollWhileActive() {
        while (true) {
            if (_uiState.value.session.phase == QuickSendPhase.WAITING) {
                try {
                    quickSendManager.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "刷新收到的文件失败", e)
                }
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    /** 把收到的对方文件导出到系统下载目录。 */
    fun exportReceived(fileName: String) {
        viewModelScope.launch {
            try {
                quickSendManager.exportReceived(fileName)
                    .onSuccess { savedName -> _messages.send("已导出到系统下载目录：$savedName") }
                    .onFailure { error -> _messages.send("导出失败：${error.toUserMessage()}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("导出失败：${e.toUserMessage()}")
            }
        }
    }

    override fun onCleared() {
        // 兜底：用户以非预期方式离开页面时结束会话，避免端口与会话目录泄漏。
        if (_uiState.value.session.phase != QuickSendPhase.IDLE) {
            cleanupScope.launch {
                try {
                    quickSendManager.stopSession()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "页面退出时结束会话失败", e)
                }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "QuickSendViewModel"
        const val REFRESH_INTERVAL_MS = 2_000L
    }
}

package com.manst.lantransfer.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.transfer.TransferManager
import com.manst.lantransfer.transfer.TransferTask
import com.manst.lantransfer.ui.common.toUserMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/** 传输队列页状态：进行中与已完成两个分区。 */
data class TransfersUiState(
    val active: List<TransferTask> = emptyList(),
    val finished: List<TransferTask> = emptyList()
) {
    val isEmpty: Boolean get() = active.isEmpty() && finished.isEmpty()
}

class TransfersViewModel(
    private val transferManager: TransferManager
) : ViewModel() {

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val uiState: StateFlow<TransfersUiState> = transferManager.tasks
        .map { tasks ->
            TransfersUiState(
                active = tasks.filter { it.isActive },
                finished = tasks.filter { it.isFinished }
                    .sortedByDescending { it.finishedAt }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransfersUiState(
                active = transferManager.tasks.value.filter { it.isActive },
                finished = transferManager.tasks.value.filter { it.isFinished }
                    .sortedByDescending { it.finishedAt }
            )
        )

    fun cancel(task: TransferTask) = runManagerAction("取消任务") { transferManager.cancel(task.id) }

    fun retry(task: TransferTask) = runManagerAction("重试任务") { transferManager.retry(task.id) }

    fun remove(task: TransferTask) = runManagerAction("移除任务") { transferManager.remove(task.id) }

    /** 清空历史记录（已完成/失败/取消的任务）。 */
    fun clearHistory() = runManagerAction("清空历史") { transferManager.clearHistory() }

    private fun runManagerAction(label: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            _messages.trySend("${label}失败：${e.toUserMessage()}")
        }
    }
}
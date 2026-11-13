package com.manst.lantransfer.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.manst.lantransfer.core.LanTransferException
import kotlinx.coroutines.flow.Flow

/**
 * 把异常转换为可直接展示给用户的中文文案。
 * 协议层已经把错误映射为 [LanTransferException] 子类，这里只负责兜底。
 */
fun Throwable.toUserMessage(): String {
    val raw = message
    return when {
        raw.isNullOrBlank() -> "操作失败，请稍后重试"
        this is LanTransferException -> raw
        else -> "操作失败：$raw"
    }
}

/**
 * 轻量 ViewModel 工厂：屏幕所需的仓库/管理器都来自 [com.manst.lantransfer.AppContainer]，
 * 因此不需要为每个 ViewModel 单独实现 Factory。
 */
class SimpleViewModelFactory<VM : ViewModel>(private val creator: () -> VM) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}

/** 用 Snackbar 展示 ViewModel 发出的一次性消息；页面销毁时自动取消收集。 */
@Composable
fun SnackbarMessageEffect(messages: Flow<String>, snackbarHostState: SnackbarHostState) {
    LaunchedEffect(messages, snackbarHostState) {
        messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
}

package com.manst.lantransfer.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.transfer.TransferManager
import com.manst.lantransfer.ui.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** 服务器列表页状态：最近使用优先的服务器列表 + 进行中的传输数量（用于角标）。 */
data class ServerListUiState(
    val servers: List<ServerProfile> = emptyList(),
    val activeTransfers: Int = 0
)

class ServerListViewModel(
    private val serverRepository: ServerRepository,
    private val transferManager: TransferManager
) : ViewModel() {

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val uiState: StateFlow<ServerListUiState> = combine(
        serverRepository.profiles,
        transferManager.activeCount
    ) { profiles, activeCount ->
        ServerListUiState(
            servers = sortProfiles(profiles),
            activeTransfers = activeCount
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServerListUiState(servers = sortProfiles(serverRepository.profiles.value))
    )

    /** 删除服务器配置；删除后 Snackbar 提示结果。 */
    fun delete(profile: ServerProfile) {
        viewModelScope.launch {
            try {
                serverRepository.delete(profile.id)
                _messages.send("已删除「${profile.name}」")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("删除失败：${e.toUserMessage()}")
            }
        }
    }

    private fun sortProfiles(profiles: List<ServerProfile>): List<ServerProfile> =
        profiles.sortedWith(
            compareByDescending<ServerProfile> { it.lastUsedAt }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
}

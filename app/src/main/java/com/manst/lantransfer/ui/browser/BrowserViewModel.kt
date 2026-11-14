package com.manst.lantransfer.ui.browser

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemoteEntryOrder
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.protocol.RemoteFileSystem
import com.manst.lantransfer.protocol.RemoteFileSystems
import com.manst.lantransfer.transfer.TransferManager
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

/** 远程浏览页状态。 */
data class BrowserUiState(
    val profile: ServerProfile? = null,
    val profileMissing: Boolean = false,
    val currentPath: String = RemotePath.ROOT,
    val entries: List<RemoteEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null
) {
    val canGoUp: Boolean get() = RemotePath.parent(currentPath) != null
}

/**
 * 远程浏览页 ViewModel。
 *
 * 连接策略遵循架构约束「一任务一连接」：每次列目录/建目录/重命名/删除都会新建
 * [RemoteFileSystem]，操作结束立即关闭；页面销毁时关闭所有仍在使用的连接。
 * 所有协议调用都运行在 [Dispatchers.IO]。
 */
class BrowserViewModel(
    private val serverRepository: ServerRepository,
    private val transferManager: TransferManager,
    private val contentResolver: ContentResolver,
    private val profileId: String,
    initialPath: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val connections = mutableSetOf<RemoteFileSystem>()
    private var loadJob: Job? = null

    init {
        val profile = serverRepository.find(profileId)
        if (profile == null) {
            _uiState.value = BrowserUiState(
                profileMissing = true,
                isLoading = false,
                currentPath = RemotePath.normalize(initialPath)
            )
        } else {
            val startPath = RemotePath.normalize(initialPath)
            _uiState.value = BrowserUiState(profile = profile, currentPath = startPath, isLoading = true)
            markRecentlyUsed(profile)
            loadDirectory(startPath)
        }
    }

    /** 打开某个目录（面包屑/进入子目录共用）。 */
    fun navigateTo(path: String) {
        val normalized = RemotePath.normalize(path)
        if (normalized == _uiState.value.currentPath && !_uiState.value.isLoading) return
        loadDirectory(normalized)
    }

    /** 返回上级目录；已在根目录时返回 false，由界面决定退出页面。 */
    fun navigateUp(): Boolean {
        val parent = RemotePath.parent(_uiState.value.currentPath) ?: return false
        loadDirectory(parent)
        return true
    }

    fun refresh() = loadDirectory(_uiState.value.currentPath)

    /** 下载文件：交给传输队列执行，本页只负责入队与提示。 */
    fun download(entry: RemoteEntry) {
        val profile = _uiState.value.profile ?: return
        try {
            transferManager.enqueueDownload(profile, entry)
            _messages.trySend("已加入下载队列：${entry.name}")
        } catch (e: Exception) {
            _messages.trySend("加入下载队列失败：${e.toUserMessage()}")
        }
    }

    /** 下载当前所在文件夹（递归，保留子目录结构）。 */
    fun downloadCurrentFolder() {
        val profile = _uiState.value.profile ?: return
        if (_uiState.value.isBusy) return
        val path = _uiState.value.currentPath
        val rawName = RemotePath.name(path)
        val folderName = if (rawName == RemotePath.ROOT || rawName.isBlank()) "全部文件" else rawName
        try {
            transferManager.enqueueDownloadFolder(profile, path, folderName)
            _messages.trySend("已加入文件夹下载队列：$folderName")
        } catch (e: Exception) {
            _messages.trySend("文件夹下载入队失败：${e.toUserMessage()}")
        }
    }

    /** 递归上传 SAF 目录树到当前目录（保留子目录结构）。 */
    fun uploadFolder(treeUri: Uri) {
        val profile = _uiState.value.profile ?: return
        takePersistablePermission(treeUri)
        viewModelScope.launch {
            try {
                transferManager.enqueueUploadFolder(profile, treeUri, _uiState.value.currentPath)
                _messages.send("已加入文件夹上传队列")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("文件夹上传入队失败：${e.toUserMessage()}")
            }
        }
    }

    /** 上传 SAF 选中的文件到当前目录（逐个记录持久化读权限，便于进程重启后断点续传）。 */
    fun upload(uris: List<Uri>) {
        val profile = _uiState.value.profile ?: return
        if (uris.isEmpty()) return
        val remoteDir = _uiState.value.currentPath
        viewModelScope.launch {
            var queued = 0
            var failed = 0
            for (uri in uris) {
                takePersistablePermission(uri)
                val metadata = try {
                    withContext(Dispatchers.IO) { resolveUploadMetadata(uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "读取上传文件信息失败：$uri", e)
                    null
                }
                if (metadata == null) {
                    failed++
                    continue
                }
                try {
                    transferManager.enqueueUpload(profile, uri, metadata.first, metadata.second, remoteDir)
                    queued++
                } catch (e: Exception) {
                    Log.w(TAG, "上传任务入队失败：$uri", e)
                    failed++
                }
            }
            val message = buildString {
                append("已加入上传队列：$queued 个文件")
                if (failed > 0) append("，$failed 个失败")
            }
            _messages.send(message)
        }
    }

    fun createDirectory(name: String) {
        val trimmed = name.trim()
        val invalid = when {
            trimmed.isEmpty() -> "文件夹名称不能为空"
            trimmed.contains('/') -> "文件夹名称不能包含 /"
            else -> null
        }
        if (invalid != null) {
            _messages.trySend(invalid)
            return
        }
        val currentPath = _uiState.value.currentPath
        performRemoteAction(
            action = "创建文件夹",
            successMessage = "已创建文件夹「$trimmed」"
        ) { fileSystem ->
            fileSystem.mkdir(RemotePath.join(currentPath, trimmed))
        }
    }

    fun rename(entry: RemoteEntry, newName: String) {
        val trimmed = newName.trim()
        val invalid = when {
            trimmed.isEmpty() -> "名称不能为空"
            trimmed.contains('/') -> "名称不能包含 /"
            trimmed == entry.name -> "名称与原名相同"
            else -> null
        }
        if (invalid != null) {
            _messages.trySend(invalid)
            return
        }
        val parent = RemotePath.parent(entry.path) ?: RemotePath.ROOT
        performRemoteAction(
            action = "重命名",
            successMessage = "已重命名为「$trimmed」"
        ) { fileSystem ->
            fileSystem.rename(entry.path, RemotePath.join(parent, trimmed))
        }
    }

    fun delete(entry: RemoteEntry) {
        performRemoteAction(
            action = "删除",
            successMessage = "已删除「${entry.name}」"
        ) { fileSystem ->
            fileSystem.delete(entry.path, entry.isDirectory)
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        val snapshot = synchronized(connections) { connections.toList() }
        snapshot.forEach { fileSystem ->
            try {
                fileSystem.close()
            } catch (_: Exception) {
                // 页面销毁时关闭连接失败的异常没有可恢复动作，忽略即可。
            }
        }
        synchronized(connections) { connections.clear() }
        super.onCleared()
    }

    private fun loadDirectory(path: String) {
        val profile = _uiState.value.profile ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(currentPath = path, isLoading = true, errorMessage = null) }
            try {
                val entries = withRemoteConnection(profile) { fileSystem -> fileSystem.list(path) }
                _uiState.update {
                    it.copy(
                        entries = entries.sortedWith(RemoteEntryOrder),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(entries = emptyList(), isLoading = false, errorMessage = e.toUserMessage())
                }
            }
        }
    }

    private fun performRemoteAction(
        action: String,
        successMessage: String,
        block: suspend (RemoteFileSystem) -> Unit
    ) {
        val profile = _uiState.value.profile ?: return
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                withRemoteConnection(profile) { fileSystem -> block(fileSystem) }
                _messages.send(successMessage)
                loadDirectory(_uiState.value.currentPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$action 失败：${e.toUserMessage()}")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    /** 新建连接 -> 执行操作 -> 关闭连接，保证每次操作都是独立连接。 */
    private suspend fun <T> withRemoteConnection(
        profile: ServerProfile,
        block: suspend (RemoteFileSystem) -> T
    ): T = withContext(Dispatchers.IO) {
        val fileSystem = RemoteFileSystems.create(profile)
        registerConnection(fileSystem)
        try {
            fileSystem.connect()
            block(fileSystem)
        } finally {
            unregisterConnection(fileSystem)
            try {
                fileSystem.close()
            } catch (_: Exception) {
                // 关闭连接失败不应覆盖真正的业务异常。
            }
        }
    }

    private fun registerConnection(fileSystem: RemoteFileSystem) {
        synchronized(connections) { connections.add(fileSystem) }
    }

    private fun unregisterConnection(fileSystem: RemoteFileSystem) {
        synchronized(connections) { connections.remove(fileSystem) }
    }

    private fun markRecentlyUsed(profile: ServerProfile) {
        viewModelScope.launch {
            try {
                serverRepository.markUsed(profile.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "记录服务器最近使用时间失败", e)
            }
        }
    }

    /**
     * 记录 SAF 授予的持久化读权限（失败忽略：部分来源只提供临时授权，
     * 进程重启后的断点续传会退化为重新入队）。
     */
    private fun takePersistablePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // 该 Uri 只提供临时授权，忽略即可。
        } catch (_: Exception) {
            // 个别厂商的 SAF 实现会抛其他异常，同样忽略。
        }
    }

    private fun resolveUploadMetadata(uri: Uri): Pair<String, Long>? {
        var displayName: String? = null
        var size = -1L
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        return name to size
    }

    private companion object {
        const val TAG = "BrowserViewModel"
    }
}
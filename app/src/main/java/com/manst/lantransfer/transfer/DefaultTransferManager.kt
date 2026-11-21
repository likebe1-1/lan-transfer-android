package com.manst.lantransfer.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.protocol.RemoteFileSystem
import com.manst.lantransfer.protocol.RemoteFileSystems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 传输队列默认实现（v2：持久化 / 历史 / 自动重试 / 断点续传 / 文件夹递归）。
 *
 * 设计：
 * - 单 worker 协程串行执行队列，[tasks] 是 UI 的唯一数据源；
 * - 每个任务使用独立的 [RemoteFileSystem] 连接，任务结束（含失败、取消）立即关闭；
 * - 队列与历史通过 [TransferStore] 持久化（500ms 节流）：进程重启后未完成任务恢复为 QUEUED 并自动继续，
 *   已结束任务作为历史保留（默认最多 200 条，见 [TransferHistoryLimit]）；
 * - 下载先写入 cacheDir/transfer/<taskId>.part（支持断点续传），完成后复制到 MediaStore 下载目录并删除 staging；
 *   失败时保留 staging 以便重试或重启后续传；
 * - 自动重试：非用户取消的 IO/网络错误最多重试 3 次（退避 2s/4s/8s），认证/路径错误不重试；
 * - 文件夹传输：递归列出远端目录 / 遍历 SAF 目录树，拆分为逐文件任务，串行执行；
 * - 有活动任务时启动 [TransferService] 前台保活，队列清空后停止。
 */
class DefaultTransferManager(
    context: Context,
    private val serverRepository: ServerRepository,
    private val transferStore: TransferStore = JsonTransferStore(
        File(context.applicationContext.filesDir, TRANSFER_STORE_FILE_NAME),
        // 传输队列里也会保存 profile（含密码），统一用 Keystore 加密落盘
        com.manst.lantransfer.data.AndroidKeystoreSecretCipher()
    )
) : TransferManager {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()

    private val stagingDirectory: File = File(appContext.cacheDir, STAGING_DIRECTORY_NAME)

    private val queueModel = TransferQueueModel()
    private val mutableTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    private val mutableActiveCount = MutableStateFlow(0)

    private val requests = HashMap<String, TransferRequest>()
    private val trackers = HashMap<String, TransferProgressTracker>()
    private val activeConnections = ConcurrentHashMap<String, RemoteFileSystem>()
    private val cancelRequests = ConcurrentHashMap.newKeySet<String>()
    private val downloadStore = MediaStoreDownloadStore(appContext)

    private var persistJob: Job? = null

    override val tasks: StateFlow<List<TransferTask>> = mutableTasks.asStateFlow()

    override val activeCount: StateFlow<Int> = mutableActiveCount.asStateFlow()

    init {
        stagingDirectory.mkdirs()
        restorePersistedTasks()
        scope.launch { runQueueLoop() }
    }

    // ------------------------------------------------------------ 入队

    override fun enqueueDownload(profile: ServerProfile, entry: RemoteEntry): String =
        enqueue(TransferRequest.Download(profile = profile, entry = entry))

    override fun enqueueUpload(
        profile: ServerProfile,
        sourceUri: Uri,
        displayName: String,
        size: Long,
        remoteDir: String
    ): String {
        val remoteName = TransferFileNames.sanitize(displayName)
        val remotePath = RemotePath.join(remoteDir, remoteName)
        takePersistableReadPermission(sourceUri)
        return enqueue(
            TransferRequest.Upload(
                profile = profile,
                sourceUri = sourceUri.toString(),
                displayName = remoteName,
                remotePath = remotePath,
                totalBytes = size
            )
        )
    }

    private fun enqueue(request: TransferRequest): String {
        val task = createTask(request)
        val startService: Boolean
        synchronized(stateLock) {
            requests[task.id] = request
            queueModel.add(task)
            publishLocked()
            startService = queueModel.countActive() == 1
        }
        if (startService) ensureForegroundService()
        return task.id
    }

    private fun createTask(request: TransferRequest): TransferTask = TransferTask(
        id = UUID.randomUUID().toString(),
        profileId = request.profile.id,
        serverName = request.profile.name,
        protocolLabel = request.profile.protocol.label,
        direction = request.direction,
        displayName = request.displayName,
        remotePath = request.remotePath,
        sourceUri = request.sourceUri,
        localRelativeDir = (request as? TransferRequest.Download)?.localRelativeDir,
        totalBytes = request.totalBytes,
        profileSnapshot = request.profile,
        createdAt = System.currentTimeMillis()
    )

    // ------------------------------------------------------------ 文件夹传输

    override fun enqueueDownloadFolder(
        profile: ServerProfile,
        remoteDir: String,
        folderDisplayName: String
    ): String {
        val batchId = UUID.randomUUID().toString()
        val displayFolder = TransferFileNames.sanitize(folderDisplayName)
        scope.launch(Dispatchers.IO) {
            val fileSystem = RemoteFileSystems.create(profile)
            try {
                fileSystem.connect()
                val entries = collectRemoteFiles(fileSystem, RemotePath.normalize(remoteDir), displayFolder)
                if (entries.isEmpty()) {
                    notifyFolderFailure(profile, displayFolder, TransferDirection.DOWNLOAD, "文件夹为空或没有可下载的文件")
                    return@launch
                }
                addFolderTasks(profile, entries, TransferDirection.DOWNLOAD)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "递归列出远端文件夹失败：" + remoteDir, e)
                notifyFolderFailure(
                    profile = profile,
                    folderName = displayFolder,
                    direction = TransferDirection.DOWNLOAD,
                    message = "文件夹下载失败：" + TransferErrors.toUserMessage(e, TransferDirection.DOWNLOAD)
                )
            } finally {
                safeClose(fileSystem)
            }
        }
        return batchId
    }

    override fun enqueueUploadFolder(profile: ServerProfile, treeUri: Uri, remoteDir: String): String {
        val batchId = UUID.randomUUID().toString()
        val displayFolder = TransferFileNames.sanitize(
            treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "文件夹"
        )
        takePersistableReadPermission(treeUri)
        scope.launch(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(appContext, treeUri)
                    ?: throw IOException("无法读取所选文件夹")
                val entries = ArrayList<FolderEntry>()
                collectDocumentFiles(root, "", remoteDir, entries)
                if (entries.isEmpty()) {
                    notifyFolderFailure(profile, displayFolder, TransferDirection.UPLOAD, "所选文件夹为空或没有可上传的文件")
                    return@launch
                }
                addFolderTasks(profile, entries, TransferDirection.UPLOAD)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "递归读取所选文件夹失败", e)
                notifyFolderFailure(
                    profile = profile,
                    folderName = displayFolder,
                    direction = TransferDirection.UPLOAD,
                    message = "文件夹上传失败：" + TransferErrors.toUserMessage(e, TransferDirection.UPLOAD)
                )
            }
        }
        return batchId
    }

    /** 递归列出远端目录下的所有文件（广度优先，最多 [TransferFolderPaths.MAX_FOLDER_FILES] 个）。 */
    private suspend fun collectRemoteFiles(
        fileSystem: RemoteFileSystem,
        remoteRoot: String,
        folderDisplayName: String
    ): List<FolderEntry> {
        val result = ArrayList<FolderEntry>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(remoteRoot to "")
        while (pending.isNotEmpty()) {
            val (directory, relativeDir) = pending.removeFirst()
            val children = try {
                fileSystem.list(directory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (directory == remoteRoot) throw e
                Log.w(TAG, "跳过无法列出的远端子目录：" + directory, e)
                continue
            }
            for (child in children) {
                val name = child.name
                if (name.isEmpty() || name == "." || name == "..") continue
                val childRelative = if (relativeDir.isEmpty()) name else relativeDir + "/" + name
                if (child.isDirectory) {
                    pending.add(child.path to childRelative)
                } else {
                    if (result.size >= TransferFolderPaths.MAX_FOLDER_FILES) {
                        throw IOException(
                            "文件夹内文件超过 " + TransferFolderPaths.MAX_FOLDER_FILES + " 个，请分批传输"
                        )
                    }
                    val safeName = TransferFileNames.sanitize(name)
                    val safeRelativeFile = TransferFolderPaths.sanitizeRelativePath(childRelative) ?: safeName
                    result.add(
                        FolderEntry(
                            remotePath = child.path,
                            displayName = safeName,
                            localRelativeDir = TransferFolderPaths.downloadLocalRelativeDir(
                                folderDisplayName,
                                safeRelativeFile
                            ),
                            totalBytes = child.size,
                            sourceUri = null
                        )
                    )
                }
            }
        }
        return result
    }

    /** 递归遍历 SAF 目录树，把每个文件映射为一条上传任务（保留远端子目录结构）。 */
    private fun collectDocumentFiles(
        directory: DocumentFile,
        relativePrefix: String,
        remoteBase: String,
        out: MutableList<FolderEntry>
    ) {
        val children = try {
            directory.listFiles()
        } catch (e: RuntimeException) {
            Log.w(TAG, "读取 SAF 子目录失败，已跳过：" + (directory.name ?: relativePrefix), e)
            return
        }
        for (child in children) {
            val name = child.name?.takeIf { it.isNotBlank() } ?: continue
            val childRelative = if (relativePrefix.isEmpty()) name else relativePrefix + "/" + name
            if (child.isDirectory) {
                collectDocumentFiles(child, childRelative, remoteBase, out)
            } else {
                if (out.size >= TransferFolderPaths.MAX_FOLDER_FILES) {
                    throw IOException(
                        "文件夹内文件超过 " + TransferFolderPaths.MAX_FOLDER_FILES + " 个，请分批传输"
                    )
                }
                out.add(
                    FolderEntry(
                        remotePath = TransferFolderPaths.remotePathForUpload(remoteBase, childRelative),
                        displayName = TransferFileNames.sanitize(name),
                        localRelativeDir = null,
                        totalBytes = child.length(),
                        sourceUri = child.uri.toString()
                    )
                )
            }
        }
    }

    private fun addFolderTasks(profile: ServerProfile, entries: List<FolderEntry>, direction: TransferDirection) {
        val prepared = ArrayList<Pair<TransferTask, TransferRequest>>(entries.size)
        for (entry in entries) {
            val request = when (direction) {
                TransferDirection.DOWNLOAD -> TransferRequest.Download(
                    profile = profile,
                    entry = RemoteEntry(
                        name = entry.displayName,
                        path = entry.remotePath,
                        isDirectory = false,
                        size = entry.totalBytes
                    ),
                    localRelativeDir = entry.localRelativeDir
                )
                TransferDirection.UPLOAD -> {
                    val sourceUri = entry.sourceUri ?: continue
                    TransferRequest.Upload(
                        profile = profile,
                        sourceUri = sourceUri,
                        displayName = entry.displayName,
                        remotePath = entry.remotePath,
                        totalBytes = entry.totalBytes
                    )
                }
            }
            val task = createTask(request).copy(localRelativeDir = entry.localRelativeDir)
            prepared.add(task to request)
        }
        if (prepared.isEmpty()) return
        synchronized(stateLock) {
            for ((task, request) in prepared) {
                requests[task.id] = request
                queueModel.add(task)
            }
            publishLocked()
        }
        ensureForegroundService()
        Log.i(TAG, "文件夹任务已入队：" + prepared.size + " 个文件（" + direction.name + "）")
    }

    private fun notifyFolderFailure(
        profile: ServerProfile,
        folderName: String,
        direction: TransferDirection,
        message: String
    ) {
        Log.w(TAG, message)
        val synthetic = TransferTask(
            id = "folder-failure-" + UUID.randomUUID(),
            profileId = profile.id,
            serverName = profile.name,
            protocolLabel = profile.protocol.label,
            direction = direction,
            displayName = folderName,
            remotePath = "",
            state = TransferState.FAILED,
            error = message,
            finishedAt = System.currentTimeMillis()
        )
        TransferNotifications.notifyResult(appContext, synthetic)
    }

    // ------------------------------------------------------------ 队列执行

    private suspend fun runQueueLoop() {
        while (currentCoroutineContext().isActive) {
            val next = tasks.first { list -> list.any { it.state == TransferState.QUEUED } }
                .firstOrNull { it.state == TransferState.QUEUED }
            if (next == null) continue
            runTask(next.id)
        }
    }

    private suspend fun runTask(taskId: String) {
        val execution = beginTask(taskId) ?: return
        ensureForegroundService()
        val fileSystem = RemoteFileSystems.create(execution.request.profile)
        activeConnections[taskId] = fileSystem
        var retryBackoffMillis = 0L
        try {
            if (cancelRequests.contains(taskId)) throw TransferCancelledException()
            withContext(Dispatchers.IO) {
                fileSystem.connect()
                updateTask(taskId) { it.copy(resumable = fileSystem.supportsResume) }
                when (val request = execution.request) {
                    is TransferRequest.Download -> downloadWithStaging(taskId, request, fileSystem)
                    is TransferRequest.Upload -> uploadWithResume(taskId, request, fileSystem)
                }
            }
            val completed = finishTask(taskId, TransferState.COMPLETED, null)
            if (completed != null) {
                TransferNotifications.notifyResult(appContext, completed)
                recordProfileUsage(completed.profileId)
            }
        } catch (e: CancellationException) {
            finishTask(taskId, TransferState.CANCELLED, null)
            throw e
        } catch (e: TransferCancelledException) {
            finishTask(taskId, TransferState.CANCELLED, null)
        } catch (e: Exception) {
            if (cancelRequests.contains(taskId)) {
                finishTask(taskId, TransferState.CANCELLED, null)
            } else {
                retryBackoffMillis = handleTaskFailure(taskId, execution.request.direction, e)
            }
        } finally {
            // 若失败处理期间用户刚好取消，则不再安排自动重试（避免取消被重试覆盖）。
            val cancelledDuringFailure = cancelRequests.contains(taskId)
            activeConnections.remove(taskId)
            cancelRequests.remove(taskId)
            synchronized(stateLock) { trackers.remove(taskId) }
            safeClose(fileSystem)
            if (cancelledDuringFailure && retryBackoffMillis > 0L) {
                retryBackoffMillis = 0L
                finishTask(taskId, TransferState.CANCELLED, null)
            }
            stopForegroundIfIdle()
        }
        if (retryBackoffMillis > 0L) {
            Log.i(TAG, "任务失败，等待 " + retryBackoffMillis + "ms 后自动重试：" + taskId)
            delay(retryBackoffMillis)
        }
    }

    /** 处理任务失败：可重试时重置为 QUEUED 并返回退避毫秒数；否则标记失败并返回 0。 */
    private fun handleTaskFailure(taskId: String, direction: TransferDirection, error: Exception): Long {
        val attempts = synchronized(stateLock) { queueModel.get(taskId)?.attempts ?: 0 }
        val message = TransferErrors.toUserMessage(error, direction)
        if (TransferRetryPolicy.shouldRetry(attempts, error)) {
            val nextAttempt = TransferRetryPolicy.nextAttempt(attempts)
            val backoff = TransferRetryPolicy.backoffMillis(nextAttempt)
            val retryMessage = message + "（" + (backoff / 1000L) + " 秒后自动重试，第 " +
                nextAttempt + "/" + TransferRetryPolicy.MAX_RETRIES + " 次）"
            Log.w(TAG, "传输任务失败，将自动重试：" + taskId + " attempts=" + nextAttempt, error)
            updateTask(taskId) {
                it.copy(
                    state = TransferState.QUEUED,
                    attempts = nextAttempt,
                    error = retryMessage,
                    bytesPerSecond = 0L,
                    startedAt = 0L,
                    finishedAt = 0L
                )
            }
            return backoff
        }
        val finalError = if (attempts > 0) {
            message + "（已自动重试 " + attempts + " 次仍失败）"
        } else {
            message
        }
        Log.w(TAG, "传输任务最终失败：" + taskId + " attempts=" + attempts, error)
        val failed = finishTask(taskId, TransferState.FAILED, finalError)
        if (failed != null) TransferNotifications.notifyResult(appContext, failed)
        return 0L
    }

    /** 取队首任务并原子地切换为 RUNNING。 */
    private fun beginTask(taskId: String): Execution? = synchronized(stateLock) {
        val task = queueModel.get(taskId) ?: return null
        if (task.state != TransferState.QUEUED) return null
        val request = requests[taskId]
        if (request == null) {
            queueModel.update(taskId) {
                it.copy(
                    state = TransferState.FAILED,
                    error = "任务参数丢失，请重新发起传输",
                    finishedAt = System.currentTimeMillis()
                )
            }
            publishLocked()
            return null
        }
        val tracker = TransferProgressTracker()
        trackers[taskId] = tracker
        val running = queueModel.update(taskId) {
            it.copy(
                state = TransferState.RUNNING,
                startedAt = System.currentTimeMillis(),
                bytesPerSecond = 0L,
                error = null
            )
        } ?: return null
        publishLocked()
        Execution(running, request, tracker)
    }

    // ------------------------------------------------------------ 下载（staging + MediaStore）

    private suspend fun downloadWithStaging(
        taskId: String,
        request: TransferRequest.Download,
        fileSystem: RemoteFileSystem
    ) {
        if (currentTask(taskId) == null) throw TransferCancelledException()
        val staging = stagingFile(taskId)
        val remoteSize = resolveRemoteSize(fileSystem, request)
        var startOffset = 0L
        if (fileSystem.supportsResume && staging.isFile && staging.length() > 0L && remoteSize > 0L) {
            startOffset = if (staging.length() <= remoteSize) {
                staging.length()
            } else {
                // 远端文件变小（可能被替换），旧暂存已不可用，重新下载
                staging.delete()
                0L
            }
        }
        if (startOffset == 0L && staging.exists()) staging.delete()
        updateTask(taskId) {
            it.copy(
                totalBytes = if (remoteSize > 0L) remoteSize else it.totalBytes,
                transferredBytes = startOffset,
                resumable = fileSystem.supportsResume
            )
        }
        if (remoteSize <= 0L || startOffset < remoteSize) {
            FileOutputStream(staging, startOffset > 0L).use { sink ->
                if (startOffset > 0L) {
                    fileSystem.downloadPartial(request.remotePath, sink, startOffset) { transferred ->
                        reportProgress(taskId, transferred)
                    }
                } else {
                    fileSystem.download(request.remotePath, sink) { transferred ->
                        reportProgress(taskId, transferred)
                    }
                }
            }
        }
        // staging 下载完成：复制到系统「下载」目录（支持 localRelativeDir 子目录）
        val stagedLength = staging.length()
        val displayName = downloadStore.reserveFileName(request.entry.name, request.localRelativeDir)
        updateTask(taskId) { it.copy(localDisplayName = displayName) }
        var pendingUri: Uri? = null
        try {
            val uri = downloadStore.createPendingFile(displayName, request.localRelativeDir)
            pendingUri = uri
            downloadStore.openSink(uri).use { sink ->
                staging.inputStream().use { source -> source.copyTo(sink, COPY_BUFFER_SIZE) }
            }
            val committedUri = downloadStore.commitAndGetUri(uri)
            pendingUri = null
            updateTask(taskId) { it.copy(localUri = committedUri.toString(), localDisplayName = displayName) }
            staging.delete()
            reportProgress(taskId, stagedLength)
        } catch (e: Exception) {
            pendingUri?.let { downloadStore.discard(it) }
            throw e
        } finally {
            downloadStore.releaseFileName(displayName, request.localRelativeDir)
        }
    }

    private suspend fun resolveRemoteSize(
        fileSystem: RemoteFileSystem,
        request: TransferRequest.Download
    ): Long {
        if (request.entry.size > 0L) return request.entry.size
        return try {
            fileSystem.size(request.remotePath)
        } catch (e: Exception) {
            Log.w(TAG, "获取远端文件大小失败：" + request.remotePath, e)
            -1L
        }
    }

    // ------------------------------------------------------------ 上传（可续传 + 创建父目录）

    private suspend fun uploadWithResume(
        taskId: String,
        request: TransferRequest.Upload,
        fileSystem: RemoteFileSystem
    ) {
        val uri = Uri.parse(request.sourceUri)
        val parent = RemotePath.parent(request.remotePath)
        if (parent != null && parent != RemotePath.ROOT) {
            try {
                fileSystem.mkdirs(parent)
            } catch (e: Exception) {
                Log.w(TAG, "创建远端父目录失败（继续尝试上传）：" + parent, e)
            }
        }
        val task = currentTask(taskId) ?: throw TransferCancelledException()
        val sourceSize = querySourceSize(uri).let { if (it > 0L) it else task.totalBytes }
        var startOffset = 0L
        if (fileSystem.supportsResume && task.transferredBytes > 0L) {
            val remoteSize = try {
                fileSystem.size(request.remotePath)
            } catch (e: Exception) {
                -1L
            }
            if (remoteSize > 0L && remoteSize == task.transferredBytes) startOffset = remoteSize
        }
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw IOException("无法读取本地文件：" + request.displayName, e)
        } ?: throw IOException("无法读取本地文件：" + request.displayName)
        input.use { source ->
            updateTask(taskId) {
                it.copy(
                    totalBytes = if (sourceSize > 0L) sourceSize else it.totalBytes,
                    transferredBytes = startOffset,
                    resumable = fileSystem.supportsResume
                )
            }
            val sizeHint = if (sourceSize > 0L) sourceSize else -1L
            if (startOffset > 0L) {
                // 协议实现约定：传入完整文件流，由实现自行跳过 startOffset 前缀；
                // 进度回调为累计字节数（含 startOffset）。
                fileSystem.uploadPartial(source, request.remotePath, sizeHint, startOffset) { reported ->
                    reportProgress(taskId, reported)
                }
            } else {
                fileSystem.upload(source, request.remotePath, sizeHint) { reported ->
                    reportProgress(taskId, reported)
                }
            }
        }
    }

    private fun querySourceSize(uri: Uri): Long {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return -1L
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "查询本地文件大小失败：" + uri, e)
            -1L
        }
    }

    // ------------------------------------------------------------ 进度

    private fun reportProgress(taskId: String, transferredBytes: Long) {
        val sample = synchronized(stateLock) {
            val task = queueModel.get(taskId) ?: return
            val tracker = trackers[taskId] ?: return
            val total = if (task.totalBytes > 0L) task.totalBytes else -1L
            tracker.sample(SystemClock.elapsedRealtime(), transferredBytes, total)
        } ?: return
        updateTask(taskId) { task ->
            task.copy(
                transferredBytes = sample.transferredBytes,
                bytesPerSecond = sample.bytesPerSecond,
                totalBytes = if (sample.totalBytes > 0L) sample.totalBytes else task.totalBytes
            )
        }
    }

    // ------------------------------------------------------------ 状态更新 / 持久化

    private fun currentTask(taskId: String): TransferTask? =
        synchronized(stateLock) { queueModel.get(taskId) }

    private fun updateTask(taskId: String, transform: (TransferTask) -> TransferTask): TransferTask? =
        synchronized(stateLock) {
            val updated = queueModel.update(taskId, transform)
            if (updated != null) publishLocked()
            updated
        }

    private fun finishTask(taskId: String, state: TransferState, error: String?): TransferTask? {
        val finishedAt = System.currentTimeMillis()
        return synchronized(stateLock) {
            val finished = queueModel.update(taskId) { task ->
                val total = when {
                    task.totalBytes > 0L -> task.totalBytes
                    state == TransferState.COMPLETED -> task.transferredBytes
                    else -> task.totalBytes
                }
                val transferred = if (state == TransferState.COMPLETED && total > 0L) {
                    maxOf(task.transferredBytes, total)
                } else {
                    task.transferredBytes
                }
                task.copy(
                    state = state,
                    error = error,
                    finishedAt = finishedAt,
                    bytesPerSecond = 0L,
                    transferredBytes = transferred,
                    totalBytes = total
                )
            }
            if (finished != null) {
                trackers.remove(taskId)
                requests.remove(taskId)
                publishLocked()
            }
            finished
        }
    }

    private fun publishLocked() {
        val snapshot = queueModel.snapshot()
        mutableTasks.value = snapshot
        mutableActiveCount.value = snapshot.count { it.isActive }
        schedulePersistLocked()
    }

    private fun schedulePersistLocked() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch(Dispatchers.IO) {
            delay(PERSIST_THROTTLE_MILLIS)
            persistNow()
        }
    }

    private fun persistNow() {
        val snapshot = synchronized(stateLock) { queueModel.snapshot() }
        try {
            transferStore.save(snapshot)
        } catch (e: Exception) {
            Log.w(TAG, "保存传输任务快照失败", e)
        }
    }

    private fun restorePersistedTasks() {
        val loaded = try {
            transferStore.load()
        } catch (e: Exception) {
            Log.w(TAG, "读取持久化传输任务失败，按空队列启动", e)
            emptyList()
        }
        if (loaded.isEmpty()) {
            cleanupOrphanStaging(emptySet())
            return
        }
        val restored = ArrayList<TransferTask>(loaded.size)
        val activeIds = HashSet<String>()
        for (task in loaded) {
            val normalized = when (task.state) {
                TransferState.QUEUED, TransferState.RUNNING -> restoreActiveTask(task, activeIds)
                else -> task.copy(bytesPerSecond = 0L)
            }
            restored.add(normalized)
        }
        synchronized(stateLock) {
            restored.forEach { queueModel.add(it) }
            publishLocked()
        }
        val keepStagingIds = restored
            .filter { it.state != TransferState.COMPLETED }
            .mapTo(HashSet()) { it.id }
        cleanupOrphanStaging(keepStagingIds)
        if (activeIds.isNotEmpty()) {
            // 不能在 AppContainer 懒初始化路径里同步启动前台服务：
            // TransferService.onCreate 会再次访问 container.transferManager（同一个 lazy），
            // 同步启动会让主线程等 lazy 锁、服务无法及时 startForeground 而触发
            // ForegroundServiceDidNotStartInTimeException（进程崩溃）。延后到初始化完成后启动。
            scope.launch {
                kotlinx.coroutines.delay(300L)
                ensureForegroundService()
            }
        }
        Log.i(TAG, "已恢复传输任务 " + restored.size + " 条（自动继续 " + activeIds.size + " 条）")
    }

    private fun restoreActiveTask(task: TransferTask, activeIds: MutableSet<String>): TransferTask {
        val failedAt = System.currentTimeMillis()
        val profile = task.profileSnapshot ?: serverRepository.find(task.profileId)
        if (profile == null) {
            return task.copy(
                state = TransferState.FAILED,
                error = "服务器配置已删除，任务无法恢复",
                finishedAt = failedAt,
                bytesPerSecond = 0L,
                startedAt = 0L
            )
        }
        val request = requestFromTask(task, profile)
        if (request == null) {
            return task.copy(
                state = TransferState.FAILED,
                error = "任务缺少源文件地址，无法恢复",
                finishedAt = failedAt,
                bytesPerSecond = 0L,
                startedAt = 0L
            )
        }
        if (task.direction == TransferDirection.UPLOAD && !canReadSource(task.sourceUri)) {
            return task.copy(
                state = TransferState.FAILED,
                error = "本地文件无法读取（授权可能已失效），请重新选择文件后重试",
                finishedAt = failedAt,
                bytesPerSecond = 0L,
                startedAt = 0L
            )
        }
        val protocolSupportsResume = profile.protocol != TransferProtocol.SCP
        val staged = if (task.direction == TransferDirection.DOWNLOAD) stagingFile(task.id) else null
        val hasLocalProgress = when (task.direction) {
            TransferDirection.DOWNLOAD -> staged?.isFile == true && staged.length() > 0L
            TransferDirection.UPLOAD -> task.transferredBytes > 0L
        }
        val resumable = protocolSupportsResume && hasLocalProgress
        requests[task.id] = request
        activeIds.add(task.id)
        return task.copy(
            state = TransferState.QUEUED,
            bytesPerSecond = 0L,
            startedAt = 0L,
            error = null,
            resumable = resumable,
            profileSnapshot = profile
        )
    }

    private fun requestFromTask(task: TransferTask, profile: ServerProfile): TransferRequest? =
        when (task.direction) {
            TransferDirection.DOWNLOAD -> TransferRequest.Download(
                profile = profile,
                entry = RemoteEntry(
                    name = task.displayName,
                    path = task.remotePath,
                    isDirectory = false,
                    size = task.totalBytes
                ),
                localRelativeDir = task.localRelativeDir
            )
            TransferDirection.UPLOAD -> {
                val sourceUri = task.sourceUri
                if (sourceUri.isNullOrBlank()) null
                else TransferRequest.Upload(
                    profile = profile,
                    sourceUri = sourceUri,
                    displayName = task.displayName,
                    remotePath = task.remotePath,
                    totalBytes = task.totalBytes
                )
            }
        }

    private fun canReadSource(sourceUri: String?): Boolean {
        if (sourceUri.isNullOrBlank()) return false
        return try {
            appContext.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { } != null
        } catch (e: Exception) {
            Log.w(TAG, "校验本地文件可读性失败：" + sourceUri, e)
            false
        }
    }

    // ------------------------------------------------------------ 取消 / 重试 / 移除 / 清空

    override fun cancel(taskId: String) {
        var running = false
        var cancelledQueued = false
        synchronized(stateLock) {
            val task = queueModel.get(taskId) ?: return
            when (task.state) {
                TransferState.QUEUED -> {
                    queueModel.update(taskId) {
                        it.copy(
                            state = TransferState.CANCELLED,
                            finishedAt = System.currentTimeMillis(),
                            bytesPerSecond = 0L
                        )
                    }
                    requests.remove(taskId)
                    trackers.remove(taskId)
                    publishLocked()
                    cancelledQueued = true
                }
                TransferState.RUNNING -> {
                    cancelRequests.add(taskId)
                    running = true
                }
                else -> return
            }
        }
        if (running) closeActiveConnection(taskId)
        if (cancelledQueued) Log.i(TAG, "已取消排队中的传输任务：" + taskId)
        stopForegroundIfIdle()
    }

    override fun retry(taskId: String) {
        val task = synchronized(stateLock) { queueModel.get(taskId) } ?: return
        if (!task.isFinished) return
        val profile = serverRepository.find(task.profileId) ?: task.profileSnapshot
        if (profile == null) {
            markTaskFailed(taskId, "服务器配置已删除，无法重试")
            return
        }
        val request = requestFromTask(task, profile)
        if (request == null) {
            markTaskFailed(taskId, "任务缺少源文件地址，无法重试")
            return
        }
        if (task.direction == TransferDirection.UPLOAD && !canReadSource(task.sourceUri)) {
            markTaskFailed(taskId, "本地文件无法读取（授权可能已失效），请重新选择文件后重试")
            return
        }
        synchronized(stateLock) {
            requests[taskId] = request
            queueModel.update(taskId) {
                it.copy(
                    state = TransferState.QUEUED,
                    error = null,
                    attempts = 0,
                    bytesPerSecond = 0L,
                    startedAt = 0L,
                    finishedAt = 0L,
                    profileSnapshot = profile
                )
            }
            publishLocked()
        }
        ensureForegroundService()
    }

    private fun markTaskFailed(taskId: String, message: String) {
        finishTask(taskId, TransferState.FAILED, message)
    }

    override fun remove(taskId: String) {
        var running = false
        synchronized(stateLock) {
            val task = queueModel.get(taskId) ?: return
            if (task.state == TransferState.RUNNING) {
                cancelRequests.add(taskId)
                running = true
            }
            queueModel.remove(taskId)
            requests.remove(taskId)
            trackers.remove(taskId)
            publishLocked()
        }
        if (running) closeActiveConnection(taskId)
        deleteStaging(taskId)
        stopForegroundIfIdle()
    }

    override fun clearFinished() = clearHistory()

    override fun clearHistory() {
        val removed = synchronized(stateLock) {
            val cleared = queueModel.clearFinished()
            cleared.forEach {
                requests.remove(it.id)
                trackers.remove(it.id)
            }
            publishLocked()
            cleared
        }
        removed.forEach { deleteStaging(it.id) }
    }

    // ------------------------------------------------------------ staging / 前台服务 / 连接管理

    private fun stagingFile(taskId: String): File = File(stagingDirectory, taskId + STAGING_SUFFIX)

    private fun deleteStaging(taskId: String) {
        val file = stagingFile(taskId)
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "删除下载暂存文件失败：" + file.absolutePath)
        }
    }

    private fun cleanupOrphanStaging(keepTaskIds: Set<String>) {
        val files = stagingDirectory.listFiles() ?: return
        for (file in files) {
            val taskId = file.name.removeSuffix(STAGING_SUFFIX)
            if (taskId !in keepTaskIds) {
                if (!file.delete()) Log.w(TAG, "清理无用下载暂存文件失败：" + file.absolutePath)
            }
        }
    }

    private fun ensureForegroundService() {
        // 无界面时（仪器测试/后台恢复）不启动前台服务：系统可能拒绝后台 startForegroundService，
        // 且主线程被测试占用时服务无法及时 startForeground，会触发 FGS 超时崩溃。
        val application = appContext as? com.manst.lantransfer.LanTransferApplication
        if (application != null && !application.hasForegroundActivity) {
            Log.i(TAG, "应用当前无前台界面，跳过启动传输前台服务")
            return
        }
        try {
            TransferService.start(appContext)
        } catch (e: RuntimeException) {
            Log.w(TAG, "启动前台传输服务失败，传输继续执行但进程可能被系统回收", e)
        }
    }

    private fun stopForegroundIfIdle() {
        val idle = synchronized(stateLock) { !queueModel.hasActive() }
        if (!idle) return
        try {
            appContext.stopService(Intent(appContext, TransferService::class.java))
        } catch (e: RuntimeException) {
            Log.w(TAG, "停止前台传输服务失败", e)
        }
        TransferNotifications.cancelProgress(appContext)
    }

    private fun closeActiveConnection(taskId: String) {
        val connection = activeConnections[taskId] ?: return
        scope.launch(Dispatchers.IO) { safeClose(connection) }
    }

    private fun safeClose(fileSystem: RemoteFileSystem) {
        try {
            fileSystem.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭远程连接失败", e)
        }
    }

    private fun recordProfileUsage(profileId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                serverRepository.markUsed(profileId)
            } catch (e: Exception) {
                Log.w(TAG, "更新服务器最近使用时间失败", e)
            }
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.i(TAG, "Uri 不支持持久化授权（当前进程内仍可读取）：" + uri)
        } catch (e: RuntimeException) {
            Log.i(TAG, "申请持久化 Uri 授权失败（当前进程内仍可读取）：" + uri, e)
        }
    }

    // ------------------------------------------------------------ 内部类型

    private class Execution(
        val task: TransferTask,
        val request: TransferRequest,
        val tracker: TransferProgressTracker
    )

    private class FolderEntry(
        val remotePath: String,
        val displayName: String,
        val localRelativeDir: String?,
        val totalBytes: Long,
        val sourceUri: String?
    )

    private sealed interface TransferRequest {
        val profile: ServerProfile
        val direction: TransferDirection
        val displayName: String
        val remotePath: String
        val totalBytes: Long
        val sourceUri: String?

        data class Download(
            override val profile: ServerProfile,
            val entry: RemoteEntry,
            val localRelativeDir: String? = null
        ) : TransferRequest {
            override val direction: TransferDirection = TransferDirection.DOWNLOAD
            override val displayName: String = entry.name
            override val remotePath: String = entry.path
            override val totalBytes: Long = entry.size
            override val sourceUri: String? = null
        }

        data class Upload(
            override val profile: ServerProfile,
            override val sourceUri: String,
            override val displayName: String,
            override val remotePath: String,
            override val totalBytes: Long
        ) : TransferRequest {
            override val direction: TransferDirection = TransferDirection.UPLOAD
        }
    }

    private companion object {
        const val TAG = "DefaultTransferManager"
        const val TRANSFER_STORE_FILE_NAME = "transfer_tasks.json"
        const val STAGING_DIRECTORY_NAME = "transfer"
        const val STAGING_SUFFIX = ".part"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PERSIST_THROTTLE_MILLIS = 500L
    }
}
package com.manst.lantransfer.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.protocol.RemoteFileSystem
import com.manst.lantransfer.protocol.RemoteFileSystems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 传输队列默认实现。
 *
 * 设计：
 * - 单 worker 协程串行执行队列中的任务（局域网带宽下并发收益低，且 FTP 控制连接不适合并发）；
 * - [tasks] 是 UI 的唯一数据源，任务状态按 QUEUED -> RUNNING -> COMPLETED/FAILED/CANCELLED 流转；
 * - 每个任务使用独立的 [RemoteFileSystem] 连接，任务结束（含失败、取消）立即关闭；
 * - 进度按 100ms / 128KB 节流推送到 UI，瞬时速度来自 2 秒滑动窗口；
 * - 取消运行中任务 = 关闭该任务连接，由协议实现把关闭连接产生的 IO 异常映射为 [TransferCancelledException]；
 * - 有活动任务时启动 [TransferService] 前台保活，队列清空后停止；
 * - 进程被杀后队列不恢复（队列为内存态），这是本版已知限制。
 */
class DefaultTransferManager(
    context: Context,
    private val serverRepository: ServerRepository
) : TransferManager {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()

    private val queueModel = TransferQueueModel()
    private val mutableTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    private val mutableActiveCount = MutableStateFlow(0)

    private val requests = HashMap<String, TransferRequest>()
    private val trackers = HashMap<String, TransferProgressTracker>()
    private val activeConnections = ConcurrentHashMap<String, RemoteFileSystem>()
    private val cancelRequests = ConcurrentHashMap.newKeySet<String>()
    private val downloadStore = MediaStoreDownloadStore(appContext)

    override val tasks: StateFlow<List<TransferTask>> = mutableTasks.asStateFlow()

    override val activeCount: StateFlow<Int> = mutableActiveCount.asStateFlow()

    init {
        scope.launch { runQueueLoop() }
    }

    // ------------------------------------------------------------ 入队

    override fun enqueueDownload(profile: ServerProfile, entry: RemoteEntry): String =
        enqueue(TransferRequest.Download(profile, entry))

    override fun enqueueUpload(
        profile: ServerProfile,
        sourceUri: Uri,
        displayName: String,
        size: Long,
        remoteDir: String
    ): String {
        val remoteName = TransferFileNames.sanitize(displayName)
        val remotePath = RemotePath.join(remoteDir, remoteName)
        val request = TransferRequest.Upload(
            profile = profile,
            sourceUri = sourceUri.toString(),
            displayName = remoteName,
            remotePath = remotePath,
            totalBytes = size
        )
        return enqueue(request)
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
        // 在 UI 触发的入队时机启动前台服务，避免之后在后台启动被系统拒绝。
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
        totalBytes = request.totalBytes,
        createdAt = System.currentTimeMillis()
    )

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
        try {
            if (cancelRequests.contains(taskId)) throw TransferCancelledException()
            withContext(Dispatchers.IO) {
                fileSystem.connect()
                when (val request = execution.request) {
                    is TransferRequest.Download -> downloadToMediaStore(taskId, request, fileSystem)
                    is TransferRequest.Upload -> uploadFromContentUri(taskId, request, fileSystem)
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
                Log.w(TAG, "传输任务失败：" + taskId, e)
                val failed = finishTask(taskId, TransferState.FAILED, TransferErrors.toUserMessage(e, execution.request.direction))
                if (failed != null) TransferNotifications.notifyResult(appContext, failed)
            }
        } finally {
            activeConnections.remove(taskId)
            cancelRequests.remove(taskId)
            synchronized(stateLock) { trackers.remove(taskId) }
            safeClose(fileSystem)
            stopForegroundIfIdle()
        }
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

    // ------------------------------------------------------------ 下载 / 上传

    private suspend fun downloadToMediaStore(
        taskId: String,
        request: TransferRequest.Download,
        fileSystem: RemoteFileSystem
    ) {
        val displayName = downloadStore.reserveFileName(request.entry.name)
        updateTask(taskId) { it.copy(localDisplayName = displayName) }
        var pendingUri: Uri? = null
        try {
            val uri = downloadStore.createPendingFile(displayName)
            pendingUri = uri
            downloadStore.openSink(uri).use { sink ->
                fileSystem.download(request.entry.path, sink) { transferred ->
                    reportProgress(taskId, transferred)
                }
            }
            downloadStore.commit(uri)
            pendingUri = null
        } catch (e: Exception) {
            pendingUri?.let { downloadStore.discard(it) }
            throw e
        } finally {
            downloadStore.releaseFileName(displayName)
        }
    }

    private suspend fun uploadFromContentUri(
        taskId: String,
        request: TransferRequest.Upload,
        fileSystem: RemoteFileSystem
    ) {
        val uri = Uri.parse(request.sourceUri)
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw IOException("无法读取本地文件：" + request.displayName, e)
        } ?: throw IOException("无法读取本地文件：" + request.displayName)
        input.use { source ->
            val sizeHint = if (request.totalBytes > 0L) request.totalBytes else -1L
            fileSystem.upload(source, request.remotePath, sizeHint) { transferred ->
                reportProgress(taskId, transferred)
            }
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

    // ------------------------------------------------------------ 状态更新

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
    }

    // ------------------------------------------------------------ 取消 / 重试 / 移除

    override fun cancel(taskId: String) {
        var running = false
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
                    return
                }
                TransferState.RUNNING -> {
                    cancelRequests.add(taskId)
                    running = true
                }
                else -> return
            }
        }
        if (running) closeActiveConnection(taskId)
        stopForegroundIfIdle()
    }

    override fun retry(taskId: String) {
        val task = synchronized(stateLock) { queueModel.get(taskId) } ?: return
        if (!task.isFinished) return
        val profile = serverRepository.find(task.profileId)
        if (profile == null) {
            markRetryFailure(taskId, "服务器配置已被删除，无法重试")
            return
        }
        val request = when (task.direction) {
            TransferDirection.DOWNLOAD -> TransferRequest.Download(
                profile = profile,
                entry = RemoteEntry(
                    name = task.displayName,
                    path = task.remotePath,
                    isDirectory = false,
                    size = task.totalBytes
                )
            )
            TransferDirection.UPLOAD -> {
                val sourceUri = task.sourceUri
                if (sourceUri.isNullOrBlank()) {
                    markRetryFailure(taskId, "本地文件地址已失效，无法重试")
                    return
                }
                TransferRequest.Upload(
                    profile = profile,
                    sourceUri = sourceUri,
                    displayName = task.displayName,
                    remotePath = task.remotePath,
                    totalBytes = task.totalBytes
                )
            }
        }
        synchronized(stateLock) {
            queueModel.remove(taskId)
            requests.remove(taskId)
            trackers.remove(taskId)
            publishLocked()
        }
        enqueue(request)
    }

    private fun markRetryFailure(taskId: String, message: String) {
        updateTask(taskId) { it.copy(error = message) }
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
        stopForegroundIfIdle()
    }

    override fun clearFinished() {
        synchronized(stateLock) {
            val removed = queueModel.clearFinished()
            removed.forEach { trackers.remove(it.id) }
            publishLocked()
        }
    }

    // ------------------------------------------------------------ 前台服务 / 连接管理

    private fun ensureForegroundService() {
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

    // ------------------------------------------------------------ 内部类型

    private class Execution(
        val task: TransferTask,
        val request: TransferRequest,
        val tracker: TransferProgressTracker
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
            val entry: RemoteEntry
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
    }
}

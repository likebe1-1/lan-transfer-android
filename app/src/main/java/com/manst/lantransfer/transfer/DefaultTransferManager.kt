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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 传输队列默认实现（v2：持久化 / 历史 / 自动重试 / 断点续传 / 文件夹递归）。
 *
 * 设计：
 * - 有限并发执行队列：默认 5 路（可配置 1–6，见 [TransferTuning]），最多 [TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS] 个任务同时传输
 *   （小文件多时显著减少 connect/认证/断开的固定开销），并发决策见 [TransferScheduler]；
 *   调度循环只负责分配槽位，真正的生产工作在各自协程的 withContext(Dispatchers.IO) 中执行；
 * - [tasks] 是 UI 的唯一数据源；同一个 taskId 同一时刻最多只有一个执行体；
 * - 每个任务使用独立的 [RemoteFileSystem] 连接，任务结束（含失败、取消）立即关闭；
 * - 队列与历史通过 [TransferStore] 持久化（500ms 节流）：进程重启后未完成任务恢复为 QUEUED 并自动继续，
 *   已结束任务作为历史保留（默认最多 200 条，见 [TransferHistoryLimit]）；
 * - 下载直接写入 MediaStore 的 pending 文件（IS_PENDING=1），完成后仅提交（IS_PENDING=0），
 *   不再经过 cacheDir 暂存文件 + 复制，同一份数据只落盘一次；写入前按远端大小预分配文件（ftruncate），
 *   续传时按「连续已写字节」续写（详见 [downloadToMediaStore] 的取舍说明与 [DownloadPreallocationPolicy]）；
 * - 自动重试：非用户取消的 IO/网络错误最多重试 3 次（退避 2s/4s/8s），认证/路径错误不重试；
 * - 文件夹传输：递归列出远端目录 / 遍历 SAF 目录树，拆分为逐文件任务，按上面同一套并发调度执行；
 * - 有活动任务时启动 [TransferService] 前台保活，队列清空后停止。
 */
class DefaultTransferManager(
    context: Context,
    private val serverRepository: ServerRepository,
    private val transferStore: TransferStore = JsonTransferStore(
        File(context.applicationContext.filesDir, TRANSFER_STORE_FILE_NAME),
        // 传输队列里也会保存 profile（含密码），统一用 Keystore 加密落盘
        com.manst.lantransfer.data.AndroidKeystoreSecretCipher()
    ),
    /** 传输并发配置（默认 5 路，范围 1–6）；用户可在「传输设置」页调整。 */
    private val transferTuning: TransferTuningStore =
        TransferTuningStore.fromContext(context.applicationContext)
) : TransferManager {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()

    private val queueModel = TransferQueueModel()

    /** 有限并发调度策略（纯 JVM 逻辑，可单元测试）。 */
    /**
     * 并发调度器：按 [transferTuning] 的当前值创建；设置页修改后通过下面的 collector 热替换，
     * 对新入队的任务立即生效（正在传输的任务不受影响）。
     */
    @Volatile
    private var scheduler = TransferScheduler(transferTuning.maxConcurrentTasks)

    /**
     * 当前已被 worker 占用的任务 id：同一 taskId 同一时刻只会有一个执行体，
     * 任务失败退避等待期间仍算「占用中」（避免退避窗口内被其它 worker 重新取出）。
     */
    private val executingTaskIds = ConcurrentHashMap.newKeySet<String>()

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
        // 并发配置热更新：设置页修改后，对新入队任务立即生效（正在传输的不受影响）
        scope.launch {
            transferTuning.tuning.collect { tuning ->
                val desired = tuning.maxConcurrentTasks
                if (desired != scheduler.maxConcurrency) {
                    scheduler = TransferScheduler(desired)
                    Log.i(TAG, "传输并发上限已更新为 " + desired)
                }
            }
        }
        // 旧版本把下载暂存文件放在 cacheDir/transfer；升级后改为直接写 MediaStore pending 文件，
        // 启动时清理这些不再使用的旧暂存文件（失败的任务已在 old 版本持久化里，逻辑见 restoreActiveTask）。
        cleanupLegacyStaging()
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
            // 只有「活动任务数由 0 变 1」才需要启动前台服务，与 stopForegroundIfIdle 严格配对；
            // 并发下第二个及后续任务入队时服务已经在运行，重复 start 没有意义。
            startService = scheduler.shouldStartForegroundService(queueModel.countActive())
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

    /**
     * 队列调度循环：维持最多 [TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS] 个任务同时执行（可通过「传输设置」调整）。
     *
     * 调度决策全部交给纯 JVM 的 [TransferScheduler]（可单元测试），本循环只负责
     * 「占槽位 -> 启动子协程 -> 等待槽位释放或新任务出现」，不执行任何 IO：
     * - 每个任务作为 [scope] 下的独立子 Job 启动（SupervisorJob：某个任务异常既不会打断调度循环，
     *   也不会取消其它正在传输的任务）；
     * - 任务内部的阻塞 IO 仍在各自协程的 withContext(Dispatchers.IO) 中执行，
     *   因此多个任务并行时不会因为单一线程池饥饿而互相阻塞（调度循环自身在 Dispatchers.Default 上，
     *   只做状态读写的轻量工作，不会被 IO 阻塞）；
     * - 等待使用 StateFlow（新任务入队）与任务完成信号（释放槽位）唤醒，不轮询、不空转。
     */
    private suspend fun runQueueLoop() = coroutineScope {
        // 唤醒信号：任一任务结束（释放槽位）或队列出现新的可执行任务时唤醒调度循环。
        val wakeUp = Channel<Unit>(Channel.CONFLATED)
        val queueWatcher = launch {
            tasks.collect { list ->
                if (list.any { it.state == TransferState.QUEUED }) wakeUp.trySend(Unit)
            }
        }
        // 仅在调度循环内访问（单协程访问，无需额外同步）
        val runningJobs = LinkedHashMap<String, Job>()
        try {
            while (currentCoroutineContext().isActive) {
                runningJobs.entries.removeAll { it.value.isCompleted }
                val startable = scheduler.startableTaskIds(tasks.value, runningJobs.keys)
                for (taskId in startable) {
                    val job = scope.launch { runTask(taskId) }
                    runningJobs[taskId] = job
                    job.invokeOnCompletion { wakeUp.trySend(Unit) }
                }
                if (runningJobs.isEmpty()) {
                    // 没有任务在执行：挂起等待「出现可启动的任务」。
                    // StateFlow.first 会先读取当前值，因此不会丢掉「入队」与「开始等待」之间的唤醒。
                    tasks.first { list -> scheduler.startableTaskIds(list, emptySet()).isNotEmpty() }
                } else {
                    // 等任一任务结束（释放槽位）或队列出现新的可执行任务。
                    wakeUp.receive()
                }
            }
        } finally {
            queueWatcher.cancel()
        }
    }

    /**
     * 执行单个任务。[executingTaskIds] 是「同一 taskId 不会被启动两次」的最后一道保险：
     * 即使调度器因竞态重复调度同一个任务，也只会有一个执行体真正运行。
     */
    private suspend fun runTask(taskId: String) {
        if (!executingTaskIds.add(taskId)) {
            Log.w(TAG, "任务已在执行中，忽略重复调度：" + taskId)
            return
        }
        try {
            executeTask(taskId)
        } finally {
            executingTaskIds.remove(taskId)
        }
    }

    private suspend fun executeTask(taskId: String) {
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
                    is TransferRequest.Download -> downloadToMediaStore(taskId, request, fileSystem)
                    is TransferRequest.Upload -> uploadWithResume(taskId, request, fileSystem)
                }
            }
            val completed = finishTask(taskId, TransferState.COMPLETED, null)
            if (completed != null) {
                TransferNotifications.notifyResult(appContext, completed)
                recordProfileUsage(completed.profileId)
            }
        } catch (e: CancellationException) {
            // 用户取消/进程内取消：丢弃未提交的 pending 文件，避免残留（已提交的可见文件不受影响）。
            discardPendingDownload(taskId)
            finishTask(taskId, TransferState.CANCELLED, null)
            throw e
        } catch (e: TransferCancelledException) {
            discardPendingDownload(taskId)
            finishTask(taskId, TransferState.CANCELLED, null)
        } catch (e: Exception) {
            if (cancelRequests.contains(taskId)) {
                discardPendingDownload(taskId)
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
                discardPendingDownload(taskId)
                finishTask(taskId, TransferState.CANCELLED, null)
            }
            stopForegroundIfIdle()
        }
        if (retryBackoffMillis > 0L) {
            // 退避等待期间本任务仍占一个并发槽位（任务已重置为 QUEUED，但仍在 executingTaskIds 中，
            // 因此不会被其它 worker 提前重新取出——否则等于绕过了退避，退化成忙重试）。
            // 其余槽位仍可执行其它任务，所以退避不会阻塞整个队列，作用与改造前的串行等待一致。
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
        // 最终失败不再自动重试：清理未提交的 pending 下载文件，避免不可见的磁盘残留。
        discardPendingDownload(taskId)
        val failed = finishTask(taskId, TransferState.FAILED, finalError)
        if (failed != null) TransferNotifications.notifyResult(appContext, failed)
        return 0L
    }

    /**
     * 原子地把任务从 QUEUED 切换为 RUNNING 并取出执行参数。
     *
     * 整个判断 + 切换都在 [stateLock] 内完成：并发 worker 即使同时选中同一个任务，
     * 也只有第一个能拿到 [TransferQueueModel.beginAttempt] 的返回值，其余返回 null 直接退出。
     */
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
        val running = queueModel.beginAttempt(taskId) ?: return null
        val tracker = TransferProgressTracker()
        trackers[taskId] = tracker
        publishLocked()
        Execution(running, request, tracker)
    }

    // ------------------------------------------------------------ 下载（直接写 MediaStore pending 文件）

    /**
     * 下载主体：远端字节流直接写进 MediaStore 的 pending 文件（IS_PENDING=1），完成后只提交、不再复制。
     *
     * 方案取舍（任务 #2 的方案 B，替代旧实现「cacheDir staging + 复制到下载目录」）：
     * - 旧实现同一份数据写两次磁盘：先写 cacheDir/transfer/<taskId>.part，完成后整文件读+写复制进
     *   MediaStore，大文件耗时与存储分配都接近翻倍；方案 A（把 staging 移到 getExternalFilesDir）
     *   只能减少跨分区开销，复制本身仍存在，因此这里选更彻底的方案 B：直接写最终 pending 文件，
     *   完成时仅把 IS_PENDING 置 0，没有全量复制步骤；
     * - 代价是续传必须「续写 pending 文件」而不是独立的暂存文件，所以用
     *   [MediaStoreDownloadStore.openPendingSink] 按偏移定位写入；
     * - 开始写之前按远端大小预分配文件（[MediaStoreDownloadStore.preallocate]），减少大文件写入期
     *   反复扩展文件造成的碎片；但预分配后文件大小立即等于远端大小，所以**不能用文件大小判断
     *   已下载多少**：真实进度由本方法内的 [CountingOutputStream] 统计（写进目标文件的连续字节数），
     *   并随任务快照持久化（500ms 节流，进程被杀时最多回退少量字节）；
     * - 续传偏移取 min(文件大小, 任务快照 transferredBytes)，由 [DownloadPreallocationPolicy] 统一决策：
     *   预分配过的文件「文件大小」区分不出空尾巴与真实数据，而多通道等乱序写实现报告的字节数
     *   可能超过连续已写前缀；两者取小才是安全下界，续写至多重写少量尾部字节，不会留下空洞；
     * - 远端文件变小（被替换）时丢弃旧 pending 重新下载，绝不把新旧内容拼在一起。
     *
     * 残留清理策略（pending 文件对用户不可见，但会占磁盘）：
     * 1) 自动重试（网络/IO 抖动）保留 pending，下一次尝试走 [RemoteFileSystem.downloadPartial] 续写；
     * 2) 进程被杀/重启：localUri 已持久化在任务里，恢复后按「任务快照记录的连续已写字节」续写
     *    （持久化有 500ms 节流，最多重复写少量尾部字节，不会留下空洞）；
     * 3) 最终失败、用户取消、删除任务、清空历史：调用方通过 [discardPendingDownload] 主动删除
     *    pending 记录，不留残骸（已提交的可见文件绝不删除）；
     * 4) 若进程在 localUri 持久化（<500ms）之前被杀，该 pending 记录无人引用，
     *    由 MediaStore 自身对长期未提交 pending 项的过期回收兜底。
     */
    private suspend fun downloadToMediaStore(
        taskId: String,
        request: TransferRequest.Download,
        fileSystem: RemoteFileSystem
    ) {
        val initialTask = currentTask(taskId) ?: throw TransferCancelledException()
        val remoteSize = resolveRemoteSize(fileSystem, request)
        var displayName: String? = null
        var targetUri: Uri? = null
        var startOffset = 0L
        var skipTransfer = false
        // 本次需要预分配到的字节数（0 表示不预分配）；只有「从 0 开始写」的新文件才需要。
        var preallocateBytes = 0L

        // 1) 复用上一次尝试 / 进程崩溃遗留下来的 pending 文件（断点续传的关键路径）。
        val previousUri = initialTask.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val previousName = initialTask.localDisplayName
        val previousBytes = if (previousUri != null && !previousName.isNullOrBlank()) {
            downloadStore.localFileSize(previousUri)
        } else {
            null
        }
        val previousPending =
            previousBytes != null && previousUri != null && downloadStore.isPending(previousUri)
        if (!previousPending && previousBytes != null && remoteSize > 0L && previousBytes == remoteSize) {
            // 进程在 commit 与「任务标记完成」之间被杀：文件其实已完整可见，直接按完成收尾。
            displayName = previousName
            targetUri = previousUri
            startOffset = previousBytes
            skipTransfer = true
        } else {
            // 预分配会把文件大小变成远端大小，因此「已写多少」不能只看文件大小；
            // 统一交给 DownloadPreallocationPolicy 决策（min(文件大小, 任务快照已写字节)）。
            val plan = DownloadPreallocationPolicy.plan(
                remoteBytes = remoteSize,
                recordedBytes = initialTask.transferredBytes,
                pendingDiskBytes = if (previousPending) previousBytes else null,
                protocolSupportsResume = fileSystem.supportsResume
            )
            preallocateBytes = plan.preallocateBytes
            if (plan.reusePending) {
                displayName = previousName
                targetUri = previousUri
                startOffset = plan.startOffset
                skipTransfer = !plan.needsTransfer
            } else {
                if (previousPending) {
                    // 协议不支持续传（如 SCP）/ 远端文件变小或被替换 / 远端大小未知：
                    // 旧 pending 无法续写，丢弃后从头下载，避免每次重试残留一个文件。
                    downloadStore.discard(previousUri)
                } else if (previousBytes != null) {
                    Log.w(TAG, "下载任务已有不可续传的本地文件，重新下载：" + previousUri)
                }
            }
        }

        var reserved = false
        try {
            if (targetUri == null) {
                // 2) 新建 pending 目标（沿用原有文件名查重 + localRelativeDir 子目录逻辑）。
                displayName = downloadStore.reserveFileName(request.entry.name, request.localRelativeDir)
                reserved = true
                targetUri = downloadStore.createPendingFile(displayName, request.localRelativeDir)
                updateTask(taskId) {
                    it.copy(
                        localDisplayName = displayName,
                        localUri = targetUri.toString(),
                        totalBytes = if (remoteSize > 0L) remoteSize else it.totalBytes,
                        transferredBytes = 0L,
                        resumable = fileSystem.supportsResume
                    )
                }
            } else {
                updateTask(taskId) {
                    it.copy(
                        localDisplayName = displayName,
                        localUri = targetUri.toString(),
                        totalBytes = if (remoteSize > 0L) remoteSize else it.totalBytes,
                        transferredBytes = startOffset,
                        resumable = fileSystem.supportsResume
                    )
                }
            }

            val target = targetUri ?: throw IOException("下载目标文件未创建")

            // 3) 远端数据直接写进 pending 文件；startOffset > 0 时从该偏移续写（断点续传不退化）。
            if (!skipTransfer && (remoteSize <= 0L || startOffset < remoteSize)) {
                val sink = try {
                    downloadStore.openPendingSink(target, startOffset)
                } catch (e: IOException) {
                    // 兜底：极少数设备/自定义 MediaProvider 可能不支持 "rw" 定位写入。此时降级为普通
                    // 输出流从头重写——仍然只写一份数据（直接写 pending 文件），只是本次退化为不可续传，
                    // 而不是让整个下载失败。
                    Log.w(TAG, "无法按偏移写入 pending 文件，降级为从头写入：" + target, e)
                    startOffset = 0L
                    preallocateBytes = if (remoteSize > 0L) remoteSize else 0L
                    updateTask(taskId) { it.copy(transferredBytes = 0L) }
                    downloadStore.openSink(target)
                }
                var preallocated = false
                var writtenTotal = startOffset
                sink.use { raw ->
                    // 开始写之前预分配（ftruncate 到远端大小）：只对「从 0 开始写」的新文件做。
                    // 预分配后文件大小恒等于远端大小，真实进度只能靠 counting 统计的连续已写字节。
                    if (preallocateBytes > 0L) {
                        preallocated = downloadStore.preallocate(target, preallocateBytes)
                    }
                    val counting = CountingOutputStream(raw) { written ->
                        reportProgress(taskId, startOffset + written)
                    }
                    if (startOffset > 0L) {
                        fileSystem.downloadPartial(request.remotePath, counting, startOffset) { }
                    } else {
                        fileSystem.download(request.remotePath, counting) { }
                    }
                    writtenTotal = startOffset + counting.writtenTotal
                }
                // 远端列表里的 size 可能已过期（文件在传输期间被替换/缩小）：实际写入字节少于
                // 预分配大小时裁掉尾部 0 填充，避免提交一个尾部是空洞却显示「完整」的文件。
                if (preallocated && writtenTotal < preallocateBytes) {
                    downloadStore.truncateTo(target, writtenTotal)
                }
            }

            // 4) 提交 pending 记录使文件对用户可见：这是唯一的「落盘完成」动作，没有任何复制。
            val committedUri = downloadStore.commitAndGetUri(target)
            val finalBytes = downloadStore.localFileSize(committedUri)
                ?: if (remoteSize > 0L) remoteSize else startOffset
            updateTask(taskId) {
                it.copy(localUri = committedUri.toString(), localDisplayName = displayName)
            }
            reportProgress(taskId, finalBytes)
        } catch (e: Exception) {
            // 保留 pending 供自动重试/重启后续传；最终失败与取消由调用方统一 discard（见方法注释的清理策略）。
            Log.w(TAG, "下载中断，保留 pending 目标等待续传：" + targetUri, e)
            throw e
        } finally {
            if (reserved) {
                downloadStore.releaseFileName(displayName ?: request.entry.name, request.localRelativeDir)
            }
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
        val hasLocalProgress = when (task.direction) {
            // 下载：localUri 指向尚未提交的 pending 文件（写入时即持久化），实际大小与可用性
            // 由 downloadToMediaStore 在 IO 线程校验，避免在初始化线程里做 MediaStore 查询。
            TransferDirection.DOWNLOAD -> !task.localUri.isNullOrBlank()
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
        if (cancelledQueued) {
            // 排队中取消的任务可能已经带着上次失败留下的 pending 文件：一并清理。
            discardPendingDownload(taskId)
            Log.i(TAG, "已取消排队中的传输任务：" + taskId)
        }
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
        var localUri: String? = null
        synchronized(stateLock) {
            val task = queueModel.get(taskId) ?: return
            // 已完成任务的文件一定已提交、对用户可见，不需要再查询 pending 状态。
            localUri = if (task.state == TransferState.COMPLETED) null else task.localUri
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
        // 删除任务时清理它未提交的 pending 下载文件（已提交的可见文件保留给用户）。
        discardPendingUri(localUri)
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
        removed.filter { it.state != TransferState.COMPLETED }
            .forEach { discardPendingUri(it.localUri) }
    }

    // ------------------------------------------------------------ 下载残留清理 / 前台服务 / 连接管理

    /** 旧版本把下载暂存文件放在 cacheDir/transfer/<taskId>.part；升级后不再使用，启动时清理。 */
    private fun cleanupLegacyStaging() {
        val directory = File(appContext.cacheDir, LEGACY_STAGING_DIRECTORY_NAME)
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (!file.delete()) Log.w(TAG, "清理旧版下载暂存文件失败：" + file.absolutePath)
        }
        if (!directory.delete()) Log.w(TAG, "删除旧版下载暂存目录失败：" + directory.absolutePath)
    }

    /**
     * 丢弃任务当前关联的、尚未提交（IS_PENDING=1）的下载文件。
     *
     * 只在「任务最终失败 / 用户取消 / 删除任务 / 清空历史」时调用：自动重试与进程重启恢复
     * 需要保留 pending 文件继续续传。已提交（用户可见）的文件绝不删除。
     */
    private fun discardPendingDownload(taskId: String) {
        val task = currentTask(taskId) ?: return
        val uri = task.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (!downloadStore.isPending(uri)) return
        downloadStore.discard(uri)
        updateTask(taskId) { it.copy(localUri = null, localDisplayName = null) }
    }

    /** 同上，但用于任务已从队列移除（remove / clearHistory）后按 Uri 字符串清理。 */
    private fun discardPendingUri(uriString: String?) {
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (downloadStore.isPending(uri)) downloadStore.discard(uri)
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

    /**
     * 统计真正写进下游输出流的字节数（下载进度与续传偏移的唯一可信来源）。
     *
     * 为什么不用协议回调做进度：下载前预分配会把文件大小固定成远端大小，文件大小不再是进度；
     * 而部分协议（如多通道 SFTP）的进度来自临时文件，可能超过已经写进目标文件的连续前缀。
     * 只有这里统计的「连续已写字节」才能安全地用作续传偏移和持久化进度：
     * 写入下游的字节先落进 fd，再上报进度，所以它永远是真实已写数据的安全下界。
     */
    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val onWritten: (Long) -> Unit
    ) : OutputStream() {

        /** 已写入下游输出流的累计字节数（不含续传偏移）。 */
        var writtenTotal: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            advance(1L)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            advance(len.toLong())
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun advance(bytes: Long) {
            if (bytes <= 0L) return
            writtenTotal += bytes
            onWritten(writtenTotal)
        }
    }

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
        /** 旧版本（staging 方案）的 cacheDir 子目录名，仅用于升级时清理。 */
        const val LEGACY_STAGING_DIRECTORY_NAME = "transfer"
        const val PERSIST_THROTTLE_MILLIS = 500L
    }
}

/**
 * 下载断点续传决策（纯 JVM 逻辑，可单元测试）。
 *
 * 输入是「已写入的字节数」与「远端文件大小」，输出下一步动作：
 * - [Decision.Resume]：从指定偏移续写（offset 可能为 0，表示重新从头写）；
 * - [Decision.AlreadyComplete]：本地已写满（上次写完但没来得及 commit），直接提交即可；
 * - [Decision.Restart]：旧 pending 不可用（远端文件变小/被替换、协议不支持续传、大小未知），丢弃重下。
 *
 * 调用方 [DownloadPreallocationPolicy] 传入的已写字节数是「安全下界」而不是预分配后的文件大小：
 * 预分配过的 pending 文件大小恒等于远端大小，不能代表真实进度；见该类的说明。
 */
internal object DownloadResumePolicy {

    sealed interface Decision {
        data class Resume(val offset: Long) : Decision
        data object AlreadyComplete : Decision
        data object Restart : Decision
    }

    fun decide(
        protocolSupportsResume: Boolean,
        pendingBytes: Long?,
        remoteBytes: Long
    ): Decision {
        if (!protocolSupportsResume) return Decision.Restart
        if (pendingBytes == null || pendingBytes <= 0L) return Decision.Resume(0L)
        // 远端大小未知时无法校验本地字节是否仍属于同一份内容，保守重下，避免拼接出损坏文件。
        if (remoteBytes <= 0L) return Decision.Restart
        return when {
            pendingBytes > remoteBytes -> Decision.Restart
            pendingBytes == remoteBytes -> Decision.AlreadyComplete
            else -> Decision.Resume(pendingBytes)
        }
    }
}

/**
 * 下载「预分配 + 续传定位」决策（纯 JVM 逻辑，可单元测试）。
 *
 * 关键问题：下载前按远端大小预分配（[MediaStoreDownloadStore.preallocate]）会让 pending 文件的
 * 磁盘大小立即等于远端大小，因此**磁盘大小不再等于已下载字节数**。如果直接沿用「磁盘大小即进度」，
 * 一次中断后重试就会把预分配的空尾巴当成「已下载完成」，提交一个尾部全是 0 的损坏文件。
 *
 * 解决办法：真实已写偏移取「磁盘大小」和「任务快照记录的连续已写字节」中的较小者：
 * - 预分配过的文件：磁盘大小 == 远端大小，记录值才是真实进度（可能因 500ms 持久化节流略小，
 *   但只会让续写重复少量尾部字节，不会留下空洞）；
 * - 未预分配 / 旧版本遗留的文件：磁盘大小是精确的已写字节；而多通道等乱序写实现上报的进度
 *   可能超过连续已写前缀，磁盘大小正好是它的上界；
 * 两者取小永远是安全下界。远端大小未知或远端变小（被替换）时无法校验已有内容，一律从头下载。
 */
internal object DownloadPreallocationPolicy {

    /**
     * 一次下载尝试的落盘计划。
     *
     * @property reusePending 是否复用上一次尝试遗留的 pending 文件；false 表示新建目标文件。
     * @property startOffset 本次写入起点：真实「连续已写」字节数，绝不是预分配出来的文件大小。
     * @property preallocateBytes 需要 ftruncate 预分配到的字节数；0 表示不预分配
     *   （远端大小未知，或续传时文件已存在/无需重新占位）。
     * @property needsTransfer 是否仍需传输；false 表示本地已写满（上次写完没来得及提交），直接提交即可。
     */
    data class Plan(
        val reusePending: Boolean,
        val startOffset: Long,
        val preallocateBytes: Long,
        val needsTransfer: Boolean
    )

    /**
     * 决定「是否新建目标并预分配 / 从哪里续写 / 是否已完成」。
     *
     * @param remoteBytes 远端文件大小；<=0 表示未知。
     * @param recordedBytes 任务快照记录的连续已写字节数（由 [DefaultTransferManager] 内的
     *   CountingOutputStream 统计，是真实已写数据的安全下界）。
     * @param pendingDiskBytes 可复用 pending 文件的磁盘大小；null 表示没有可复用的 pending 文件。
     *   预分配过的文件该值等于远端大小，不能直接当成已下载字节。
     * @param protocolSupportsResume 协议是否支持按偏移续传。
     */
    fun plan(
        remoteBytes: Long,
        recordedBytes: Long,
        pendingDiskBytes: Long?,
        protocolSupportsResume: Boolean
    ): Plan {
        // 1) 远端大小未知：无法预分配，也无法校验旧文件是否仍属于同一份内容 -> 从头下载。
        if (remoteBytes <= 0L) return startNew(preallocateBytes = 0L)
        // 2) 没有可复用的 pending 文件，或协议不支持续传：新建目标并预分配远端大小。
        if (pendingDiskBytes == null || !protocolSupportsResume) return startNew(remoteBytes)
        // 3) 磁盘文件比远端还大：远端文件被替换成更小版本，旧内容不能续写（预分配过也可能是旧大小）。
        if (pendingDiskBytes > remoteBytes) return startNew(remoteBytes)

        val writtenBytes = minOf(pendingDiskBytes, recordedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        return when (val decision = DownloadResumePolicy.decide(true, writtenBytes, remoteBytes)) {
            is DownloadResumePolicy.Decision.Resume -> Plan(
                reusePending = true,
                startOffset = decision.offset,
                // 从 0 开始写时需要（重新）预分配；真正的续写阶段文件已占好位。
                preallocateBytes = if (decision.offset == 0L) remoteBytes else 0L,
                needsTransfer = true
            )
            DownloadResumePolicy.Decision.AlreadyComplete -> Plan(
                reusePending = true,
                startOffset = remoteBytes,
                preallocateBytes = 0L,
                needsTransfer = false
            )
            DownloadResumePolicy.Decision.Restart -> startNew(remoteBytes)
        }
    }

    private fun startNew(preallocateBytes: Long) = Plan(
        reusePending = false,
        startOffset = 0L,
        preallocateBytes = preallocateBytes.coerceAtLeast(0L),
        needsTransfer = true
    )
}

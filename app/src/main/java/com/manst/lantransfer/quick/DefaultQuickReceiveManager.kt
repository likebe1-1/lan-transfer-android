package com.manst.lantransfer.quick

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Log
import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.ShareFileNames
import com.manst.lantransfer.protocol.RemoteFileSystem
import com.manst.lantransfer.protocol.RemoteFileSystems
import com.manst.lantransfer.transfer.TransferDirection
import com.manst.lantransfer.transfer.TransferErrors
import com.manst.lantransfer.transfer.TransferProgressTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

/**
 * 快捷传输接收端默认实现。
 *
 * 能力：
 * - 局域网发现：[startDiscovery] 用 [UdpBeaconScanner] 监听 UDP 广播（配合 WifiManager.MulticastLock），
 *   按接收码去重、按最近收到时间排序，6 秒未再收到即过期清理；
 * - 连接：[join] 用接收码派生临时帐号，按指定协议或 SFTP -> SCP -> FTPS -> FTP 顺序依次尝试，
 *   第一个 connect + 根目录 list 成功的协议即会话协议，随后递归列出对方根目录（子目录文件也纳入
 *   remoteFiles），并后台自动 [downloadAll]（不阻塞 join 返回）；
 * - 接收：[downloadAll] / [download] 顺序下载到 [QuickDownloadSink]：文件夹传输时用
 *   [QuickDownloadSink.openInDir] 在系统下载目录下保留相对子目录结构，提交成功才计入完成；
 * - 互传：[sendFiles] 用同一个连接把本机文件上传到对方根目录；
 * - 限流：同一目标连续 5 次接收码错误后锁定 10 分钟（[QuickJoinAttemptLimiter]），成功连接即清除；
 * - 所有网络操作在 Dispatchers.IO 上、由同一把 Mutex 串行；同一时刻只允许一个会话。
 */
class DefaultQuickReceiveManager(
    context: Context,
    private val downloadSink: QuickDownloadSink
) : QuickReceiveManager {

    private val appContext: Context = context.applicationContext
    private val resolver = appContext.contentResolver

    private val mutableState = MutableStateFlow(QuickReceiveState())

    override val state: StateFlow<QuickReceiveState> = mutableState.asStateFlow()

    /** 串行化连接/下载/上传/离开，保证同一个 RemoteFileSystem 不会被并发使用。 */
    private val operationMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val discoveryLock = Any()

    private var scanner: QuickBeaconScanner? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    private var expiryJob: Job? = null

    private var autoDownloadJob: Job? = null

    private var fileSystem: RemoteFileSystem? = null

    /** 接收码错误次数限制：按目标（host + 三端口）连续 5 次认证失败后锁定 10 分钟。 */
    private val joinAttemptLimiter = QuickJoinAttemptLimiter()

    // ------------------------------------------------------------ 局域网发现

    override fun startDiscovery() {
        synchronized(discoveryLock) {
            if (scanner != null) return
            acquireMulticastLock()
            val newScanner = UdpBeaconScanner()
            try {
                newScanner.start { session -> onBeaconReceived(session) }
            } catch (e: Exception) {
                Log.w(TAG, "启动局域网发现失败", e)
                releaseMulticastLock()
                mutableState.update { current ->
                    current.copy(error = "启动局域网发现失败，请确认 Wi-Fi 可用或手动输入对方 IP")
                }
                return
            }
            scanner = newScanner
            mutableState.update { current ->
                when (current.phase) {
                    QuickReceivePhase.IDLE, QuickReceivePhase.FAILED, QuickReceivePhase.SCANNING ->
                        current.copy(phase = QuickReceivePhase.SCANNING, error = null)
                    else -> current
                }
            }
            expiryJob?.cancel()
            expiryJob = scope.launch {
                while (isActive) {
                    delay(DISCOVERY_EXPIRY_CHECK_MS)
                    pruneExpiredSessions()
                }
            }
            Log.i(TAG, "开始监听局域网快捷传输广播")
        }
    }

    override fun stopDiscovery() {
        synchronized(discoveryLock) {
            expiryJob?.cancel()
            expiryJob = null
            scanner?.let { current -> runCatching { current.stop() } }
            scanner = null
            releaseMulticastLock()
            mutableState.update { current ->
                if (current.phase == QuickReceivePhase.SCANNING) {
                    current.copy(phase = QuickReceivePhase.IDLE, discovered = emptyList())
                } else {
                    current.copy(discovered = emptyList())
                }
            }
            Log.i(TAG, "停止监听局域网快捷传输广播")
        }
    }

    /** 扫描线程回调：按接收码去重，保留最近一次广播信息。 */
    private fun onBeaconReceived(session: QuickSessionInfo) {
        mutableState.update { current ->
            val merged = ArrayList<QuickSessionInfo>(current.discovered.size + 1)
            current.discovered.filterTo(merged) { it.code != session.code }
            merged.add(session)
            current.copy(discovered = merged.sortedByDescending { it.receivedAt })
        }
    }

    private fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        mutableState.update { current ->
            val alive = current.discovered.filter { now - it.receivedAt <= DISCOVERY_TTL_MS }
            if (alive.size == current.discovered.size) current else current.copy(discovered = alive)
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG) ?: return
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "申请 WifiManager.MulticastLock 失败，可能收不到局域网广播（不影响手动输入 IP）", e)
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 WifiManager.MulticastLock 失败", e)
        }
    }

    // ------------------------------------------------------------ 连接

    override suspend fun join(target: QuickJoinTarget): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                joinLocked(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "连接对方设备失败", e)
                joinFailure("连接对方设备失败：" + quickErrorMessage(e))
            }
        }
    }

    private suspend fun joinLocked(target: QuickJoinTarget): Result<Unit> {
        val code = QuickSessionCode.normalize(target.code)
            ?: return joinFailure("接收码必须是 6 位数字")
        val host = target.host.trim()
        if (host.isEmpty()) return joinFailure("请输入对方的 IP 地址")
        // 同一目标连续接收码错误会被限流：锁定期间直接拒绝，避免继续暴力尝试
        val attemptKey = QuickJoinAttemptLimiter.keyFor(
            host,
            target.ftpPort,
            target.ftpsPort,
            target.sshPort
        )
        val lockedSeconds = joinAttemptLimiter.lockedRemainingSeconds(attemptKey)
        if (lockedSeconds > 0L) {
            return joinFailure(joinLockedMessage(lockedSeconds))
        }
        closeActiveSession()

        val candidates = target.protocol?.let { protocol -> listOf(protocol) }
            ?: QuickTransferDefaults.PROTOCOL_PREFERENCE
        val credentials = QuickSessionCode.deriveCredentials(code)
        mutableState.update { current ->
            current.copy(
                phase = QuickReceivePhase.CONNECTING,
                joined = null,
                activeProtocol = null,
                remoteFiles = emptyList(),
                transfer = null,
                error = null,
                message = "正在连接 " + host + "（" + candidates.joinToString("/") { it.label } + "）"
            )
        }

        val failures = ArrayList<String>(candidates.size)
        // 自动回退会依次尝试多种协议，但同一个接收码错误只计 1 次失败
        var authenticationFailed = false
        for (protocol in candidates) {
            val port = target.portFor(protocol)
            val candidate = RemoteFileSystems.create(
                createProfile(protocol, host, port, credentials.username, credentials.password)
            )
            try {
                val entries = withTimeout(JOIN_TIMEOUT_MS) {
                    candidate.connect()
                    candidate.list(RemotePath.ROOT)
                }
                fileSystem = candidate
                // 文件夹传输：对端根目录下可能是目录树，递归展开后子目录文件才可下载
                val remoteEntries = listRemoteTree(candidate, entries)
                val sessionInfo = QuickSessionInfo(
                    code = code,
                    deviceName = target.deviceName.ifBlank { host },
                    addresses = listOf(host),
                    ftpPort = target.ftpPort,
                    ftpsPort = target.ftpsPort,
                    sshPort = target.sshPort,
                    protocols = listOf(protocol)
                )
                mutableState.update { current ->
                    current.copy(
                        phase = QuickReceivePhase.CONNECTED,
                        joined = sessionInfo,
                        activeProtocol = protocol,
                        remoteFiles = remoteEntries,
                        error = null,
                        message = "已通过 " + protocol.label + " 连接对方设备"
                    )
                }
                joinAttemptLimiter.recordSuccess(attemptKey)
                Log.i(
                    TAG,
                    "已通过 " + protocol.label + " 连接 " + host + ":" + port +
                        "，远端共 " + remoteEntries.size + " 项（文件 " + remoteEntries.count { !it.isDirectory } + " 个）"
                )
                scheduleAutoDownload()
                return Result.success(Unit)
            } catch (e: TimeoutCancellationException) {
                runCatching { candidate.close() }
                failures += protocol.label + "：连接超时（" + (JOIN_TIMEOUT_MS / 1000L) + " 秒）"
            } catch (e: CancellationException) {
                runCatching { candidate.close() }
                throw e
            } catch (e: Exception) {
                runCatching { candidate.close() }
                if (e is AuthenticationException) authenticationFailed = true
                failures += protocol.label + "：" + quickErrorMessage(e)
            }
        }

        if (authenticationFailed) {
            joinAttemptLimiter.recordFailure(attemptKey)
            val remaining = joinAttemptLimiter.lockedRemainingSeconds(attemptKey)
            if (remaining > 0L) {
                // 第 5 次错误会触发锁定，直接返回剩余锁定时间提示
                return joinFailure(joinLockedMessage(remaining))
            }
        }
        val summary = "无法连接对方设备（已尝试 " + candidates.joinToString("/") { it.label } + "）：" +
            failures.joinToString("；")
        return joinFailure(summary)
    }

    private fun createProfile(
        protocol: TransferProtocol,
        host: String,
        port: Int,
        username: String,
        password: String
    ): ServerProfile = ServerProfile(
        name = "快捷传输 " + host,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = password,
        ftpsMode = FtpsMode.EXPLICIT,
        passiveMode = true,
        allowInsecureTls = true
    )

    /** 限流提示：剩余锁定秒数换算成分钟（向上取整，至少 1 分钟）。 */
    private fun joinLockedMessage(remainingSeconds: Long): String {
        val minutes = ((remainingSeconds + 59L) / 60L).coerceAtLeast(1L)
        return "接收码错误次数过多，请 " + minutes + " 分钟后再试"
    }

    private fun joinFailure(message: String): Result<Unit> {
        mutableState.update { current ->
            current.copy(
                phase = QuickReceivePhase.FAILED,
                joined = null,
                activeProtocol = null,
                transfer = null,
                message = null,
                error = message
            )
        }
        Log.w(TAG, message)
        return Result.failure(IllegalStateException(message))
    }

    private fun scheduleAutoDownload() {
        autoDownloadJob?.cancel()
        autoDownloadJob = scope.launch {
            try {
                downloadAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "自动接收文件失败", e)
            }
        }
    }

    // ------------------------------------------------------------ 远端目录递归扫描

    /**
     * 递归扫描对端根目录：返回根条目 + 所有子目录内的条目（文件/目录都保留，层级由 [RemoteEntry.path] 表示）。
     * 超时或个别子目录失败只降级为根目录内容/跳过该目录，不影响整次连接。
     */
    private suspend fun listRemoteTree(
        currentFileSystem: RemoteFileSystem,
        rootEntries: List<RemoteEntry>
    ): List<RemoteEntry> {
        return try {
            withTimeout(RECURSIVE_LIST_TIMEOUT_MS) { expandDirectories(currentFileSystem, rootEntries) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "递归扫描对方目录超时，仅使用根目录内容")
            rootEntries
        }
    }

    /** 广度优先列出子目录；深度/条目数有上界，避免异常目录树拖垮会话。 */
    private suspend fun expandDirectories(
        currentFileSystem: RemoteFileSystem,
        rootEntries: List<RemoteEntry>
    ): List<RemoteEntry> {
        val result = ArrayList<RemoteEntry>(rootEntries.size)
        result += rootEntries
        val queue = ArrayDeque<Pair<String, Int>>()
        for (entry in rootEntries) {
            if (entry.isDirectory && !entry.isSymlink) queue.addLast(entry.path to 1)
        }
        val visited = HashSet<String>()
        while (queue.isNotEmpty() && result.size < QuickFolderPaths.MAX_RECURSIVE_ENTRIES) {
            val (directoryPath, depth) = queue.removeFirst()
            if (!visited.add(directoryPath)) continue
            if (depth > QuickFolderPaths.MAX_RELATIVE_DEPTH) continue
            val children = try {
                currentFileSystem.list(directoryPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "递归列出子目录失败，已跳过：" + directoryPath, e)
                continue
            }
            val remaining = QuickFolderPaths.MAX_RECURSIVE_ENTRIES - result.size
            if (remaining <= 0) break
            val accepted = children.take(remaining)
            result += accepted
            if (depth < QuickFolderPaths.MAX_RELATIVE_DEPTH) {
                for (child in accepted) {
                    if (child.isDirectory && !child.isSymlink) queue.addLast(child.path to depth + 1)
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------ 下载

    override suspend fun download(remotePath: String): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val currentFileSystem = fileSystem
                    ?: return@withContext transferFailure("请先连接对方设备")
                val wanted = remotePath.trim()
                if (wanted.isEmpty()) return@withContext transferFailure("请选择要接收的文件")
                val normalizedWanted = RemotePath.normalize(wanted)
                val entry = mutableState.value.remoteFiles.firstOrNull { remote ->
                    // 支持传绝对路径、相对路径（子目录文件）或纯文件名
                    remote.isFile && (
                        remote.path == normalizedWanted ||
                            remote.path == wanted ||
                            remote.path.endsWith("/" + wanted) ||
                            remote.name == wanted
                        )
                } ?: return@withContext transferFailure("对方目录中没有这个文件：" + wanted)
                mutableState.update { it.copy(phase = QuickReceivePhase.TRANSFERRING, error = null) }
                val result = downloadOneLocked(currentFileSystem, entry, 0, 1)
                mutableState.update {
                    it.copy(phase = QuickReceivePhase.CONNECTED, transfer = null)
                }
                if (result.isFailure) {
                    Result.failure(
                        IllegalStateException(result.exceptionOrNull()?.message ?: "接收文件失败")
                    )
                } else {
                    Result.success(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transferFailure("接收文件失败：" + quickErrorMessage(e))
            }
        }
    }

    override suspend fun downloadAll(): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                downloadAllLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transferFailure("接收文件失败：" + quickErrorMessage(e))
            }
        }
    }

    private suspend fun downloadAllLocked(): Result<Unit> {
        val currentFileSystem = fileSystem ?: return transferFailure("请先连接对方设备")
        var files = mutableState.value.remoteFiles.filter { it.isFile }
        if (files.isEmpty()) {
            // 连接后列表可能为空/过期，重新递归列出一次再决定
            val refreshed = runCatching {
                listRemoteTree(currentFileSystem, currentFileSystem.list(RemotePath.ROOT))
            }.getOrNull()
            if (refreshed != null) {
                mutableState.update { it.copy(remoteFiles = refreshed) }
                files = refreshed.filter { it.isFile }
            }
        }
        if (files.isEmpty()) {
            mutableState.update {
                it.copy(phase = QuickReceivePhase.CONNECTED, transfer = null, message = "对方没有可接收的文件")
            }
            return Result.success(Unit)
        }

        mutableState.update {
            it.copy(phase = QuickReceivePhase.TRANSFERRING, error = null, message = null, transfer = null)
        }
        var completed = 0
        val failed = ArrayList<String>()
        files.forEachIndexed { index, entry ->
            if (fileSystem !== currentFileSystem) return@forEachIndexed
            val result = downloadOneLocked(currentFileSystem, entry, index, files.size)
            if (result.isSuccess) completed++ else failed += result.exceptionOrNull()?.message ?: entry.name
        }
        if (fileSystem !== currentFileSystem) {
            return Result.failure(IllegalStateException("连接已断开，接收已停止"))
        }
        val message = if (failed.isEmpty()) {
            "已接收 " + completed + " 个文件"
        } else {
            "接收完成：成功 " + completed + " 个，失败 " + failed.size + " 个"
        }
        mutableState.update {
            it.copy(
                phase = QuickReceivePhase.CONNECTED,
                transfer = null,
                message = message,
                error = if (failed.isEmpty()) null else failed.joinToString("；")
            )
        }
        return if (failed.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("部分文件接收失败：" + failed.joinToString("；")))
        }
    }

    private suspend fun downloadOneLocked(
        currentFileSystem: RemoteFileSystem,
        entry: RemoteEntry,
        index: Int,
        fileCount: Int
    ): Result<Unit> {
        val displayName = ShareFileNames.sanitize(entry.name)
        // 文件夹传输：保留远端相对目录结构；顶层文件 relativeDir 为 null，直接落到下载根目录
        val relativeDir = QuickFolderPaths.sanitizeRelativeDir(QuickFolderPaths.relativeDirOf(entry.path))
        val failureLabel = if (relativeDir != null) relativeDir + "/" + displayName else displayName
        val target = try {
            downloadSink.openInDir(displayName, relativeDir)
        } catch (e: Exception) {
            val message = "创建下载文件失败：" + quickErrorMessage(e)
            mutableState.update {
                it.copy(failedFiles = it.failedFiles + failureLabel, error = message, transfer = null)
            }
            Log.w(TAG, "创建下载文件失败：" + failureLabel, e)
            return Result.failure(IllegalStateException(message))
        }
        val totalBytes = if (entry.size > 0L) entry.size else -1L
        val tracker = TransferProgressTracker()
        publishProgress(displayName, QuickTransferProgress.Direction.RECEIVE, 0L, totalBytes, index, fileCount, 0L)
        try {
            target.outputStream.use { sink ->
                currentFileSystem.download(entry.path, sink) { transferred ->
                    // 节流推送并附带滑动窗口瞬时速度，UI 据此显示速度与预计剩余时间
                    val sample = tracker.sample(System.currentTimeMillis(), transferred, totalBytes)
                    if (sample != null) {
                        publishProgress(
                            displayName,
                            QuickTransferProgress.Direction.RECEIVE,
                            transferred,
                            totalBytes,
                            index,
                            fileCount,
                            sample.bytesPerSecond
                        )
                    }
                }
            }
            val committed = target.commit()
            if (committed.isFailure) {
                throw committed.exceptionOrNull() ?: IOException("保存下载文件失败")
            }
            val finalName = committed.getOrThrow()
            mutableState.update {
                it.copy(completedFiles = it.completedFiles + finalName, transfer = null)
            }
            Log.i(TAG, "已接收文件：" + finalName + "（" + entry.path + "）")
            return Result.success(Unit)
        } catch (e: CancellationException) {
            runCatching { target.abort() }
            throw e
        } catch (e: Exception) {
            runCatching { target.abort() }
            val message = quickErrorMessage(e)
            mutableState.update {
                it.copy(failedFiles = it.failedFiles + failureLabel, transfer = null, error = message)
            }
            Log.w(TAG, "接收文件失败：" + entry.path, e)
            return Result.failure(IllegalStateException(message))
        }
    }

    private fun publishProgress(
        displayName: String,
        direction: QuickTransferProgress.Direction,
        transferred: Long,
        totalBytes: Long,
        index: Int,
        fileCount: Int,
        bytesPerSecond: Long = 0L
    ) {
        mutableState.update {
            it.copy(
                transfer = QuickTransferProgress(
                    displayName = displayName,
                    direction = direction,
                    bytesTransferred = transferred,
                    totalBytes = totalBytes,
                    bytesPerSecond = bytesPerSecond,
                    fileIndex = index,
                    fileCount = fileCount
                )
            )
        }
    }

    private fun transferFailure(message: String): Result<Unit> {
        mutableState.update { current ->
            current.copy(
                phase = if (current.phase == QuickReceivePhase.TRANSFERRING) {
                    QuickReceivePhase.CONNECTED
                } else {
                    current.phase
                },
                transfer = null,
                error = message
            )
        }
        Log.w(TAG, message)
        return Result.failure(IllegalStateException(message))
    }

    // ------------------------------------------------------------ 互传：发送文件给对方

    override suspend fun sendFiles(uris: List<Uri>): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentFileSystem = fileSystem ?: return@withContext transferFailure("请先连接对方设备")
            if (uris.isEmpty()) return@withContext transferFailure("请先选择要发送的文件")
            if (uris.size > QuickTransferDefaults.MAX_FILES) {
                return@withContext transferFailure("一次最多发送 " + QuickTransferDefaults.MAX_FILES + " 个文件")
            }
            // 目录项不参与上传重名判断（只与对端根目录下的文件比较）
            val existingNames = mutableState.value.remoteFiles
                .filter { !it.isDirectory }
                .mapTo(LinkedHashSet<String>()) { it.name }
            val sent = ArrayList<String>(uris.size)
            try {
                mutableState.update {
                    it.copy(phase = QuickReceivePhase.TRANSFERRING, error = null, message = null, transfer = null)
                }
                uris.forEachIndexed { index, uri ->
                    val openable = queryOpenable(uri)
                    val rawName = openable?.first
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        ?: "快捷传输文件"
                    val declaredSize = openable?.second ?: -1L
                    val displayName = ShareFileNames.uniqueName(rawName, existingNames)
                    val remotePath = RemotePath.join(RemotePath.ROOT, displayName)
                    val input = try {
                        resolver.openInputStream(uri)
                    } catch (e: Exception) {
                        throw IOException("无法读取本地文件：" + rawName, e)
                    } ?: throw IOException("无法读取本地文件：" + rawName)
                    val totalBytes = if (declaredSize > 0L) declaredSize else -1L
                    val tracker = TransferProgressTracker()
                    publishProgress(
                        displayName,
                        QuickTransferProgress.Direction.SEND,
                        0L,
                        totalBytes,
                        index,
                        uris.size,
                        0L
                    )
                    input.use { source ->
                        currentFileSystem.upload(source, remotePath, totalBytes) { transferred ->
                            val sample = tracker.sample(System.currentTimeMillis(), transferred, totalBytes)
                            if (sample != null) {
                                publishProgress(
                                    displayName,
                                    QuickTransferProgress.Direction.SEND,
                                    transferred,
                                    totalBytes,
                                    index,
                                    uris.size,
                                    sample.bytesPerSecond
                                )
                            }
                        }
                    }
                    existingNames.add(displayName)
                    sent.add(displayName)
                    mutableState.update { it.copy(sentFiles = it.sentFiles + displayName) }
                    Log.i(TAG, "已发送文件给对方：" + displayName)
                }
                val refreshed = runCatching {
                    listRemoteTree(currentFileSystem, currentFileSystem.list(RemotePath.ROOT))
                }.getOrNull()
                mutableState.update {
                    it.copy(
                        phase = QuickReceivePhase.CONNECTED,
                        transfer = null,
                        remoteFiles = refreshed ?: it.remoteFiles,
                        message = "已发送 " + sent.size + " 个文件给对方"
                    )
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "发送给对方的文件失败：" + quickErrorMessage(e, TransferDirection.UPLOAD)
                mutableState.update {
                    it.copy(phase = QuickReceivePhase.CONNECTED, transfer = null, error = message)
                }
                Log.w(TAG, message, e)
                Result.failure(IllegalStateException(message))
            }
        }
    }

    private fun queryOpenable(uri: Uri): Pair<String, Long>? {
        return try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                if (name.isNullOrBlank()) null else name to size
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询本地文件名失败", e)
            null
        }
    }

    // ------------------------------------------------------------ 结束会话

    override suspend fun leave() {
        // 先关闭连接/取消自动接收：协议层约定 close 可跨线程调用，正在传输的下载会尽快失败返回，
        // 避免用户点「断开」后还要等整批文件下完才拿到锁。
        autoDownloadJob?.cancel()
        fileSystem?.let { current -> runCatching { current.close() } }
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                stopDiscovery()
                closeActiveSession()
                mutableState.value = QuickReceiveState()
                Log.i(TAG, "已离开快捷接收会话")
            }
        }
    }

    private fun closeActiveSession() {
        autoDownloadJob?.cancel()
        autoDownloadJob = null
        fileSystem?.let { current -> runCatching { current.close() } }
        fileSystem = null
    }

    private fun quickErrorMessage(
        error: Throwable,
        direction: TransferDirection = TransferDirection.DOWNLOAD
    ): String {
        if (error is TimeoutCancellationException) {
            return "连接超时，请确认两台设备在同一 Wi-Fi 且对方会话仍在进行"
        }
        val mapped = TransferErrors.toUserMessage(error, direction)
        return if (mapped.isNotBlank()) mapped else error.javaClass.simpleName
    }

    private companion object {
        const val TAG = "DefaultQuickReceiveManager"
        const val DISCOVERY_TTL_MS = 6_000L
        const val DISCOVERY_EXPIRY_CHECK_MS = 1_000L
        const val JOIN_TIMEOUT_MS = 20_000L
        const val RECURSIVE_LIST_TIMEOUT_MS = 60_000L
        const val MULTICAST_LOCK_TAG = "LanTransferQuick"
    }
}

/**
 * 文件夹传输的路径工具（纯 JVM 逻辑，供快捷发送/接收两个 Manager 与 JVM/设备测试共用）。
 *
 * 放在本文件是因为 task-14 的 write scope 只覆盖两个 Manager 文件；内容与 Android API 无关，
 * 可直接在 JVM 单测里验证。
 */
internal object QuickFolderPaths {

    /** 相对路径最大层级。 */
    const val MAX_RELATIVE_DEPTH = 16

    /** 相对路径最大长度（字符）。 */
    const val MAX_RELATIVE_LENGTH = 1024

    /** 递归扫描远端/会话目录的最大条目数，防止异常目录树拖垮会话。 */
    const val MAX_RECURSIVE_ENTRIES = 5_000

    /** 规范化相对路径：统一用 "/"、去掉空段与 "."/".."，空结果返回 null。 */
    fun normalizeRelative(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val segments = ArrayList<String>()
        for (segment in text.replace('\\', '/').split('/')) {
            val name = segment.trim()
            when {
                name.isBlank() || name == "." -> continue
                // ".." 回退上一级（不越出根），防止相对路径上跳造成目录穿越
                name == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> {
                    if (segments.size >= MAX_RELATIVE_DEPTH) break
                    segments += name
                }
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/").take(MAX_RELATIVE_LENGTH).trimEnd('/').ifBlank { null }
    }

    /** 远端绝对路径（"/相册/2024/a.jpg"）-> 相对目录（"相册/2024"）；根目录上的文件返回 null。 */
    fun relativeDirOf(path: String?): String? {
        val normalized = normalizeRelative(path) ?: return null
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) null else normalized.substring(0, index)
    }

    /** 会话目录内文件的相对路径（"/" 分隔）；顶层文件返回 null。 */
    fun relativePathOf(directory: File, file: File): String? {
        return try {
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            normalizeRelative(relative)?.takeIf { it.contains('/') }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** 逐段清洗后用作媒体库/本地子目录的相对路径；空结果返回 null。 */
    fun sanitizeRelativeDir(raw: String?): String? {
        val normalized = normalizeRelative(raw) ?: return null
        val cleaned = normalized.split('/')
            .map { segment -> ShareFileNames.sanitize(segment) }
            .filter { it.isNotBlank() }
        return cleaned.joinToString("/").ifBlank { null }
    }

    /** 会话文件的稳定键：子目录文件用相对路径，顶层文件用文件名。 */
    fun fileKey(relativePath: String?, name: String): String =
        normalizeRelative(relativePath) ?: name
}

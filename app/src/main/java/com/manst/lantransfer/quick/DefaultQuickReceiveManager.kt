package com.manst.lantransfer.quick

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Log
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
import java.io.IOException

/**
 * 快捷传输接收端默认实现。
 *
 * 能力：
 * - 局域网发现：[startDiscovery] 用 [UdpBeaconScanner] 监听 UDP 广播（配合 WifiManager.MulticastLock），
 *   按接收码去重、按最近收到时间排序，6 秒未再收到即过期清理；
 * - 连接：[join] 用接收码派生临时帐号，按指定协议或 SFTP -> SCP -> FTPS -> FTP 顺序依次尝试，
 *   第一个 connect + list 成功的协议即会话协议，随后后台自动 [downloadAll]（不阻塞 join 返回）；
 * - 接收：[downloadAll] / [download] 顺序下载到 [QuickDownloadSink]（系统下载目录），提交成功才计入完成；
 * - 互传：[sendFiles] 用同一个连接把本机文件上传到对方根目录；
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
                        remoteFiles = entries,
                        error = null,
                        message = "已通过 " + protocol.label + " 连接对方设备"
                    )
                }
                Log.i(TAG, "已通过 " + protocol.label + " 连接 " + host + ":" + port + "，远端文件 " + entries.size + " 个")
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
                failures += protocol.label + "：" + quickErrorMessage(e)
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

    // ------------------------------------------------------------ 下载

    override suspend fun download(remotePath: String): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val currentFileSystem = fileSystem
                    ?: return@withContext transferFailure("请先连接对方设备")
                val wanted = remotePath.trim()
                if (wanted.isEmpty()) return@withContext transferFailure("请选择要接收的文件")
                val entry = mutableState.value.remoteFiles.firstOrNull { remote ->
                    remote.isFile && (remote.path == RemotePath.normalize(wanted) || remote.name == wanted)
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
            // 连接后列表可能为空/过期，重列一次再决定
            val refreshed = runCatching { currentFileSystem.list(RemotePath.ROOT) }.getOrNull()
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
        val target = try {
            downloadSink.open(displayName)
        } catch (e: Exception) {
            val message = "创建下载文件失败：" + quickErrorMessage(e)
            mutableState.update {
                it.copy(failedFiles = it.failedFiles + displayName, error = message, transfer = null)
            }
            Log.w(TAG, "创建下载文件失败：" + displayName, e)
            return Result.failure(IllegalStateException(message))
        }
        val totalBytes = if (entry.size > 0L) entry.size else -1L
        publishProgress(displayName, QuickTransferProgress.Direction.RECEIVE, 0L, totalBytes, index, fileCount)
        try {
            target.outputStream.use { sink ->
                currentFileSystem.download(entry.path, sink) { transferred ->
                    publishProgress(
                        displayName,
                        QuickTransferProgress.Direction.RECEIVE,
                        transferred,
                        totalBytes,
                        index,
                        fileCount
                    )
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
                it.copy(failedFiles = it.failedFiles + displayName, transfer = null, error = message)
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
        fileCount: Int
    ) {
        mutableState.update {
            it.copy(
                transfer = QuickTransferProgress(
                    displayName = displayName,
                    direction = direction,
                    bytesTransferred = transferred,
                    totalBytes = totalBytes,
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
            val existingNames = mutableState.value.remoteFiles
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
                    publishProgress(
                        displayName,
                        QuickTransferProgress.Direction.SEND,
                        0L,
                        totalBytes,
                        index,
                        uris.size
                    )
                    input.use { source ->
                        currentFileSystem.upload(source, remotePath, totalBytes) { transferred ->
                            publishProgress(
                                displayName,
                                QuickTransferProgress.Direction.SEND,
                                transferred,
                                totalBytes,
                                index,
                                uris.size
                            )
                        }
                    }
                    existingNames.add(displayName)
                    sent.add(displayName)
                    mutableState.update { it.copy(sentFiles = it.sentFiles + displayName) }
                    Log.i(TAG, "已发送文件给对方：" + displayName)
                }
                val refreshed = runCatching { currentFileSystem.list(RemotePath.ROOT) }.getOrNull()
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
        const val MULTICAST_LOCK_TAG = "LanTransferQuick"
    }
}

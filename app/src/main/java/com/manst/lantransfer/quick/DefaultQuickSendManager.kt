package com.manst.lantransfer.quick

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.manst.lantransfer.data.ShareFileNames
import com.manst.lantransfer.server.LanAddress
import com.manst.lantransfer.server.LocalServerConfig
import com.manst.lantransfer.server.LocalServerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 快捷传输发送端默认实现。
 *
 * 一次会话的完整流程：
 * 1. 生成 8 位接收码，用 [QuickSessionCode.deriveCredentials] 派生临时帐号（双方算法一致，无需手动配置）；
 * 2. 选择三个空闲端口（优先 [QuickTransferDefaults.DEFAULT_FTP_PORT] / DEFAULT_FTPS_PORT / DEFAULT_SSH_PORT，
 *    被占用则逐个 +1；SFTP 与 SCP 共用 ssh 端口）；
 * 3. 把用户选择的文件复制进会话目录（externalFilesDir/quick-send/接收码）：普通文件走
 *    [SessionFileStore.importFiles]，SAF 目录树（OpenDocumentTree 返回的 tree Uri）走
 *    [SessionFileStore.importDirectory] 并保留子目录结构（QuickFile.relativePath）；
 * 4. 以会话目录为共享根目录、四协议全开启动 [LocalServerManager]，子目录可被对端浏览/下载；
 * 5. 每秒通过 [UdpBeaconBroadcaster] 在局域网广播 [QuickSessionInfo]；
 * 6. 每 2 秒递归扫描会话目录：把会话开始后新出现（或内容被覆盖）的文件归入 receivedFiles（含子目录），实现互传；
 * 7. 会话结束前把 receivedFiles 自动保留到系统下载目录（保留相对子目录结构，已手动导出过的不重复导出），
 *    之后再删除会话目录——避免对方上传的互传文件随会话结束丢失。
 *
 * 生命周期：同一时间只允许一个会话；[stopSession] 幂等，停止广播、停止服务器、删除会话目录并把状态复位。
 */
class DefaultQuickSendManager(
    context: Context,
    private val localServerManager: LocalServerManager,
    private val sessionFileStore: SessionFileStore,
    /** 会话结束自动保留收到文件用的落盘目标（默认写系统下载目录；设备测试可注入假实现）。 */
    private val autoExportSink: QuickDownloadSink =
        MediaStoreQuickDownloadSink(context.applicationContext)
) : QuickSendManager {

    private val mutableState = MutableStateFlow(QuickSendState())

    override val state: StateFlow<QuickSendState> = mutableState.asStateFlow()

    /** 会话级串行锁：启停、扫描、导出互不并发，保证状态与资源一致。 */
    private val sessionMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var scanJob: Job? = null

    private var beacon: UdpBeaconBroadcaster? = null

    private var sessionDirectory: File? = null

    /** 会话开始（导入完成）时的会话目录快照，用于识别对方后来上传的文件。 */
    private var baselineFiles: Map<String, FileFingerprint> = emptyMap()

    /** 本机导入文件的键（relativePath ?: name；sendingFiles 只展示这些本机文件）。 */
    private var importedKeys: Set<String> = emptySet()

    /** 已导出到系统下载目录的收到文件：文件键 -> 导出时的修改时间，避免重复出现在 receivedFiles。 */
    private val exportedReceivedAt: MutableMap<String, Long> = HashMap()

    private data class FileFingerprint(val size: Long, val modifiedAt: Long)

    private data class PortSelection(val ftpPort: Int, val ftpsPort: Int, val sshPort: Int)

    // ------------------------------------------------------------ 会话启停

    override suspend fun startSession(uris: List<Uri>): Result<Unit> = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                startSessionLocked(uris)
            } catch (e: CancellationException) {
                cleanupSessionLocked()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "启动快捷发送会话失败", e)
                failStart("启动快捷发送会话失败：" + userMessage(e))
            }
        }
    }

    private suspend fun startSessionLocked(uris: List<Uri>): Result<Unit> {
        cleanupSessionLocked()
        if (uris.isEmpty()) return failStart("请先选择要发送的文件")
        if (uris.size > QuickTransferDefaults.MAX_FILES) {
            return failStart("一次最多发送 " + QuickTransferDefaults.MAX_FILES + " 个文件")
        }
        mutableState.value = QuickSendState(phase = QuickSendPhase.PREPARING)

        // 快捷会话需要独占本机服务器（端口与共享目录都要换成会话专用的）
        if (localServerManager.state.value.isRunning) {
            Log.i(TAG, "快捷发送会话需要独占本机服务器，先停止当前运行的服务")
            runCatching { localServerManager.stop() }
                .onFailure { Log.w(TAG, "停止先前运行的本机服务器失败", it) }
        }

        val code = QuickSessionCode.generate()
        val credentials = QuickSessionCode.deriveCredentials(code)
        sessionFileStore.clear(sessionFileStore.directoryFor(code))
        val directory = sessionFileStore.directoryFor(code)
        sessionDirectory = directory

        // 普通文件 Uri 与 SAF 目录树 Uri 分开导入：目录树保留子目录结构（QuickFile.relativePath）
        val fileUris = ArrayList<Uri>(uris.size)
        val treeUris = ArrayList<Uri>()
        for (uri in uris) {
            if (isTreeUri(uri)) treeUris += uri else fileUris += uri
        }
        val importedFiles = ArrayList<QuickFile>(uris.size)
        val failedItems = ArrayList<String>()
        if (fileUris.isNotEmpty()) {
            val result = sessionFileStore.importFiles(directory, fileUris)
            importedFiles += result.imported
            failedItems += result.failed
        }
        for (treeUri in treeUris) {
            try {
                val result = sessionFileStore.importDirectory(directory, treeUri)
                importedFiles += result.imported
                failedItems += result.failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 旧实现/导入器异常不应让整个会话崩溃：记录失败并继续导入其他选择
                Log.w(TAG, "导入文件夹失败：" + treeUri, e)
                val reason = if (e is UnsupportedOperationException) {
                    "当前版本不支持文件夹导入"
                } else {
                    e.message ?: "导入失败"
                }
                failedItems += "文件夹（" + reason + "）"
            }
        }
        if (importedFiles.isEmpty()) {
            val detail = if (failedItems.isEmpty()) "" else "（" + failedItems.joinToString("、") + "）"
            return failStart("没有成功导入任何文件" + detail)
        }
        if (failedItems.isNotEmpty()) {
            Log.w(TAG, "部分文件/文件夹复制到会话目录失败：" + failedItems.joinToString("、"))
        }

        val ports = selectPorts()
            ?: return failStart("找不到可用的监听端口，请检查是否有其他应用占用 2121/2122/2222 附近端口")
        val config = LocalServerConfig(
            enableFtp = true,
            enableFtps = true,
            enableSftp = true,
            enableScp = true,
            ftpPort = ports.ftpPort,
            ftpsPort = ports.ftpsPort,
            sshPort = ports.sshPort,
            username = credentials.username,
            password = credentials.password,
            shareRootOverride = directory.absolutePath
        )
        val configErrors = localServerManager.validate(config)
        if (configErrors.isNotEmpty()) {
            return failStart("快捷会话服务器配置非法：" + configErrors.joinToString("；"))
        }
        val startResult = localServerManager.start(config)
        if (startResult.isFailure) {
            val detail = startResult.exceptionOrNull()?.let { userMessage(it) } ?: "未知错误"
            return failStart("启动本机服务器失败：" + detail)
        }
        val serverState = localServerManager.state.value
        val runningEndpoints = serverState.endpoints.filter { it.running }
        if (runningEndpoints.isEmpty()) {
            return failStart(serverState.lastError ?: "四种协议服务端都没有启动成功")
        }
        if (serverState.lastError != null) {
            Log.w(TAG, "部分协议服务端启动失败（会话继续）：" + serverState.lastError)
        }
        val protocols = QuickTransferDefaults.PROTOCOLS.filter { protocol ->
            runningEndpoints.any { it.protocol == protocol }
        }

        val importedKeySet = importedFiles.mapTo(HashSet()) { fileKey(it) }
        importedKeys = importedKeySet
        val scannedFiles = scanSessionFiles(directory)
        var sendingFiles = scannedFiles.filter { fileKey(it) in importedKeySet }
        if (sendingFiles.isEmpty()) {
            // 兜底：个别导入实现的 relativePath 可能与磁盘实际路径不完全一致，至少保证导入成功的文件可见
            sendingFiles = importedFiles.sortedWith(QUICK_FILE_ORDER)
        }
        baselineFiles = sendingFiles.associate {
            fileKey(it) to FileFingerprint(it.size, it.modifiedAt)
        }
        val deviceName = Build.MODEL?.trim().orEmpty().ifBlank { "Android 设备" }
        mutableState.value = QuickSendState(
            phase = QuickSendPhase.WAITING,
            code = code,
            deviceName = deviceName,
            addresses = LanAddress.localIpv4Addresses(),
            endpoints = runningEndpoints,
            protocols = protocols,
            sessionDirectory = directory.absolutePath,
            sendingFiles = sendingFiles
        )
        val sessionInfo = mutableState.value.sessionInfo ?: return failStart("生成快捷会话信息失败")
        beacon = UdpBeaconBroadcaster().also { broadcaster ->
            runCatching { broadcaster.start(sessionInfo) }
                .onFailure { Log.w(TAG, "启动局域网广播失败（对方可手动输入 IP 连接）", it) }
        }
        startScanLoop()
        Log.i(
            TAG,
            "快捷发送会话已启动：code=" + code + " 协议=" +
                protocols.joinToString("/") { it.label } + " 端点=" +
                runningEndpoints.joinToString("、") { it.protocol.label + ":" + it.port }
        )
        return Result.success(Unit)
    }

    override suspend fun stopSession() {
        sessionMutex.withLock {
            withContext(Dispatchers.IO) { cleanupSessionLocked() }
        }
    }

    /** 清理会话资源并复位状态；可重复调用。 */
    private suspend fun cleanupSessionLocked() {
        scanJob?.cancel()
        scanJob = null
        beacon?.let { broadcaster -> runCatching { broadcaster.stop() } }
        beacon = null
        val directory = sessionDirectory
        if (directory != null) {
            // 清理前先刷新一次并自动保留收到文件，避免对方上传的互传文件随会话目录一起被删
            if (mutableState.value.phase == QuickSendPhase.WAITING) {
                runCatching { scanReceivedFilesLocked() }
                    .onFailure { Log.w(TAG, "会话结束前刷新收到文件失败", it) }
                autoExportReceivedLocked(directory)
            }
            runCatching { localServerManager.stop() }
                .onFailure { Log.w(TAG, "停止快捷会话服务器失败", it) }
            sessionFileStore.clear(directory)
        }
        sessionDirectory = null
        baselineFiles = emptyMap()
        importedKeys = emptySet()
        exportedReceivedAt.clear()
        mutableState.value = QuickSendState()
    }

    private suspend fun failStart(message: String): Result<Unit> {
        cleanupSessionLocked()
        mutableState.value = QuickSendState(error = message)
        Log.w(TAG, message)
        return Result.failure(IllegalStateException(message))
    }

    // ------------------------------------------------------------ 收到文件扫描

    private fun startScanLoop() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                delay(RECEIVED_SCAN_INTERVAL_MS)
                try {
                    sessionMutex.withLock {
                        withContext(Dispatchers.IO) { scanReceivedFilesLocked() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "扫描快捷会话目录失败", e)
                }
            }
        }
    }

    override suspend fun refresh() {
        sessionMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    scanReceivedFilesLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "刷新快捷会话目录失败", e)
                }
            }
        }
    }

    /** 必须在 sessionMutex 内调用。 */
    private fun scanReceivedFilesLocked() {
        val current = mutableState.value
        if (current.phase != QuickSendPhase.WAITING) return
        val directory = sessionDirectory ?: return
        // 递归扫描会话目录：对方可能把文件上传到任意子目录（FTP/SFTP 均可建目录）
        val files = scanSessionFiles(directory)
        val sendingFiles = files.filter { fileKey(it) in importedKeys }
        val receivedFiles = files.filter { isReceivedFile(it) }
        if (current.sendingFiles == sendingFiles && current.receivedFiles == receivedFiles) return
        mutableState.value = current.copy(sendingFiles = sendingFiles, receivedFiles = receivedFiles)
    }

    /** 会话开始后新出现的文件、或内容被覆盖过的文件，视为对方上传的收到文件。 */
    private fun isReceivedFile(file: QuickFile): Boolean {
        val key = fileKey(file)
        val exportedAt = exportedReceivedAt[key]
        if (exportedAt != null) {
            if (exportedAt == file.modifiedAt) return false
            exportedReceivedAt.remove(key)
        }
        val baseline = baselineFiles[key] ?: return true
        return baseline.size != file.size || baseline.modifiedAt != file.modifiedAt
    }

    // ------------------------------------------------------------ 导出收到的文件

    override suspend fun exportReceived(fileName: String): Result<String> = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            if (mutableState.value.phase != QuickSendPhase.WAITING) {
                return@withContext exportFailure("当前没有进行中的快捷发送会话")
            }
            val directory = sessionDirectory
                ?: return@withContext exportFailure("快捷发送会话目录不存在")
            val wanted = QuickFolderPaths.normalizeRelative(fileName)
                ?: return@withContext exportFailure("文件名不能为空")
            val received = mutableState.value.receivedFiles
            // UI 可能传文件名（顶层文件）或相对路径（子目录文件），两种都要能找到
            val entry = received.firstOrNull {
                QuickFolderPaths.fileKey(it.relativePath, it.name) == wanted
            } ?: received.firstOrNull {
                QuickFolderPaths.normalizeRelative(it.relativePath) == wanted || it.name == fileName.trim()
            } ?: return@withContext exportFailure("会话中没有这个文件：" + fileName.trim())
            val relativePath = QuickFolderPaths.normalizeRelative(entry.relativePath)
            val file = relativePath?.let { path ->
                File(directory, path.replace('/', File.separatorChar))
            } ?: File(directory, entry.name)
            if (!file.isFile) return@withContext exportFailure("会话中没有这个文件：" + fileName.trim())
            val insideSession = try {
                file.canonicalFile.path.startsWith(directory.canonicalFile.path + File.separator)
            } catch (e: IOException) {
                false
            }
            if (!insideSession) return@withContext exportFailure("非法的文件名：" + fileName.trim())

            val key = QuickFolderPaths.fileKey(entry.relativePath, entry.name)
            val result = sessionFileStore.exportToDownloads(file)
            result.onSuccess { displayName ->
                exportedReceivedAt[key] = file.lastModified()
                val current = mutableState.value
                mutableState.value = current.copy(
                    receivedFiles = current.receivedFiles.filterNot {
                        QuickFolderPaths.fileKey(it.relativePath, it.name) == key
                    }
                )
                Log.i(TAG, "已把收到的文件导出到系统下载目录：" + displayName + "（" + key + "）")
            }.onFailure { error ->
                Log.w(TAG, "导出收到的文件失败：" + key, error)
            }
            result.fold(
                onSuccess = { displayName -> Result.success(displayName) },
                onFailure = { error -> Result.failure(IllegalStateException(userMessage(error))) }
            )
        }
    }

    private fun exportFailure(message: String): Result<String> =
        Result.failure(IllegalStateException(message))

    // ------------------------------------------------------------ 自动保留收到文件

    /**
     * 会话结束前把「收到的文件」自动导出到系统下载目录（保留相对子目录结构）。
     * - 用户已手动导出过的文件（exportedReceivedAt 有记录）不再重复导出；
     * - 单个文件失败只记录日志，不阻塞停止会话；
     * - 必须在清理会话目录之前调用，避免对方上传的互传文件丢失。
     */
    private suspend fun autoExportReceivedLocked(directory: File) {
        if (mutableState.value.phase != QuickSendPhase.WAITING) return
        val received = mutableState.value.receivedFiles
        if (received.isEmpty()) return
        var exported = 0
        for (file in received) {
            val key = fileKey(file)
            if (exportedReceivedAt.containsKey(key)) continue
            val relativePath = QuickFolderPaths.normalizeRelative(file.relativePath)
            val relativeDir = QuickFolderPaths.sanitizeRelativeDir(QuickFolderPaths.relativeDirOf(relativePath))
            val source = relativePath?.let { path ->
                File(directory, path.replace('/', File.separatorChar))
            } ?: File(directory, file.name)
            if (!source.isFile) continue
            try {
                val target = autoExportSink.openInDir(ShareFileNames.sanitize(source.name), relativeDir)
                try {
                    source.inputStream().use { input ->
                        target.outputStream.use { output ->
                            input.copyTo(output, AUTO_EXPORT_BUFFER_SIZE)
                        }
                    }
                    val committed = target.commit()
                    if (committed.isFailure) {
                        throw committed.exceptionOrNull() ?: IOException("保存下载文件失败")
                    }
                    exportedReceivedAt[key] = source.lastModified()
                    exported++
                    Log.i(TAG, "会话结束前自动保留收到文件：" + committed.getOrThrow() + "（" + key + "）")
                } catch (e: Exception) {
                    runCatching { target.abort() }
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "自动保留收到文件失败（会话仍会结束）：" + key, e)
            }
        }
        if (exported > 0) {
            Log.i(TAG, "会话结束前已自动保留 " + exported + " 个收到文件到系统下载目录")
        }
    }

    // ------------------------------------------------------------ 会话目录递归扫描

    /** OpenDocumentTree 返回的目录树 Uri（普通文件/分享 Uri 不是 tree Uri）。 */
    private fun isTreeUri(uri: Uri): Boolean = try {
        DocumentsContract.isTreeUri(uri)
    } catch (e: Exception) {
        false
    }

    /**
     * 递归扫描会话目录，返回所有可见文件：顶层文件 relativePath 为 null，子目录文件为 "/" 分隔相对路径。
     * 忽略隐藏文件、导入临时文件与隐藏目录（与数据层 listFiles 的可见性规则保持一致）。
     */
    private fun scanSessionFiles(directory: File): List<QuickFile> {
        if (!directory.isDirectory) return emptyList()
        val files = directory.walkTopDown()
            .onEnter { dir -> dir == directory || !dir.name.startsWith(".") }
            .filter { file -> file.isFile && !isIgnoredSessionFileName(file.name) }
            .map { file ->
                QuickFile(
                    name = file.name,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    relativePath = QuickFolderPaths.relativePathOf(directory, file)
                )
            }
            .toList()
        return files.sortedWith(QUICK_FILE_ORDER)
    }

    private fun isIgnoredSessionFileName(name: String): Boolean =
        name.startsWith(".") || name.endsWith(IMPORT_TEMP_SUFFIX)

    private fun fileKey(file: QuickFile): String =
        QuickFolderPaths.fileKey(file.relativePath, file.name)

    // ------------------------------------------------------------ 端口选择

    /** 依次为 FTP / FTPS / SSH 选空闲端口：优先 2121 / 2122 / 2222，被占用则 +1，三者互不相同。 */
    private fun selectPorts(): PortSelection? {
        val ftpPort = findFreePort(QuickTransferDefaults.DEFAULT_FTP_PORT) ?: return null
        val ftpsPort = findFreePort(QuickTransferDefaults.DEFAULT_FTPS_PORT, setOf(ftpPort)) ?: return null
        val sshPort = findFreePort(QuickTransferDefaults.DEFAULT_SSH_PORT, setOf(ftpPort, ftpsPort)) ?: return null
        return PortSelection(ftpPort, ftpsPort, sshPort)
    }

    private fun findFreePort(preferred: Int, exclude: Set<Int> = emptySet()): Int? {
        var port = preferred.coerceAtLeast(MIN_PORT)
        while (port <= MAX_PORT) {
            if (port !in exclude && isPortAvailable(port)) return port
            port++
        }
        return null
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port), 1)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun userMessage(error: Throwable): String {
        val raw = error.message?.trim()
        return if (raw.isNullOrEmpty()) error.javaClass.simpleName else raw
    }

    private companion object {
        const val TAG = "DefaultQuickSendManager"
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val RECEIVED_SCAN_INTERVAL_MS = 2_000L
        const val AUTO_EXPORT_BUFFER_SIZE = 64 * 1024

        /** 数据层导入临时文件后缀（隐藏文件规则与 SessionFileNames 保持一致）。 */
        const val IMPORT_TEMP_SUFFIX = ".importing"
    }
}

/** 会话文件排序：与数据层 listFiles 一致，按修改时间倒序、同时间按名称不区分大小写。 */
private val QUICK_FILE_ORDER: Comparator<QuickFile> = compareByDescending<QuickFile> { it.modifiedAt }
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

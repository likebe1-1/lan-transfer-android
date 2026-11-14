package com.manst.lantransfer.quick

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
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
 * 1. 生成 6 位接收码，用 [QuickSessionCode.deriveCredentials] 派生临时帐号（双方算法一致，无需手动配置）；
 * 2. 选择三个空闲端口（优先 [QuickTransferDefaults.DEFAULT_FTP_PORT] / DEFAULT_FTPS_PORT / DEFAULT_SSH_PORT，
 *    被占用则逐个 +1；SFTP 与 SCP 共用 ssh 端口）；
 * 3. 用 [SessionFileStore.importFiles] 把用户选择的文件复制进会话目录（externalFilesDir/quick-send/接收码）；
 * 4. 以会话目录为共享根目录、四协议全开启动 [LocalServerManager]；
 * 5. 每秒通过 [UdpBeaconBroadcaster] 在局域网广播 [QuickSessionInfo]；
 * 6. 每 2 秒扫描会话目录：把会话开始后新出现（或内容被覆盖）的文件归入 receivedFiles，实现互传。
 *
 * 生命周期：同一时间只允许一个会话；[stopSession] 幂等，停止广播、停止服务器、删除会话目录并把状态复位。
 */
class DefaultQuickSendManager(
    context: Context,
    private val localServerManager: LocalServerManager,
    private val sessionFileStore: SessionFileStore
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

    /** 本机导入的文件名（sendingFiles 始终只展示这些本机文件）。 */
    private var importedNames: Set<String> = emptySet()

    /** 已导出到系统下载目录的收到文件：文件名 -> 导出时的修改时间，避免重复出现在 receivedFiles。 */
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

        val importResult = sessionFileStore.importFiles(directory, uris)
        if (importResult.imported.isEmpty()) {
            val detail = if (importResult.failed.isEmpty()) "" else "（" + importResult.failed.joinToString("、") + "）"
            return failStart("没有成功导入任何文件" + detail)
        }
        if (importResult.failed.isNotEmpty()) {
            Log.w(TAG, "部分文件复制到会话目录失败：" + importResult.failed.joinToString("、"))
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

        val sendingFiles = sessionFileStore.listFiles(directory)
        importedNames = sendingFiles.mapTo(LinkedHashSet()) { it.name }
        baselineFiles = sendingFiles.associate { it.name to FileFingerprint(it.size, it.modifiedAt) }
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
            runCatching { localServerManager.stop() }
                .onFailure { Log.w(TAG, "停止快捷会话服务器失败", it) }
            sessionFileStore.clear(directory)
        }
        sessionDirectory = null
        importedNames = emptySet()
        baselineFiles = emptyMap()
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
        val files = sessionFileStore.listFiles(directory)
        val sendingFiles = files.filter { it.name in importedNames }
        val receivedFiles = files.filter { isReceivedFile(it) }
        if (current.sendingFiles == sendingFiles && current.receivedFiles == receivedFiles) return
        mutableState.value = current.copy(sendingFiles = sendingFiles, receivedFiles = receivedFiles)
    }

    /** 会话开始后新出现的文件、或内容被覆盖过的文件，视为对方上传的收到文件。 */
    private fun isReceivedFile(file: QuickFile): Boolean {
        val exportedAt = exportedReceivedAt[file.name]
        if (exportedAt != null) {
            if (exportedAt == file.modifiedAt) return false
            exportedReceivedAt.remove(file.name)
        }
        val baseline = baselineFiles[file.name] ?: return true
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
            val safeName = fileName.trim()
            if (safeName.isEmpty()) return@withContext exportFailure("文件名不能为空")
            val file = File(directory, safeName)
            if (!file.isFile) return@withContext exportFailure("会话中没有这个文件：" + safeName)
            val insideSession = try {
                file.canonicalFile.parentFile?.canonicalPath == directory.canonicalFile.canonicalPath
            } catch (e: IOException) {
                false
            }
            if (!insideSession) return@withContext exportFailure("非法的文件名：" + safeName)

            val result = sessionFileStore.exportToDownloads(file)
            result.onSuccess { displayName ->
                exportedReceivedAt[file.name] = file.lastModified()
                val current = mutableState.value
                mutableState.value = current.copy(
                    receivedFiles = current.receivedFiles.filterNot { it.name == file.name }
                )
                Log.i(TAG, "已把收到的文件导出到系统下载目录：" + displayName)
            }.onFailure { error ->
                Log.w(TAG, "导出收到的文件失败：" + file.name, error)
            }
            result.fold(
                onSuccess = { displayName -> Result.success(displayName) },
                onFailure = { error -> Result.failure(IllegalStateException(userMessage(error))) }
            )
        }
    }

    private fun exportFailure(message: String): Result<String> =
        Result.failure(IllegalStateException(message))

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
    }
}

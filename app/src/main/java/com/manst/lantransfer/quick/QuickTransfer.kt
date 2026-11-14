package com.manst.lantransfer.quick

import android.net.Uri
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.server.ServerEndpointState
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/** 快捷传输（两台设备同一 Wi-Fi 下直接互传文件）的全局常量。 */
object QuickTransferDefaults {
    /** 局域网发现 UDP 广播端口。 */
    const val BEACON_PORT = 47823

    /** 接收码长度（纯数字）。 */
    const val CODE_LENGTH = 6

    /** 会话可用的四种协议。 */
    val PROTOCOLS: List<TransferProtocol> = listOf(
        TransferProtocol.FTP,
        TransferProtocol.FTPS,
        TransferProtocol.SFTP,
        TransferProtocol.SCP
    )

    /** 自动选择协议时的优先级：加密优先（SFTP → SCP → FTPS → FTP）。 */
    val PROTOCOL_PREFERENCE: List<TransferProtocol> = listOf(
        TransferProtocol.SFTP,
        TransferProtocol.SCP,
        TransferProtocol.FTPS,
        TransferProtocol.FTP
    )

    const val DEFAULT_FTP_PORT = 2121
    const val DEFAULT_FTPS_PORT = 2122
    const val DEFAULT_SSH_PORT = 2222

    /** 单次快速传输最多可选文件数（防止误选过多）。 */
    const val MAX_FILES = 200
}

/** 接收码对应的临时登录凭据（发送端与接收端用同一算法派生，无需手动传帐号密码）。 */
data class QuickCredentials(
    val username: String,
    val password: String
)

/** 接收码工具：生成 / 规范化 / 派生凭据。 */
object QuickSessionCode {

    fun generate(random: Random = Random.Default): String {
        val value = random.nextInt(100_000, 1_000_000)
        return value.toString()
    }

    /** 规范化用户输入的接收码：去掉空格/横线，必须为 6 位数字，否则返回 null。 */
    fun normalize(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val digits = input.filter { it.isDigit() }
        return if (digits.length == QuickTransferDefaults.CODE_LENGTH) digits else null
    }

    /** 由接收码派生服务器临时帐号（发送端设置到本机服务器，接收端用同样值登录）。 */
    fun deriveCredentials(code: String): QuickCredentials {
        val normalized = normalize(code) ?: code.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("LanTransferQuick|" + normalized).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
        return QuickCredentials(
            username = "lt" + normalized,
            password = "lt-" + normalized + "-" + digest.substring(0, 16)
        )
    }
}

/** 发送端通过局域网广播出来的会话信息。 */
data class QuickSessionInfo(
    val code: String,
    val deviceName: String,
    val addresses: List<String>,
    val ftpPort: Int = QuickTransferDefaults.DEFAULT_FTP_PORT,
    val ftpsPort: Int = QuickTransferDefaults.DEFAULT_FTPS_PORT,
    val sshPort: Int = QuickTransferDefaults.DEFAULT_SSH_PORT,
    val protocols: List<TransferProtocol> = QuickTransferDefaults.PROTOCOLS,
    /** 本地收到的时间戳（毫秒），用于过期清理。 */
    val receivedAt: Long = System.currentTimeMillis()
) {
    val primaryHost: String get() = addresses.firstOrNull().orEmpty()

    fun portFor(protocol: TransferProtocol): Int = when (protocol) {
        TransferProtocol.FTP -> ftpPort
        TransferProtocol.FTPS -> ftpsPort
        TransferProtocol.SFTP, TransferProtocol.SCP -> sshPort
    }
}

/** 会话中的文件（发送端的待发送文件、或收到的对方文件）。 */
data class QuickFile(
    val name: String,
    val size: Long,
    val modifiedAt: Long
)

enum class QuickSendPhase { IDLE, PREPARING, WAITING, STOPPING }

data class QuickSendState(
    val phase: QuickSendPhase = QuickSendPhase.IDLE,
    val code: String = "",
    val deviceName: String = "",
    val addresses: List<String> = emptyList(),
    val endpoints: List<ServerEndpointState> = emptyList(),
    val protocols: List<TransferProtocol> = emptyList(),
    val sessionDirectory: String = "",
    /** 本机要发给对方的文件。 */
    val sendingFiles: List<QuickFile> = emptyList(),
    /** 对方上传到本机的文件（互传：对方也能发给本机）。 */
    val receivedFiles: List<QuickFile> = emptyList(),
    val error: String? = null
) {
    val isActive: Boolean get() = phase == QuickSendPhase.WAITING
    val sessionInfo: QuickSessionInfo?
        get() = if (phase == QuickSendPhase.WAITING && code.isNotBlank()) {
            QuickSessionInfo(
                code = code,
                deviceName = deviceName,
                addresses = addresses,
                ftpPort = endpoints.firstOrNull { it.protocol == TransferProtocol.FTP }?.port
                    ?: QuickTransferDefaults.DEFAULT_FTP_PORT,
                ftpsPort = endpoints.firstOrNull { it.protocol == TransferProtocol.FTPS }?.port
                    ?: QuickTransferDefaults.DEFAULT_FTPS_PORT,
                sshPort = endpoints.firstOrNull {
                    it.protocol == TransferProtocol.SFTP || it.protocol == TransferProtocol.SCP
                }?.port ?: QuickTransferDefaults.DEFAULT_SSH_PORT,
                protocols = protocols
            )
        } else {
            null
        }
}

/**
 * 发送端会話管理器：
 * 选择文件后自动把文件复制到会话目录、用随机接收码派生临时帐号启动本机服务器（四种协议全部开启）、
 * 并在局域网内广播会话信息；对方连接后可从本机下载文件，也可以上传文件到本机（互传）。
 */
interface QuickSendManager {
    val state: StateFlow<QuickSendState>

    /** 选择文件并开始一个发送会话；同一时间只允许一个会话。 */
    suspend fun startSession(uris: List<Uri>): Result<Unit>

    suspend fun stopSession()

    /** 重新扫描会话目录（对方上传的文件会出现在 receivedFiles 中）。 */
    suspend fun refresh()

    /** 把收到的对方文件保存到系统下载目录。 */
    suspend fun exportReceived(fileName: String): Result<String>
}

enum class QuickReceivePhase { IDLE, SCANNING, CONNECTING, CONNECTED, TRANSFERRING, FAILED }

/** 当前正在传输的文件进度。 */
data class QuickTransferProgress(
    val displayName: String,
    val direction: Direction,
    val bytesTransferred: Long,
    val totalBytes: Long,
    /** 当前瞬时速度（字节/秒，滑动窗口计算）；未知时为 0。 */
    val bytesPerSecond: Long = 0L,
    val fileIndex: Int = 0,
    val fileCount: Int = 0
) {
    enum class Direction { RECEIVE, SEND }
}

/** 接收端连接目标（协议为 null 时按 SFTP→SCP→FTPS→FTP 自动回退）。 */
data class QuickJoinTarget(
    val code: String,
    val host: String,
    val deviceName: String = "",
    val protocol: TransferProtocol? = null,
    val ftpPort: Int = QuickTransferDefaults.DEFAULT_FTP_PORT,
    val ftpsPort: Int = QuickTransferDefaults.DEFAULT_FTPS_PORT,
    val sshPort: Int = QuickTransferDefaults.DEFAULT_SSH_PORT
) {
    fun portFor(protocol: TransferProtocol): Int = when (protocol) {
        TransferProtocol.FTP -> ftpPort
        TransferProtocol.FTPS -> ftpsPort
        TransferProtocol.SFTP, TransferProtocol.SCP -> sshPort
    }
}

data class QuickReceiveState(
    val phase: QuickReceivePhase = QuickReceivePhase.IDLE,
    /** 局域网发现到的附近发送端。 */
    val discovered: List<QuickSessionInfo> = emptyList(),
    val joined: QuickSessionInfo? = null,
    val activeProtocol: TransferProtocol? = null,
    val remoteFiles: List<com.manst.lantransfer.core.RemoteEntry> = emptyList(),
    val transfer: QuickTransferProgress? = null,
    /** 已成功保存到下载目录的文件展示名。 */
    val completedFiles: List<String> = emptyList(),
    val failedFiles: List<String> = emptyList(),
    /** 已成功发送给对方的文件名。 */
    val sentFiles: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null
) {
    val isConnected: Boolean
        get() = phase == QuickReceivePhase.CONNECTED ||
            phase == QuickReceivePhase.TRANSFERRING ||
            (joined != null && phase != QuickReceivePhase.FAILED)
}

/**
 * 接收端会話管理器：
 * 监听局域网广播发现发送端（或手动输入 IP + 接收码），连接后列出对方文件并下载到系统下载目录；
 * 也可以把自己的文件上传给对方（互传）。
 */
interface QuickReceiveManager {
    val state: StateFlow<QuickReceiveState>

    /** 开始局域网发现（UDP 广播监听）。 */
    fun startDiscovery()

    fun stopDiscovery()

    /** 连接发送端并列出对方文件；成功后会自动开始下载全部文件。 */
    suspend fun join(target: QuickJoinTarget): Result<Unit>

    suspend fun download(remotePath: String): Result<Unit>

    suspend fun downloadAll(): Result<Unit>

    /** 互传：把自己的文件上传给对方（使用当前会话协议）。 */
    suspend fun sendFiles(uris: List<Uri>): Result<Unit>

    suspend fun leave()
}

/** 局域网广播发送器。 */
interface QuickBeaconBroadcaster {
    fun start(info: QuickSessionInfo)

    fun stop()
}

/** 局域网广播扫描器。 */
interface QuickBeaconScanner {
    fun start(onSession: (QuickSessionInfo) -> Unit)

    fun stop()
}

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
    /** 新的接收码长度（8 位数字；沿用 6 位是为了兼容旧版本扫码/输入）。 */
    const val CODE_LENGTH = 8

    /** 兼容旧版本的接收码长度（仍接受 6 位输入）。 */
    const val LEGACY_CODE_LENGTH = 6

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
        // 新会话使用 8 位接收码（1e8 空间，比 6 位多 100 倍）
        val value = random.nextLong(10_000_000L, 100_000_000L)
        return value.toString()
    }

    /** 规范化用户输入的接收码：去掉空格/横线，必须是 8 位（当前）或 6 位（兼容旧版本）数字。 */
    fun normalize(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val digits = input.filter { it.isDigit() }
        return if (digits.length == QuickTransferDefaults.CODE_LENGTH ||
            digits.length == QuickTransferDefaults.LEGACY_CODE_LENGTH
        ) {
            digits
        } else {
            null
        }
    }

    /**
     * 由接收码派生服务器临时帐号（发送端设置到本机服务器，接收端用同样值登录）。
     *
     * 说明：派生盐是公开常量（安装包内可见），因此**安全性来自接收码本身的熵**——
     * 当前 8 位接收码（1e8 空间）配合服务端认证失败限流（[com.manst.lantransfer.server.ServerAuthGuard]）
     * 已能有效抵御在线暴力猜测；离线穷举需要拿到服务器密码 hash，而临时帐号每次会话随机。
     */
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

/**
 * 发送端公开的会话信息。
 *
 * 局域网广播中**不再携带接收码**（防止同网段设备随意看到接收码）；
 * 接收方通过发送方屏幕输入、或扫描发送页二维码获取接收码后再连接。
 */
data class QuickSessionInfo(
    val code: String = "",
    val deviceName: String,
    val addresses: List<String>,
    val ftpPort: Int = QuickTransferDefaults.DEFAULT_FTP_PORT,
    val ftpsPort: Int = QuickTransferDefaults.DEFAULT_FTPS_PORT,
    val sshPort: Int = QuickTransferDefaults.DEFAULT_SSH_PORT,
    val protocols: List<TransferProtocol> = QuickTransferDefaults.PROTOCOLS,
    /**
     * FTPS 服务端自签名证书的 SHA-256 指纹（十六进制）。
     * 接收方据此固定证书，避免「信任任意证书」被中间人利用；旧版本发送方为 null。
     */
    val ftpsFingerprintSha256: String? = null,
    /** SSH（SFTP/SCP）服务端主机密钥的 SHA-256 指纹；旧版本发送方为 null。 */
    val sshHostKeySha256: String? = null,
    /** 本地收到的时间戳（毫秒），用于过期清理。 */
    val receivedAt: Long = System.currentTimeMillis()
) {
    /** 该会话信息是否附带接收码（广播为 false；二维码/发送端本地状态为 true）。 */
    val hasCode: Boolean get() = QuickSessionCode.normalize(code) != null

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
    val modifiedAt: Long,
    /** 相对会话目录的路径（文件夹传输时用于保留子目录结构；普通文件为 null）。 */
    val relativePath: String? = null
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
    /** SSH 服务端主机密钥指纹（启动后探测得到，用于接收方固定主机密钥）。 */
    val sshHostKeySha256: String? = null,
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
                protocols = protocols,
                // 仅当本机 FTPS 端点启用时提供证书指纹，供接收方固定证书
                ftpsFingerprintSha256 = if (endpoints.any { it.protocol == TransferProtocol.FTPS }) {
                    runCatching { com.manst.lantransfer.server.ftp.FtpServerTls.certificateSha256Hex() }.getOrNull()
                } else {
                    null
                },
                sshHostKeySha256 = sshHostKeySha256
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
    val sshPort: Int = QuickTransferDefaults.DEFAULT_SSH_PORT,
    /** 发送方 FTPS 证书指纹（可选）：非空时接收方严格固定该证书。 */
    val ftpsFingerprintSha256: String? = null,
    /** 发送方 SSH 主机密钥指纹（可选）：非空时接收方严格固定 SFTP/SCP 主机密钥。 */
    val sshHostKeySha256: String? = null
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
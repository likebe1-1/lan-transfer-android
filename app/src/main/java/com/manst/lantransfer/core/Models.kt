package com.manst.lantransfer.core

import java.util.Locale
import java.util.UUID

/** 支持的传输协议。 */
enum class TransferProtocol(val label: String, val defaultPort: Int) {
    FTP("FTP", 21),
    FTPS("FTPS", 21),
    SFTP("SFTP", 22),
    SCP("SCP", 22);

    val isSsh: Boolean get() = this == SFTP || this == SCP
    val isFtp: Boolean get() = this == FTP || this == FTPS
}

/** FTPS 的 TLS 模式。 */
enum class FtpsMode(val label: String) {
    EXPLICIT("显式 TLS（AUTH TLS）"),
    IMPLICIT("隐式 TLS（端口 990）")
}

/** 一台可连接的服务器配置。 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: TransferProtocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val initialPath: String = "/",
    val ftpsMode: FtpsMode = FtpsMode.EXPLICIT,
    val passiveMode: Boolean = true,
    /**
     * FTPS 是否信任任意服务端证书（自签证书场景）。
     * 出于安全考虑默认关闭（严格校验 + 主机名校验）；仅当用户显式选择时才放宽。
     */
    val allowInsecureTls: Boolean = false,
    val lastUsedAt: Long = 0L
) {
    val endpoint: String get() = "$host:$port"
}

/** 远端目录/文件条目。 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
    val permissions: String = ""
) {
    val isFile: Boolean get() = !isDirectory
}

/** 远端条目排序：目录优先，然后按名称不区分大小写。 */
val RemoteEntryOrder: Comparator<RemoteEntry> = Comparator { a, b ->
    when {
        a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
        else -> a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
    }
}

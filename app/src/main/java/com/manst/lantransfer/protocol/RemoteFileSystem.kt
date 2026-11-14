package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 统一远程文件系统抽象。四种协议（FTP / FTPS / SFTP / SCP）都实现该接口，
 * 上层 UI、传输队列不感知协议差异。
 *
 * 约定：
 * - 所有路径使用 "/" 分隔的绝对路径；
 * - 每个实例代表一次独立连接，用完必须 [close]；
 * - 阻塞实现必须在 [kotlinx.coroutines.Dispatchers.IO] 上调用（实现内部可自行切换）；
 * - 取消传输的方式是关闭连接，实现应把由此产生的 IO 异常转换为
 *   [com.manst.lantransfer.core.TransferCancelledException]；
 * - 实现类必须保持纯 JVM（不依赖 Android API），以便 JVM 单元测试。
 */
interface RemoteFileSystem : Closeable {
    val profile: ServerProfile

    /** 建立连接并完成认证。 */
    suspend fun connect()

    /** 列出目录内容（不含 "." 和 ".."）。 */
    suspend fun list(path: String): List<RemoteEntry>

    /** 下载远端文件到本地输出流，onProgress 参数为“已累计传输字节数”。 */
    suspend fun download(remotePath: String, sink: OutputStream, onProgress: (Long) -> Unit = {})

    /** 上传本地输入流到远端路径，sizeHint 用于进度总量（未知时传 -1）。 */
    suspend fun upload(source: InputStream, remotePath: String, sizeHint: Long = -1L, onProgress: (Long) -> Unit = {})

    /** 创建远端目录（已存在时应视为成功）。 */
    suspend fun mkdir(path: String)

    /** 删除远端文件或空目录。 */
    suspend fun delete(path: String, isDirectory: Boolean)

    /** 重命名/移动远端文件或目录。 */
    suspend fun rename(fromPath: String, toPath: String)

    /** 协议是否支持断点续传（FTP/FTPS/SFTP 支持；SCP 不支持，需从 0 重传）。 */
    val supportsResume: Boolean
        get() = false

    /**
     * 断点续传下载：startOffset 为本地已下载字节数（>0 时从该偏移继续），
     * 实现必须把 startOffset 计入 onProgress 的累计字节数。
     * 默认实现忽略偏移（从 0 开始），调用方应仅在 [supportsResume] 为 true 时使用。
     */
    suspend fun downloadPartial(
        remotePath: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit = {}
    ) {
        download(remotePath, sink, onProgress)
    }

    /**
     * 断点续传上传：startOffset 为远端已存在的字节数，实现需校验远端文件大小后追加剩余内容。
     * 默认实现忽略偏移（整体重传）。
     */
    suspend fun uploadPartial(
        source: InputStream,
        remotePath: String,
        sizeHint: Long = -1L,
        startOffset: Long = 0L,
        onProgress: (Long) -> Unit = {}
    ) {
        upload(source, remotePath, sizeHint, onProgress)
    }

    /** 远端文件大小（字节）；不存在或协议不支持返回 -1。 */
    suspend fun size(path: String): Long = -1L

    /** 递归创建目录（默认逐级调用 [mkdir]，已存在的层级忽略错误）。 */
    suspend fun mkdirs(path: String) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (normalized == "/") return
        var current = "/"
        for (segment in normalized.trim('/').split('/')) {
            if (segment.isBlank()) continue
            current = if (current == "/") "/$segment" else "$current/$segment"
            runCatching { mkdir(current) }
        }
    }

    override fun close()
}

/** 按配置创建对应的协议实现。 */
object RemoteFileSystems {
    fun create(profile: ServerProfile): RemoteFileSystem = when (profile.protocol) {
        TransferProtocol.FTP, TransferProtocol.FTPS -> FtpRemoteFileSystem(profile)
        TransferProtocol.SFTP, TransferProtocol.SCP -> SshRemoteFileSystem(profile)
    }
}

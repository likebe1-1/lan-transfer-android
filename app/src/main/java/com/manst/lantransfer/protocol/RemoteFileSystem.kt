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

    override fun close()
}

/** 按配置创建对应的协议实现。 */
object RemoteFileSystems {
    fun create(profile: ServerProfile): RemoteFileSystem = when (profile.protocol) {
        TransferProtocol.FTP, TransferProtocol.FTPS -> FtpRemoteFileSystem(profile)
        TransferProtocol.SFTP, TransferProtocol.SCP -> SshRemoteFileSystem(profile)
    }
}

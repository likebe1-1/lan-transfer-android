package com.manst.lantransfer.transfer

import android.net.Uri
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.ServerProfile
import kotlinx.coroutines.flow.StateFlow

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

/** 一条传输任务的可观察快照。 */
data class TransferTask(
    val id: String,
    val profileId: String,
    val serverName: String,
    val protocolLabel: String,
    val direction: TransferDirection,
    val displayName: String,
    val remotePath: String,
    val sourceUri: String? = null,
    val localDisplayName: String? = null,
    val totalBytes: Long = -1L,
    val transferredBytes: Long = 0L,
    val state: TransferState = TransferState.QUEUED,
    val error: String? = null,
    val bytesPerSecond: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
) {
    val progress: Float
        get() = if (totalBytes > 0L) (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val isActive: Boolean
        get() = state == TransferState.QUEUED || state == TransferState.RUNNING

    val isFinished: Boolean
        get() = state == TransferState.COMPLETED || state == TransferState.FAILED || state == TransferState.CANCELLED
}

/**
 * 传输队列。任务串行执行（局域网带宽下并发收益低、且 FTP 控制连接有并发限制）。
 *
 * 实现要求：
 * - 每个任务使用独立的 [com.manst.lantransfer.protocol.RemoteFileSystem] 连接，任务结束即关闭；
 * - 下载写入 MediaStore 公共目录 Download/LanTransfer/（Android 10+ 无需存储权限）；
 * - 上传通过 SAF content Uri 读取本地文件；
 * - 有进行中任务时启动前台服务保活，队列清空后停止。
 */
interface TransferManager {
    val tasks: StateFlow<List<TransferTask>>

    val activeCount: StateFlow<Int>

    /** 下载远端文件；返回任务 id。 */
    fun enqueueDownload(profile: ServerProfile, entry: RemoteEntry): String

    /** 上传本地文件到远端目录；返回任务 id。 */
    fun enqueueUpload(
        profile: ServerProfile,
        sourceUri: Uri,
        displayName: String,
        size: Long,
        remoteDir: String
    ): String

    fun cancel(taskId: String)

    /** 重新入队一个失败/取消的任务。 */
    fun retry(taskId: String)

    fun remove(taskId: String)

    fun clearFinished()
}

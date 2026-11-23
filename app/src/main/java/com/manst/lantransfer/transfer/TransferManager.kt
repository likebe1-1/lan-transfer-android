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
    /** 下载完成后本地 MediaStore Uri（通知点击打开文件用）。 */
    val localUri: String? = null,
    /** 下载目标在「下载」目录下的相对子目录（文件夹递归下载时使用，如 "相册/2024"）。 */
    val localRelativeDir: String? = null,
    /** 自动重试次数（用于 UI 展示与重试策略）。 */
    val attempts: Int = 0,
    /** 是否支持从已传字节继续（协议能力 + 本地条件）。 */
    val resumable: Boolean = true,
    /** 入队时的服务器快照（用于进程重启后恢复队列，不展示在 UI 上）。 */
    val profileSnapshot: ServerProfile? = null,
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
 * 传输队列。任务有限并发执行（默认 5 路，可在「传输设置」中调整 1–6，见 [TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS]）：
 * 大量小文件时减少「连接/认证/断开」的固定开销；每个任务使用独立连接，
 * 如遇服务端连接数限制可把并发上限调为 1 退回串行。
 *
 * 实现要求：
 * - 每个任务使用独立的 [com.manst.lantransfer.protocol.RemoteFileSystem] 连接，任务结束即关闭；
 * - 下载直接写入系统「下载」目录 Download/（不新建子文件夹，Android 10+ 无需存储权限）；
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

    /**
     * 递归下载远端目录到系统「下载」目录（保留子目录结构），
     * 文件任务按现有并发调度执行；返回批次 id（仅用于 UI 提示）。
     */
    fun enqueueDownloadFolder(
        profile: ServerProfile,
        remoteDir: String,
        folderDisplayName: String = remoteDir.substringAfterLast('/').ifBlank { "文件夹" }
    ): String = throw UnsupportedOperationException("当前版本不支持文件夹下载")

    /**
     * 递归上传 SAF 目录树到远端目录（保留目录结构），逐文件加入队列；返回批次 id。
     */
    fun enqueueUploadFolder(profile: ServerProfile, treeUri: Uri, remoteDir: String): String =
        throw UnsupportedOperationException("当前版本不支持文件夹上传")

    /** 清空历史记录（已完成/失败/取消的任务）。 */
    fun clearHistory() = clearFinished()
}
package com.manst.lantransfer.quick

import java.io.OutputStream

/** 一次下载写入目标：先写 pending 记录，成功后 commit 使其对用户可见。 */
interface QuickDownloadTarget {
    val displayName: String

    val outputStream: OutputStream

    /** 写入完成后调用；返回最终在系统下载目录中的展示名。 */
    fun commit(): Result<String>

    /** 失败/取消时调用，清理未完成的下载记录。 */
    fun abort()
}

/** 接收端下载落盘目标（MediaStore.Downloads：Download/LanTransfer/）。 */
interface QuickDownloadSink {
    fun open(displayName: String, mimeType: String? = null): QuickDownloadTarget
}

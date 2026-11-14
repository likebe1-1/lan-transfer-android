package com.manst.lantransfer.quick

import android.net.Uri
import java.io.File

data class SessionImportResult(
    val imported: List<QuickFile> = emptyList(),
    val failed: List<String> = emptyList()
)

/**
 * 快捷传输会话文件仓库：负责把用户选择的 SAF 文件复制进会话目录、
 * 列出会话目录内容、把收到的文件导出到系统下载目录、结束会话时清理。
 */
interface SessionFileStore {
    /** 会话目录（externalFilesDir("quick-send")/<code>），不存在时创建。 */
    fun directoryFor(code: String): File

    /** 复制 SAF 选择的文件到会话目录（文件名清洗、重名自动追加序号）；单个失败不影响其他。 */
    suspend fun importFiles(directory: File, uris: List<Uri>): SessionImportResult

    fun listFiles(directory: File): List<QuickFile>

    /** 把会话目录中的文件导出到系统下载目录（Download/LanTransfer/），返回展示名。 */
    suspend fun exportToDownloads(file: File): Result<String>

    fun clear(directory: File)
}

package com.manst.lantransfer.data

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 共享目录中的一个文件（本机服务器对局域网暴露的内容）。 */
data class ShareFile(
    val name: String,
    val size: Long,
    val modifiedAt: Long
)

data class ShareImportResult(
    val imported: List<String> = emptyList(),
    val failed: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = failed.isEmpty() && imported.isNotEmpty()
}

/**
 * 共享目录仓库：本机服务器对外暴露的根目录。
 *
 * - 目录固定位于应用私有外部目录（getExternalFilesDir）下的 share 子目录；
 * - [importFromUris] 把用户通过 SAF 选择的文件复制进共享目录（用于「发文件」给其他设备）；
 * - 其他设备上传到本机服务器的文件也会出现在 [files] 中（收到文件）；
 * - [exportToDownloads] 把共享目录里的文件导出到系统下载目录，方便本机查看。
 */
interface ShareRepository {
    val files: StateFlow<List<ShareFile>>

    val directoryPath: String

    fun directory(): File

    suspend fun refresh()

    suspend fun importFromUris(uris: List<Uri>): ShareImportResult

    suspend fun delete(name: String): Boolean

    suspend fun exportToDownloads(name: String): Result<String>
}

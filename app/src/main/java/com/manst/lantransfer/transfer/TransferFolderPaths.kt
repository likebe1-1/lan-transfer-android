package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.RemotePath

/**
 * 文件夹递归传输的路径处理（纯 JVM 逻辑，可单元测试）。
 *
 * 所有来自远端或 SAF 的相对路径都会逐段清洗（非法字符替换、去掉 "."/".."），
 * 防止目录穿越；本地目标目录统一放在系统「下载」目录下的相对子目录中。
 */
internal object TransferFolderPaths {

    /** 单次文件夹传输最多包含的文件数。 */
    const val MAX_FOLDER_FILES = 1000

    /** 清洗相对路径（保留子目录层级）；没有任何有效层级时返回 null。 */
    fun sanitizeRelativePath(raw: String?): String? = sanitizeSegments(raw)

    /** 清洗本地下载子目录（相对 Download/）；空路径返回 null。 */
    fun sanitizeRelativeDir(raw: String?): String? = sanitizeSegments(raw)

    /**
     * 下载文件夹时计算某个远端文件在本地「下载」目录下的相对子目录：
     * 例如 folderDisplayName="相册"、远端相对路径 "2024/01/a.jpg" -> "相册/2024/01"；
     * 远端根目录下的文件 -> "相册"。
     */
    fun downloadLocalRelativeDir(folderDisplayName: String?, remoteRelativeFilePath: String): String {
        val folder = TransferFileNames.sanitize(folderDisplayName)
        val safeFile = sanitizeSegments(remoteRelativeFilePath) ?: return folder
        val parent = safeFile.substringBeforeLast('/', "")
        return if (parent.isEmpty()) folder else folder + "/" + parent
    }

    /** 递归上传时把 SAF 相对路径拼到远端目录下，保留子目录结构。 */
    fun remotePathForUpload(remoteDir: String, treeRelativePath: String): String {
        val relative = sanitizeSegments(treeRelativePath) ?: TransferFileNames.FALLBACK_NAME
        return RemotePath.join(remoteDir, relative)
    }

    private fun sanitizeSegments(raw: String?): String? {
        val parts = raw
            ?.replace('\\', '/')
            ?.split('/')
            .orEmpty()
        val safe = parts
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { TransferFileNames.sanitize(it) }
        return if (safe.isEmpty()) null else safe.joinToString("/")
    }
}

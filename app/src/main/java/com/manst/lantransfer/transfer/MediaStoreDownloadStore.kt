package com.manst.lantransfer.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * 下载落盘：写入 MediaStore.Downloads（默认 Download/，文件夹下载支持 Download/<子目录>/）。
 *
 * Android 10+ 通过 MediaStore 写入公共目录无需存储权限。写入过程遵循 IS_PENDING 约定：
 * 先插入 pending 记录 -> 写入字节 -> 提交（IS_PENDING=0）；失败时删除 pending 记录，避免留下空文件。
 * 文件名在「目标子目录」内查重（MediaStore 现有文件 + 进程内预留），重名追加 " (1)"、" (2)"……
 */
internal class MediaStoreDownloadStore(private val context: Context) {

    private val resolver = context.contentResolver
    private val lock = Any()

    /** 进程内已预留的 "相对目录|小写文件名"。 */
    private val reservedNames = HashSet<String>()

    /** 预留一个不重名的文件名（比较 MediaStore 目标子目录已有文件与本次进程中已分配的名字）。 */
    fun reserveFileName(rawName: String?, relativeDir: String? = null): String = synchronized(lock) {
        val dir = TransferFolderPaths.sanitizeRelativeDir(relativeDir)
        val keyPrefix = reservationKey(dir, "")
        val existing = HashSet<String>()
        reservedNames
            .filter { it.startsWith(keyPrefix) }
            .mapTo(existing) { it.substring(keyPrefix.length) }
        existing.addAll(queryExistingNames(relativePathFor(dir)))
        val unique = TransferFileNames.uniqueName(rawName, existing)
        reservedNames.add(reservationKey(dir, unique.lowercase(Locale.ROOT)))
        unique
    }

    /** 释放文件名的进程内预留（任务结束后调用；成功写入的文件由 MediaStore 查询负责查重）。 */
    fun releaseFileName(displayName: String, relativeDir: String? = null) {
        synchronized(lock) {
            val dir = TransferFolderPaths.sanitizeRelativeDir(relativeDir)
            reservedNames.remove(reservationKey(dir, displayName.lowercase(Locale.ROOT)))
        }
    }

    /** 插入 pending 记录并返回目标 Uri；失败抛出 [IOException]。 */
    fun createPendingFile(displayName: String, relativeDir: String? = null): Uri {
        val dir = TransferFolderPaths.sanitizeRelativeDir(relativeDir)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, guessMimeType(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePathFor(dir))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection(), values)
        } catch (e: Exception) {
            throw IOException("无法在下载目录创建文件：" + displayName, e)
        } ?: throw IOException("无法在下载目录创建文件：" + displayName)
        return uri
    }

    /** 打开待写入的输出流。 */
    fun openSink(uri: Uri): OutputStream {
        return try {
            resolver.openOutputStream(uri)?.buffered()
                ?: throw IOException("无法打开下载文件写入流")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("无法打开下载文件写入流", e)
        }
    }

    /** 提交 pending 记录，使文件对用户可见。 */
    fun commit(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        val rows = try {
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            throw IOException("保存下载文件失败", e)
        }
        if (rows <= 0) throw IOException("保存下载文件失败：媒体库记录不存在")
    }

    /** 提交 pending 记录并返回最终 Uri（通知点击打开文件用）。 */
    fun commitAndGetUri(uri: Uri): Uri {
        commit(uri)
        return uri
    }

    /** 删除未完成的 pending 记录（失败清理，尽力而为）。 */
    fun discard(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "清理未完成的下载文件失败", e)
        }
    }

    private fun reservationKey(relativeDir: String?, lowercaseName: String): String =
        (relativeDir ?: "").lowercase(Locale.ROOT) + "|" + lowercaseName

    private fun relativePathFor(relativeDir: String?): String =
        if (relativeDir.isNullOrBlank()) RELATIVE_PATH else RELATIVE_PATH + relativeDir + "/"

    private fun queryExistingNames(relativePath: String): List<String> {
        val names = ArrayList<String>()
        try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(relativePath),
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(index)) names.add(cursor.getString(index))
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "查询下载目录已有文件失败，退化为仅按进程内重名处理", e)
        }
        return names
    }

    private fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun guessMimeType(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return DEFAULT_MIME_TYPE
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
    }

    companion object {
        /** 系统「下载」根目录（末尾带斜杠，MediaStore 存储值格式）。 */
        val RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val TAG = "MediaStoreDownloadStore"
    }
}

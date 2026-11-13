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
 * 下载落盘：写入 MediaStore.Downloads 的公共目录 Download/LanTransfer/。
 *
 * Android 10+ 通过 MediaStore 写入公共目录无需存储权限。写入过程遵循 IS_PENDING 约定：
 * 先插入 pending 记录 -> 写入字节 -> 提交（IS_PENDING=0）；失败时删除 pending 记录，避免留下空文件。
 * 文件名在进程内与 MediaStore 现有文件中查重，重名追加 " (1)"、" (2)"……
 */
internal class MediaStoreDownloadStore(private val context: Context) {

    private val resolver = context.contentResolver
    private val lock = Any()
    private val reservedNames = HashSet<String>()

    /** 预留一个不重名的文件名（比较 MediaStore 已有文件与本次进程中已分配的名字）。 */
    fun reserveFileName(rawName: String?): String = synchronized(lock) {
        val existing = HashSet<String>()
        existing.addAll(reservedNames)
        existing.addAll(queryExistingNames())
        val unique = TransferFileNames.uniqueName(rawName, existing)
        reservedNames.add(unique.lowercase(Locale.ROOT))
        unique
    }

    /** 释放文件名的进程内预留（任务结束后调用；成功写入的文件由 MediaStore 查询负责查重）。 */
    fun releaseFileName(displayName: String) {
        synchronized(lock) {
            reservedNames.remove(displayName.lowercase(Locale.ROOT))
        }
    }

    /** 插入 pending 记录并返回目标 Uri；失败抛出 [IOException]。 */
    fun createPendingFile(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, guessMimeType(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
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

    /** 删除未完成的 pending 记录（失败清理，尽力而为）。 */
    fun discard(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "清理未完成的下载文件失败", e)
        }
    }

    private fun queryExistingNames(): List<String> {
        val names = ArrayList<String>()
        try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(RELATIVE_PATH),
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
        /** 下载子目录：Download/LanTransfer/。 */
        val RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransfer/"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val TAG = "MediaStoreDownloadStore"
    }
}

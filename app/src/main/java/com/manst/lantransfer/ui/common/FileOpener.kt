package com.manst.lantransfer.ui.common

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * 打开已保存到系统「下载」目录的文件。
 *
 * 传输队列历史与快捷接收的已完成文件都通过它打开：
 * 优先使用任务记录中的 content Uri，没有记录时按展示名在 MediaStore 中查询同名记录。
 */
object FileOpener {

    private const val TAG = "FileOpener"

    /** 通过 MediaStore 下载记录查询同名文件 Uri；查不到返回 null。 */
    fun findDownloadedFileUri(context: Context, displayName: String): Uri? {
        if (displayName.isBlank()) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + " = ?",
                arrayOf(displayName),
                MediaStore.Downloads.DATE_ADDED + " DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "查询下载目录文件失败：" + displayName, e)
            null
        }
    }

    /** 打开系统「下载」目录中的同名文件；成功返回 true。 */
    fun openDownloadedFile(context: Context, displayName: String): Boolean {
        val uri = findDownloadedFileUri(context, displayName) ?: return false
        return openUri(context, uri, guessMimeType(displayName))
    }

    /** 打开传输任务对应的本地文件（优先任务记录的 Uri，回退按展示名查下载目录）。 */
    fun openTransferFile(context: Context, localUri: String?, displayName: String?): Boolean {
        if (!localUri.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(localUri) }.getOrNull()
            if (uri != null && openUri(context, uri, displayName?.let { guessMimeType(it) })) {
                return true
            }
        }
        if (!displayName.isNullOrBlank()) {
            return openDownloadedFile(context, displayName)
        }
        return false
    }

    /** 用 ACTION_VIEW 打开 content Uri；没有可用应用或缺少权限时返回 false。 */
    fun openUri(context: Context, uri: Uri, mimeType: String? = null): Boolean {
        val type = mimeType
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有可打开该文件的应用：" + uri, e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "打开文件缺少权限：" + uri, e)
            false
        }
    }

    fun guessMimeType(displayName: String): String? {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}

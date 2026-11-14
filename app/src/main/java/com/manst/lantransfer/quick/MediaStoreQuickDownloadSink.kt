package com.manst.lantransfer.quick

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.data.ShareFileNames
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * 接收端下载落盘目标默认实现：MediaStore.Downloads（Download/）。
 *
 * 生命周期：
 * 1. [open] 插入 IS_PENDING=1 的媒体库记录并预留不重名的展示名；
 * 2. 调用方把远端数据写入 [QuickDownloadTarget.outputStream]；
 * 3. [QuickDownloadTarget.commit] 关闭流、置 IS_PENDING=0，使文件对用户可见并返回展示名；
 *    失败或取消时调用 [QuickDownloadTarget.abort] 删除 pending 记录。
 *
 * 所有方法都是阻塞 IO，调用方应在 Dispatchers.IO 上调用。
 */
class MediaStoreQuickDownloadSink(private val context: Context) : QuickDownloadSink {

    private val appContext: Context = context.applicationContext
    private val resolver = appContext.contentResolver

    private val nameLock = Any()

    /** 进程内已预留的展示名（小写），与 MediaStore 现有行一起参与查重。 */
    private val reservedNames = HashSet<String>()

    override fun open(displayName: String, mimeType: String?): QuickDownloadTarget {
        val uniqueName = reserveName(displayName)
        var pendingUri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, uniqueName)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    mimeType?.takeIf { it.isNotBlank() } ?: guessMimeType(uniqueName)
                )
                put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(downloadsCollection(), values)
                ?: throw IOException("无法在系统下载目录创建文件")
            pendingUri = uri
            val rawStream = resolver.openOutputStream(uri)
                ?: throw IOException("无法写入系统下载目录")
            return PendingDownloadTarget(uniqueName, uri, rawStream) { releaseName(uniqueName) }
        } catch (e: Exception) {
            pendingUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            releaseName(uniqueName)
            Log.w(TAG, "创建下载文件失败：" + displayName, e)
            throw LanTransferException("创建下载文件失败：" + (e.message ?: "未知错误"), e)
        }
    }

    // ------------------------------------------------------------ 待提交的下载目标

    private inner class PendingDownloadTarget(
        override val displayName: String,
        private val uri: Uri,
        rawStream: OutputStream,
        private val onFinished: () -> Unit
    ) : QuickDownloadTarget {

        private var streamClosed = false
        private var committed = false
        private var aborted = false

        /** 包装底层流以感知调用方是否已关闭，并避免 FilterOutputStream 逐字节写。 */
        private val trackingStream = object : FilterOutputStream(rawStream) {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                out.write(buffer, offset, length)
            }

            override fun close() {
                if (streamClosed) return
                try {
                    out.flush()
                } finally {
                    out.close()
                    streamClosed = true
                }
            }
        }

        override val outputStream: OutputStream = trackingStream

        override fun commit(): Result<String> {
            if (committed) return Result.success(displayName)
            if (aborted) return Result.failure(LanTransferException("下载已取消"))
            return try {
                if (!streamClosed) trackingStream.close()
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                val rows = resolver.update(uri, values, null, null)
                if (rows <= 0) throw IOException("下载文件记录不存在")
                committed = true
                onFinished()
                Result.success(displayName)
            } catch (e: Exception) {
                Log.w(TAG, "保存下载文件失败：" + displayName, e)
                abortInternal()
                Result.failure(
                    LanTransferException("保存下载文件失败：" + (e.message ?: "未知错误"), e)
                )
            }
        }

        override fun abort() {
            if (committed || aborted) return
            abortInternal()
        }

        private fun abortInternal() {
            aborted = true
            if (!streamClosed) {
                try {
                    trackingStream.close()
                } catch (e: IOException) {
                    Log.w(TAG, "关闭未完成的下载流失败", e)
                }
            }
            try {
                resolver.delete(uri, null, null)
            } catch (e: RuntimeException) {
                Log.w(TAG, "清理未完成的下载记录失败", e)
            }
            onFinished()
        }
    }

    // ------------------------------------------------------------ MediaStore 辅助

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun reserveName(rawName: String): String = synchronized(nameLock) {
        val existing = HashSet<String>()
        existing.addAll(reservedNames)
        existing.addAll(queryDownloadDirectoryNames())
        val unique = ShareFileNames.uniqueName(rawName, existing)
        reservedNames.add(unique.lowercase(Locale.ROOT))
        unique
    }

    private fun releaseName(displayName: String) {
        synchronized(nameLock) {
            reservedNames.remove(displayName.lowercase(Locale.ROOT))
        }
    }

    private fun queryDownloadDirectoryNames(): List<String> {
        val names = ArrayList<String>()
        try {
            resolver.query(
                downloadsCollection(),
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(DOWNLOADS_RELATIVE_PATH),
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(index)) names.add(cursor.getString(index))
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "查询系统下载目录已有文件失败，仅按进程内重名处理", e)
        }
        return names
    }

    private fun guessMimeType(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return DEFAULT_MIME_TYPE
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
    }

    private companion object {
        const val TAG = "MediaStoreQuickDownloadSink"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val DOWNLOADS_RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/"
    }
}
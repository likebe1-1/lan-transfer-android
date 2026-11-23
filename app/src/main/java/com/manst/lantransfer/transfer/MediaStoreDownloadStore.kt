package com.manst.lantransfer.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
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
 *
 * 断点续传支持：传输层把远端字节流直接写进 pending 文件（[openPendingSink]），
 * 失败后保留 pending 记录，下一次尝试从「真实已写偏移」续写，
 * 无需 cacheDir 暂存文件，也就没有「先写暂存再复制到下载目录」的二次写盘。
 *
 * 预分配：新下载在写第一个字节前用 [Os.ftruncate] 按远端大小预分配（[preallocate]），
 * 减少大文件写入期反复扩展文件造成的碎片与元数据更新。预分配后文件大小立即等于远端大小，
 * 因此「已下载多少字节」必须由传输层单独统计，**绝不能把文件大小当成进度**；
 * [openPendingSink] 也只校验续写偏移没有越过文件末尾（允许文件因预分配而更大）。
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

    /**
     * 下载记录当前在磁盘上的字节数（pending 或已提交均可）；记录不存在或不可读时返回 null。
     *
     * 注意：下载前预分配（[preallocate]）过的 pending 文件，该值从一开始就等于远端大小，
     * 并不代表已经写入的字节数；真实进度由传输层统计（见 [openPendingSink] 与
     * [com.manst.lantransfer.transfer.DownloadPreallocationPolicy] 的说明）。
     * 已提交的文件（IS_PENDING=0）不存在预分配问题，该值即完整文件大小。
     */
    fun localFileSize(uri: Uri): Long? {
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0L }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取下载文件大小失败：" + uri, e)
            null
        }
    }

    /** 该记录是否仍处于 IS_PENDING=1（已插入但尚未提交、对用户不可见）状态。 */
    fun isPending(uri: Uri): Boolean {
        return try {
            resolver.query(
                uri,
                arrayOf(MediaStore.Downloads.IS_PENDING),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst() && !cursor.isNull(0) && cursor.getInt(0) != 0
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "查询下载文件 pending 状态失败：" + uri, e)
            false
        }
    }

    /**
     * 以 [offset] 为起点打开 pending 文件的写入流（下载主体直接写它，避免「暂存 + 复制」二次写盘）。
     *
     * - offset == 0：先截断文件，避免重新下载时残留旧内容（调用方随后可再 [preallocate] 预分配）；
     * - offset > 0：定位到该偏移续写；只校验 offset 没有越过文件末尾（磁盘大小 < offset 时抛
     *   [IOException]），允许文件比 offset 大——预分配过的文件磁盘大小始终等于远端大小，
     *   而 offset 是传输层统计的「真实已写偏移」，二者不相等是正常情况；从该偏移重写尚未确认的
     *   少量尾部字节是幂等的，不会留下空洞。
     */
    fun openPendingSink(uri: Uri, offset: Long): OutputStream {
        if (offset < 0L) throw IOException("续传偏移不能为负数：" + offset)
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            throw IOException("无法打开下载文件写入流", e)
        } ?: throw IOException("无法打开下载文件写入流")
        try {
            val fileDescriptor = descriptor.fileDescriptor
            if (offset == 0L) {
                Os.ftruncate(fileDescriptor, 0L)
            } else {
                val actual = descriptor.statSize
                if (actual < offset) {
                    throw IOException("下载文件续传位置越界：磁盘 " + actual + " 字节，续写偏移 " + offset)
                }
                Os.lseek(fileDescriptor, offset, OsConstants.SEEK_SET)
            }
            return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            if (e is IOException) throw e
            throw IOException("无法定位下载文件写入位置", e)
        }
    }

    /**
     * 按 [size] 字节预分配下载文件（[Os.ftruncate]），大文件写入前一次性占好空间。
     *
     * 预分配后文件在磁盘上的大小立即等于 [size]，**此时文件大小不再等于已下载字节数**：
     * 传输层用自己统计的「连续已写」进度作为续传偏移（见 [openPendingSink]）。
     * 设备/自定义 MediaProvider 不支持时（无法打开 fd、ftruncate 失败等）安全忽略并返回 false，
     * 下载照常进行，只是失去预分配带来的空间预留优化。
     */
    fun preallocate(uri: Uri, size: Long): Boolean {
        if (size <= 0L) return false
        return resize(uri, size, "预分配")
    }

    /**
     * 把下载文件截断/扩展到 [size] 字节（写出字节数少于预分配大小时裁掉尾部 0 填充）。
     * 失败时安全忽略并返回 false：文件保持原样，下载仍可正常提交。
     */
    fun truncateTo(uri: Uri, size: Long): Boolean {
        if (size < 0L) return false
        return resize(uri, size, "调整大小")
    }

    private fun resize(uri: Uri, size: Long, action: String): Boolean {
        return try {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                Os.ftruncate(descriptor.fileDescriptor, size)
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "下载文件" + action + "失败（已忽略，继续下载）：" + uri, e)
            false
        }
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

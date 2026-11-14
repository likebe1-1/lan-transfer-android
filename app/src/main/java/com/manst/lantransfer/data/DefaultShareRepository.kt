package com.manst.lantransfer.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.manst.lantransfer.core.LanTransferException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 共享目录默认实现。
 *
 * 共享目录固定为应用外部私有目录下的 share 子目录（getExternalFilesDir/share），
 * 本机服务器（FTP/FTPS/SFTP/SCP）把它作为根目录对外暴露：
 * - [importFromUris] 把用户通过 SAF 选择的文件复制进来（「发文件」给其他设备）；
 * - 其他设备上传到本机服务器的文件也会落在此目录（收到文件），UI 调 [refresh] 刷新列表；
 * - [exportToDownloads] 把共享文件导出到系统下载目录，方便本机查看。
 *
 * 所有磁盘操作都在 [Dispatchers.IO] 上执行；导入大文件采用 64KB 缓冲流式复制，不整块读入内存。
 */
class DefaultShareRepository(private val context: Context) : ShareRepository {

    private val appContext: Context = context.applicationContext
    private val resolver = appContext.contentResolver
    private val mutableFiles = MutableStateFlow<List<ShareFile>>(emptyList())

    /** 导入操作串行化，避免并发导入同名文件时互相覆盖。 */
    private val importMutex = Mutex()

    /** 导出时进程内已预留的系统下载目录文件名（与 MediaStore 现有行一起查重）。 */
    private val downloadNameLock = Any()
    private val reservedDownloadNames = HashSet<String>()

    private val shareDirectory: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        SHARE_DIRECTORY_NAME
    )

    override val files: StateFlow<List<ShareFile>> = mutableFiles.asStateFlow()

    override val directoryPath: String = shareDirectory.absolutePath

    init {
        ensureShareDirectory()
        cleanStaleTempFiles()
    }

    override fun directory(): File {
        ensureShareDirectory()
        return shareDirectory
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        publishFiles(scanFiles())
    }

    override suspend fun importFromUris(uris: List<Uri>): ShareImportResult = importMutex.withLock {
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) return@withContext ShareImportResult()
            val imported = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (uri in uris) {
                val fallbackName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: "导入文件"
                val displayName = queryDisplayName(uri) ?: fallbackName
                var tempFile: File? = null
                try {
                    val dir = directory()
                    val existingNames = dir.list()?.toList().orEmpty()
                    val name = ShareFileNames.uniqueName(displayName, existingNames)
                    val target = File(dir, name)
                    val size = querySize(uri)
                    if (size > 0L && dir.usableSpace in 1 until size) {
                        throw IOException("共享目录可用空间不足")
                    }
                    val temp = File(dir, "." + name + IMPORT_TEMP_SUFFIX)
                    tempFile = temp
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("无法读取所选文件")
                    input.use { source ->
                        FileOutputStream(temp).use { sink ->
                            source.copyTo(sink, COPY_BUFFER_SIZE)
                            sink.flush()
                        }
                    }
                    if (!temp.renameTo(target)) {
                        throw IOException("保存共享文件失败")
                    }
                    tempFile = null
                    imported += name
                } catch (e: Exception) {
                    Log.w(TAG, "导入共享文件失败：" + displayName, e)
                    failed += displayName
                } finally {
                    tempFile?.let { if (it.exists()) it.delete() }
                }
            }
            publishFiles(scanFiles())
            ShareImportResult(imported = imported, failed = failed)
        }
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val target = resolveShareFile(name) ?: return@withContext false
        if (!target.isFile) return@withContext false
        val deleted = try {
            target.delete()
        } catch (e: SecurityException) {
            Log.w(TAG, "删除共享文件失败：" + name, e)
            false
        }
        if (deleted) publishFiles(scanFiles())
        deleted
    }

    override suspend fun exportToDownloads(name: String): Result<String> = withContext(Dispatchers.IO) {
        val source = resolveShareFile(name)
        if (source == null || !source.isFile) {
            return@withContext Result.failure(LanTransferException("共享文件不存在或名称非法：" + name))
        }
        val displayName = reserveDownloadName(source.name)
        var pendingUri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, guessMimeType(displayName))
                put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(downloadsCollection(), values)
                ?: throw IOException("无法在系统下载目录创建文件")
            pendingUri = uri
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("无法写入系统下载目录")
            output.use { sink ->
                source.inputStream().use { input -> input.copyTo(sink, COPY_BUFFER_SIZE) }
            }
            val committed = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            val rows = resolver.update(uri, committed, null, null)
            if (rows <= 0) throw IOException("保存下载文件失败：媒体库记录不存在")
            pendingUri = null
            Result.success(displayName)
        } catch (e: Exception) {
            pendingUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            Log.w(TAG, "导出共享文件到下载目录失败：" + name, e)
            Result.failure(LanTransferException("导出到系统下载目录失败：" + (e.message ?: "未知错误"), e))
        } finally {
            releaseDownloadName(displayName)
        }
    }

    // ------------------------------------------------------------ 目录扫描

    private fun ensureShareDirectory() {
        if (shareDirectory.exists()) return
        if (!shareDirectory.mkdirs() && !shareDirectory.exists()) {
            Log.w(TAG, "创建共享目录失败：" + shareDirectory.absolutePath)
        }
    }

    /** 清理上次进程被杀时残留的导入临时文件（只删除本实现约定的隐藏临时文件）。 */
    private fun cleanStaleTempFiles() {
        val stale = shareDirectory.listFiles()?.filter { file ->
            file.isFile && file.name.startsWith(".") && file.name.endsWith(IMPORT_TEMP_SUFFIX)
        }.orEmpty()
        for (file in stale) {
            if (!file.delete()) Log.w(TAG, "清理残留导入临时文件失败：" + file.name)
        }
    }

    private fun scanFiles(): List<ShareFile> {
        return shareDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !isIgnoredFileName(it.name) }
            ?.map { ShareFile(name = it.name, size = it.length(), modifiedAt = it.lastModified()) }
            ?.sortedWith(
                compareByDescending<ShareFile> { it.modifiedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            ?.toList()
            .orEmpty()
    }

    private fun publishFiles(list: List<ShareFile>) {
        mutableFiles.value = list
    }

    /** 隐藏文件与导入临时文件不进入共享列表（仍可通过本机服务器的完整路径访问）。 */
    private fun isIgnoredFileName(name: String): Boolean =
        name.startsWith(".") || name.endsWith(IMPORT_TEMP_SUFFIX)

    // ------------------------------------------------------------ SAF / MediaStore 辅助

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询所选文件名失败：" + uri, e)
            null
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return -1L
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "查询所选文件大小失败：" + uri, e)
            -1L
        }
    }

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun reserveDownloadName(rawName: String): String = synchronized(downloadNameLock) {
        val existing = HashSet<String>()
        existing.addAll(reservedDownloadNames)
        existing.addAll(queryDownloadDirectoryNames())
        val unique = ShareFileNames.uniqueName(rawName, existing)
        reservedDownloadNames.add(unique.lowercase(Locale.ROOT))
        unique
    }

    private fun releaseDownloadName(displayName: String) {
        synchronized(downloadNameLock) {
            reservedDownloadNames.remove(displayName.lowercase(Locale.ROOT))
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

    /**
     * 把外部传入的文件名解析为共享目录内的普通文件；拒绝路径分隔符、..、绝对路径
     * 以及 canonicalPath 逃逸出共享目录的情况（防目录穿越）。
     */
    private fun resolveShareFile(name: String): File? {
        if (name.isBlank()) return null
        if (name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name.indexOf('\u0000') >= 0) return null
        return try {
            val root = shareDirectory.canonicalFile
            if (!root.isDirectory) return null
            val candidate = File(root, name).canonicalFile
            if (candidate.parentFile == root) candidate else null
        } catch (e: IOException) {
            Log.w(TAG, "解析共享文件路径失败：" + name, e)
            null
        }
    }

    private companion object {
        const val TAG = "DefaultShareRepository"
        const val SHARE_DIRECTORY_NAME = "share"
        const val IMPORT_TEMP_SUFFIX = ".importing"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val DOWNLOADS_RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/"
    }
}
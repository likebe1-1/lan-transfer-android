package com.manst.lantransfer.quick

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.data.ShareFileNames
import com.manst.lantransfer.transfer.TransferFolderPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 快捷传输会话文件仓库默认实现。
 *
 * 会话目录位于应用外部私有目录的 quick-send/<接收码> 下，发送端的本机服务器以该目录为根目录：
 * - 用户选择的文件通过 [importFiles] 从 SAF 流式复制进来，文件名清洗并自动去重；
 * - 对方上传到会话目录的文件由本机服务器写入，[listFiles] 展示给用户；
 * - [exportToDownloads] 把收到的文件导出到系统下载目录；
 * - 会话结束时 [clear] 递归删除会话目录。
 */
class DefaultSessionFileStore(private val context: Context) : SessionFileStore {

    private val appContext: Context = context.applicationContext
    private val resolver = appContext.contentResolver

    /** 导入操作串行化，避免并发导入同名文件互相覆盖。 */
    private val importMutex = Mutex()

    /** 导出时进程内已预留的系统下载目录文件名。 */
    private val downloadNameLock = Any()
    private val reservedDownloadNames = HashSet<String>()

    private val rootDirectory: File
        get() = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            ROOT_DIRECTORY_NAME
        )

    init {
        ensureRootDirectory()
    }

    override fun directoryFor(code: String): File {
        ensureRootDirectory()
        val safeCode = SessionFileNames.sanitizeCode(code)
        val directory = File(rootDirectory, safeCode)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            Log.w(TAG, "创建会话目录失败：" + directory.absolutePath)
        }
        return directory
    }

    override suspend fun importFiles(directory: File, uris: List<Uri>): SessionImportResult =
        importMutex.withLock {
            withContext(Dispatchers.IO) {
                if (uris.isEmpty()) return@withContext SessionImportResult()
                val targetDirectory = requireSessionDirectory(directory)
                    ?: throw IllegalArgumentException("非法的会话目录")
                targetDirectory.mkdirs()
                val imported = mutableListOf<QuickFile>()
                val failed = mutableListOf<String>()
                for (uri in uris) {
                    val fallbackName = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() }
                        ?: "导入文件"
                    val rawName = queryDisplayName(uri) ?: fallbackName
                    var tempFile: File? = null
                    try {
                        val existingNames = targetDirectory.list()?.toList().orEmpty()
                        val name = SessionFileNames.uniqueName(rawName, existingNames)
                        val target = File(targetDirectory, name)
                        val temp = File(targetDirectory, SessionFileNames.importTempName(name))
                        tempFile = temp
                        val input = resolver.openInputStream(uri)
                            ?: throw IOException("无法读取所选文件")
                        input.use { source ->
                            FileOutputStream(temp).use { sink ->
                                source.copyTo(sink, COPY_BUFFER_SIZE)
                                sink.flush()
                            }
                        }
                        if (!temp.renameTo(target)) throw IOException("保存会话文件失败")
                        tempFile = null
                        imported += SessionFileNames.toQuickFile(target)
                    } catch (e: Exception) {
                        Log.w(TAG, "复制文件到会话目录失败：" + rawName, e)
                        failed += rawName
                    } finally {
                        tempFile?.let { if (it.exists()) it.delete() }
                    }
                }
                SessionImportResult(imported = imported, failed = failed)
            }
        }

    /**
     * 导入 SAF 目录树：保留「被选目录名」作为会话目录下的顶层，递归保留内部子目录结构。
     * 逐文件使用与 [importFiles] 相同的临时文件 + rename 原子落盘、名字清洗与重名规则；
     * 单个文件失败不影响其他文件。返回的 [QuickFile.relativePath] 是相对会话目录的真实路径。
     */
    override suspend fun importDirectory(directory: File, treeUri: Uri): SessionImportResult =
        importMutex.withLock {
            withContext(Dispatchers.IO) {
                val targetDirectory = requireSessionDirectory(directory)
                    ?: throw IllegalArgumentException("非法的会话目录")
                targetDirectory.mkdirs()
                val root = DocumentFile.fromTreeUri(appContext, treeUri)
                    ?: throw IllegalArgumentException("无法读取所选文件夹")
                val rawRootName = queryDisplayName(treeUri)
                    ?: root.name
                    ?: "文件夹"
                val safeRootName = ShareFileNames.sanitize(rawRootName)
                val imported = mutableListOf<QuickFile>()
                val failed = mutableListOf<String>()
                importDocumentDirectory(root, safeRootName, targetDirectory, imported, failed)
                SessionImportResult(imported = imported, failed = failed)
            }
        }

    private fun importDocumentDirectory(
        documentDirectory: DocumentFile,
        relativeDirectory: String,
        sessionDirectory: File,
        imported: MutableList<QuickFile>,
        failed: MutableList<String>
    ) {
        val children = try {
            documentDirectory.listFiles()
        } catch (e: RuntimeException) {
            Log.w(TAG, "读取 SAF 子目录失败，已跳过：" + relativeDirectory, e)
            failed += relativeDirectory
            return
        }
        for (child in children) {
            val rawName = child.name?.takeIf { it.isNotBlank() } ?: continue
            val rawRelative = if (relativeDirectory.isEmpty()) rawName else relativeDirectory + "/" + rawName
            if (child.isDirectory) {
                val safeRelative = TransferFolderPaths.sanitizeRelativePath(rawRelative) ?: safeRelativeFallback(rawRelative)
                importDocumentDirectory(child, safeRelative, sessionDirectory, imported, failed)
            } else {
                importDocumentFile(child, rawRelative, sessionDirectory, imported, failed)
            }
        }
    }

    private fun importDocumentFile(
        document: DocumentFile,
        rawRelativePath: String,
        sessionDirectory: File,
        imported: MutableList<QuickFile>,
        failed: MutableList<String>
    ) {
        var tempFile: File? = null
        try {
            val safeRelative = TransferFolderPaths.sanitizeRelativePath(rawRelativePath)
                ?: throw IOException("文件名非法")
            val relativeParent = safeRelative.substringBeforeLast('/', "")
            val targetDirectory = if (relativeParent.isEmpty()) {
                sessionDirectory
            } else {
                File(sessionDirectory, relativeParent)
            }
            if (!targetDirectory.exists() && !targetDirectory.mkdirs() && !targetDirectory.exists()) {
                throw IOException("创建会话子目录失败")
            }
            if (!isInsideSessionDirectory(targetDirectory, sessionDirectory)) {
                throw IOException("非法的目标目录")
            }
            val rawName = safeRelative.substringAfterLast('/')
            val existingNames = targetDirectory.list()?.toList().orEmpty()
            val name = ShareFileNames.uniqueName(rawName, existingNames)
            val target = File(targetDirectory, name)
            val temp = File(targetDirectory, SessionFileNames.importTempName(name))
            tempFile = temp
            val input = resolver.openInputStream(document.uri)
                ?: throw IOException("无法读取所选文件")
            input.use { source ->
                FileOutputStream(temp).use { sink ->
                    source.copyTo(sink, COPY_BUFFER_SIZE)
                    sink.flush()
                }
            }
            if (!temp.renameTo(target)) throw IOException("保存会话文件失败")
            tempFile = null
            val actualRelative = target.relativeToOrNull(sessionDirectory)?.invariantSeparatorsPath
                ?: safeRelative
            imported += QuickFile(
                name = target.name,
                size = target.length(),
                modifiedAt = target.lastModified(),
                relativePath = actualRelative
            )
        } catch (e: Exception) {
            Log.w(TAG, "复制文件夹内的文件失败：" + rawRelativePath, e)
            failed += rawRelativePath
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
    }

    private fun safeRelativeFallback(rawRelative: String): String {
        val name = rawRelative.substringAfterLast('/')
        return ShareFileNames.sanitize(name)
    }

    private fun isInsideSessionDirectory(target: File, sessionDirectory: File): Boolean {
        return try {
            val root = sessionDirectory.canonicalFile
            val candidate = target.canonicalFile
            candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
        } catch (e: IOException) {
            false
        }
    }

    override fun listFiles(directory: File): List<QuickFile> {
        val target = requireSessionDirectory(directory) ?: return emptyList()
        if (!target.isDirectory) return emptyList()
        val files = target.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !SessionFileNames.isIgnored(it.name) }
            ?.map { SessionFileNames.toQuickFile(it) }
            ?.toList()
            .orEmpty()
        return SessionFileNames.sortFiles(files)
    }

    override suspend fun exportToDownloads(file: File): Result<String> = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            return@withContext Result.failure(
                LanTransferException("会话文件不存在或已被删除：" + file.name)
            )
        }
        val displayName = reserveDownloadName(file.name)
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
                file.inputStream().use { input -> input.copyTo(sink, COPY_BUFFER_SIZE) }
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
            Log.w(TAG, "导出会话文件到下载目录失败：" + file.name, e)
            Result.failure(
                LanTransferException("导出到系统下载目录失败：" + (e.message ?: "未知错误"), e)
            )
        } finally {
            releaseDownloadName(displayName)
        }
    }

    override fun clear(directory: File) {
        if (!isInsideRoot(directory)) {
            Log.w(TAG, "拒绝删除会话根目录之外的内容：" + directory.absolutePath)
            return
        }
        try {
            if (!directory.deleteRecursively()) {
                Log.w(TAG, "清理会话目录失败：" + directory.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理会话目录失败：" + directory.absolutePath, e)
        }
    }

    // ------------------------------------------------------------ 目录与查询辅助

    private fun ensureRootDirectory() {
        val root = rootDirectory
        if (root.exists()) return
        if (!root.mkdirs() && !root.exists()) {
            Log.w(TAG, "创建快捷传输根目录失败：" + root.absolutePath)
        }
    }

    /** 只允许操作 quick-send 根目录内的会话目录（防目录穿越）。 */
    private fun requireSessionDirectory(directory: File): File? =
        if (isInsideRoot(directory)) directory else null

    private fun isInsideRoot(directory: File): Boolean {
        return try {
            val root = rootDirectory.canonicalFile
            val target = directory.canonicalFile
            target.path == root.path || target.path.startsWith(root.path + File.separator)
        } catch (e: IOException) {
            Log.w(TAG, "解析会话目录路径失败：" + directory.absolutePath, e)
            false
        }
    }

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

    private companion object {
        const val TAG = "DefaultSessionFileStore"
        const val ROOT_DIRECTORY_NAME = "quick-send"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val DOWNLOADS_RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/"
    }
}

/**
 * 会话文件命名/过滤/排序的纯逻辑，供 [DefaultSessionFileStore] 与 JVM 单元测试共用。
 * 文件名清洗与重名处理直接复用 data 包的 [ShareFileNames]。
 */
internal object SessionFileNames {

    const val IMPORT_TEMP_SUFFIX = ".importing"

    private const val FALLBACK_SESSION_CODE = "session"
    private const val MAX_SESSION_CODE_LENGTH = 32

    fun sanitize(rawName: String?): String = ShareFileNames.sanitize(rawName)

    fun uniqueName(rawName: String?, existingNames: Collection<String>): String =
        ShareFileNames.uniqueName(rawName, existingNames)

    /** 导入临时文件名：.<最终名>.importing，导入完成后 rename 为正式名。 */
    fun importTempName(finalName: String): String = "." + finalName + IMPORT_TEMP_SUFFIX

    /** 隐藏文件与导入临时文件不进入会话文件列表。 */
    fun isIgnored(fileName: String): Boolean =
        fileName.startsWith(".") || fileName.endsWith(IMPORT_TEMP_SUFFIX)

    fun toQuickFile(file: File): QuickFile = QuickFile(
        name = file.name,
        size = file.length(),
        modifiedAt = file.lastModified()
    )

    /** 按修改时间倒序；同一时间按名称不区分大小写。 */
    fun sortFiles(files: List<QuickFile>): List<QuickFile> = files.sortedWith(
        compareByDescending<QuickFile> { it.modifiedAt }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )

    /** 接收码用作目录名：只保留字母/数字/-/_，防止路径穿越；空值回退为 session。 */
    fun sanitizeCode(code: String?): String {
        val cleaned = code
            ?.trim()
            ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            ?.take(MAX_SESSION_CODE_LENGTH)
            .orEmpty()
        return cleaned.ifBlank { FALLBACK_SESSION_CODE }
    }
}
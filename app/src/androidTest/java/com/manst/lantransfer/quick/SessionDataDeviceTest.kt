package com.manst.lantransfer.quick

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 快捷传输数据层仪器测试：真实 MediaStore 上验证会话导入/导出、QuickDownloadSink 的
 * commit/abort 语义，以及 listFiles 过滤排序与 clear 清理。
 *
 * 本测试只要求编译通过，由 Lead 在专用模拟器统一执行。
 */
@RunWith(AndroidJUnit4::class)
class SessionDataDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private lateinit var store: DefaultSessionFileStore
    private lateinit var sink: MediaStoreQuickDownloadSink

    private val createdSourceUris = mutableListOf<Uri>()
    private val exportedNames = mutableListOf<String>()
    private val committedSinkNames = mutableListOf<String>()

    private val quickRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "quick-send")

    @Before
    fun setUp() {
        runCatching { quickRoot.deleteRecursively() }
        deleteTestSourceRows()
        store = DefaultSessionFileStore(context)
        sink = MediaStoreQuickDownloadSink(context)
    }

    @After
    fun tearDown() {
        runCatching { quickRoot.deleteRecursively() }
        createdSourceUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdSourceUris.clear()
        deleteExportRows(exportedNames.toList())
        exportedNames.clear()
        deleteExportRows(committedSinkNames.toList())
        committedSinkNames.clear()
        deleteTestSourceRows()
    }

    @Test
    fun directoryFor_createsSessionDirectoryUnderQuickSendRoot() {
        val directory = store.directoryFor("123456")
        assertTrue(directory.isDirectory)
        assertEquals(File(quickRoot, "123456").absolutePath, directory.absolutePath)

        // 非法接收码会被清洗，不能逃逸出 quick-send 根目录
        val escaped = store.directoryFor("../evil")
        assertEquals(File(quickRoot, "evil").absolutePath, escaped.absolutePath)
        assertTrue(escaped.canonicalPath.startsWith(quickRoot.canonicalPath))
    }

    @Test
    fun importFiles_copiesContentAndDedupesRepeatedName() = runBlocking {
        val content = "会话文件内容 quick-session".toByteArray(Charsets.UTF_8)
        val uri = createSourceUri("quick-session.txt", content)
        val directory = store.directoryFor("100001")

        val first = store.importFiles(directory, listOf(uri))
        assertEquals(emptyList<String>(), first.failed)
        assertEquals(listOf("quick-session.txt"), first.imported.map { it.name })
        assertEquals(content.size.toLong(), first.imported.first().size)
        val copied = File(directory, "quick-session.txt")
        assertTrue(copied.isFile)
        assertTrue(content.contentEquals(copied.readBytes()))

        // 同名再导入自动追加序号
        val second = store.importFiles(directory, listOf(uri))
        assertEquals(emptyList<String>(), second.failed)
        assertEquals(listOf("quick-session (1).txt"), second.imported.map { it.name })
        // 不残留导入临时文件
        val leftovers = directory.listFiles()?.filter {
            it.name.endsWith(SessionFileNames.IMPORT_TEMP_SUFFIX)
        }.orEmpty()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun importFiles_singleFailureDoesNotAffectOthers() = runBlocking {
        val goodUri = createSourceUri("quick-good.txt", "good".toByteArray(Charsets.UTF_8))
        val badUri = Uri.parse("content://com.manst.lantransfer.nonexistent/files/missing.txt")
        val directory = store.directoryFor("100005")

        val result = store.importFiles(directory, listOf(badUri, goodUri))

        assertEquals(1, result.failed.size)
        assertEquals(listOf("quick-good.txt"), result.imported.map { it.name })
        assertTrue(File(directory, "quick-good.txt").isFile)
    }

    @Test
    fun listFiles_ignoresHiddenAndTempAndSortsByModifiedTime() {
        val directory = store.directoryFor("100002")
        File(directory, "a-older.txt").writeText("older")
        Thread.sleep(1_100L)
        File(directory, "z-newer.txt").writeText("newer")
        File(directory, ".hidden").writeText("hidden")
        File(directory, "leftover.importing").writeText("temp")
        File(directory, ".abc.txt.importing").writeText("temp")
        File(directory, "subdir").mkdirs()

        val files = store.listFiles(directory)

        assertEquals(listOf("z-newer.txt", "a-older.txt"), files.map { it.name })
        assertEquals(5L, files.first { it.name == "a-older.txt" }.size)
    }

    @Test
    fun exportToDownloads_copiesFileIntoMediaStore() = runBlocking {
        val directory = store.directoryFor("100003")
        val fileName = "quick-export-" + System.currentTimeMillis() + ".txt"
        val content = "导出内容 quick-export".toByteArray(Charsets.UTF_8)
        val file = File(directory, fileName).apply { writeBytes(content) }

        val result = store.exportToDownloads(file)

        assertTrue(result.isSuccess)
        val displayName = result.getOrThrow()
        exportedNames += displayName
        assertEquals(fileName, displayName)
        assertTrue(mediaFileExists(displayName, EXPORT_DOWNLOADS_DIR))

        // 文件不存在时返回失败而不是抛异常
        assertTrue(store.exportToDownloads(File(directory, "missing.txt")).isFailure)
    }

    @Test
    fun downloadSink_commitMakesFileVisible() {
        val baseName = "quick-sink-" + System.currentTimeMillis() + ".txt"
        val content = "sink commit content".toByteArray(Charsets.UTF_8)

        val target = sink.open(baseName, "text/plain")
        assertEquals(baseName, target.displayName)
        target.outputStream.use { it.write(content) }
        val result = target.commit()

        assertTrue(result.isSuccess)
        assertEquals(baseName, result.getOrThrow())
        committedSinkNames += target.displayName
        assertTrue(mediaFileExists(baseName, EXPORT_DOWNLOADS_DIR))
    }

    @Test
    fun downloadSink_abortRemovesPendingRecord() {
        val baseName = "quick-abort-" + System.currentTimeMillis() + ".txt"
        val target = sink.open(baseName, "text/plain")
        target.outputStream.write("aborted".toByteArray(Charsets.UTF_8))
        target.abort()

        assertFalse(mediaFileExists(target.displayName, EXPORT_DOWNLOADS_DIR))
        // abort 之后再 commit 返回失败，不会把已取消的文件变成可见
        assertTrue(target.commit().isFailure)
    }

    @Test
    fun downloadSink_sanitizesAndDedupesDisplayName() {
        val baseName = "quick-dup-" + System.currentTimeMillis() + "a/b.txt"
        val first = sink.open(baseName, "text/plain")
        val second = sink.open(baseName, "text/plain")
        try {
            assertFalse(first.displayName.contains('/'))
            assertFalse(second.displayName.contains('/'))
            assertTrue(first.displayName != second.displayName)
            assertTrue(second.displayName.startsWith(first.displayName.substringBeforeLast('.')))
        } finally {
            first.abort()
            second.abort()
        }
    }

    @Test
    fun clear_removesSessionDirectoryRecursively() {
        val directory = store.directoryFor("100004")
        val nested = File(directory, "nested")
        assertTrue(nested.mkdirs())
        File(nested, "a.txt").writeText("a")
        File(directory, "b.txt").writeText("b")

        store.clear(directory)

        assertFalse(directory.exists())
    }

    // ------------------------------------------------------------ MediaStore 辅助

    private fun createSourceUri(displayName: String, content: ByteArray): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, TEST_SOURCE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建测试用 content Uri" }
        createdSourceUris += uri
        try {
            val output = requireNotNull(resolver.openOutputStream(uri)) { "无法写入测试内容" }
            output.use { it.write(content) }
            val committed = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, committed, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun mediaFileExists(displayName: String, relativePath: String): Boolean {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?",
            arrayOf(displayName, relativePath),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false
    }

    private fun deleteTestSourceRows() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(
                collection,
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(TEST_SOURCE_DIR)
            )
        }
    }

    private fun deleteExportRows(names: List<String>) {
        if (names.isEmpty()) return
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val placeholders = names.joinToString(",") { "?" }
        val selection = MediaStore.Downloads.RELATIVE_PATH + " = ? AND " +
            MediaStore.Downloads.DISPLAY_NAME + " IN (" + placeholders + ")"
        val args = (listOf(EXPORT_DOWNLOADS_DIR) + names).toTypedArray()
        runCatching { resolver.delete(collection, selection, args) }
    }

    private companion object {
        val TEST_SOURCE_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransferTest/"
        val EXPORT_DOWNLOADS_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransfer/"
    }
}

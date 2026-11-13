package com.manst.lantransfer.data

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
 * [DefaultShareRepository] 的仪器测试：在真实设备/模拟器上验证共享目录扫描、SAF 导入
 * （用 MediaStore 自己插入的 content Uri 模拟，不需要 FileProvider）、删除防穿越与导出到下载目录。
 *
 * 说明：本测试只要求编译通过，由 Lead 在专用模拟器统一执行。
 */
@RunWith(AndroidJUnit4::class)
class ShareRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private lateinit var repository: DefaultShareRepository
    private val exportedNames = mutableListOf<String>()
    private val createdTestUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        repository = DefaultShareRepository(context)
        repository.directory().listFiles()?.forEach { it.deleteRecursively() }
        deleteTestDownloadsRows()
        runBlocking { repository.refresh() }
    }

    @After
    fun tearDown() {
        repository.directory().listFiles()?.forEach { it.deleteRecursively() }
        createdTestUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdTestUris.clear()
        deleteTestDownloadsRows()
        deleteExportedRows()
        exportedNames.clear()
    }

    @Test
    fun directory_isShareSubdirectoryOfExternalFilesDir() {
        val expectedRoot = context.getExternalFilesDir(null) ?: context.filesDir
        assertEquals(File(expectedRoot, "share").absolutePath, repository.directoryPath)
        assertTrue(repository.directory().isDirectory)
    }

    @Test
    fun refresh_scansFilesSortedByModifiedTimeAndIgnoresHiddenAndDirectories() = runBlocking {
        val dir = repository.directory()
        // 名字顺序与修改时间顺序刻意相反，保证断言真正验证「按修改时间倒序」
        val older = File(dir, "a-older.txt").apply { writeText("older") }
        Thread.sleep(1_100L)
        val newer = File(dir, "z-newer.txt").apply { writeText("newer") }
        File(dir, ".hidden.tmp").writeText("temp")
        File(dir, ".importing-temp.importing").writeText("temp")
        File(dir, "subdir").mkdirs()

        repository.refresh()

        assertEquals(listOf("z-newer.txt", "a-older.txt"), repository.files.value.map { it.name })
        assertEquals(5L, repository.files.value.first { it.name == "a-older.txt" }.size)
        assertTrue(newer.lastModified() > older.lastModified())
    }

    @Test
    fun importFromUri_copiesContentAndDedupesRepeatedName() = runBlocking {
        val content = "共享内容 share-content".toByteArray(Charsets.UTF_8)
        val uri = createMediaStoreTestFile("share-import.txt", content)

        val first = repository.importFromUris(listOf(uri))
        assertEquals(emptyList<String>(), first.failed)
        assertEquals(listOf("share-import.txt"), first.imported)
        val copied = File(repository.directory(), "share-import.txt")
        assertTrue(copied.isFile)
        assertTrue(content.contentEquals(copied.readBytes()))
        assertEquals(listOf("share-import.txt"), repository.files.value.map { it.name })

        val second = repository.importFromUris(listOf(uri))
        assertEquals(emptyList<String>(), second.failed)
        assertEquals(listOf("share-import (1).txt"), second.imported)
        assertTrue(File(repository.directory(), "share-import (1).txt").isFile)
    }

    @Test
    fun importWithInvalidUri_reportsFailureWithoutCrash() = runBlocking {
        val missing = Uri.parse("content://com.manst.lantransfer.nonexistent/files/missing.txt")
        val result = repository.importFromUris(listOf(missing))
        assertTrue(result.imported.isEmpty())
        assertEquals(1, result.failed.size)
        assertFalse(result.isSuccess)
    }

    @Test
    fun delete_removesFileAndRejectsPathTraversal() = runBlocking {
        val dir = repository.directory()
        val file = File(dir, "to-delete.txt").apply { writeText("x") }
        File(dir, "subdir").mkdirs()
        repository.refresh()

        assertTrue(repository.delete("to-delete.txt"))
        assertFalse(file.exists())
        assertFalse(repository.delete("to-delete.txt"))
        // 防目录穿越：路径分隔符、..、绝对路径、子目录都不允许
        assertFalse(repository.delete("../outside.txt"))
        assertFalse(repository.delete("/data/local/tmp/outside.txt"))
        assertFalse(repository.delete("subdir"))
        assertFalse(repository.delete(""))
    }

    @Test
    fun exportToDownloads_copiesFileIntoMediaStore() = runBlocking {
        val dir = repository.directory()
        val fileName = "share-export-" + System.currentTimeMillis() + ".txt"
        val content = "导出内容 export-content".toByteArray(Charsets.UTF_8)
        File(dir, fileName).writeBytes(content)
        repository.refresh()

        val result = repository.exportToDownloads(fileName)
        assertTrue(result.isSuccess)
        val displayName = result.getOrThrow()
        exportedNames += displayName
        assertEquals(fileName, displayName)

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val exists = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?",
            arrayOf(displayName, EXPORT_DOWNLOADS_DIR),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false
        assertTrue("导出后应能在 MediaStore 中查询到文件", exists)
    }

    @Test
    fun exportToDownloads_returnsFailureForMissingOrIllegalName() = runBlocking {
        assertTrue(repository.exportToDownloads("definitely-missing.txt").isFailure)
        assertTrue(repository.exportToDownloads("../escape.txt").isFailure)
        assertTrue(repository.exportToDownloads("").isFailure)
    }

    // ------------------------------------------------------------ 辅助方法

    private fun createMediaStoreTestFile(displayName: String, content: ByteArray): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, TEST_DOWNLOADS_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建测试用 content Uri" }
        createdTestUris += uri
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

    private fun deleteTestDownloadsRows() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(
                collection,
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(TEST_DOWNLOADS_DIR)
            )
        }
    }

    private fun deleteExportedRows() {
        if (exportedNames.isEmpty()) return
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val placeholders = exportedNames.joinToString(",") { "?" }
        val selection = MediaStore.Downloads.RELATIVE_PATH + " = ? AND " +
            MediaStore.Downloads.DISPLAY_NAME + " IN (" + placeholders + ")"
        val args = (listOf(EXPORT_DOWNLOADS_DIR) + exportedNames).toTypedArray()
        runCatching { resolver.delete(collection, selection, args) }
    }

    private companion object {
        val TEST_DOWNLOADS_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransferTest/"
        val EXPORT_DOWNLOADS_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransfer/"
    }
}

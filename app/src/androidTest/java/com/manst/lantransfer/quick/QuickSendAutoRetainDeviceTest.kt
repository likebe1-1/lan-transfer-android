package com.manst.lantransfer.quick

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 快速传文件「发送端收到文件自动保留」设备测试（只要求编译，由 Lead 统一跑）：
 * 会话进行中模拟对方上传（顶层 + 子目录）→ 用户手动导出其中一个 → stopSession：
 * 未手动导出的收到文件自动保存到系统下载目录（保留相对子目录），会话目录随后删除，已手动导出的不重复导出。
 */
@RunWith(AndroidJUnit4::class)
class QuickSendAutoRetainDeviceTest {

    private val tag = "QuickAutoRetainTest"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val resolver get() = context.contentResolver

    private var sender: DefaultQuickSendManager? = null

    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setUp() = runBlocking {
        runCatching { app.container.localServerManager.stop() }
        deleteTestDownloads()
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { sender?.stopSession() }
        sender = null
        runCatching { app.container.localServerManager.stop() }
        createdUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdUris.clear()
        deleteTestDownloads()
    }

    @Test
    fun stopSession_autoExportsReceivedFilesToDownloads_withoutDuplicatingManualExport(): Unit = runBlocking {
        val stamp = System.currentTimeMillis()
        val prefix = "quick-auto-" + stamp
        val ownName = prefix + "-own.txt"
        val ownUri = createContentUri(ownName, "自己的文件内容".repeat(30).toByteArray())

        val sendManager = DefaultQuickSendManager(
            context,
            app.container.localServerManager,
            app.container.sessionFileStore
        )
        sender = sendManager
        val started = sendManager.startSession(listOf(ownUri))
        assertTrue("启动发送会话失败：" + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)
        val waiting = withTimeout(30_000L) {
            sendManager.state.first { state -> state.phase == QuickSendPhase.WAITING || state.error != null }
        }
        assertEquals("会话应进入等待状态：" + waiting.error, QuickSendPhase.WAITING, waiting.phase)
        val sessionDirectory = File(waiting.sessionDirectory)
        assertTrue("会话目录应存在", sessionDirectory.isDirectory)

        // 模拟对方上传：一个顶层文件 + 一个子目录文件
        val receivedTopName = prefix + "-received.txt"
        val receivedFolder = prefix + "-folder"
        val receivedNestedName = prefix + "-nested.txt"
        writeSessionFile(sessionDirectory, receivedTopName, "对方上传的顶层文件".repeat(40).toByteArray())
        writeSessionFile(
            sessionDirectory,
            receivedFolder + "/" + receivedNestedName,
            "对方上传的子目录文件".repeat(50).toByteArray()
        )

        // 发送端 2 秒扫描 / 手动 refresh 后应递归识别为 receivedFiles
        var received = emptyList<QuickFile>()
        val detected = withTimeoutOrNull(20_000L) {
            while (true) {
                sendManager.refresh()
                received = sendManager.state.value.receivedFiles
                val hasTop = received.any { it.name == receivedTopName }
                val hasNested = received.any { it.relativePath == receivedFolder + "/" + receivedNestedName }
                if (hasTop && hasNested) return@withTimeoutOrNull true
                delay(300L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        assertEquals(
            "发送端应递归识别到对方上传的文件，实际=" + received.map { it.relativePath ?: it.name },
            true,
            detected
        )

        // 用户先手动导出顶层文件：会话结束时不应重复导出
        val manualExport = sendManager.exportReceived(receivedTopName)
        assertTrue("手动导出收到文件失败：" + (manualExport.exceptionOrNull()?.message ?: ""), manualExport.isSuccess)
        val manualDisplayName = manualExport.getOrThrow()
        assertEquals("手动导出后应能在下载目录查到", 1, countDownloadsLike(receivedTopName.substringBeforeLast('.') + "%"))

        // 结束会话：未手动导出的子目录文件应自动保留到 Download/<folder>/，随后清理会话目录
        sendManager.stopSession()
        assertEquals(QuickSendPhase.IDLE, sendManager.state.value.phase)
        assertFalse("会话目录应被删除", sessionDirectory.exists())

        // 已手动导出的文件不重复导出（同名前缀只应有 1 条记录）
        assertEquals(
            "已手动导出的文件不应被自动重复导出，手动导出名=" + manualDisplayName,
            1,
            countDownloadsLike(receivedTopName.substringBeforeLast('.') + "%")
        )
        // 子目录收到文件应自动保留并保持相对目录结构
        val nestedRow = queryDownloadRow(receivedNestedName)
        assertNotNull("子目录收到文件应自动保留到下载目录", nestedRow)
        assertEquals(
            "自动保留应保持相对子目录结构",
            "Download/" + receivedFolder + "/",
            nestedRow!!.second
        )
        Log.i(tag, "自动保留完成：nested=" + nestedRow.first + " @ " + nestedRow.second)
    }

    // ------------------------------------------------------------ 辅助方法

    private fun writeSessionFile(directory: File, relativePath: String, content: ByteArray) {
        val file = File(directory, relativePath.replace('/', File.separatorChar))
        file.parentFile?.mkdirs()
        file.writeBytes(content)
        Log.i(tag, "写入会话目录文件：" + file.absolutePath + "（" + content.size + " 字节）")
    }

    private fun createContentUri(displayName: String, content: ByteArray): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, TEST_SOURCE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建测试 content Uri" }
        createdUris += uri
        try {
            val output = requireNotNull(resolver.openOutputStream(uri)) { "无法写入测试 content Uri" }
            output.use { it.write(content) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun queryDownloadRow(displayName: String): Pair<String, String>? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0) to (cursor.getString(1) ?: "")
        }
    }

    private fun countDownloadsLike(namePrefix: String): Int {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
            arrayOf(namePrefix),
            null
        )?.use { cursor -> cursor.count } ?: 0
    }

    private fun deleteTestDownloads() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(collection, MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(TEST_SOURCE_DIR))
        }
        runCatching {
            resolver.delete(collection, MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", arrayOf("quick-auto-%"))
        }
    }

    private companion object {
        val TEST_SOURCE_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickTest/"
    }
}
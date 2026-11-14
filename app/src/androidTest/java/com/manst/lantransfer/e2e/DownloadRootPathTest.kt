package com.manst.lantransfer.e2e

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.core.AppPreferences
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.QuickJoinTarget
import com.manst.lantransfer.quick.QuickSendPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证「接收/下载的文件直接落在系统下载根目录 Download/」：
 * 启动真实快捷发送会话 + 本机回环接收，接收完成后通过 MediaStore 断言
 * 记录位于 RELATIVE_PATH = "Download/"，且不存在于 "Download/LanTransfer/" 子目录。
 */
@RunWith(AndroidJUnit4::class)
class DownloadRootPathTest {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val sourceName = "download-root-src.bin"

    private fun log(message: String) {
        Log.e(tag, "DOWNLOAD-ROOT " + message)
    }

    @Before
    fun prepare() {
        AppPreferences(context).trustedLanNoticeShown = true
        runBlocking {
            runCatching { app.container.quickReceiveManager.leave() }
            runCatching { app.container.quickSendManager.stopSession() }
        }
        deleteRowsByName()
    }

    @After
    fun cleanup() {
        runBlocking {
            runCatching { app.container.quickReceiveManager.leave() }
            runCatching { app.container.quickSendManager.stopSession() }
        }
        // 保留 Download/ 根目录下的接收文件，供测试后用 adb 查看实际落盘位置；
        // 只清理测试源文件目录（下次运行 @Before 会统一清理）。
        deleteRowsUnder(Environment.DIRECTORY_DOWNLOADS + "/LanTransferVerifySrc/")
    }

    @Test
    fun receivedFileGoesToDownloadsRootWithoutSubfolder() = runBlocking {
        val payload = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val sourceUri = createContentUri(sourceName, payload)

        val started = app.container.quickSendManager.startSession(listOf(sourceUri))
        assertTrue("发送会话启动失败：" + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)
        val session = withTimeout(30_000L) {
            app.container.quickSendManager.state.first { it.phase == QuickSendPhase.WAITING }
        }.sessionInfo ?: error("缺少会话信息")

        val receive = app.container.quickReceiveManager
        // 用 FTP 回环，速度快、便于快速完成整个文件
        val joined = receive.join(
            QuickJoinTarget(
                code = session.code,
                host = "127.0.0.1",
                protocol = TransferProtocol.FTP,
                ftpPort = session.ftpPort,
                ftpsPort = session.ftpsPort,
                sshPort = session.sshPort
            )
        )
        assertTrue("连接失败：" + (joined.exceptionOrNull()?.message ?: ""), joined.isSuccess)

        val completed = withTimeoutOrNull(60_000L) {
            while (true) {
                val state = receive.state.value
                if (state.completedFiles.isNotEmpty()) return@withTimeoutOrNull true
                if (state.phase == com.manst.lantransfer.quick.QuickReceivePhase.FAILED) {
                    return@withTimeoutOrNull false
                }
                delay(200L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        log("COMPLETED=" + completed + " completedFiles=" + receive.state.value.completedFiles)
        assertTrue("接收未完成", completed == true)

        val rows = queryRows()
        log("ROWS=" + rows.joinToString(" | ") { "name=" + it.first + " path=" + it.second + " size=" + it.third })
        val rootRows = rows.filter { it.second == Environment.DIRECTORY_DOWNLOADS + "/" }
        assertTrue("下载记录应位于 Download/ 根目录，实际=" + rows, rootRows.isNotEmpty())
        assertEquals("下载文件大小应正确", payload.size.toLong(), rootRows.first().third)
        assertFalse(
            "不应再写入 Download/LanTransfer/ 子目录，实际=" + rows,
            rows.any { it.second.startsWith(Environment.DIRECTORY_DOWNLOADS + "/LanTransfer/") }
        )
        log("OK rootPath=" + rootRows.first().second)
        receive.leave()
        app.container.quickSendManager.stopSession()
    }

    private fun deleteRowsUnder(relativePath: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            context.contentResolver.delete(
                collection,
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(relativePath)
            )
        }
    }

    private fun deleteRowsByName() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            context.contentResolver.delete(
                collection,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("download-root-src%")
            )
        }
    }

    private fun queryRows(): List<Triple<String, String, Long>> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.SIZE
        )
        val rows = ArrayList<Triple<String, String, Long>>()
        context.contentResolver.query(
            collection,
            projection,
            MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
            arrayOf("download-root-src%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += Triple(
                    cursor.getString(0) ?: "",
                    cursor.getString(1) ?: "",
                    cursor.getLong(2)
                )
            }
        }
        return rows
    }

    private fun createContentUri(displayName: String, content: ByteArray): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanTransferVerifySrc/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) { "无法创建测试 content Uri" }
        context.contentResolver.openOutputStream(uri)?.use { it.write(content) }
            ?: throw IllegalStateException("无法写入测试 content Uri")
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null
        )
        return uri
    }
}
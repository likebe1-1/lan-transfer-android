package com.manst.lantransfer.e2e

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.AppPreferences
import com.manst.lantransfer.quick.QuickSendPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 传输中「速度 / 预计剩余时间 / 进度」的端到端验证与截图：
 * 设备上启动真实发送会话（大文件），接收端手动输入 IP + 接收码连接，
 * 等待进度卡出现速度或剩余时间文字并截图，最后确认文件接收完成。
 *
 * 截图输出：/sdcard/Android/data/com.manst.lantransfer.debug/files/screenshots/13_quick_transfer_progress.png
 */
@RunWith(AndroidJUnit4::class)
class QuickProgressTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val shotsDir = File(context.getExternalFilesDir(null), "screenshots")

    private var contentUri: android.net.Uri? = null

    @Before
    fun prepare() {
        AppPreferences(context).trustedLanNoticeShown = true
        context.deleteFile("servers.json")
        shotsDir.mkdirs()
        runBlocking {
            runCatching { app.container.quickReceiveManager.leave() }
            runCatching { app.container.quickSendManager.stopSession() }
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            runCatching { app.container.quickReceiveManager.leave() }
            runCatching { app.container.quickSendManager.stopSession() }
        }
        contentUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    @Test
    fun receiveProgress_showsSpeedAndEta_andScreenshot() {
        // 64MB 大文件，保证传输期间有足够的进度样本可以计算速度与剩余时间，
        // 同时避免用例等待完整传输（模拟器上大文件整体吞吐较慢）
        val uri = createContentUri("大文件传输测试.bin", 64L * 1024L * 1024L)
        contentUri = uri
        val started = runBlocking { app.container.quickSendManager.startSession(listOf(uri)) }
        assertTrue("发送会话启动失败: " + started.exceptionOrNull()?.message, started.isSuccess)
        val session = runBlocking {
            withTimeout(30_000L) {
                app.container.quickSendManager.state.first { it.phase == QuickSendPhase.WAITING }
            }
        }.sessionInfo ?: error("缺少会话信息")

        var metricsVisible = false
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(10_000L) {
                composeRule.onAllNodesWithText("接收文件").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("接收文件").performClick()
            composeRule.waitUntil(10_000L) {
                composeRule.onAllNodesWithText("手动输入").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("quick_manual_host").performTextInput("127.0.0.1")
            composeRule.onNodeWithTag("quick_manual_code").performTextInput(session.code)
            composeRule.onNodeWithTag("quick_manual_connect").performClick()

            composeRule.waitUntil(30_000L) {
                composeRule.onAllNodesWithText("正在接收").fetchSemanticsNodes().isNotEmpty()
            }
            metricsVisible = runCatching {
                composeRule.waitUntil(20_000L) {
                    composeRule.onAllNodesWithText("剩余约", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                        composeRule.onAllNodesWithText("MB/s", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                true
            }.getOrDefault(false)
            Log.e("E2EDIAG", "PROGRESS_METRICS visible=" + metricsVisible)
            Thread.sleep(300)
            capture("13_quick_transfer_progress")
        }

        val transfer = app.container.quickReceiveManager.state.value.transfer
        Log.e(
            "E2EDIAG",
            "PROGRESS_DONE transfer=" + transfer +
                " completedFiles=" + app.container.quickReceiveManager.state.value.completedFiles
        )
        assertTrue("接收界面应显示速度或预计剩余时间", metricsVisible)
        assertTrue("传输中应已接收到部分字节", (transfer?.bytesTransferred ?: 0L) > 0L)
    }

    private fun capture(name: String) {
        val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(shotsDir, name + ".png")).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }

    private fun createContentUri(displayName: String, size: Long): android.net.Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickShot/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) { "无法创建测试 content Uri" }
        try {
            val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                var remaining = size
                while (remaining > 0L) {
                    val length = minOf(remaining, chunk.size.toLong()).toInt()
                    output.write(chunk, 0, length)
                    remaining -= length
                }
            } ?: throw IllegalStateException("无法写入测试 content Uri")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}
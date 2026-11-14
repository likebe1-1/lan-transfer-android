package com.manst.lantransfer.e2e

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import kotlinx.coroutines.runBlocking
import android.util.Log
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 快速传文件界面截图：入口卡、发送选文件页、发送会话页（真实会话）、接收页。
 * 截图输出到 /sdcard/Android/data/com.manst.lantransfer.debug/files/screenshots/。
 */
@RunWith(AndroidJUnit4::class)
class QuickScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication
    private val shotsDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun prepare() = runBlocking {
        shotsDir.mkdirs()
        com.manst.lantransfer.core.AppPreferences(context).trustedLanNoticeShown = true
        app.container.quickSendManager.stopSession()
        app.container.quickReceiveManager.stopDiscovery()
        context.getExternalFilesDir(null)?.let { root ->
            File(root, "quick-send").deleteRecursively()
        }
        Unit
    }

    @After
    fun clean() = runBlocking {
        app.container.quickSendManager.stopSession()
        app.container.quickReceiveManager.stopDiscovery()
        Unit
    }

    private fun capture(name: String) {
        Thread.sleep(500)
        val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(shotsDir, name + ".png")).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun createContentUri(displayName: String, content: ByteArray) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickShot/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        context.contentResolver.openOutputStream(uri)?.use { it.write(content) }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null
        )
    }

    @Test
    fun captureQuickTransferScreens() {
        createContentUri("季度报告.pdf", ByteArray(256 * 1024) { (it % 199).toByte() })
        createContentUri("旅行照片.jpg", ByteArray(512 * 1024) { (it % 251).toByte() })

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            waitForText("快速传文件")
            capture("08_quick_entry")

            // 接收页
            composeRule.onNodeWithText("接收文件").performClick()
            waitForText("附近设备")
            Thread.sleep(1500)
            capture("09_quick_receive")

            composeRule.onNodeWithContentDescription("返回").performClick()
            waitForText("快速传文件")

            // 发送页（未开始：选择文件）
            composeRule.onNodeWithText("发送文件").performClick()
            waitForText("选择要发送的文件")
            capture("10_quick_send_pick")

            // 启动真实会话后截取会话页
            val uris = collectShotUris()
            Log.e("E2EDIAG", "QUICK-SHOT uris=" + uris.size)
            val started = runBlocking { app.container.quickSendManager.startSession(uris) }
            Log.e("E2EDIAG", "QUICK-SHOT started=" + started.isSuccess + " err=" + started.exceptionOrNull()?.message)
            check(started.isSuccess) { "会话启动失败: " + started.exceptionOrNull()?.message }
            Thread.sleep(5000)
            Log.e("E2EDIAG", "QUICK-SHOT state=" + app.container.quickSendManager.state.value)
            capture("11_quick_send_session")
            runCatching { waitForText("结束会话", timeoutMillis = 5_000) }
            Thread.sleep(1500)
            capture("11b_quick_send_session_after_wait")
        }
    }

    private fun collectShotUris(): List<android.net.Uri> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val names = listOf("季度报告.pdf", "旅行照片.jpg")
        val selection = MediaStore.Downloads.RELATIVE_PATH + " = ?"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickShot/")
        val uris = ArrayList<android.net.Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            selection,
            args,
            null
        )?.use { cursor ->
            while (cursor.moveToNext() && uris.size < names.size) {
                val id = cursor.getLong(0)
                uris += android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return uris
    }
}
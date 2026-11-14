package com.manst.lantransfer.e2e

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.server.LocalServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 本机服务器界面截图：以运行中的真实服务器状态进入界面并截图。
 * 截图输出：/sdcard/Android/data/com.manst.lantransfer.debug/files/screenshots/
 */
@RunWith(AndroidJUnit4::class)
class ServerScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication
    private val shotsDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun prepareServer() = runBlocking {
        shotsDir.mkdirs()
        com.manst.lantransfer.core.AppPreferences(context).trustedLanNoticeShown = true
        val share = app.container.shareRepository
        val server = app.container.localServerManager
        server.stop()
        val root = share.directory().apply { mkdirs() }
        root.listFiles()?.forEach { if (it.isFile) it.delete() }
        File(root, "旅行照片.zip").writeBytes(ByteArray(1024 * 1024) { index -> (index % 251).toByte() })
        File(root, "会议记录.pdf").writeBytes(ByteArray(256 * 1024) { index -> (index % 199).toByte() })
        File(root, "readme.txt").writeText("共享给局域网设备的说明文件\n", Charsets.UTF_8)
        share.refresh()
        val started = server.start(
            LocalServerConfig(
                enableFtp = true,
                enableFtps = true,
                enableSftp = true,
                enableScp = true,
                ftpPort = 2121,
                ftpsPort = 2122,
                sshPort = 2222,
                username = "android",
                password = "lan12345"
            )
        )
        check(started.isSuccess) { "服务器启动失败: " + started.exceptionOrNull()?.message }
        Unit
    }

    @After
    fun stopServer() = runBlocking {
        app.container.localServerManager.stop()
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

    @Test
    fun serverScreenScreenshots() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithContentDescription("本机服务器").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithContentDescription("本机服务器").onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithText("服务器运行中").fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(2500)
            capture("06_server_running")

            // 滚动到共享文件区
            runCatching {
                composeRule.onNodeWithTag("server_screen_list").performScrollToNode(hasText("共享文件"))
            }
            Thread.sleep(1500)
            capture("07_server_share_files")
        }
    }
}
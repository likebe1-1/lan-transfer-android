package com.manst.lantransfer.e2e

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.JsonServerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 截图巡游测试：用真实服务器数据驱动 UI，逐个界面截图，供 README/交付材料使用。
 *
 * 前置：宿主机已启动 scripts/e2e_servers.py --masquerade 10.0.2.2。
 * 截图输出：/sdcard/Android/data/com.manst.lantransfer.debug/files/screenshots/
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTourTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shotsDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun seedServers() {
        com.manst.lantransfer.core.AppPreferences(context).trustedLanNoticeShown = true
        val storageFile = File(context.filesDir, "servers.json")
        storageFile.delete()
        val repository = JsonServerRepository(storageFile)
        runBlocking {
            repository.save(
                ServerProfile(
                    name = "家用 NAS",
                    protocol = TransferProtocol.FTP,
                    host = "10.0.2.2",
                    port = 2121,
                    username = "test",
                    password = "test"
                )
            )
            repository.save(
                ServerProfile(
                    name = "办公室 FTPS",
                    protocol = TransferProtocol.FTPS,
                    host = "10.0.2.2",
                    port = 2122,
                    username = "test",
                    password = "test"
                )
            )
            repository.save(
                ServerProfile(
                    name = "树莓派 SFTP",
                    protocol = TransferProtocol.SFTP,
                    host = "10.0.2.2",
                    port = 2222,
                    username = "test",
                    password = "test"
                )
            )
            repository.save(
                ServerProfile(
                    name = "群晖 SCP",
                    protocol = TransferProtocol.SCP,
                    host = "10.0.2.2",
                    port = 2222,
                    username = "test",
                    password = "test"
                )
            )
        }
        shotsDir.mkdirs()
    }

    private fun capture(name: String) {
        Thread.sleep(400)
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

    @Test
    fun screenshotTour() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("局域网传输")
            waitForText("家用 NAS")
            capture("01_server_list")

            // 远程浏览
            composeRule.onNodeWithText("家用 NAS").performClick()
            waitForText("docs", timeoutMillis = 40_000)
            capture("02_remote_browser")

            // 子目录
            composeRule.onNodeWithText("docs").performClick()
            waitForText("readme.txt", timeoutMillis = 30_000)
            capture("03_remote_subdirectory")
            composeRule.onNodeWithContentDescription("返回").performClick()
            waitForText("docs", timeoutMillis = 20_000)

            // 下载文件后进入传输队列
            composeRule.onNodeWithText(".e2e-cert.pem").performClick()
            Thread.sleep(2500)
            composeRule.onNodeWithContentDescription("更多操作").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("传输队列").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("传输队列").onFirst().performClick()
            Thread.sleep(2000)
            capture("04_transfer_queue")
        }
    }
}
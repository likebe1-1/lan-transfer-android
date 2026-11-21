package com.manst.lantransfer.e2e

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.AppPreferences
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 站点配图：网络工具（15）与一键组网（16）。
 *
 * 截图输出：/sdcard/Android/data/com.manst.lantransfer.debug/files/screenshots/
 * 与 ScreenshotTourTest / QuickScreenshotTest / ServerScreenshotTest 共用同一目录与命名规则。
 */
@RunWith(AndroidJUnit4::class)
class SiteScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shotsDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun prepare() {
        shotsDir.mkdirs()
        context.deleteFile("servers.json")
        AppPreferences(context).trustedLanNoticeShown = true
    }

    private fun capture(name: String) {
        Thread.sleep(800)
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
    fun captureNetworkTools() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            composeRule.onNodeWithContentDescription("网络工具").performClick()
            waitForText("当前网络")
            // 等本机网络信息填充完成，避免截到「读取中」
            Thread.sleep(2000)
            capture("15_network_tools")
        }
    }

    /** 服务器编辑页（README 引用；站点未使用，但保持截图集一致）。 */
    @Test
    fun captureServerEditor() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            composeRule.onAllNodesWithText("添加服务器").onFirst().performClick()
            waitForText("新增服务器")
            Thread.sleep(900)
            capture("05_server_editor")
        }
    }

    /** 首次启动的可信局域网提示（README 引用）。 */
    @Test
    fun captureTrustedLanNotice() {
        AppPreferences(context).trustedLanNoticeShown = false
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("请在你信任的局域网下使用")
            Thread.sleep(900)
            capture("12_trusted_lan_notice")
        }
        AppPreferences(context).trustedLanNoticeShown = true
    }

    @Test
    fun captureNetworkSetup() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            composeRule.onNodeWithContentDescription("网络工具").performClick()
            waitForText("一键组网（热点）")
            composeRule.onAllNodesWithText("一键组网").onFirst().performClick()
            waitForText("我是发送方（A 开热点）")
            Thread.sleep(1500)
            capture("16_network_setup")
        }
    }
}

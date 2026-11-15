package com.manst.lantransfer.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.AppPreferences
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 桌面快捷方式启动测试：给 MainActivity 传入快捷方式 action，
 * 验证 MainActivity 解析 action -> AppContainer.pendingRoute -> Compose 导航的链路。
 *
 * 说明：快捷方式在桌面长按的实际展示由系统负责，这里只验证启动 Intent 的处理逻辑。
 */
@RunWith(AndroidJUnit4::class)
class ShortcutLaunchTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private val application get() = targetContext.applicationContext as com.manst.lantransfer.LanTransferApplication

    @Before
    fun reset() {
        AppPreferences(targetContext).trustedLanNoticeShown = true
        application.container.pendingRoute.value = null
        targetContext.deleteFile("servers.json")
        targetContext.deleteFile("local_server.json")
    }

    @After
    fun clean() {
        application.container.pendingRoute.value = null
    }

    private fun launchWithShortcut(action: String): ActivityScenario<MainActivity> {
        val intent = Intent(targetContext, MainActivity::class.java).apply {
            this.action = action
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun quickSendShortcut_opensQuickSendScreen() {
        launchWithShortcut(Routes.ACTION_QUICK_SEND).use {
            waitForText("选择要发送的文件")
            composeRule.onNodeWithText("选择要发送的文件").assertIsDisplayed()
        }
    }

    @Test
    fun quickReceiveShortcut_opensQuickReceiveScreen() {
        launchWithShortcut(Routes.ACTION_QUICK_RECEIVE).use {
            waitForText("附近设备")
            composeRule.onNodeWithText("附近设备").assertIsDisplayed()
            composeRule.onNodeWithTag("quick_receive_list")
                .performScrollToNode(hasText("手动输入"))
            composeRule.onNodeWithText("手动输入").assertIsDisplayed()
        }
    }

    @Test
    fun localServerShortcut_opensServerScreen() {
        launchWithShortcut(Routes.ACTION_LOCAL_SERVER).use {
            waitForText("开始服务器")
            composeRule.onNodeWithText("本机服务器").assertIsDisplayed()
            composeRule.onNodeWithText("开始服务器").assertIsDisplayed()
        }
    }
}

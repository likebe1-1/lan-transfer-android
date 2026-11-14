package com.manst.lantransfer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 快捷传输界面仪器冒烟测试：
 * 从服务器列表分别进入「接收文件」「发送文件」页面，断言关键文案与入口按钮。
 *
 * 只做界面呈现断言，不真正开启会话或连接（真实互传由 Lead 在模拟器/多设备环境执行）。
 */
@RunWith(AndroidJUnit4::class)
class QuickScreenSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetPersistedState() {
        targetContext.deleteFile("servers.json")
        targetContext.deleteFile("local_server.json")
        // 快捷传输管理器是进程级单例：清理其他用例可能遗留的会话/连接，保证断言从干净状态开始。
        val application = targetContext.applicationContext as LanTransferApplication
        runCatching { runBlocking { application.container.quickSendManager.stopSession() } }
        runCatching { runBlocking { application.container.quickReceiveManager.leave() } }
        runCatching { application.container.quickReceiveManager.stopDiscovery() }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun receiveScreen_showsDiscoveryAndManualEntry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("局域网传输")

            composeRule.onNodeWithText("接收文件").performClick()
            waitForText("附近设备")

            composeRule.onNodeWithText("接收文件").assertIsDisplayed()
            composeRule.onNodeWithText("附近设备").assertIsDisplayed()

            composeRule.onNodeWithTag("quick_receive_list")
                .performScrollToNode(hasText("手动输入"))
            composeRule.onNodeWithText("手动输入").assertIsDisplayed()

            composeRule.onNodeWithTag("quick_receive_list")
                .performScrollToNode(hasText("对方 IP 地址"))
            composeRule.onNodeWithText("对方 IP 地址").assertIsDisplayed()
        }
    }

    @Test
    fun sendScreen_showsPickFilesEntry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("局域网传输")

            composeRule.onNodeWithText("发送文件").performClick()
            waitForText("选择要发送的文件")

            composeRule.onNodeWithText("快速传文件").assertIsDisplayed()
            composeRule.onNodeWithText("选择要发送的文件").assertIsDisplayed()

            composeRule.onNodeWithTag("quick_send_idle_list")
                .performScrollToNode(hasText("使用步骤"))
            composeRule.onNodeWithText("使用步骤").assertIsDisplayed()
        }
    }
}
package com.manst.lantransfer.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 快捷传输界面仪器冒烟测试：
 * 1. 从服务器列表进入「接收文件」，断言附近设备/手动输入/扫码加入；
 * 2. 进入「发送文件」未开始状态；
 * 3. 通过系统分享入口自动开启发送会话，断言接收码区的「二维码」按钮与二维码 Dialog。
 *
 * 只做界面呈现断言，真实扫码与双设备互传由 Lead 在模拟器/多设备环境执行。
 */
@RunWith(AndroidJUnit4::class)
class QuickScreenSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private val application: LanTransferApplication
        get() = targetContext.applicationContext as LanTransferApplication

    @Before
    fun resetPersistedState() {
        targetContext.deleteFile("servers.json")
        targetContext.deleteFile("local_server.json")
        AppPreferences(targetContext).trustedLanNoticeShown = true
        // 快捷传输管理器是进程级单例：清理其他用例可能遗留的会话/连接/分享入口，保证断言从干净状态开始。
        application.container.shareImports.value = emptyList()
        runCatching { runBlocking { application.container.quickSendManager.stopSession() } }
        runCatching { runBlocking { application.container.quickReceiveManager.leave() } }
        runCatching { application.container.quickReceiveManager.stopDiscovery() }
        runCatching { runBlocking { application.container.localServerManager.stop() } }
        targetContext.getExternalFilesDir(null)?.let { root ->
            File(root, "quick-send").deleteRecursively()
        }
    }

    @After
    fun cleanQuickSession() {
        runCatching { runBlocking { application.container.quickSendManager.stopSession() } }
        runCatching { runBlocking { application.container.localServerManager.stop() } }
        application.container.shareImports.value = emptyList()
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

            composeRule.onNodeWithTag("quick_receive_list")
                .performScrollToNode(hasText("扫码加入"))
            composeRule.onNodeWithText("扫码加入").assertIsDisplayed()
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

    @Test
    fun sendSession_showsQrCodeButtonAndDialog() {
        // 用应用缓存里的小文件模拟「系统分享」：LanTransferApp 会跳转发送页并由 ViewModel 自动开会话
        val sharedFile = File(targetContext.cacheDir, "qr-smoke-" + System.currentTimeMillis() + ".txt")
        sharedFile.writeText("lan-transfer qr smoke")
        application.container.shareImports.value = listOf(Uri.fromFile(sharedFile))

        ActivityScenario.launch(MainActivity::class.java).use {
            // 开启会话需要复制文件并启动四协议服务端，给足超时时间
            waitForText("二维码", timeoutMillis = 40_000)
            composeRule.onNodeWithText("二维码").assertIsDisplayed()
            composeRule.onNodeWithText("二维码").performClick()

            waitForText("让对方扫一扫，自动连接")
            composeRule.onNodeWithContentDescription("快速传文件二维码").assertExists()
            composeRule.onNodeWithText("关闭").performClick()
        }
        application.container.shareImports.value = emptyList()
    }
}

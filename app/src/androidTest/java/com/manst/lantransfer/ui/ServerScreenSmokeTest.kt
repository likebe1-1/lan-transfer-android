package com.manst.lantransfer.ui

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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 本机服务器页仪器冒烟测试：从服务器列表进入「本机服务器」，
 * 验证状态卡、开始按钮、配置区与共享文件区等关键文案。
 *
 * 说明：测试只做界面呈现断言，不真正启动服务器（避免占用端口影响其他用例）；
 * 启动/停止与真实客户端的联调由 Lead 在模拟器上执行。
 */
@RunWith(AndroidJUnit4::class)
class ServerScreenSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetPersistedState() {
        targetContext.deleteFile("servers.json")
        AppPreferences(targetContext).trustedLanNoticeShown = true
        targetContext.deleteFile("local_server.json")
        // 本机服务器是进程级单例：先确保处于停止状态，避免其他用例遗留运行中的服务器影响断言。
        val application = targetContext.applicationContext as LanTransferApplication
        runCatching { runBlocking { application.container.localServerManager.stop() } }
        // 清空共享目录，保证空的共享文件状态可重复断言。
        val shareDirectory = File(targetContext.getExternalFilesDir(null), "share")
        runCatching { shareDirectory.listFiles()?.forEach { file -> file.deleteRecursively() } }
    }

    @Test
    fun openLocalServer_showsCoreSections() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("LanLink").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithContentDescription("本机服务器").performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("服务器已停止").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("本机服务器").assertIsDisplayed()
            composeRule.onNodeWithText("服务器已停止").assertIsDisplayed()
            composeRule.onNodeWithText("开始服务器").assertIsDisplayed()

            composeRule.onNodeWithTag("server_screen_list")
                .performScrollToNode(hasText("服务器设置"))
            composeRule.onNodeWithText("服务器设置").assertIsDisplayed()

            composeRule.onNodeWithTag("server_screen_list")
                .performScrollToNode(hasText("共享文件"))
            composeRule.onNodeWithText("共享文件").assertIsDisplayed()

            composeRule.onNodeWithTag("server_screen_list")
                .performScrollToNode(hasText("还没有共享文件"))
            composeRule.onNodeWithText("还没有共享文件").assertIsDisplayed()
        }
    }
}
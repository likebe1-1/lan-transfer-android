package com.manst.lantransfer.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.AppPreferences
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首次启动提示测试：
 * 1) 首次打开应用时展示「请在你信任的局域网下使用」对话框；
 * 2) 点击「我已知晓」后关闭并持久化，再次启动不再展示。
 */
@RunWith(AndroidJUnit4::class)
class TrustedLanNoticeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetPreferences() {
        AppPreferences(context).trustedLanNoticeShown = false
        context.deleteFile("servers.json")
        context.deleteFile("local_server.json")
    }

    @Test
    fun firstLaunch_showsTrustedLanNotice_andDoesNotShowAgain() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("请在你信任的局域网下使用").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("我已知晓").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("请在你信任的局域网下使用").fetchSemanticsNodes().isEmpty()
            }
            // 对话框关闭后应回到服务器列表
            assertTrue(
                "确认后应展示服务器列表",
                composeRule.onAllNodesWithText("局域网传输").fetchSemanticsNodes().isNotEmpty()
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("局域网传输").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "第二次启动不应再次展示首启提示",
                composeRule.onAllNodesWithText("请在你信任的局域网下使用").fetchSemanticsNodes().isEmpty()
            )
        }
    }
}

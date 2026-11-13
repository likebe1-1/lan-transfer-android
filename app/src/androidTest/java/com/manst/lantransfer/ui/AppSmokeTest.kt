package com.manst.lantransfer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器冒烟测试：启动 [MainActivity]，验证首屏标题、空状态与新增服务器的导航骨架。
 *
 * 说明：使用 createEmptyComposeRule 是因为需要在启动 Activity 之前清除持久化的
 * servers.json，保证首屏一定是空状态（宿主模拟器上也可重复执行）。
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearPersistedServers() {
        targetContext.deleteFile("servers.json")
    }

    @Test
    fun launch_showsTitleAndEmptyState() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("局域网传输").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("局域网传输").assertIsDisplayed()
            composeRule.onNodeWithText("还没有服务器").assertIsDisplayed()
        }
    }

    @Test
    fun addServer_opensEditorScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("还没有服务器").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("添加服务器").onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("新增服务器").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("新增服务器").assertIsDisplayed()
            composeRule.onNodeWithText("连接信息").assertIsDisplayed()
            composeRule.onNodeWithText("测试连接").assertIsDisplayed()
            composeRule.onNodeWithText("保存").assertIsDisplayed()
        }
    }
}
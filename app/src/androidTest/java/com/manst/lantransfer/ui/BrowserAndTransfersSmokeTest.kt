package com.manst.lantransfer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.core.AppPreferences
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 UI 冒烟测试：浏览器页的文件夹下载/上传菜单，以及传输队列页的历史记录入口。
 *
 * 只断言界面元素存在（不依赖真实远端服务端）；真实文件夹传输由 Lead 在模拟器执行。
 */
@RunWith(AndroidJUnit4::class)
class BrowserAndTransfersSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private val application: LanTransferApplication
        get() = targetContext.applicationContext as LanTransferApplication

    @Before
    fun prepare() = runBlocking {
        AppPreferences(targetContext).trustedLanNoticeShown = true
        targetContext.deleteFile("servers.json")
        val repository = application.container.serverRepository
        repository.profiles.value.forEach { profile ->
            runCatching { repository.delete(profile.id) }
        }
        repository.save(
            ServerProfile(
                name = "冒烟测试服务器",
                protocol = TransferProtocol.FTP,
                host = "127.0.0.1",
                port = 2121,
                username = "test",
                password = "test"
            )
        )
        Unit
    }

    @After
    fun cleanProfiles() = runBlocking {
        // 清掉本用例写入的服务器配置，避免影响其他 UI 用例「空状态」断言
        val repository = application.container.serverRepository
        repository.profiles.value.forEach { profile ->
            runCatching { repository.delete(profile.id) }
        }
        targetContext.deleteFile("servers.json")
        Unit
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDescription(description: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun browserScreen_showsFolderDownloadAndUploadMenu() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            waitForText("冒烟测试服务器")
            composeRule.onNodeWithText("冒烟测试服务器").performClick()

            // 顶栏「下载当前文件夹」与「上传」下拉菜单（远端连接失败不影响顶栏入口存在）
            waitForDescription("下载当前文件夹")
            composeRule.onNodeWithContentDescription("下载当前文件夹").assertExists()

            composeRule.onNodeWithContentDescription("上传").performClick()
            waitForText("上传文件夹")
            composeRule.onNodeWithText("上传文件夹").assertIsDisplayed()
            composeRule.onNodeWithText("上传文件").assertIsDisplayed()
        }
    }

    @Test
    fun transfersScreen_showsHistoryEntry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            composeRule.onNodeWithContentDescription("传输队列").performClick()
            waitForText("传输队列")
            composeRule.onNodeWithText("传输队列").assertIsDisplayed()
            // 顶部「清空历史」入口（无历史时禁用，但入口始终存在）
            composeRule.onNodeWithContentDescription("清空历史").assertExists()
        }
    }
}
package com.manst.lantransfer.ui

import androidx.compose.ui.test.assertIsDisplayed
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
import com.manst.lantransfer.core.AppPreferences
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 一键组网页冒烟测试：首页 → 网络工具 → 一键组网，验证核心区块。 */
@RunWith(AndroidJUnit4::class)
class NetworkSetupSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        targetContext.deleteFile("servers.json")
        AppPreferences(targetContext).trustedLanNoticeShown = true
    }

    private fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun openNetworkSetup_showsSenderAndReceiverSections() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("LanLink")
            composeRule.onNodeWithContentDescription("网络工具").performClick()
            waitForText("一键组网（热点）")
            composeRule.onAllNodesWithText("一键组网").onFirst().performClick()
            waitForText("我是发送方（A 开热点）")
            composeRule.onNodeWithText("使用步骤").assertIsDisplayed()
            composeRule.onNodeWithText("我是接收方（B 扫码加入）").assertIsDisplayed()
            composeRule.onNodeWithText("扫描 Wi-Fi 二维码").assertIsDisplayed()
        }
    }
}

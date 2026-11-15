package com.manst.lantransfer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.manst.lantransfer.update.UpdateApk
import com.manst.lantransfer.update.UpdateInfo
import com.manst.lantransfer.update.UpdateManifest
import com.manst.lantransfer.update.UpdateUiState
import com.manst.lantransfer.ui.update.UpdateDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 更新对话框的界面冒烟测试（不依赖网络）。 */
@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val info = UpdateInfo(
        manifest = UpdateManifest(
            versionCode = 11,
            versionName = "2.2.0",
            changelog = "修复若干问题，优化传输稳定性",
            apks = emptyList(),
            releasePageUrl = "https://github.com/likebe1-1/lan-transfer-android/releases",
            lanzouUrl = "https://wwbds.lanzoum.com/b0139vopuf",
            lanzouPassword = "1234"
        ),
        apk = UpdateApk(
            abi = "arm64-v8a",
            size = 100L,
            sha256 = "a".repeat(64),
            urls = listOf("https://example.com/app.apk")
        )
    )

    @Test
    fun availableDialog_showsVersionChangelogAndActions() {
        composeRule.setContent {
            MaterialTheme {
                UpdateDialog(
                    state = UpdateUiState(info = info),
                    onDownload = {},
                    onLanzou = {},
                    onDismiss = {},
                    onRetry = {}
                )
            }
        }
        composeRule.onNodeWithText("发现新版本 2.2.0").assertIsDisplayed()
        composeRule.onNodeWithText("修复若干问题，优化传输稳定性").assertIsDisplayed()
        composeRule.onNodeWithText("下载并安装").assertIsDisplayed()
        composeRule.onNodeWithText("蓝奏云下载").assertIsDisplayed()
        composeRule.onNodeWithText("稍后").assertIsDisplayed()
        composeRule.onAllNodesWithText("1234", substring = true).fetchSemanticsNodes()
            .isNotEmpty()
    }

    @Test
    fun downloadingDialog_showsProgress() {
        composeRule.setContent {
            MaterialTheme {
                UpdateDialog(
                    state = UpdateUiState(info = info, downloading = true, progress = 0.5f, downloadedBytes = 50L),
                    onDownload = {},
                    onLanzou = {},
                    onDismiss = {},
                    onRetry = {}
                )
            }
        }
        composeRule.onNodeWithText("正在下载更新").assertIsDisplayed()
        composeRule.onAllNodesWithText("50%", substring = true).fetchSemanticsNodes()
            .isNotEmpty()
    }

    @Test
    fun errorDialog_showsRetryAndLanzouFallback() {
        composeRule.setContent {
            MaterialTheme {
                UpdateDialog(
                    state = UpdateUiState(error = "下载失败，请检查网络后重试"),
                    onDownload = {},
                    onLanzou = {},
                    onDismiss = {},
                    onRetry = {}
                )
            }
        }
        composeRule.onNodeWithText("检查更新失败").assertIsDisplayed()
        composeRule.onNodeWithText("下载失败，请检查网络后重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithText("蓝奏云下载").assertIsDisplayed()
    }
}

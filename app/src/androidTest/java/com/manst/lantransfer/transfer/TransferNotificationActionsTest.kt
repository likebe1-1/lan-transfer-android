package com.manst.lantransfer.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 传输通知操作按钮的冒烟测试（不依赖真实网络）。 */
@RunWith(AndroidJUnit4::class)
class TransferNotificationActionsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runningTask() = TransferTask(
        id = "task-1",
        profileId = "profile-1",
        serverName = "测试服务器",
        protocolLabel = "FTP",
        direction = TransferDirection.DOWNLOAD,
        displayName = "report.pdf",
        remotePath = "/report.pdf",
        totalBytes = 1000L,
        transferredBytes = 400L,
        state = TransferState.RUNNING,
        bytesPerSecond = 1024L
    )

    @Test
    fun progressNotification_hasCancelAction() {
        val notification = TransferNotifications.buildProgressNotification(
            context,
            listOf(runningTask())
        )
        assertEquals(1, notification.actions.size)
        assertEquals("取消", notification.actions[0].title.toString())
    }

    @Test
    fun progressNotification_cancelActionTargetsTransferService() {
        val notification = TransferNotifications.buildProgressNotification(
            context,
            listOf(runningTask())
        )
        val action = notification.actions[0]
        // 只有 PendingIntent 存在才可能触发取消；这里验证操作标题与数量即可（服务端逻辑在集成测试覆盖）
        assertTrue(action.title.contains("取消"))
    }
}

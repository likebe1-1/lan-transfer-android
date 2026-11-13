package com.manst.lantransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferQueueModelTest {

    private fun task(id: String, state: TransferState = TransferState.QUEUED) = TransferTask(
        id = id,
        profileId = "profile-1",
        serverName = "测试服务器",
        protocolLabel = "SFTP",
        direction = TransferDirection.DOWNLOAD,
        displayName = "file-" + id + ".txt",
        remotePath = "/file-" + id + ".txt",
        totalBytes = 100L,
        state = state
    )

    @Test
    fun `按入队顺序快照`() {
        val model = TransferQueueModel()
        model.add(task("a"))
        model.add(task("b"))
        model.add(task("c"))
        assertEquals(listOf("a", "b", "c"), model.snapshot().map { it.id })
        assertEquals("a", model.nextQueued()?.id)
    }

    @Test
    fun `nextQueued 跳过已开始与已结束任务`() {
        val model = TransferQueueModel()
        model.add(task("a", TransferState.RUNNING))
        model.add(task("b", TransferState.COMPLETED))
        model.add(task("c", TransferState.QUEUED))
        model.add(task("d", TransferState.QUEUED))
        assertEquals("c", model.nextQueued()?.id)
        assertNull(model.get("missing"))
    }

    @Test
    fun `状态机依次流转并挑选下一条`() {
        val model = TransferQueueModel()
        model.add(task("1"))
        model.add(task("2"))

        val running = model.update("1") {
            it.copy(state = TransferState.RUNNING, startedAt = 10L)
        }
        assertEquals(TransferState.RUNNING, running?.state)
        // 任务 1 运行中 + 任务 2 排队中，都是“活动任务”
        assertEquals(2, model.countActive())
        assertTrue(model.hasActive())
        assertEquals("2", model.nextQueued()?.id)

        model.update("1") {
            it.copy(state = TransferState.COMPLETED, finishedAt = 20L, transferredBytes = 100L, totalBytes = 100L)
        }
        assertEquals("2", model.nextQueued()?.id)
        assertEquals(1, model.countActive())

        model.update("2") { it.copy(state = TransferState.FAILED, error = "连接超时") }
        assertFalse(model.hasActive())
        assertEquals(0, model.countActive())
        assertEquals("连接超时", model.get("2")?.error)
        assertEquals(
            listOf(TransferState.COMPLETED, TransferState.FAILED),
            model.snapshot().map { it.state }
        )
    }

    @Test
    fun `更新不存在的任务返回 null`() {
        val model = TransferQueueModel()
        assertNull(model.update("missing") { it.copy(state = TransferState.RUNNING) })
    }

    @Test
    fun `移除任务`() {
        val model = TransferQueueModel()
        model.add(task("a"))
        model.add(task("b"))
        assertEquals("a", model.remove("a")?.id)
        assertNull(model.remove("a"))
        assertEquals(listOf("b"), model.snapshot().map { it.id })
    }

    @Test
    fun `清空已完成保留排队中任务`() {
        val model = TransferQueueModel()
        model.add(task("done", TransferState.COMPLETED))
        model.add(task("failed", TransferState.FAILED))
        model.add(task("cancelled", TransferState.CANCELLED))
        model.add(task("queued", TransferState.QUEUED))
        model.add(task("running", TransferState.RUNNING))

        val removed = model.clearFinished().map { it.id }
        assertEquals(listOf("done", "failed", "cancelled"), removed)
        assertEquals(listOf("queued", "running"), model.snapshot().map { it.id })
    }

    @Test
    fun `取消排队任务后可继续取队首`() {
        val model = TransferQueueModel()
        model.add(task("1"))
        model.add(task("2"))
        model.update("1") { it.copy(state = TransferState.CANCELLED, finishedAt = 5L) }
        assertEquals("2", model.nextQueued()?.id)
        assertEquals(1, model.countActive())
        assertTrue(model.get("1")?.isFinished == true)
    }
}

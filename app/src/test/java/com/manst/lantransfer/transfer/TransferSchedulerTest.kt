package com.manst.lantransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 传输队列「有限并发」调度的单元测试。
 *
 * 覆盖面：
 * - 并发上限（默认 3）生效，且按入队顺序补满槽位；
 * - 同一个 taskId 不会被重复启动（[TransferQueueModel.beginAttempt] 原子抢占 + 已占用集合排除）；
 * - 失败重试退避期间任务不被其它 worker 提前取出，退避结束后可再次执行；
 * - 取消（排队中 / 执行中 / 退避期间）后不再被调度；
 * - activeCount（QUEUED + RUNNING）与前台服务启停判断的一致性。
 *
 * [Simulation] 复刻 [DefaultTransferManager.runQueueLoop] 的调度决策：
 * startableTaskIds 选候选 -> beginAttempt 原子抢占 -> 任务结束后释放槽位。
 */
class TransferSchedulerTest {

    @Test
    fun `默认并发上限为 3`() {
        assertEquals(3, TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS)
        assertEquals(3, TransferScheduler().maxConcurrency)
    }

    @Test
    fun `并发上限生效 - 按入队顺序补满剩余槽位`() {
        val scheduler = TransferScheduler(3)
        val tasks = (1..10).map { testTask("t" + it) }

        assertEquals(listOf("t1", "t2", "t3"), scheduler.startableTaskIds(tasks, emptySet()))
        // 已经占用了 1 个槽位，本轮只能再启动 2 个（并跳过已占用的 t1）
        assertEquals(listOf("t2", "t3"), scheduler.startableTaskIds(tasks, setOf("t1")))
        // 槽位已满
        assertEquals(emptyList<String>(), scheduler.startableTaskIds(tasks, setOf("t1", "t2", "t3")))
        assertFalse(scheduler.hasSpareSlot(3))
        assertTrue(scheduler.hasSpareSlot(2))
    }

    @Test
    fun `并发上限为 1 时退化为串行`() {
        val scheduler = TransferScheduler(1)
        val tasks = (1..5).map { testTask("t" + it) }
        assertEquals(listOf("t1"), scheduler.startableTaskIds(tasks, emptySet()))
        assertEquals(emptyList<String>(), scheduler.startableTaskIds(tasks, setOf("t1")))
    }

    @Test
    fun `只挑选排队中的任务`() {
        val scheduler = TransferScheduler(3)
        val tasks = listOf(
            testTask("running", TransferState.RUNNING),
            testTask("done", TransferState.COMPLETED),
            testTask("failed", TransferState.FAILED),
            testTask("cancelled", TransferState.CANCELLED),
            testTask("queued-1"),
            testTask("queued-2")
        )
        assertEquals(listOf("queued-1", "queued-2"), scheduler.startableTaskIds(tasks, emptySet()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `并发上限非法时拒绝构造`() {
        TransferScheduler(0)
    }

    // ------------------------------------------------------------ 不重复启动

    @Test
    fun `同一个任务第二次抢占返回 null`() {
        val model = TransferQueueModel()
        model.add(testTask("only"))
        val first = model.beginAttempt("only")
        assertEquals(TransferState.RUNNING, first?.state)
        // 再次抢占：任务已是 RUNNING，不允许第二次启动
        assertNull(model.beginAttempt("only"))
        assertNull(model.beginAttempt("missing"))
    }

    @Test
    fun `并发抢占同一个任务时只有一个执行体`() {
        val model = TransferQueueModel()
        model.add(testTask("only"))
        val lock = Any()
        val started = AtomicInteger(0)
        val threads = (1..8).map {
            Thread {
                repeat(200) {
                    // 管理器约定：队列模型的读写在 stateLock 内完成
                    synchronized(lock) {
                        if (model.beginAttempt("only") != null) started.incrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, started.get())
    }

    @Test
    fun `重复调度不会重复启动同一任务`() {
        val simulation = Simulation(ids = (1..10).map { "t" + it })
        assertEquals(listOf("t1", "t2", "t3"), simulation.fill())
        // 槽位已满，反复调度也不会有新任务启动
        repeat(5) { assertEquals(emptyList<String>(), simulation.fill()) }
        assertEquals(3, simulation.running.size)
        assertEquals(mapOf("t1" to 1, "t2" to 1, "t3" to 1), simulation.startCounts)
    }

    @Test
    fun `任务结束后按入队顺序补位`() {
        val simulation = Simulation(ids = (1..6).map { "t" + it })
        simulation.fill()
        simulation.complete("t2")
        assertEquals(listOf("t4"), simulation.fill())
        simulation.complete("t1")
        simulation.complete("t3")
        assertEquals(listOf("t5", "t6"), simulation.fill())
        assertEquals(emptyList<String>(), simulation.fill())
        // 每个任务最多启动一次
        assertTrue(simulation.startCounts.values.all { it == 1 })
        assertEquals(6, simulation.startCounts.size)
    }

    // ------------------------------------------------------------ 取消 / 重试

    @Test
    fun `排队中被取消的任务不会被启动`() {
        val simulation = Simulation(ids = listOf("t1", "t2", "t3", "t4", "t5"))
        simulation.cancelQueued("t2")
        assertEquals(listOf("t1", "t3", "t4"), simulation.fill())
        assertNull(simulation.startCounts["t2"])
        assertFalse(simulation.running.contains("t2"))
    }

    @Test
    fun `执行中被取消的任务释放槽位且不再重试`() {
        val simulation = Simulation(ids = listOf("t1", "t2", "t3", "t4"))
        simulation.fill()
        // 用户取消正在执行的任务：任务转为 CANCELLED，执行体退出并释放槽位
        simulation.cancel("t2")
        assertEquals(listOf("t4"), simulation.fill())
        // 反复调度，被取消的任务不会重新启动
        repeat(3) { simulation.fill() }
        assertEquals(1, simulation.startCounts["t2"])
        assertEquals(TransferState.CANCELLED, simulation.model.get("t2")?.state)
    }

    @Test
    fun `失败重试 - 退避期间任务不被其它 worker 提前取出`() {
        val simulation = Simulation(ids = listOf("t1"))
        assertEquals(listOf("t1"), simulation.fill())
        // t1 失败并进入自动重试：状态回到 QUEUED，但执行体仍占用槽位等待退避
        simulation.failWithRetry("t1")
        assertEquals(TransferState.QUEUED, simulation.model.get("t1")?.state)
        assertEquals(1, simulation.model.get("t1")?.attempts)
        // 退避窗口内即使还有空余槽位，也不能把 t1 再取出来（否则退避失效）；空余槽位给新任务
        simulation.addQueuedTask("t2")
        assertEquals(listOf("t2"), simulation.fill())
        assertEquals(1, simulation.startCounts["t1"])
        // 退避结束、执行体退出后，t1 才能被重新调度
        simulation.releaseAfterBackoff("t1")
        assertEquals(listOf("t1"), simulation.fill())
        assertEquals(2, simulation.startCounts["t1"])
    }

    @Test
    fun `失败重试 - 退避期间被取消后不再重试`() {
        val simulation = Simulation(ids = listOf("t1"))
        simulation.fill()
        simulation.failWithRetry("t1")
        // 失败处理期间用户取消：既有的「取消后不重试」逻辑
        simulation.cancel("t1")
        simulation.releaseAfterBackoff("t1")
        assertEquals(emptyList<String>(), simulation.fill())
        repeat(3) { simulation.fill() }
        assertEquals(1, simulation.startCounts["t1"])
        assertEquals(TransferState.CANCELLED, simulation.model.get("t1")?.state)
    }

    @Test
    fun `失败重试 - 最终失败后不再被调度`() {
        val simulation = Simulation(ids = listOf("t1"))
        simulation.fill()
        simulation.model.update("t1") { it.copy(state = TransferState.FAILED, error = "已自动重试 3 次仍失败") }
        simulation.releaseAfterBackoff("t1")
        assertEquals(emptyList<String>(), simulation.fill())
    }

    // ------------------------------------------------------------ activeCount / 前台服务

    @Test
    fun `activeCount 在并发执行时等于排队中加执行中的任务数`() {
        val simulation = Simulation(ids = (1..6).map { "t" + it })
        // 只有排队中任务
        assertEquals(6, simulation.model.countActive())
        simulation.fill()
        // 3 个执行中 + 3 个排队中
        assertEquals(3, simulation.running.size)
        assertEquals(6, simulation.model.snapshot().count { it.isActive })
        assertEquals(6, simulation.model.countActive())
        // 执行中的任务结束、排队中的任务补位：活动任务总数减一
        simulation.complete("t1")
        simulation.fill()
        assertEquals(3, simulation.running.size)
        assertEquals(5, simulation.model.countActive())
        // 执行中的任务全部结束后，只剩下排队中的任务
        simulation.running.toList().forEach { simulation.complete(it) }
        assertEquals(2, simulation.model.countActive())
        assertEquals(listOf("t5", "t6"), simulation.snapshotQueued)
        simulation.snapshotQueued.forEach { simulation.cancelQueued(it) }
        assertEquals(0, simulation.model.countActive())
        assertFalse(simulation.model.hasActive())
    }

    @Test
    fun `前台服务仅在活动任务数由 0 变 1 时启动`() {
        val scheduler = TransferScheduler()
        // 入队第一个任务：活动数 1 -> 启动服务
        assertTrue(scheduler.shouldStartForegroundService(1))
        // 并发下第二个及后续任务入队时服务已经在运行，不重复启动
        assertFalse(scheduler.shouldStartForegroundService(2))
        assertFalse(scheduler.shouldStartForegroundService(3))
        assertFalse(scheduler.shouldStartForegroundService(0))

        // 与停止条件配对：全部结束后可以停止服务，再次入队又能重新启动
        val model = TransferQueueModel()
        model.add(testTask("t1"))
        assertTrue(scheduler.shouldStartForegroundService(model.countActive()))
        model.beginAttempt("t1")
        model.update("t1") { it.copy(state = TransferState.COMPLETED) }
        assertFalse(model.hasActive())
        model.add(testTask("t2"))
        assertTrue(scheduler.shouldStartForegroundService(model.countActive()))
    }

    // ------------------------------------------------------------ 辅助

    /** 复刻 DefaultTransferManager.runQueueLoop 的调度决策（不涉及 Android 依赖）。 */
    private class Simulation(
        val concurrency: Int = TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS,
        ids: List<String>
    ) {
        val model = TransferQueueModel()
        val scheduler = TransferScheduler(concurrency)

        /** 当前被 worker 占用的任务（含退避等待中的任务）。 */
        val running = LinkedHashSet<String>()
        val startCounts = HashMap<String, Int>()

        init {
            ids.forEach { model.add(testTask(it)) }
        }

        /** 启动本轮可执行的任务，返回真正开始执行的任务 id。 */
        fun fill(): List<String> {
            val started = ArrayList<String>()
            for (id in scheduler.startableTaskIds(model.snapshot(), running)) {
                if (model.beginAttempt(id) == null) continue
                startCounts[id] = (startCounts[id] ?: 0) + 1
                running.add(id)
                started.add(id)
            }
            assertTrue("并发上限被突破：" + running, running.size <= concurrency)
            return started
        }

        fun addQueuedTask(id: String) {
            model.add(testTask(id))
        }

        fun complete(id: String) {
            model.update(id) { it.copy(state = TransferState.COMPLETED, finishedAt = 1L) }
            running.remove(id)
        }

        /** 排队中的任务被取消（管理器在 cancel 中直接改为 CANCELLED）。 */
        fun cancelQueued(id: String) {
            model.update(id) { it.copy(state = TransferState.CANCELLED, finishedAt = 1L) }
        }

        /** 执行中的任务被取消：执行体最终进入 CANCELLED 并释放槽位。 */
        fun cancel(id: String) {
            model.update(id) { it.copy(state = TransferState.CANCELLED, finishedAt = 1L) }
            running.remove(id)
        }

        /** 失败且可重试：handleTaskFailure 把状态重置为 QUEUED，但执行体仍占着槽位等待退避。 */
        fun failWithRetry(id: String) {
            model.update(id) {
                it.copy(
                    state = TransferState.QUEUED,
                    attempts = it.attempts + 1,
                    error = "连接超时（2 秒后自动重试）",
                    startedAt = 0L
                )
            }
        }

        /** 退避结束、执行体退出，槽位释放。 */
        fun releaseAfterBackoff(id: String) {
            running.remove(id)
        }

        val snapshotQueued: List<String>
            get() = model.snapshot().filter { it.state == TransferState.QUEUED }.map { it.id }
    }
}

private fun testTask(id: String, state: TransferState = TransferState.QUEUED): TransferTask = TransferTask(
    id = id,
    profileId = "profile-1",
    serverName = "测试服务器",
    protocolLabel = "SFTP",
    direction = TransferDirection.DOWNLOAD,
    displayName = id + ".txt",
    remotePath = "/" + id + ".txt",
    totalBytes = 100L,
    state = state
)

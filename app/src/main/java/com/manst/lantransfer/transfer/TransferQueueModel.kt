package com.manst.lantransfer.transfer

/**
 * 传输队列状态机（纯 JVM 逻辑，可单元测试）。
 *
 * 维护任务的插入顺序（LinkedHashMap）；并发 worker 先用 [TransferScheduler.startableTaskIds]
 * 挑选候选任务，再由 [beginAttempt] 在持有状态锁时原子地把任务从 QUEUED 切换为 RUNNING，
 * 这是「同一个任务不会被启动两次」的最终保证。
 * 任务完成后保留在列表中供 UI 展示，由 [remove] / [clearFinished] 清理。
 * 本类不做线程同步，由 [DefaultTransferManager] 在持有状态锁时调用。
 */
internal class TransferQueueModel {

    private val ordered = LinkedHashMap<String, TransferTask>()

    fun snapshot(): List<TransferTask> = ordered.values.toList()

    fun get(id: String): TransferTask? = ordered[id]

    fun add(task: TransferTask) {
        ordered[task.id] = task
    }

    /** 更新任务快照，任务不存在时返回 null。 */
    fun update(id: String, transform: (TransferTask) -> TransferTask): TransferTask? {
        val current = ordered[id] ?: return null
        val updated = transform(current)
        ordered[id] = updated
        return updated
    }

    fun remove(id: String): TransferTask? = ordered.remove(id)

    /**
     * 队首处于 QUEUED 的任务（按入队顺序）。
     * 串行场景仍可使用；有限并发调度请用 [TransferScheduler.startableTaskIds]。
     */
    fun nextQueued(): TransferTask? = ordered.values.firstOrNull { it.state == TransferState.QUEUED }

    /**
     * 原子地开始执行 [id]：仅当任务存在且**仍处于 QUEUED** 时切换为 RUNNING 并返回切换后的快照；
     * 否则返回 null（任务已被其它 worker 取走、已取消或已结束）。
     *
     * 并发执行时，候选任务的挑选与「真正开始」之间可能被其它协程插入（例如用户取消），
     * 因此必须以本方法的返回值作为是否真正开始执行的唯一依据。
     */
    fun beginAttempt(id: String, startedAt: Long = System.currentTimeMillis()): TransferTask? {
        val current = ordered[id] ?: return null
        if (current.state != TransferState.QUEUED) return null
        val running = current.copy(
            state = TransferState.RUNNING,
            startedAt = startedAt,
            bytesPerSecond = 0L,
            error = null
        )
        ordered[id] = running
        return running
    }

    fun countActive(): Int = ordered.values.count { it.isActive }

    fun hasActive(): Boolean = ordered.values.any { it.isActive }

    /** 移除所有已完成/失败/取消的任务并返回被移除的快照。 */
    fun clearFinished(): List<TransferTask> {
        val removed = ordered.values.filter { it.isFinished }
        removed.forEach { ordered.remove(it.id) }
        return removed
    }
}

/**
 * 有限并发调度决策（纯 JVM 逻辑，可单元测试）。
 *
 * 与改造前的串行 worker 相比，这里只做「还能不能再启动一个任务 / 下一个该启动谁」的判断，
 * 真正的连接与数据传输仍由 [DefaultTransferManager] 中每个任务各自的协程完成：
 * - 每个任务使用自己的 [com.manst.lantransfer.protocol.RemoteFileSystem] 连接，绝不共享；
 * - 并发上限默认为 [DEFAULT_MAX_CONCURRENT_TASKS]，同时按入队顺序优先，保持用户可见的先后感；
 * - [startableTaskIds] 会排除「已被 worker 占用」的任务（其中也包括正在失败退避中的任务），
 *   避免同一任务被重复启动，或在退避窗口内被其它 worker 提前重新取出而绕过退避。
 */
internal class TransferScheduler(
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENT_TASKS
) {

    init {
        require(maxConcurrency >= 1) { "传输并发上限必须大于 0，当前为 " + maxConcurrency }
    }

    /** 当前并发槽位是否还有空余。 */
    fun hasSpareSlot(runningCount: Int): Boolean = runningCount < maxConcurrency

    /**
     * 计算本轮可以启动的任务 id：按入队顺序，最多补满剩余的并发槽位。
     *
     * [runningTaskIds] 是当前已经被 worker 占用（含失败退避等待中）的任务 id，必须排除；
     * 返回的 id 互不重复，调用方逐个启动即可。
     */
    fun startableTaskIds(tasks: List<TransferTask>, runningTaskIds: Set<String>): List<String> {
        val capacity = maxConcurrency - runningTaskIds.size
        if (capacity <= 0) return emptyList()
        val startable = ArrayList<String>(minOf(capacity, tasks.size))
        for (task in tasks) {
            if (task.state != TransferState.QUEUED) continue
            if (task.id in runningTaskIds) continue
            startable.add(task.id)
            if (startable.size >= capacity) break
        }
        return startable
    }

    /**
     * 入队后是否需要启动前台服务：只有活动任务数由 0 变为 1（即第一个活动任务）时才需要。
     *
     * 与 [TransferQueueModel.hasActive] 的停止条件严格配对：任务全部结束（含取消/失败）后
     * [DefaultTransferManager.stopForegroundIfIdle] 才会停止服务，因此这里不能用「== 0」之类的判断，
     * 否则并发下第二个任务入队时会重复 startForegroundService。
     */
    fun shouldStartForegroundService(activeCountAfterEnqueue: Int): Boolean =
        activeCountAfterEnqueue == 1

    companion object {
        /**
         * 默认并发上限：3 路。
         *
         * 大量小文件传输时，优化的是「连接/认证/断开」这类固定开销的并行度；
         * 3 路既能明显减少串行等待，又不至于打满服务端的连接数上限（FTP 常见限制为 3~10 个会话）。
         */
        const val DEFAULT_MAX_CONCURRENT_TASKS = 3
    }
}

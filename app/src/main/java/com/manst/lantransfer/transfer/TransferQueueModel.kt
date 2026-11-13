package com.manst.lantransfer.transfer

/**
 * 传输队列状态机（纯 JVM 逻辑，可单元测试）。
 *
 * 维护任务的插入顺序（LinkedHashMap），串行 worker 通过 [nextQueued] 取队首待执行任务；
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

    /** 队首处于 QUEUED 的任务（按入队顺序）。 */
    fun nextQueued(): TransferTask? = ordered.values.firstOrNull { it.state == TransferState.QUEUED }

    fun countActive(): Int = ordered.values.count { it.isActive }

    fun hasActive(): Boolean = ordered.values.any { it.isActive }

    /** 移除所有已完成/失败/取消的任务并返回被移除的快照。 */
    fun clearFinished(): List<TransferTask> {
        val removed = ordered.values.filter { it.isFinished }
        removed.forEach { ordered.remove(it.id) }
        return removed
    }
}

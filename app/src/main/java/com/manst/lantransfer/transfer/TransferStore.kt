package com.manst.lantransfer.transfer

/**
 * 传输任务持久化（队列恢复 + 历史记录）。
 *
 * 实现要求：
 * - 原子写入（临时文件 + rename），损坏时回退为空列表而不崩溃；
 * - 保存任务的快照（含 profileSnapshot、已传字节、staging 路径信息由引擎自行管理）；
 * - 历史记录上限建议 200 条，超出丢弃最旧记录。
 */
interface TransferStore {
    fun load(): List<TransferTask>

    fun save(tasks: List<TransferTask>)

    fun clear()
}

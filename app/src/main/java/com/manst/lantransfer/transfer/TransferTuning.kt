package com.manst.lantransfer.transfer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 传输并发调优配置（纯 JVM 逻辑，可单元测试）。
 *
 * [maxConcurrentTasks] 是同时处于 RUNNING 的传输任务上限，默认 [DEFAULT]（5 路）：
 * - 每个任务独占一条远端连接，路数越大，小文件越多时越能摊薄 connect / 认证 / 断开的固定开销；
 * - 但最多只能开到服务端允许的会话数（FTP 常见限制为 3~10 个），超过后新连接会被拒绝，
 *   因此把可调范围收敛到 [MIN_MAX_CONCURRENT_TASKS]–[MAX_MAX_CONCURRENT_TASKS]，非法值一律回落默认。
 */
data class TransferTuning(
    /** 并发传输路数，合法范围 [MIN_MAX_CONCURRENT_TASKS]–[MAX_MAX_CONCURRENT_TASKS]。 */
    val maxConcurrentTasks: Int = DEFAULT
) {

    init {
        require(maxConcurrentTasks in VALID_RANGE) {
            "并发传输路数必须在 " + MIN_MAX_CONCURRENT_TASKS + "–" + MAX_MAX_CONCURRENT_TASKS +
                " 之间，当前为 " + maxConcurrentTasks
        }
    }

    /** 是否仍为默认并发路数。 */
    val isDefault: Boolean
        get() = maxConcurrentTasks == DEFAULT

    companion object {
        /** 默认并发路数：5 路。 */
        const val DEFAULT = 5

        /** 默认并发路数的语义别名（与 [TransferScheduler.DEFAULT_MAX_CONCURRENT_TASKS] 保持一致）。 */
        const val DEFAULT_MAX_CONCURRENT_TASKS = DEFAULT

        /** 最小并发路数：1 路（退化为串行）。 */
        const val MIN_MAX_CONCURRENT_TASKS = 1

        /** 最大并发路数：6 路（保守上限，避免打满服务端连接数）。 */
        const val MAX_MAX_CONCURRENT_TASKS = 6

        /** 合法范围 [MIN_MAX_CONCURRENT_TASKS]–[MAX_MAX_CONCURRENT_TASKS]。 */
        val VALID_RANGE: IntRange = MIN_MAX_CONCURRENT_TASKS..MAX_MAX_CONCURRENT_TASKS

        /** 原始值是否在合法范围内。 */
        fun isValid(maxConcurrentTasks: Int): Boolean = maxConcurrentTasks in VALID_RANGE

        /** 把任意原始值规范化为合法值：非法值（0、7、负数等）回落默认 [DEFAULT]。 */
        fun sanitize(maxConcurrentTasks: Int): Int =
            if (isValid(maxConcurrentTasks)) maxConcurrentTasks else DEFAULT

        /** 从原始值构造配置，非法值回落默认。 */
        fun fromRaw(maxConcurrentTasks: Int): TransferTuning =
            TransferTuning(sanitize(maxConcurrentTasks))
    }
}

/**
 * [TransferTuning] 的 SharedPreferences 持久化与运行时读取。
 *
 * 默认值在读取时校验：仓库里存的旧值/被篡改的值（0、7、负数等）都会回落 [TransferTuning.DEFAULT]，
 * 不会把非法值传给 [TransferScheduler] 导致构造失败。
 */
class TransferTuningStore(
    private val preferences: SharedPreferences,
    defaultMaxConcurrentTasks: Int = TransferTuning.DEFAULT
) {

    private val defaultTuning: TransferTuning = TransferTuning.fromRaw(defaultMaxConcurrentTasks)

    private val _tuning = MutableStateFlow(readTuning())

    /** 当前配置；可在 Compose / ViewModel 中收集以响应设置变化。 */
    val tuning: StateFlow<TransferTuning> = _tuning.asStateFlow()

    /** 当前并发路数（便捷读取，等价于 [tuning] 的 value）。 */
    val maxConcurrentTasks: Int
        get() = _tuning.value.maxConcurrentTasks

    /** 读取当前配置。 */
    fun current(): TransferTuning = _tuning.value

    /**
     * 更新并发路数并持久化；非法值（0、7、负数等）回落默认 5。
     * 已保存的值立即生效（默认实现下的调度器在下次创建时读取）。
     */
    fun setMaxConcurrentTasks(value: Int) {
        val next = TransferTuning.fromRaw(value)
        preferences.edit()
            .putInt(KEY_MAX_CONCURRENT_TASKS, next.maxConcurrentTasks)
            .apply()
        _tuning.value = next
    }

    /** 重新从 SharedPreferences 读取（外部修改后同步）。 */
    fun reload() {
        _tuning.value = readTuning()
    }

    private fun readTuning(): TransferTuning {
        val raw = preferences.getInt(KEY_MAX_CONCURRENT_TASKS, defaultTuning.maxConcurrentTasks)
        return TransferTuning.fromRaw(raw)
    }

    companion object {
        /** SharedPreferences 文件名。 */
        const val PREFERENCES_NAME = "transfer_tuning"

        /** 并发路数存储 key。 */
        const val KEY_MAX_CONCURRENT_TASKS = "transfer_max_concurrent"

        /** 从应用 Context 创建（使用应用级 SharedPreferences，整个进程共享）。 */
        fun fromContext(context: Context): TransferTuningStore = TransferTuningStore(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    }
}

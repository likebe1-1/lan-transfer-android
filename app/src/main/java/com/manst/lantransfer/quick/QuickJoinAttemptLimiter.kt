package com.manst.lantransfer.quick

import java.util.Locale

/**
 * 快速传文件接收端的接收码错误次数限制器（纯 JVM，时间可注入，便于单元测试）。
 *
 * 规则：
 * - 按目标 key（host + FTP/FTPS/SSH 三个端口）分别累计「连续接收码认证失败」次数；
 * - 连续 maxFailures 次（默认 5 次）失败后锁定 lockDurationMillis（默认 10 分钟）；
 * - lockedRemainingSeconds 返回剩余锁定秒数（向上取整），未锁定/已到期返回 0；
 * - 锁定到期后自动清空该目标的计数（重新从 0 累计），避免一次失败立刻再次锁定；
 * - 连接成功调用 recordSuccess 立即清除记录；锁定期间再次失败不刷新、不叠加锁定时间。
 *
 * 注意：只有「接收码错误」这类认证失败才应调用 recordFailure；网络不可达等错误不计入。
 */
class QuickJoinAttemptLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockDurationMillis: Long = DEFAULT_LOCK_DURATION_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    init {
        require(maxFailures > 0) { "最大失败次数必须大于 0" }
        require(lockDurationMillis > 0L) { "锁定时长必须大于 0" }
    }

    private class AttemptState(var failures: Int = 0, var lockedUntil: Long = 0L)

    private val lock = Any()

    /** 目标 key -> 状态；只保留在内存中，进程重启后清空（安全功能可接受）。 */
    private val states = HashMap<String, AttemptState>()

    /** 剩余锁定秒数（向上取整）：0 表示未锁定。锁定到期时顺手清掉记录。 */
    fun lockedRemainingSeconds(key: String): Long = synchronized(lock) {
        val now = clock()
        val state = activeState(key, now) ?: return 0L
        if (state.lockedUntil <= 0L) return 0L
        val remainingMillis = state.lockedUntil - now
        if (remainingMillis <= 0L) return 0L
        (remainingMillis + 999L) / 1000L
    }

    /**
     * 记录一次接收码认证失败。返回 true 表示本次失败后处于锁定状态（达到或超过上限）。
     * 锁定期间再次调用不会延长锁定时间。
     */
    fun recordFailure(key: String): Boolean = synchronized(lock) {
        val now = clock()
        val state = activeState(key, now) ?: AttemptState().also { states[key] = it }
        if (state.lockedUntil > 0L) return true
        state.failures += 1
        if (state.failures >= maxFailures) {
            state.lockedUntil = now + lockDurationMillis
            true
        } else {
            false
        }
    }

    /** 连接成功：清除该目标的失败/锁定记录。 */
    fun recordSuccess(key: String) {
        synchronized(lock) { states.remove(key) }
    }

    /** 当前累计的失败次数（仅用于调试/测试；锁定到期后返回 0）。 */
    fun failureCount(key: String): Int = synchronized(lock) {
        activeState(key, clock())?.failures ?: 0
    }

    /** 取当前有效状态：锁定已到期的记录先移除并返回 null（计数重新开始）。 */
    private fun activeState(key: String, now: Long): AttemptState? {
        val state = states[key] ?: return null
        if (state.lockedUntil > 0L && now >= state.lockedUntil) {
            states.remove(key)
            return null
        }
        return state
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_LOCK_DURATION_MS = 10L * 60L * 1000L

        /** 目标 key：host（忽略大小写/首尾空白）+ FTP/FTPS/SSH 三个端口，确保同一目标只计一份。 */
        fun keyFor(host: String, ftpPort: Int, ftpsPort: Int, sshPort: Int): String =
            host.trim().lowercase(Locale.ROOT) + "|" + ftpPort + "|" + ftpsPort + "|" + sshPort
    }
}

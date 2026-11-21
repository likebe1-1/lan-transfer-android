package com.manst.lantransfer.server

/**
 * 服务端认证失败锁定器（纯 JVM，可单测）。
 *
 * 背景：接收码只是 6~8 位数字，客户端限流（[com.manst.lantransfer.quick.QuickJoinAttemptLimiter]）
 * 只能约束本 App；攻击者用任意 FTP/SSH 客户端直连端口即可绕过。因此必须在服务端统计
 * 认证失败次数并临时封禁来源，才能真正抵御暴力猜测。
 *
 * 规则：
 * - 同一来源（IP）在 [windowMillis] 窗口内失败达到 [maxFailures] 次 → 锁定 [lockMillis]；
 * - 锁定期间该来源一律拒绝，且不再延长锁定（避免攻击者用垃圾请求无限续期）；
 * - 认证成功后清除该来源的失败记录；
 * - 键同时包含来源 IP 与（可选）用户名，避免不同用户互相影响。
 */
class ServerAuthGuard(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val lockMillis: Long = DEFAULT_LOCK_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private data class Record(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long = 0L
    )

    private val records = HashMap<String, Record>()

    /** 该来源当前是否被锁定。 */
    fun isLocked(source: String, username: String? = null): Boolean {
        val key = keyOf(source, username)
        val record = synchronized(records) { records[key] } ?: return false
        synchronized(records) {
            val now = clock()
            if (record.lockedUntil > 0L && now >= record.lockedUntil) {
                record.lockedUntil = 0L
                record.failures.clear()
                return false
            }
            return record.lockedUntil > now
        }
    }

    /** 锁定剩余毫秒（未锁定时为 0）。 */
    fun remainingLockMillis(source: String, username: String? = null): Long {
        val key = keyOf(source, username)
        val record = synchronized(records) { records[key] } ?: return 0L
        synchronized(records) {
            val remaining = record.lockedUntil - clock()
            return remaining.coerceAtLeast(0L)
        }
    }

    /** 记录一次认证失败；返回是否因此触发了锁定。 */
    fun recordFailure(source: String, username: String? = null): Boolean {
        val key = keyOf(source, username)
        val now = clock()
        synchronized(records) {
            val record = records.getOrPut(key) { Record() }
            if (record.lockedUntil > 0L && now < record.lockedUntil) {
                // 锁定期间再失败不延长锁定
                return false
            }
            if (record.lockedUntil > 0L && now >= record.lockedUntil) {
                record.lockedUntil = 0L
                record.failures.clear()
            }
            record.failures.add(now)
            record.failures.removeAll { it < now - windowMillis }
            if (record.failures.size >= maxFailures) {
                record.lockedUntil = now + lockMillis
                record.failures.clear()
                return true
            }
            return false
        }
    }

    /** 认证成功：清除该来源的失败记录与锁定。 */
    fun recordSuccess(source: String, username: String? = null) {
        synchronized(records) { records.remove(keyOf(source, username)) }
    }

    /** 清理过期记录（长期运行的服务定时调用，避免内存增长）。 */
    fun prune() {
        val now = clock()
        synchronized(records) {
            records.entries.removeAll { (_, record) ->
                record.lockedUntil <= now && record.failures.all { it < now - windowMillis }
            }
        }
    }

    fun lockedSourceCount(): Int = synchronized(records) {
        val now = clock()
        records.values.count { it.lockedUntil > now }
    }

    /** 只按来源 IP 计数：避免攻击者轮换用户名分摊失败次数、或换用户名绕过锁定。 */
    private fun keyOf(source: String, username: String?): String =
        source.trim().lowercase()

    companion object {
        /** 5 次失败 / 10 分钟窗口 / 锁定 10 分钟：与客户端限流保持一致的体验。 */
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_WINDOW_MILLIS = 10 * 60 * 1000L
        const val DEFAULT_LOCK_MILLIS = 10 * 60 * 1000L
    }
}

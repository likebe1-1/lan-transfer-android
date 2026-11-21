package com.manst.lantransfer.server

/**
 * 服务端认证失败锁定器（纯 JVM，可单测）。
 *
 * 背景：接收码只是 6~8 位数字，客户端限流（[com.manst.lantransfer.quick.QuickJoinAttemptLimiter]）
 * 只能约束本 App；攻击者用任意 FTP/SSH 客户端直连端口即可绕过。因此必须在服务端统计
 * 认证失败次数并临时封禁来源，才能真正抵御暴力猜测。
 *
 * 三层策略（所有可变状态都由同一把 [records] 监视器保护，绝不在锁外持有 record）：
 * 1. 单来源阈值（原有行为，保持不变）：同一来源（IP）在 [windowMillis] 窗口内失败达到
 *    [maxFailures] 次 → 锁定；锁定期间该来源一律拒绝，且不再延长锁定（避免攻击者用垃圾请求无限续期）。
 * 2. 全局滑动窗口上限（新增）：整个服务在 [globalWindowMillis] 内失败达到 [globalMaxFailures]
 *    次 → 进入 [globalCooldownMillis] 的全局冷却，期间所有来源（包括从未出现过的来源）快速失败。
 *    目的：阻断「换 IP 水平爆破」——单来源阈值对每个新 IP 都要重新累计，攻击者换 IP 即可绕过；
 *    而全局上限与来源无关，只与全服务失败速率有关。正常用户几乎不可能触发：默认 60 次/分钟，
 *    约等于同时有 12 个来源各自打满 5 次阈值才会进入冷却。
 * 3. 可信来源阶梯锁定（新增，低成本缓解 NAT/CGNAT 误伤）：最近 [trustedSourceMillis] 内成功
 *    认证过的来源视为「可信来源」——典型场景是同一出口 IP 后的正常设备刚传完文件。可信来源
 *    首次违规只锁第一档（默认为 [lockMillis] 的 1/10，即 1 分钟），累犯升到一半（5 分钟），
 *    再犯才到整档（10 分钟）；从未成功认证过的陌生来源（典型攻击者）不受阶梯影响，首次即锁满
 *    [lockMillis]，与旧行为完全一致，因此防护强度不会被削弱。
 *    阶梯在「安静」[ladderResetMillis] 后回落到第一档；成功认证**不**重置阶梯——否则攻击者可借
 *    同一 NAT 后面正常设备的成功认证，把自己的阶梯反复压回 1 分钟。
 *
 * 快速失败：全局冷却期间的请求只做一次时间比较，不累加任何记录（攻击者 flood 无法让状态膨胀）。
 */
class ServerAuthGuard(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val lockMillis: Long = DEFAULT_LOCK_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 全局滑动窗口内允许的失败总数；<= 0 表示关闭全局上限（不推荐）。 */
    private val globalMaxFailures: Int = DEFAULT_GLOBAL_MAX_FAILURES,
    /** 全局滑动窗口长度。 */
    private val globalWindowMillis: Long = DEFAULT_GLOBAL_WINDOW_MILLIS,
    /** 触发全局上限后的冷却时长；<= 0 表示关闭。 */
    private val globalCooldownMillis: Long = DEFAULT_GLOBAL_COOLDOWN_MILLIS,
    /** 可信来源的阶梯锁定时长（由短到长）；传空数组等价于关闭阶梯（直接用 [lockMillis]）。 */
    trustedLockSteps: LongArray = longArrayOf(lockMillis / 10, lockMillis / 2, lockMillis),
    /** 成功认证后多久内仍视为「可信来源」。 */
    private val trustedSourceMillis: Long = DEFAULT_TRUSTED_SOURCE_MILLIS,
    /** 距上次锁定「安静」多久后阶梯回落到第一档。 */
    private val ladderResetMillis: Long = DEFAULT_LADDER_RESET_MILLIS
) {

    init {
        require(maxFailures > 0) { "maxFailures 必须为正数" }
        require(windowMillis > 0L) { "windowMillis 必须为正数" }
        require(lockMillis > 0L) { "lockMillis 必须为正数" }
        require(globalWindowMillis > 0L) { "globalWindowMillis 必须为正数" }
        require(trustedSourceMillis >= 0L) { "trustedSourceMillis 不能为负数" }
        require(ladderResetMillis >= 0L) { "ladderResetMillis 不能为负数" }
    }

    private data class Record(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long = 0L,
        /** 已触发过的阶梯档位（成功认证不重置，只靠安静时长回落）。 */
        var lockCount: Int = 0,
        /** 上次锁定开始时刻（0 表示从未锁定），用于阶梯回落判定。 */
        var lastLockAt: Long = 0L,
        /** 最近一次成功认证时刻（0 表示从未成功），用于识别可信来源。 */
        var lastSuccessAt: Long = 0L
    )

    /** 单来源记录。 */
    private val records = HashMap<String, Record>()

    /** 全局滑动窗口内的失败时间戳（按时间升序追加，长度受 [globalMaxFailures] 约束）。 */
    private val globalFailures = ArrayList<Long>()

    /** 全局冷却截止时刻（0 表示未处于冷却）。 */
    private var globalCooldownUntil = 0L

    /** 上次自动清理时间（仅在持有 [records] 锁时读写）。 */
    private var lastPruneAt = 0L

    /** 阶梯档位：去除非正数、按 [lockMillis] 截断、升序去重；为空时退化为单档 [lockMillis]。 */
    private val lockSteps: LongArray = normalizeLockSteps(trustedLockSteps, lockMillis)

    /** 全局上限是否启用（配置值 <= 0 视为关闭）。 */
    private val globalLimitEnabled: Boolean
        get() = globalMaxFailures > 0 && globalCooldownMillis > 0L

    /** 该来源当前是否被拒绝（单来源锁定或全局冷却），整个过程在同一个锁内完成。 */
    fun isLocked(source: String, username: String? = null): Boolean = synchronized(records) {
        val now = clock()
        if (globalLimitEnabled && globalCooldownUntil > 0L) {
            if (now < globalCooldownUntil) return true
            globalCooldownUntil = 0L
        }
        val record = records[keyOf(source, username)] ?: return false
        if (record.lockedUntil > 0L && now >= record.lockedUntil) {
            record.lockedUntil = 0L
            record.failures.clear()
            return false
        }
        record.lockedUntil > now
    }

    /** 单来源锁定剩余毫秒（未锁定时为 0；全局冷却请用 [globalCooldownRemainingMillis]）。 */
    fun remainingLockMillis(source: String, username: String? = null): Long = synchronized(records) {
        val record = records[keyOf(source, username)] ?: return 0L
        (record.lockedUntil - clock()).coerceAtLeast(0L)
    }

    /** 记录一次认证失败；返回该来源是否因此被拒绝（触发单来源锁定或全局冷却）。 */
    fun recordFailure(source: String, username: String? = null): Boolean {
        val key = keyOf(source, username)
        val now = clock()
        synchronized(records) {
            // 全局冷却期间：快速失败，不累加任何计数（否则 flood 会让状态无成本膨胀）
            if (globalLimitEnabled && globalCooldownUntil > 0L) {
                if (now < globalCooldownUntil) return true
                globalCooldownUntil = 0L
            }
            // 节流自动清理：即使调用方忘记定期 prune，长期运行也不会无限增长
            if (now - lastPruneAt >= PRUNE_INTERVAL_MILLIS) {
                pruneLocked(now)
                lastPruneAt = now
            }
            // 全局滑窗先记账：即便某个来源已被单独锁定，攻击流量也不该从全局预算里消失
            val globalRejected = noteGlobalFailureLocked(now)
            val record = records.getOrPut(key) { Record() }
            if (record.lockedUntil > 0L && now < record.lockedUntil) {
                // 锁定期间再失败不延长锁定
                return globalRejected
            }
            if (record.lockedUntil > 0L && now >= record.lockedUntil) {
                record.lockedUntil = 0L
                record.failures.clear()
            }
            record.failures.add(now)
            record.failures.removeAll { it < now - windowMillis }
            if (record.failures.size >= maxFailures) {
                val trusted = isTrustedSource(record, now)
                if (trusted && ladderResetMillis > 0L && record.lastLockAt > 0L &&
                    now - record.lastLockAt > ladderResetMillis
                ) {
                    // 安静足够久：阶梯回落到第一档
                    record.lockCount = 0
                }
                // 陌生来源（典型攻击者）保持原有整档锁定，绝不因阶梯放宽
                record.lockedUntil = now + if (trusted) {
                    lockSteps[record.lockCount.coerceIn(0, lockSteps.lastIndex)]
                } else {
                    lockMillis
                }
                record.lastLockAt = now
                if (trusted) record.lockCount = (record.lockCount + 1).coerceAtMost(lockSteps.size)
                record.failures.clear()
                return true
            }
            return globalRejected
        }
    }

    /**
     * 认证成功：清除该来源的失败记录与锁定，并把它标记为「可信来源」（锁定时可享受阶梯缩短）。
     * 故意不重置阶梯档位，理由见类注释。
     */
    fun recordSuccess(source: String, username: String? = null) {
        synchronized(records) {
            val now = clock()
            val key = keyOf(source, username)
            val record = records[key]
            if (record == null) {
                records[key] = Record(lastSuccessAt = now)
            } else {
                record.failures.clear()
                record.lockedUntil = 0L
                record.lastSuccessAt = now
            }
        }
    }

    /** 清理过期记录（长期运行的服务定时调用，避免内存增长）。 */
    fun prune() {
        val now = clock()
        synchronized(records) {
            pruneLocked(now)
            lastPruneAt = now
        }
    }

    /** 当前是否处于全局冷却（冷却期间 [isLocked] 对所有来源返回 true）。 */
    fun isGlobalCooldown(): Boolean = synchronized(records) {
        if (globalCooldownUntil <= 0L) return false
        if (clock() >= globalCooldownUntil) {
            globalCooldownUntil = 0L
            false
        } else {
            true
        }
    }

    /** 全局冷却剩余毫秒（未处于冷却时为 0）。 */
    fun globalCooldownRemainingMillis(): Long = synchronized(records) {
        (globalCooldownUntil - clock()).coerceAtLeast(0L)
    }

    /** 全局滑动窗口内的失败次数（只读观测用）。 */
    fun globalFailureCountInWindow(): Int = synchronized(records) {
        val cutoff = clock() - globalWindowMillis
        globalFailures.count { it >= cutoff }
    }

    private fun pruneLocked(now: Long) {
        globalFailures.removeAll { it < now - globalWindowMillis }
        records.entries.removeAll { (_, record) ->
            record.lockedUntil <= now &&
                record.failures.all { it < now - windowMillis } &&
                // 可信来源要保留：后续它再次触发锁定时按阶梯缩短，这是缓解误伤的关键状态
                !isTrustedSource(record, now)
        }
    }

    /** 全局滑窗记账；返回本次是否触发了全局冷却。 */
    private fun noteGlobalFailureLocked(now: Long): Boolean {
        if (!globalLimitEnabled) return false
        globalFailures.add(now)
        globalFailures.removeAll { it < now - globalWindowMillis }
        if (globalFailures.size < globalMaxFailures) return false
        // 触发上限：清空窗口并进入冷却；冷却期间的请求不再记账（快速失败）
        globalFailures.clear()
        globalCooldownUntil = now + globalCooldownMillis
        return true
    }

    /** 最近 [trustedSourceMillis] 内成功认证过的来源视为可信（典型：NAT 后面的正常设备）。 */
    private fun isTrustedSource(record: Record, now: Long): Boolean =
        trustedSourceMillis > 0L && record.lastSuccessAt > 0L &&
            now - record.lastSuccessAt <= trustedSourceMillis

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

        /** 全局上限：全服务 60 次失败 / 60 秒；触发后冷却 30 秒。 */
        const val DEFAULT_GLOBAL_MAX_FAILURES = 60
        const val DEFAULT_GLOBAL_WINDOW_MILLIS = 60 * 1000L
        const val DEFAULT_GLOBAL_COOLDOWN_MILLIS = 30 * 1000L

        /** 可信来源窗口与阶梯回落时长：均为 30 分钟。 */
        const val DEFAULT_TRUSTED_SOURCE_MILLIS = 30 * 60 * 1000L
        const val DEFAULT_LADDER_RESET_MILLIS = 30 * 60 * 1000L

        /** 自动清理间隔：60 秒（与记录量无关，足够便宜）。 */
        const val PRUNE_INTERVAL_MILLIS = 60 * 1000L
    }
}

/** 规范化阶梯档位：去除非正数、按 [lockMillis] 截断、升序去重；为空时退化为单档 [lockMillis]。 */
private fun normalizeLockSteps(steps: LongArray, lockMillis: Long): LongArray {
    val normalized = steps.map { it.coerceAtMost(lockMillis) }
        .filter { it > 0L }
        .distinct()
        .sorted()
    return if (normalized.isEmpty()) longArrayOf(lockMillis) else normalized.toLongArray()
}

package com.manst.lantransfer.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务端认证失败限流：防止接收码被暴力枚举。 */
class ServerAuthGuardTest {

    private var now = 1_000_000L
    private val guard = ServerAuthGuard(clock = { now })

    @Test
    fun failuresBelowThreshold_doNotLock() {
        repeat(4) { assertFalse(guard.recordFailure("10.0.0.5")) }
        assertFalse(guard.isLocked("10.0.0.5"))
    }

    @Test
    fun fifthFailure_locksForTenMinutes() {
        repeat(4) { guard.recordFailure("10.0.0.5") }
        assertTrue("第 5 次失败应触发锁定", guard.recordFailure("10.0.0.5"))
        assertTrue(guard.isLocked("10.0.0.5"))
        assertTrue(guard.remainingLockMillis("10.0.0.5") > 9 * 60 * 1000L)
    }

    @Test
    fun lockExpires_afterTenMinutesAndCounterResets() {
        repeat(5) { guard.recordFailure("10.0.0.6") }
        assertTrue(guard.isLocked("10.0.0.6"))
        now += 10 * 60 * 1000L + 1
        assertFalse(guard.isLocked("10.0.0.6"))
        // 解锁后重新累计：再失败 4 次仍不锁
        repeat(4) { assertFalse(guard.recordFailure("10.0.0.6")) }
        assertFalse(guard.isLocked("10.0.0.6"))
    }

    @Test
    fun failureDuringLock_doesNotExtend() {
        repeat(5) { guard.recordFailure("10.0.0.7") }
        val remaining = guard.remainingLockMillis("10.0.0.7")
        repeat(5) { guard.recordFailure("10.0.0.7") }
        assertEquals(remaining, guard.remainingLockMillis("10.0.0.7"))
    }

    @Test
    fun success_clearsFailuresAndLock() {
        repeat(5) { guard.recordFailure("10.0.0.8") }
        assertTrue(guard.isLocked("10.0.0.8"))
        guard.recordSuccess("10.0.0.8")
        assertFalse(guard.isLocked("10.0.0.8"))
        // 清除后重新计数
        repeat(4) { guard.recordFailure("10.0.0.8") }
        assertFalse(guard.isLocked("10.0.0.8"))
    }

    @Test
    fun sources_areIsolated() {
        repeat(5) { guard.recordFailure("10.0.0.9") }
        assertTrue(guard.isLocked("10.0.0.9"))
        assertFalse(guard.isLocked("10.0.0.10"))
        assertEquals(1, guard.lockedSourceCount())
    }

    @Test
    fun window_expiresOldFailures() {
        repeat(4) { guard.recordFailure("10.0.0.11") }
        // 超出 10 分钟窗口后旧失败被清理，再失败 1 次不应锁定
        now += 10 * 60 * 1000L + 1
        assertFalse(guard.recordFailure("10.0.0.11"))
        assertFalse(guard.isLocked("10.0.0.11"))
    }

    @Test
    fun autoPrune_onRecordFailure_clearsExpiredRecords() {
        repeat(5) { guard.recordFailure("10.0.0.20") }
        assertEquals(1, guard.lockedSourceCount())
        now += 10 * 60 * 1000L + 1
        // 下一次失败会触发内部节流清理（间隔 60 秒已过）
        guard.recordFailure("10.0.0.21")
        // 旧来源已被清理，新的失败记录仍在
        assertEquals(0, guard.lockedSourceCount())
    }

    @Test
    fun concurrentFailures_doNotCorruptState() {
        val threads = (1..8).map { index ->
            Thread {
                repeat(50) { round -> guard.recordFailure("10.1.0." + index) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // 每个来源 50 次失败都会触发锁定；并发下不应抛异常，且锁定数不超过来源数
        assertTrue(guard.lockedSourceCount() in 1..8)
    }

    @Test
    fun prune_removesStaleRecords() {
        repeat(5) { guard.recordFailure("10.0.0.12") }
        assertEquals(1, guard.lockedSourceCount())
        now += 10 * 60 * 1000L + 1
        guard.prune()
        assertEquals(0, guard.lockedSourceCount())
    }

    // ------------------------------------------------------------ 全局滑动窗口上限
    // 目的：阻断「换 IP 水平爆破」——单来源阈值对每个新 IP 都要重新累计，攻击者换 IP 即可绕过。

    /** 全局上限测试专用实例：窗口 60 秒、冷却 30 秒，失败上限可调。 */
    private fun globalGuard(globalMaxFailures: Int = 10): ServerAuthGuard = ServerAuthGuard(
        globalMaxFailures = globalMaxFailures,
        globalWindowMillis = 60_000L,
        globalCooldownMillis = 30_000L,
        clock = { now }
    )

    @Test
    fun globalLimit_triggersCooldownAcrossDifferentSources() {
        val guard = globalGuard()
        repeat(9) { index -> assertFalse(guard.recordFailure("172.16.0." + index)) }
        assertFalse(guard.isGlobalCooldown())
        // 每个来源都只失败 1 次（远低于单来源阈值），但全服务累计已达上限
        assertTrue("全局上限应触发冷却", guard.recordFailure("172.16.0.99"))
        assertTrue(guard.isGlobalCooldown())
        assertTrue(guard.globalCooldownRemainingMillis() in 1L..30_000L)
    }

    @Test
    fun globalCooldown_rejectsEverySourceWithoutRecording() {
        val guard = globalGuard()
        repeat(10) { index -> guard.recordFailure("172.17.0." + index) }
        assertTrue(guard.isGlobalCooldown())
        // 冷却期间：连从未出现过的来源也直接被判为拒绝
        assertTrue(guard.isLocked("172.17.9.9"))
        assertTrue(guard.recordFailure("172.17.9.9"))
        // 冷却期间的请求不累加单来源记录，攻击者无法借此放大状态
        assertEquals(0, guard.lockedSourceCount())
        assertEquals(0, guard.globalFailureCountInWindow())
    }

    @Test
    fun globalCooldown_expires_andCountingResumes() {
        val guard = globalGuard()
        repeat(10) { index -> guard.recordFailure("172.18.0." + index) }
        assertTrue(guard.isGlobalCooldown())
        now += 30_000L + 1
        assertFalse(guard.isGlobalCooldown())
        assertFalse(guard.isLocked("172.18.0.1"))
        // 恢复后对全新来源重新累计：4 次失败既不锁单来源，也不再触发全局上限
        repeat(4) { assertFalse(guard.recordFailure("172.18.9.9")) }
        assertFalse(guard.isGlobalCooldown())
        assertFalse(guard.isLocked("172.18.9.9"))
        // 冷却前那 1 次失败仍在 10 分钟窗口内，因此它所在来源再失败 4 次就会锁定（窗口未被冷却清空）
        repeat(4) { guard.recordFailure("172.18.0.1") }
        assertTrue(guard.isLocked("172.18.0.1"))
    }

    @Test
    fun globalWindow_expiresOldFailures() {
        val guard = globalGuard()
        repeat(5) { index -> guard.recordFailure("172.19.0." + index) }
        now += 60_000L + 1
        repeat(5) { index -> guard.recordFailure("172.19.1." + index) }
        assertFalse("超出 60 秒窗口的旧失败不应计入全局上限", guard.isGlobalCooldown())
    }

    @Test
    fun defaultGlobalThreshold_toleratesNormalTraffic() {
        // 默认 60 次/分钟：50 个不同来源各失败 1 次仍远低于上限
        repeat(50) { index -> guard.recordFailure("172.20.0." + index) }
        assertFalse(guard.isGlobalCooldown())
        assertFalse(guard.isLocked("172.20.1.1"))
    }

    @Test
    fun globalCooldown_doesNotClearPerSourceLock() {
        val guard = globalGuard()
        repeat(5) { guard.recordFailure("172.21.0.1") } // 陌生来源 → 单来源锁定 10 分钟
        assertTrue(guard.isLocked("172.21.0.1"))
        val perSourceRemaining = guard.remainingLockMillis("172.21.0.1")
        repeat(10) { index -> guard.recordFailure("172.21.1." + index) }
        assertTrue(guard.isGlobalCooldown())
        assertEquals(perSourceRemaining, guard.remainingLockMillis("172.21.0.1"))
        now += 30_000L + 1
        assertFalse(guard.isGlobalCooldown())
        assertTrue("全局冷却结束不应解除单来源锁定", guard.isLocked("172.21.0.1"))
    }

    @Test
    fun prune_trimsGlobalWindowBookkeeping() {
        val guard = globalGuard()
        repeat(5) { index -> guard.recordFailure("172.22.0." + index) }
        assertEquals(5, guard.globalFailureCountInWindow())
        now += 60_000L + 1
        guard.prune()
        assertEquals(0, guard.globalFailureCountInWindow())
    }

    @Test
    fun concurrentFailures_globalCooldownStaysConsistent() {
        val guard = globalGuard(globalMaxFailures = 20)
        val threads = (1..8).map { subnet ->
            Thread {
                repeat(30) { round -> guard.recordFailure("192.168." + subnet + "." + round) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("并发下全局上限也必然被触发", guard.isGlobalCooldown())
        assertTrue(guard.globalCooldownRemainingMillis() in 1L..30_000L)
        assertTrue(guard.lockedSourceCount() in 0..8)
    }

    // ------------------------------------------------------------ 可信来源阶梯锁定（缓解 NAT 误伤）
    // 可信来源 = 最近 30 分钟内成功认证过的来源：首次违规只锁 1 分钟，累犯 5 分钟，再犯 10 分钟。
    // 陌生来源（典型攻击者）不受阶梯影响，首次即锁满 10 分钟。

    @Test
    fun unknownSource_firstLockKeepsFullDuration() {
        // 从未成功认证过的来源按原策略立即锁满 10 分钟：阶梯不削弱对攻击者的防护
        repeat(5) { guard.recordFailure("10.2.1.1") }
        assertTrue(guard.isLocked("10.2.1.1"))
        assertTrue(guard.remainingLockMillis("10.2.1.1") > 9 * 60 * 1000L)
        // 解锁后累犯仍是整档，不会变短
        now += 10 * 60 * 1000L + 1
        repeat(5) { guard.recordFailure("10.2.1.1") }
        assertTrue(guard.remainingLockMillis("10.2.1.1") > 9 * 60 * 1000L)
    }

    @Test
    fun trustedSource_firstLockIsShort_thenEscalatesToFull() {
        // 同一 NAT 出口后的正常设备刚刚成功传过文件 → 标记为可信来源
        guard.recordSuccess("10.2.0.1")
        repeat(5) { guard.recordFailure("10.2.0.1") }
        assertTrue(guard.isLocked("10.2.0.1"))
        val first = guard.remainingLockMillis("10.2.0.1")
        assertTrue("可信来源首次锁定应缩短到约 1 分钟，实际 " + first + " ms", first in 1L..60_000L)

        now += first + 1
        repeat(5) { guard.recordFailure("10.2.0.1") }
        val second = guard.remainingLockMillis("10.2.0.1")
        assertTrue("第 2 次累犯应升到约 5 分钟，实际 " + second + " ms", second > first)
        assertTrue(second in 1L..300_000L)

        now += second + 1
        repeat(5) { guard.recordFailure("10.2.0.1") }
        assertEquals("第 3 次累犯达到 10 分钟上限", 10 * 60 * 1000L, guard.remainingLockMillis("10.2.0.1"))
    }

    @Test
    fun trustedSource_stillLocksAtSameFailureThreshold() {
        // 阶梯只缩短锁定时长，不改变 5 次阈值（防护强度不变）
        guard.recordSuccess("10.2.5.1")
        repeat(4) { assertFalse(guard.recordFailure("10.2.5.1")) }
        assertFalse(guard.isLocked("10.2.5.1"))
        assertTrue(guard.recordFailure("10.2.5.1"))
    }

    @Test
    fun success_doesNotResetEscalation() {
        guard.recordSuccess("10.2.3.1")
        repeat(5) { guard.recordFailure("10.2.3.1") } // 1 分钟档
        now += guard.remainingLockMillis("10.2.3.1") + 1
        repeat(5) { guard.recordFailure("10.2.3.1") } // 5 分钟档
        now += guard.remainingLockMillis("10.2.3.1") + 1
        guard.recordSuccess("10.2.3.1") // 成功认证不应把阶梯压回第一档
        repeat(5) { guard.recordFailure("10.2.3.1") }
        assertEquals(10 * 60 * 1000L, guard.remainingLockMillis("10.2.3.1"))
    }

    @Test
    fun escalationFallsBackAfterQuietPeriod() {
        // 信任窗口放大到 24 小时，单独验证「安静时长」对阶梯的回落作用
        val guard = ServerAuthGuard(clock = { now }, trustedSourceMillis = 24 * 60 * 60 * 1000L)
        guard.recordSuccess("10.2.2.1")
        repeat(5) { guard.recordFailure("10.2.2.1") } // 1 分钟档
        val first = guard.remainingLockMillis("10.2.2.1")
        now += first + 1
        repeat(5) { guard.recordFailure("10.2.2.1") } // 5 分钟档
        val second = guard.remainingLockMillis("10.2.2.1")
        assertTrue(second > first)
        // 安静超过 30 分钟：阶梯回落到第一档
        now += second + 1 + 31 * 60 * 1000L
        repeat(5) { guard.recordFailure("10.2.2.1") }
        assertTrue(guard.remainingLockMillis("10.2.2.1") <= 60_000L)
    }

    @Test
    fun trustedStatusExpiresSoLadderCannotBeAbused() {
        val guard = ServerAuthGuard(clock = { now }, trustedSourceMillis = 60_000L)
        guard.recordSuccess("10.2.4.1")
        now += 60_000L + 1
        repeat(5) { guard.recordFailure("10.2.4.1") }
        assertTrue("信任过期后按陌生来源锁满 10 分钟", guard.remainingLockMillis("10.2.4.1") > 9 * 60 * 1000L)
    }

    @Test
    fun customLockMillis_scalesTrustedLadderAndNeverExceedsIt() {
        val guard = ServerAuthGuard(lockMillis = 120_000L, clock = { now })
        guard.recordSuccess("10.2.6.1")
        repeat(5) { guard.recordFailure("10.2.6.1") }
        assertEquals("首档随 lockMillis 缩放", 12_000L, guard.remainingLockMillis("10.2.6.1"))
        repeat(2) {
            now += guard.remainingLockMillis("10.2.6.1") + 1
            repeat(5) { guard.recordFailure("10.2.6.1") }
        }
        assertEquals("阶梯上限等于 lockMillis", 120_000L, guard.remainingLockMillis("10.2.6.1"))
    }

    @Test
    fun globalCooldown_doesNotAffectTrustedEscalationBookkeeping() {
        // 全局冷却与阶梯互不干扰：冷却结束、单来源解锁后，阶梯仍按累犯上升
        val guard = globalGuard()
        guard.recordSuccess("172.23.0.1")
        repeat(5) { guard.recordFailure("172.23.0.1") } // 可信来源首档 1 分钟
        assertEquals(60_000L, guard.remainingLockMillis("172.23.0.1"))
        repeat(10) { index -> guard.recordFailure("172.23.1." + index) } // 触发全局冷却
        assertTrue(guard.isGlobalCooldown())
        now += 30_000L + 1
        assertFalse(guard.isGlobalCooldown())
        // 单来源锁定 1 分钟后已到期，再累犯一次应升到 5 分钟档
        now += 60_000L
        repeat(5) { guard.recordFailure("172.23.0.1") }
        assertEquals(300_000L, guard.remainingLockMillis("172.23.0.1"))
    }
}

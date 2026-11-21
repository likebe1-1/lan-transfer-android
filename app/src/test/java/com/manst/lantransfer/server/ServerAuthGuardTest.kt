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
}

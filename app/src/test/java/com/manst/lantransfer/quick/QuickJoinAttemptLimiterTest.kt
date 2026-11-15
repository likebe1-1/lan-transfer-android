package com.manst.lantransfer.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QuickJoinAttemptLimiter] 的 JVM 单元测试：5 次锁定、10 分钟解锁、成功清除与目标隔离。 */
class QuickJoinAttemptLimiterTest {

    /** 可手动推进的假时钟（毫秒）。 */
    private var now = 0L

    private fun limiter(
        maxFailures: Int = QuickJoinAttemptLimiter.DEFAULT_MAX_FAILURES,
        lockDurationMillis: Long = QuickJoinAttemptLimiter.DEFAULT_LOCK_DURATION_MS
    ) = QuickJoinAttemptLimiter(maxFailures, lockDurationMillis) { now }

    private val key = QuickJoinAttemptLimiter.keyFor("192.168.1.5", 2121, 2122, 2222)

    @Test
    fun firstFourFailures_doNotLock_fifthLocksForTenMinutes() {
        val limiter = limiter()
        repeat(4) { attempt ->
            assertFalse("第 " + (attempt + 1) + " 次失败不应锁定", limiter.recordFailure(key))
            assertEquals(0L, limiter.lockedRemainingSeconds(key))
        }
        assertEquals(4, limiter.failureCount(key))

        assertTrue("第 5 次失败应进入锁定", limiter.recordFailure(key))
        assertEquals(5, limiter.failureCount(key))
        assertEquals("锁定 10 分钟", 600L, limiter.lockedRemainingSeconds(key))
    }

    @Test
    fun lockedRemainingSeconds_countsDownAndRoundsUp() {
        val limiter = limiter()
        repeat(5) { limiter.recordFailure(key) }
        assertEquals(600L, limiter.lockedRemainingSeconds(key))

        now = 1L
        assertEquals("剩余 599.999 秒向上取整为 600", 600L, limiter.lockedRemainingSeconds(key))
        now = 300_000L
        assertEquals(300L, limiter.lockedRemainingSeconds(key))
        now = 599_001L
        assertEquals(1L, limiter.lockedRemainingSeconds(key))
        now = 600_000L
        assertEquals("10 分钟后自动解锁", 0L, limiter.lockedRemainingSeconds(key))
    }

    @Test
    fun lockedState_cannotBeExtendedByFurtherFailures() {
        val limiter = limiter()
        repeat(5) { limiter.recordFailure(key) }
        now = 100_000L
        assertTrue("锁定期间失败仍返回已锁定", limiter.recordFailure(key))
        // 原锁定在 600000ms 到期，不因锁定期间的失败而顺延
        assertEquals(500L, limiter.lockedRemainingSeconds(key))
        now = 599_999L
        assertEquals(1L, limiter.lockedRemainingSeconds(key))
        now = 600_000L
        assertEquals(0L, limiter.lockedRemainingSeconds(key))
    }

    @Test
    fun success_clearsFailuresAndLock() {
        val limiter = limiter()
        repeat(4) { limiter.recordFailure(key) }
        limiter.recordSuccess(key)
        assertEquals(0, limiter.failureCount(key))
        assertEquals(0L, limiter.lockedRemainingSeconds(key))

        // 成功清除后重新从 0 累计：再 4 次也不锁定，第 5 次才锁定
        repeat(4) { limiter.recordFailure(key) }
        assertEquals(0L, limiter.lockedRemainingSeconds(key))
        assertTrue(limiter.recordFailure(key))
    }

    @Test
    fun success_clearsActiveLock() {
        val limiter = limiter()
        repeat(5) { limiter.recordFailure(key) }
        assertTrue(limiter.lockedRemainingSeconds(key) > 0L)
        limiter.recordSuccess(key)
        assertEquals(0L, limiter.lockedRemainingSeconds(key))
        assertEquals(0, limiter.failureCount(key))
    }

    @Test
    fun lockExpiresAfterTenMinutes_andCounterRestarts() {
        val limiter = limiter()
        repeat(5) { limiter.recordFailure(key) }
        now = QuickJoinAttemptLimiter.DEFAULT_LOCK_DURATION_MS
        assertEquals(0L, limiter.lockedRemainingSeconds(key))
        assertEquals("锁定到期后计数清零", 0, limiter.failureCount(key))

        repeat(4) {
            assertFalse("解锁后重新累计，前 4 次不锁定", limiter.recordFailure(key))
        }
        assertTrue("解锁后第 5 次再次锁定", limiter.recordFailure(key))
    }

    @Test
    fun differentTargetsAreIsolated() {
        val limiter = limiter()
        val otherKey = QuickJoinAttemptLimiter.keyFor("192.168.1.6", 2121, 2122, 2222)
        repeat(5) { limiter.recordFailure(key) }
        assertTrue(limiter.lockedRemainingSeconds(key) > 0L)
        assertEquals("其他目标不受影响", 0L, limiter.lockedRemainingSeconds(otherKey))
        assertEquals(0, limiter.failureCount(otherKey))
        assertFalse(limiter.recordFailure(otherKey))
    }

    @Test
    fun keyFor_normalizesHostAndIncludesAllPorts() {
        val base = QuickJoinAttemptLimiter.keyFor("192.168.1.5", 2121, 2122, 2222)
        assertEquals(base, QuickJoinAttemptLimiter.keyFor(" 192.168.1.5 ", 2121, 2122, 2222))
        assertEquals(base, QuickJoinAttemptLimiter.keyFor("192.168.1.5", 2121, 2122, 2222))
        assertNotEquals(base, QuickJoinAttemptLimiter.keyFor("192.168.1.5", 2123, 2122, 2222))
        assertNotEquals(base, QuickJoinAttemptLimiter.keyFor("192.168.1.5", 2121, 2123, 2222))
        assertNotEquals(base, QuickJoinAttemptLimiter.keyFor("192.168.1.5", 2121, 2122, 2223))
        assertNotEquals(base, QuickJoinAttemptLimiter.keyFor("192.168.1.6", 2121, 2122, 2222))
    }

    @Test
    fun customThresholdAndDuration_areRespected() {
        val limiter = limiter(maxFailures = 2, lockDurationMillis = 60_000L)
        assertFalse(limiter.recordFailure(key))
        assertTrue(limiter.recordFailure(key))
        assertEquals(60L, limiter.lockedRemainingSeconds(key))
        now = 60_000L
        assertEquals(0L, limiter.lockedRemainingSeconds(key))
    }

    @Test
    fun recordSuccessOnUnknownKey_isSafe() {
        val limiter = limiter()
        limiter.recordSuccess(key)
        assertEquals(0L, limiter.lockedRemainingSeconds(key))
        assertEquals(0, limiter.failureCount(key))
    }
}

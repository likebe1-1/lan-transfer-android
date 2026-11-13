package com.manst.lantransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressTest {

    @Test
    fun `采样不足时速度为零`() {
        val meter = TransferSpeedMeter(windowMillis = 1000)
        assertEquals(0L, meter.bytesPerSecond())
        meter.sample(0, 0)
        assertEquals(0L, meter.bytesPerSecond())
        assertEquals(1, meter.sampleCount())
    }

    @Test
    fun `窗口内按字节增量除以时间计算速度`() {
        val meter = TransferSpeedMeter(windowMillis = 2000)
        meter.sample(0, 0)
        meter.sample(500, 500_000)
        meter.sample(1000, 1_000_000)
        // (1000000 - 0) 字节 / 1000ms
        assertEquals(1_000_000L, meter.bytesPerSecond())
    }

    @Test
    fun `超出窗口的旧采样被淘汰`() {
        val meter = TransferSpeedMeter(windowMillis = 1000)
        meter.sample(0, 0)
        meter.sample(500, 500_000)
        meter.sample(1000, 1_000_000)
        meter.sample(2000, 4_000_000)
        // 窗口只剩 (1000, 1000000) 与 (2000, 4000000)
        assertEquals(3_000_000L, meter.bytesPerSecond())
    }

    @Test
    fun `重置后速度归零`() {
        val meter = TransferSpeedMeter()
        meter.sample(0, 0)
        meter.sample(1000, 1024)
        assertTrue(meter.bytesPerSecond() > 0L)
        meter.reset()
        assertEquals(0L, meter.bytesPerSecond())
    }

    @Test
    fun `节流器首次推送后按时间间隔推送`() {
        val throttler = TransferProgressThrottler(emitIntervalMillis = 100, emitByteStep = 1024 * 1024)
        assertTrue(throttler.shouldEmit(0, 0, 10_000_000))
        assertFalse(throttler.shouldEmit(50, 10, 10_000_000))
        assertTrue(throttler.shouldEmit(100, 20, 10_000_000))
    }

    @Test
    fun `节流器按字节步长提前推送`() {
        val throttler = TransferProgressThrottler(emitIntervalMillis = 100, emitByteStep = 128 * 1024)
        assertTrue(throttler.shouldEmit(0, 0, 10_000_000))
        assertTrue(throttler.shouldEmit(10, 128 * 1024, 10_000_000))
        assertFalse(throttler.shouldEmit(20, 128 * 1024 + 100, 10_000_000))
    }

    @Test
    fun `节流器在最后一个字节时必然推送`() {
        val throttler = TransferProgressThrottler(emitIntervalMillis = 100, emitByteStep = 1024 * 1024)
        assertTrue(throttler.shouldEmit(0, 0, 5000))
        assertTrue(throttler.shouldEmit(10, 5000, 5000))
        // 总量未知（-1）时没有“最后一字节”概念
        throttler.reset()
        assertTrue(throttler.shouldEmit(0, 0, -1))
        assertFalse(throttler.shouldEmit(10, 10, -1))
    }

    @Test
    fun `进度聚合器返回速度并遵守节流`() {
        val tracker = TransferProgressTracker(
            emitIntervalMillis = 100,
            emitByteStep = 128 * 1024,
            speedWindowMillis = 2000
        )
        val first = tracker.sample(1000, 0, 10_000)
        assertNotNull(first)
        assertEquals(0L, first!!.bytesPerSecond)

        assertNull(tracker.sample(1050, 10, 10_000))

        val second = tracker.sample(1100, 1000, 10_000)
        assertNotNull(second)
        assertEquals(1000L, second!!.transferredBytes)
        assertEquals(10_000L, second.totalBytes)
        assertEquals(10_000L, second.bytesPerSecond)

        assertNull(tracker.sample(1110, 1100, 10_000))
        val stepEmit = tracker.sample(1120, 1000 + 128 * 1024, 10_000)
        assertNotNull(stepEmit)
    }

    @Test
    fun `进度聚合器重置后重新推送首次采样`() {
        val tracker = TransferProgressTracker()
        assertNotNull(tracker.sample(0, 0, 100))
        assertNull(tracker.sample(10, 1, 100))
        tracker.reset()
        assertNotNull(tracker.sample(20, 2, 100))
    }
}

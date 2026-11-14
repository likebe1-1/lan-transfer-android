package com.manst.lantransfer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun bytes_formatsHumanReadableSizes() {
        assertEquals("--", Format.bytes(-1))
        assertEquals("0 B", Format.bytes(0))
        assertEquals("1023 B", Format.bytes(1023))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 KB", Format.bytes(1536))
        assertEquals("1.0 MB", Format.bytes(1024L * 1024L))
        assertEquals("1.0 GB", Format.bytes(1024L * 1024L * 1024L))
    }

    @Test
    fun speed_formatsPerSecondValue() {
        assertEquals("--", Format.speed(0))
        assertEquals("1.0 KB/s", Format.speed(1024))
    }

    @Test
    fun duration_formatsSecondsMinutesAndHours() {
        assertEquals("--", Format.duration(-1))
        assertEquals("0 秒", Format.duration(0))
        assertEquals("45 秒", Format.duration(45))
        assertEquals("1 分钟", Format.duration(60))
        assertEquals("2 分 05 秒", Format.duration(125))
        assertEquals("1 小时", Format.duration(3600))
        assertEquals("1 小时 02 分", Format.duration(3720))
    }

    @Test
    fun remainingTime_estimatesEtaFromSpeed() {
        // 1024 字节剩余、512 B/s → 约 2 秒
        assertEquals("剩余约 2 秒", Format.remainingTime(1024, 512))
        // 5 MB 剩余、1 MB/s → 约 5 秒
        assertEquals("剩余约 5 秒", Format.remainingTime(5L * 1024L * 1024L, 1024L * 1024L))
        // 剩余 0 → 即将完成；速度或大小未知 → 剩余时间未知
        assertEquals("即将完成", Format.remainingTime(0, 1024))
        assertEquals("剩余时间未知", Format.remainingTime(-1, 1024))
        assertEquals("剩余时间未知", Format.remainingTime(1024, 0))
        assertEquals("剩余时间未知", Format.remainingTime(-1, 0))
    }

    @Test
    fun remainingSeconds_roundsUpAndHandlesUnknown() {
        assertEquals(-1L, Format.remainingSeconds(-1, 1024))
        assertEquals(-1L, Format.remainingSeconds(1024, 0))
        assertEquals(0L, Format.remainingSeconds(0, 1024))
        assertEquals(1L, Format.remainingSeconds(1, 1024))
        assertEquals(2L, Format.remainingSeconds(1025, 1024))
    }

    @Test
    fun date_handlesUnknownValue() {
        assertEquals("--", Format.date(0))
        assertEquals("--", Format.date(-5))
    }
}
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
    fun date_handlesUnknownValue() {
        assertEquals("--", Format.date(0))
        assertEquals("--", Format.date(-5))
    }
}

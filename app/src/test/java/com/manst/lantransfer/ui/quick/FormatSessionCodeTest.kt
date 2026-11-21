package com.manst.lantransfer.ui.quick

import org.junit.Assert.assertEquals
import org.junit.Test

/** 接收码展示分组（8 位 / 旧版 6 位 / 其他长度原样返回）。 */
class FormatSessionCodeTest {

    @Test
    fun eightDigitCode_groupsFourPlusFour() {
        assertEquals("1234 5678", formatSessionCode("12345678"))
        assertEquals("1234 5678", formatSessionCode("1234 5678"))
    }

    @Test
    fun legacySixDigitCode_groupsThreePlusThree() {
        assertEquals("123 456", formatSessionCode("123456"))
    }

    @Test
    fun otherLengths_returnTrimmedInput() {
        assertEquals("12345", formatSessionCode(" 12345 "))
        assertEquals("", formatSessionCode(""))
        assertEquals("abc", formatSessionCode("abc"))
    }
}

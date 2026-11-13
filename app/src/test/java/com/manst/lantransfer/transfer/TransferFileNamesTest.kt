package com.manst.lantransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFileNamesTest {

    @Test
    fun `合法文件名保持不变`() {
        assertEquals("照片 2024.jpg", TransferFileNames.sanitize("照片 2024.jpg"))
        assertEquals(".hidden", TransferFileNames.sanitize(".hidden"))
        assertEquals("report.tar.gz", TransferFileNames.sanitize("report.tar.gz"))
    }

    @Test
    fun `路径分隔符与非法字符替换为下划线`() {
        assertEquals("a_b_c_d_e_f_g_h_i_j.txt", TransferFileNames.sanitize("a/b\\c:d*e?f\"g<h>i|j.txt"))
    }

    @Test
    fun `控制字符替换为下划线`() {
        assertEquals("a_b", TransferFileNames.sanitize("a\u0001b"))
        assertEquals("a_b", TransferFileNames.sanitize("a\u007Fb"))
    }

    @Test
    fun `空名与点号回退为默认名`() {
        assertEquals(TransferFileNames.FALLBACK_NAME, TransferFileNames.sanitize(null))
        assertEquals(TransferFileNames.FALLBACK_NAME, TransferFileNames.sanitize("   "))
        assertEquals(TransferFileNames.FALLBACK_NAME, TransferFileNames.sanitize("."))
        assertEquals(TransferFileNames.FALLBACK_NAME, TransferFileNames.sanitize(".."))
    }

    @Test
    fun `去掉结尾的点与空格`() {
        assertEquals("file", TransferFileNames.sanitize("file...   "))
        assertEquals("file", TransferFileNames.sanitize("  file.  "))
    }

    @Test
    fun `超长文件名截断但保留扩展名`() {
        val longWithExtension = "a".repeat(200) + ".jpg"
        val sanitized = TransferFileNames.sanitize(longWithExtension)
        assertEquals(TransferFileNames.MAX_NAME_LENGTH, sanitized.length)
        assertTrue(sanitized.endsWith(".jpg"))

        val longWithoutExtension = "b".repeat(300)
        assertEquals(TransferFileNames.MAX_NAME_LENGTH, TransferFileNames.sanitize(longWithoutExtension).length)
    }

    @Test
    fun `序号插在扩展名之前`() {
        assertEquals("photo (1).jpg", TransferFileNames.withSequenceSuffix("photo.jpg", 1))
        assertEquals("archive.tar (2).gz", TransferFileNames.withSequenceSuffix("archive.tar.gz", 2))
        assertEquals("noext (1)", TransferFileNames.withSequenceSuffix("noext", 1))
        assertEquals(".bashrc (1)", TransferFileNames.withSequenceSuffix(".bashrc", 1))
    }

    @Test
    fun `重名时追加递增序号`() {
        assertEquals("a.txt", TransferFileNames.uniqueName("a.txt", emptyList()))
        assertEquals("a (1).txt", TransferFileNames.uniqueName("a.txt", listOf("a.txt")))
        assertEquals("a (2).txt", TransferFileNames.uniqueName("a.txt", listOf("a.txt", "a (1).txt")))
    }

    @Test
    fun `查重忽略大小写`() {
        assertEquals("A (1).TXT", TransferFileNames.uniqueName("A.TXT", listOf("a.txt")))
        assertEquals("a (1).txt", TransferFileNames.uniqueName("a.txt", listOf("A.TXT")))
    }

    @Test
    fun `查重前先清洗文件名`() {
        assertEquals("a_b.txt", TransferFileNames.uniqueName("a/b.txt", emptyList()))
    }
}

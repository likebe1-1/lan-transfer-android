package com.manst.lantransfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** SCP 适配器使用的 ls -la 解析器单元测试。 */
class ScpListingParserTest {

    @Test
    fun parsesDirectoriesFilesAndSymlinks() {
        val output = """
            total 28
            drwxr-xr-x 2 tester users 4096 Nov 13 20:45 docs
            -rw-r--r-- 1 tester users 1234 Nov 13 20:45 report.bin
            -rw-r--r-- 1 tester users    0 Nov 13 20:45 empty.bin
            lrwxrwxrwx 1 tester users    7 Jan  2  2025 latest -> docs
            .
            ..
            garbage line
        """.trimIndent()

        val entries = ScpListingParser.parse(output, "/data", now = nowMillis())
        val byName = entries.associateBy { it.name }
        assertEquals(setOf("docs", "report.bin", "empty.bin", "latest"), byName.keys)

        val docs = byName.getValue("docs")
        assertTrue(docs.isDirectory)
        assertEquals("/data/docs", docs.path)
        assertEquals(0L, docs.size)
        assertTrue(docs.modifiedAt > 0L)
        assertEquals("drwxr-xr-x", docs.permissions)

        assertEquals(1234L, byName.getValue("report.bin").size)
        assertTrue(byName.getValue("report.bin").isFile)
        assertEquals(0L, byName.getValue("empty.bin").size)
        assertTrue(byName.getValue("empty.bin").isFile)

        val link = byName.getValue("latest")
        assertTrue(link.isSymlink)
        assertTrue(!link.isDirectory)
        assertEquals("/data/latest", link.path)

        assertTrue("名称中不能残留链接目标", entries.none { it.name.contains("->") })
        assertTrue("应过滤 . 与 ..", entries.none { it.name == "." || it.name == ".." })
    }

    @Test
    fun parsesEmptyListing() {
        assertEquals(emptyList<Any>(), ScpListingParser.parse("total 0\n", "/"))
    }

    @Test
    fun parsesFileNameWithSpaces() {
        val output = "-rw-r--r-- 1 tester users 42 Nov 13 20:45 my report 2026.bin\n"
        val entries = ScpListingParser.parse(output, "/")
        assertEquals(1, entries.size)
        assertEquals("my report 2026.bin", entries.first().name)
        assertEquals("/my report 2026.bin", entries.first().path)
    }

    @Test
    fun parsesOldYearTimestamp() {
        val output = "-rw-r--r-- 1 tester users 10 Jan  2  2025 old.txt\n"
        val entry = ScpListingParser.parse(output, "/").single()
        val expected = LocalDateTime.of(2025, 1, 2, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, entry.modifiedAt)
    }

    @Test
    fun ignoresMalformedLines() {
        val output = """
            total 4
            -rw-r--r-- 1 tester users not-a-size Nov 13 20:45 broken.bin
            drwxr-xr-x 2 tester users 4096 Foo 99 99:99 bad-month
            -rw-r--r-- 1 tester users 5 Nov 13 20:45 ok.txt
        """.trimIndent()
        val entries = ScpListingParser.parse(output, "/")
        assertEquals(listOf("ok.txt"), entries.map { it.name })
    }

    private fun nowMillis(): Long = LocalDateTime.of(2026, 11, 13, 12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

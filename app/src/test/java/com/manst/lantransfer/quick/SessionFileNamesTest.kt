package com.manst.lantransfer.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [SessionFileNames] 的 JVM 单元测试：文件名清洗/重名组合、临时文件命名、忽略规则、排序。 */
class SessionFileNamesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `清洗复用 ShareFileNames 并替换非法字符`() {
        assertEquals("照片 2024.jpg", SessionFileNames.sanitize("照片 2024.jpg"))
        assertEquals("a_b_c_d.txt", SessionFileNames.sanitize("a/b\\c:d.txt"))
        assertEquals(com.manst.lantransfer.data.ShareFileNames.FALLBACK_NAME, SessionFileNames.sanitize("   "))
        assertEquals(com.manst.lantransfer.data.ShareFileNames.FALLBACK_NAME, SessionFileNames.sanitize(".."))
    }

    @Test
    fun `重名组合先清洗再追加递增序号`() {
        assertEquals("a.txt", SessionFileNames.uniqueName("a.txt", emptyList()))
        assertEquals("a (1).txt", SessionFileNames.uniqueName("a.txt", listOf("a.txt")))
        assertEquals("a (2).txt", SessionFileNames.uniqueName("a.txt", listOf("a.txt", "a (1).txt")))
        // 清洗后与已有名字冲突（忽略大小写）
        assertEquals("a_b (1).txt", SessionFileNames.uniqueName("a/b.txt", listOf("A_B.TXT")))
    }

    @Test
    fun `导入临时文件命名与忽略规则`() {
        assertEquals(".photo.jpg.importing", SessionFileNames.importTempName("photo.jpg"))
        assertTrue(SessionFileNames.isIgnored(SessionFileNames.importTempName("photo.jpg")))
        assertTrue(SessionFileNames.isIgnored(".hidden"))
        assertTrue(SessionFileNames.isIgnored("遗留.importing"))
        assertFalse(SessionFileNames.isIgnored("photo.jpg"))
        assertFalse(SessionFileNames.isIgnored("importing.txt"))
    }

    @Test
    fun `接收码清洗为安全目录名`() {
        assertEquals("123456", SessionFileNames.sanitizeCode("123456"))
        assertEquals("123456", SessionFileNames.sanitizeCode(" 123 456 "))
        assertEquals("session", SessionFileNames.sanitizeCode(null))
        assertEquals("session", SessionFileNames.sanitizeCode("   "))
        assertEquals("session", SessionFileNames.sanitizeCode(".."))
        assertEquals("session", SessionFileNames.sanitizeCode("../.."))
        assertEquals("abc-def_1", SessionFileNames.sanitizeCode("abc-def_1"))
        assertEquals("a".repeat(32), SessionFileNames.sanitizeCode("a".repeat(64)))
    }

    @Test
    fun `文件按修改时间倒序同时间按名称`() {
        val files = listOf(
            QuickFile(name = "beta.txt", size = 1L, modifiedAt = 100L),
            QuickFile(name = "gamma.txt", size = 2L, modifiedAt = 300L),
            QuickFile(name = "Alpha.txt", size = 3L, modifiedAt = 100L),
            QuickFile(name = "alpha.txt", size = 4L, modifiedAt = 200L)
        )
        val sorted = SessionFileNames.sortFiles(files)
        assertEquals(listOf("gamma.txt", "alpha.txt", "Alpha.txt", "beta.txt"), sorted.map { it.name })
    }

    @Test
    fun `文件信息转换为 QuickFile`() {
        val file = temporaryFolder.newFile("photo.jpg")
        file.writeBytes(ByteArray(5) { it.toByte() })
        val timestamp = 1_700_000_000_000L
        assertTrue(file.setLastModified(timestamp))

        val quickFile = SessionFileNames.toQuickFile(file)

        assertEquals("photo.jpg", quickFile.name)
        assertEquals(5L, quickFile.size)
        assertEquals(timestamp, quickFile.modifiedAt)
    }
}

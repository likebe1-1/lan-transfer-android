package com.manst.lantransfer.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [QuickFolderPaths] 的 JVM 单元测试：相对路径规范化、远端目录推导、媒体库子目录清洗与文件键。 */
class QuickFolderPathTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun normalizeRelative_returnsNullForEmptyInput() {
        assertNull(QuickFolderPaths.normalizeRelative(null))
        assertNull(QuickFolderPaths.normalizeRelative(""))
        assertNull(QuickFolderPaths.normalizeRelative("   "))
        assertNull(QuickFolderPaths.normalizeRelative("."))
        assertNull(QuickFolderPaths.normalizeRelative(".."))
        assertNull(QuickFolderPaths.normalizeRelative("///"))
        assertNull(QuickFolderPaths.normalizeRelative("a/../.."))
    }

    @Test
    fun normalizeRelative_collapsesSeparatorsAndDropsDotSegments() {
        assertEquals("相册/2024/照片.jpg", QuickFolderPaths.normalizeRelative("/相册/2024/照片.jpg"))
        assertEquals("相册/2024/照片.jpg", QuickFolderPaths.normalizeRelative("相册\\2024\\照片.jpg"))
        assertEquals("a/b", QuickFolderPaths.normalizeRelative("a//./b/"))
        assertEquals("a/b", QuickFolderPaths.normalizeRelative(" a / b "))
        assertEquals("b", QuickFolderPaths.normalizeRelative("a/../b"))
        assertEquals("a/c", QuickFolderPaths.normalizeRelative("a/b/../c"))
    }

    @Test
    fun normalizeRelative_capsDepth() {
        val deep = (1..(QuickFolderPaths.MAX_RELATIVE_DEPTH + 5)).joinToString("/") { "d" + it }
        val normalized = QuickFolderPaths.normalizeRelative(deep)
        assertEquals(QuickFolderPaths.MAX_RELATIVE_DEPTH, requireNotNull(normalized).split('/').size)
    }

    @Test
    fun normalizeRelative_capsLength() {
        val longName = "a".repeat(QuickFolderPaths.MAX_RELATIVE_LENGTH + 100)
        val normalized = QuickFolderPaths.normalizeRelative(longName)
        assertEquals(QuickFolderPaths.MAX_RELATIVE_LENGTH, requireNotNull(normalized).length)
    }

    @Test
    fun relativeDirOf_derivesParentDirectory() {
        assertNull(QuickFolderPaths.relativeDirOf(null))
        assertNull(QuickFolderPaths.relativeDirOf("/"))
        assertNull(QuickFolderPaths.relativeDirOf("/顶层文件.txt"))
        assertEquals("相册", QuickFolderPaths.relativeDirOf("/相册/照片.jpg"))
        assertEquals("相册/2024", QuickFolderPaths.relativeDirOf("/相册/2024/照片.jpg"))
        assertEquals("a/b", QuickFolderPaths.relativeDirOf("a/b/c.txt"))
        assertEquals("a/b", QuickFolderPaths.relativeDirOf("/a/b/../b/c.txt"))
    }

    @Test
    fun relativePathOf_returnsNullForTopLevelFile() {
        val directory = temporaryFolder.newFolder("session")
        val topLevel = File(directory, "顶层.txt").apply { writeText("x") }
        val nested = File(directory, "相册/2024/照片.txt").apply {
            parentFile?.mkdirs()
            writeText("y")
        }
        assertNull(QuickFolderPaths.relativePathOf(directory, topLevel))
        assertEquals("相册/2024/照片.txt", QuickFolderPaths.relativePathOf(directory, nested))
    }

    @Test
    fun sanitizeRelativeDir_cleansEachSegment() {
        assertEquals("相册/2024", QuickFolderPaths.sanitizeRelativeDir("相册/2024"))
        assertEquals("a_b/c_d", QuickFolderPaths.sanitizeRelativeDir("a:b/c*d"))
        assertEquals("a/b", QuickFolderPaths.sanitizeRelativeDir(" /a//./b/ "))
        assertNull(QuickFolderPaths.sanitizeRelativeDir(null))
        assertNull(QuickFolderPaths.sanitizeRelativeDir(".."))
    }

    @Test
    fun sanitizeRelativeDir_neverProducesTraversal() {
        val cleaned = QuickFolderPaths.sanitizeRelativeDir("../../etc/passwd")
        assertTrue("清洗结果不能包含 ..：" + cleaned, cleaned == null || !cleaned.split('/').contains(".."))
    }

    @Test
    fun fileKey_prefersRelativePathAndFallsBackToName() {
        assertEquals("照片.txt", QuickFolderPaths.fileKey(null, "照片.txt"))
        assertEquals("相册/照片.txt", QuickFolderPaths.fileKey("相册/照片.txt", "照片.txt"))
        assertEquals("相册/照片.txt", QuickFolderPaths.fileKey("/相册\\照片.txt", "照片.txt"))
        assertEquals("照片.txt", QuickFolderPaths.fileKey("  ", "照片.txt"))
    }
}

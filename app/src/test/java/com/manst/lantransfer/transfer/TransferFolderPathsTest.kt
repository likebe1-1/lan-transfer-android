package com.manst.lantransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TransferFolderPaths] 的纯逻辑单元测试：相对路径清洗、本地子目录、远端路径拼接。 */
class TransferFolderPathsTest {

    @Test
    fun `相对路径保留层级并清洗非法字符`() {
        assertEquals("2024/相册/a.jpg", TransferFolderPaths.sanitizeRelativePath("2024/相册/a.jpg"))
        assertEquals("a/b.txt", TransferFolderPaths.sanitizeRelativePath("a\\b.txt"))
        assertEquals("a_b/c_.txt", TransferFolderPaths.sanitizeRelativePath("a:b/c*.txt"))
        assertEquals("a/b", TransferFolderPaths.sanitizeRelativePath("/a/./b/"))
        assertEquals("a/b", TransferFolderPaths.sanitizeRelativePath("../a/../b"))
        assertEquals("x.txt", TransferFolderPaths.sanitizeRelativePath("../../x.txt"))
        assertNull(TransferFolderPaths.sanitizeRelativePath(""))
        assertNull(TransferFolderPaths.sanitizeRelativePath("   "))
        assertNull(TransferFolderPaths.sanitizeRelativePath("../.."))
    }

    @Test
    fun `本地下载子目录按文件夹名与远端父目录拼接`() {
        assertEquals(
            "相册/2024/01",
            TransferFolderPaths.downloadLocalRelativeDir("相册", "2024/01/a.jpg")
        )
        assertEquals("相册", TransferFolderPaths.downloadLocalRelativeDir("相册", "a.jpg"))
        assertEquals(
            "文件夹/子目录",
            TransferFolderPaths.downloadLocalRelativeDir("文件夹", "子目录/b.jpg")
        )
        // 文件夹名同样会被清洗
        assertEquals(
            "a_b/2024",
            TransferFolderPaths.downloadLocalRelativeDir("a/b", "2024/a.jpg")
        )
        assertEquals("未命名文件", TransferFolderPaths.downloadLocalRelativeDir(null, "a.jpg"))
    }

    @Test
    fun `递归上传的远端路径保留子目录且不允许穿越`() {
        assertEquals("/photos/2024/a.jpg", TransferFolderPaths.remotePathForUpload("/photos", "2024/a.jpg"))
        assertEquals("/photos/a.jpg", TransferFolderPaths.remotePathForUpload("/photos", "../a.jpg"))
        assertEquals("/photos/a/b.txt", TransferFolderPaths.remotePathForUpload("/photos/", "a\\b.txt"))
        assertEquals("/photos/a_b.txt", TransferFolderPaths.remotePathForUpload("/photos", "a:b.txt"))
        assertEquals("/photos/未命名文件", TransferFolderPaths.remotePathForUpload("/photos", ""))
        assertEquals("/a.jpg", TransferFolderPaths.remotePathForUpload("/", "a.jpg"))
    }

    @Test
    fun `单次文件夹传输文件数上限`() {
        assertEquals(1000, TransferFolderPaths.MAX_FOLDER_FILES)
    }
}

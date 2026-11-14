package com.manst.lantransfer.transfer

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 文件夹传输相关 Android 适配逻辑的设备测试（只要求编译，Lead 在模拟器执行）。
 *
 * 覆盖离线可验证的部分：
 * 1) [MediaStoreDownloadStore] 支持本地相对子目录（Download/<子目录>/），pending → commit → 返回可读 Uri；
 * 2) 同一子目录内重名自动追加 " (1)"；relativeDir 中的穿越/非法片段被清洗；
 * 3) 下载完成通知在存在 localUri 时附带「打开」动作（ACTION_VIEW），无 localUri 时不附带。
 *
 * 真实 FTP/SFTP 递归文件夹传输由 Lead 的端到端设备测试覆盖（需要现场起服务端）。
 */
@RunWith(AndroidJUnit4::class)
class FolderTransferDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private lateinit var downloadStore: MediaStoreDownloadStore

    private val createdUris = CopyOnWriteArrayList<Uri>()
    private val createdRelativeDirs = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        downloadStore = MediaStoreDownloadStore(context)
    }

    @After
    fun tearDown() {
        createdUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdUris.clear()
        createdRelativeDirs.forEach { relativePath ->
            runCatching {
                resolver.delete(
                    downloadsCollection(),
                    MediaStore.Downloads.RELATIVE_PATH + " = ?",
                    arrayOf(relativePath)
                )
            }
        }
        createdRelativeDirs.clear()
    }

    @Test
    fun pendingFileInRelativeDir_canBeCommittedAndReadBack() {
        val relativeDir = newRelativeDir("commit")
        val displayName = downloadStore.reserveFileName("folder-test.txt", relativeDir)
        assertEquals("folder-test.txt", displayName)
        val content = "文件夹子目录内容".toByteArray(Charsets.UTF_8)

        val pendingUri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += pendingUri
        downloadStore.openSink(pendingUri).use { it.write(content) }
        val committedUri = downloadStore.commitAndGetUri(pendingUri)
        downloadStore.releaseFileName(displayName, relativeDir)

        assertTrue(mediaRowExists(displayName, relativePathOf(relativeDir)))
        val readBack = resolver.openInputStream(committedUri)?.use { it.readBytes() }
        assertNotNull(readBack)
        assertTrue(content.contentEquals(readBack!!))
    }

    @Test
    fun duplicateNameInSameRelativeDir_getsSequenceSuffix() {
        val relativeDir = newRelativeDir("dedupe")
        val first = downloadStore.reserveFileName("dup.txt", relativeDir)
        val second = downloadStore.reserveFileName("dup.txt", relativeDir)
        val otherDir = newRelativeDir("dedupe-other")
        val other = downloadStore.reserveFileName("dup.txt", otherDir)

        assertEquals("dup.txt", first)
        assertEquals("dup (1).txt", second)
        // 不同子目录互不影响
        assertEquals("dup.txt", other)

        downloadStore.releaseFileName(first, relativeDir)
        downloadStore.releaseFileName(second, relativeDir)
        downloadStore.releaseFileName(other, otherDir)
    }

    @Test
    fun relativeDir_traversalSegmentsAreSanitized() {
        val suffix = newSuffix()
        val maliciousDir = "../../escape/" + suffix
        val requestedName = "escape-" + suffix + ".txt"
        val displayName = downloadStore.reserveFileName(requestedName, maliciousDir)
        val pendingUri = downloadStore.createPendingFile(displayName, maliciousDir)
        createdUris += pendingUri
        // 先写入并 commit 再查询：部分设备/版本下 pending 行不会出现在普通 MediaStore 查询结果中
        downloadStore.openSink(pendingUri).use { it.write(byteArrayOf(1)) }
        downloadStore.commitAndGetUri(pendingUri)

        val storedRelativePath = queryRelativePathByName(displayName)
        downloadStore.releaseFileName(displayName, maliciousDir)

        assertNotNull("应能查询到 pending 记录的 RELATIVE_PATH", storedRelativePath)
        assertFalse("相对路径不应包含 ..：" + storedRelativePath, storedRelativePath!!.contains(".."))
        assertTrue(storedRelativePath.startsWith(Environment.DIRECTORY_DOWNLOADS + "/"))
        assertTrue("应进入 escape/<suffix>/ 子目录：" + storedRelativePath, storedRelativePath.endsWith("escape/" + suffix + "/"))
        createdRelativeDirs += storedRelativePath
    }

    @Test
    fun resultNotification_addsOpenActionOnlyWhenLocalUriPresent() {
        val relativeDir = newRelativeDir("notify")
        val displayName = downloadStore.reserveFileName("open-me.txt", relativeDir)
        val pendingUri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += pendingUri
        downloadStore.openSink(pendingUri).use { it.write("open content".toByteArray(Charsets.UTF_8)) }
        val committedUri = downloadStore.commitAndGetUri(pendingUri)
        downloadStore.releaseFileName(displayName, relativeDir)

        val completed = completedDownloadTask(localUri = committedUri.toString())
        val withFile = TransferNotifications.buildResultNotification(context, completed)
        assertNotNull(withFile.actions)
        assertTrue("下载完成通知应带打开文件动作", withFile.actions.isNotEmpty())
        assertNotNull(withFile.contentIntent)

        val withoutUri = TransferNotifications.buildResultNotification(
            context,
            completed.copy(localUri = null)
        )
        assertTrue(
            "没有 localUri 时不应添加打开文件动作",
            withoutUri.actions == null || withoutUri.actions.isEmpty()
        )
        assertNotNull(withoutUri.contentIntent)
    }

    // ------------------------------------------------------------ 辅助

    private fun newSuffix(): String = System.currentTimeMillis().toString() + "-" + (Math.random() * 1000).toInt()

    /** 生成唯一的测试子目录（相对 Download/），并登记清理时的 RELATIVE_PATH。 */
    private fun newRelativeDir(tag: String): String {
        val suffix = newSuffix()
        val dir = "LanTransferFolderTest/" + tag + "-" + suffix
        createdRelativeDirs += relativePathOf(dir)
        return dir
    }

    private fun relativePathOf(relativeDir: String): String =
        Environment.DIRECTORY_DOWNLOADS + "/" + relativeDir + "/"

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun mediaRowExists(displayName: String, relativePath: String): Boolean {
        return resolver.query(
            downloadsCollection(),
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?",
            arrayOf(displayName, relativePath),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false
    }

    private fun queryRelativePathByName(displayName: String): String? {
        return resolver.query(
            downloadsCollection(),
            arrayOf(MediaStore.Downloads.RELATIVE_PATH),
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }
    }

    private fun completedDownloadTask(localUri: String?): TransferTask = TransferTask(
        id = "folder-notify-task",
        profileId = "test-profile",
        serverName = "测试服务器",
        protocolLabel = "FTP",
        direction = TransferDirection.DOWNLOAD,
        displayName = "open-me.txt",
        remotePath = "/photos/open-me.txt",
        localDisplayName = "open-me.txt",
        localUri = localUri,
        localRelativeDir = "相册/2024",
        totalBytes = 100L,
        transferredBytes = 100L,
        state = TransferState.COMPLETED,
        finishedAt = System.currentTimeMillis()
    )
}
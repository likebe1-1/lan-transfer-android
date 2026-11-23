package com.manst.lantransfer.transfer

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「下载直接写 MediaStore pending 文件」方案的设备测试（无真实服务端，用本地合成数据验证落盘语义）。
 *
 * 覆盖断点续传的关键实现点：
 * 1) 按磁盘实际大小定位续写（[MediaStoreDownloadStore.openPendingSink] offset > 0），提交后内容完整；
 * 2) offset == 0 时截断旧内容，重新下载不会残留上次的字节；
 * 3) 续写偏移与磁盘实际大小不一致时抛 IOException（宁可重下也不写坏文件）；
 * 4) [MediaStoreDownloadStore.isPending] 能区分 pending / 已提交；
 * 5) [MediaStoreDownloadStore.discard] 能删除未提交记录，不留残骸。
 *
 * 说明：本机当前没有在线模拟器/真机时只要求 assembleGithubDebugAndroidTest 编译通过。
 */
@RunWith(AndroidJUnit4::class)
class PendingDownloadResumeDeviceTest {

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
    fun resumeFromDiskSize_appendsRemainingBytesAndCommitsCompleteFile() {
        val relativeDir = newRelativeDir("resume")
        val displayName = downloadStore.reserveFileName("resume-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        val head = "hello".toByteArray(Charsets.UTF_8)
        val tail = " world".toByteArray(Charsets.UTF_8)

        downloadStore.openPendingSink(uri, 0L).use { it.write(head) }
        downloadStore.releaseFileName(displayName, relativeDir)
        assertEquals("写入第一段后 pending 文件大小应为已写字节", head.size.toLong(), downloadStore.localFileSize(uri))
        assertTrue("提交前应处于 pending 状态", downloadStore.isPending(uri))

        // 模拟断点续传：续传偏移取磁盘实际大小，而不是任务快照里的 transferredBytes。
        val resumeOffset = downloadStore.localFileSize(uri)
            ?: error("无法读取 pending 文件大小")
        downloadStore.openPendingSink(uri, resumeOffset).use { it.write(tail) }
        assertEquals(
            "续写后大小应为两段之和",
            (head.size + tail.size).toLong(),
            downloadStore.localFileSize(uri)
        )

        val committed = downloadStore.commitAndGetUri(uri)
        assertFalse("提交后应不再是 pending", downloadStore.isPending(committed))
        val content = resolver.openInputStream(committed)?.use { it.readBytes() }
        assertEquals("续传后的文件内容必须完整", "hello world", content?.toString(Charsets.UTF_8))
    }

    @Test
    fun restartAtZero_truncatesPreviouslyWrittenBytes() {
        val relativeDir = newRelativeDir("restart")
        val displayName = downloadStore.reserveFileName("restart-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        downloadStore.openPendingSink(uri, 0L).use { it.write("0123456789".toByteArray(Charsets.UTF_8)) }

        // 远端文件被替换后从头重下：必须先截断，不能让旧字节残留成文件尾部。
        downloadStore.openPendingSink(uri, 0L).use { it.write("abc".toByteArray(Charsets.UTF_8)) }
        downloadStore.releaseFileName(displayName, relativeDir)
        assertEquals("从头重写后大小应等于新内容长度", 3L, downloadStore.localFileSize(uri))

        val committed = downloadStore.commitAndGetUri(uri)
        val content = resolver.openInputStream(committed)?.use { it.readBytes() }
        assertEquals("abc", content?.toString(Charsets.UTF_8))
    }

    @Test
    fun resumeOffsetBeyondDiskSize_throwsWithoutCorruptingFile() {
        val relativeDir = newRelativeDir("mismatch")
        val displayName = downloadStore.reserveFileName("mismatch-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        downloadStore.openPendingSink(uri, 0L).use { it.write("abc".toByteArray(Charsets.UTF_8)) }

        try {
            downloadStore.openPendingSink(uri, 5L).use { it.write("x".toByteArray()) }
            fail("续写偏移大于磁盘实际大小时应抛 IOException")
        } catch (expected: IOException) {
            // 预期：宁可让上层重新下载，也不在错误位置写入导致文件损坏。
        }
        downloadStore.releaseFileName(displayName, relativeDir)
        assertEquals("失败后原文件字节数不应改变", 3L, downloadStore.localFileSize(uri))

        val committed = downloadStore.commitAndGetUri(uri)
        val content = resolver.openInputStream(committed)?.use { it.readBytes() }
        assertEquals("abc", content?.toString(Charsets.UTF_8))
    }

    @Test
    fun discard_removesUncommittedPendingRecord() {
        val relativeDir = newRelativeDir("discard")
        val displayName = downloadStore.reserveFileName("discard-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        downloadStore.openPendingSink(uri, 0L).use { it.write(ByteArray(128)) }
        assertTrue(downloadStore.isPending(uri))

        downloadStore.discard(uri)
        downloadStore.releaseFileName(displayName, relativeDir)
        assertNull("discard 后 pending 记录应已删除", downloadStore.localFileSize(uri))
    }

    // ------------------------------------------------------------ 辅助

    private fun newSuffix(): String =
        System.currentTimeMillis().toString() + "-" + (Math.random() * 1000).toInt()

    private fun newRelativeDir(tag: String): String {
        val dir = "LanTransferPendingTest/" + tag + "-" + newSuffix()
        createdRelativeDirs += Environment.DIRECTORY_DOWNLOADS + "/" + dir + "/"
        return dir
    }

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
}

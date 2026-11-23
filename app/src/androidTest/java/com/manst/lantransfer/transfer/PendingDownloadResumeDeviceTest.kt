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
 * 3) 续写偏移越过文件末尾时抛 IOException（宁可重下也不写坏文件）；
 * 4) [MediaStoreDownloadStore.isPending] 能区分 pending / 已提交；
 * 5) [MediaStoreDownloadStore.discard] 能删除未提交记录，不留残骸；
 * 6) [MediaStoreDownloadStore.preallocate]（ftruncate）能把 pending 文件预分配成远端大小，
 *    预分配后文件大小 == 远端大小但内容仍是空的，按偏移写入的内容必须落点正确。
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
    fun preallocate_reservesRemoteSizeAndOffsetWriteLandsAtCorrectPosition() {
        val relativeDir = newRelativeDir("preallocate")
        val displayName = downloadStore.reserveFileName("preallocate-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        val payload = "world".toByteArray(Charsets.UTF_8)

        assertTrue("预分配应当成功（ftruncate 可用）", downloadStore.preallocate(uri, 64L * 1024L))
        assertEquals(
            "预分配后本地文件大小应等于远端大小",
            64L * 1024L,
            downloadStore.localFileSize(uri)
        )
        assertTrue("预分配不应改变 pending 状态", downloadStore.isPending(uri))

        // 预分配后仍可按偏移定位写入：真实已写偏移 1024，而文件大小是远端大小，
        // 二者不相等是正常情况（openPendingSink 只校验偏移不越过文件末尾）。
        val offset = 1024L
        downloadStore.openPendingSink(uri, offset).use { it.write(payload) }
        downloadStore.releaseFileName(displayName, relativeDir)

        val committed = downloadStore.commitAndGetUri(uri)
        val bytes = readAllBytes(committed)
        assertEquals("提交后文件大小应保持预分配大小", 64 * 1024, bytes.size)
        assertEquals("偏移之前的字节应仍是预分配的空内容（0）", 0, bytes[0].toInt())
        assertEquals(
            "按 offset 写入的内容必须落在正确位置",
            payload.toList(),
            bytes.copyOfRange(offset.toInt(), offset.toInt() + payload.size).toList()
        )
    }

    @Test
    fun preallocateThenSequentialWrite_thenResumeFromRealWrittenOffset() {
        // 模拟真实下载顺序：打开 offset=0 的写入流（截断）-> 预分配远端大小 -> 写入前段数据；
        // 模拟中断后按「真实已写偏移」（而不是文件大小）续写剩余数据。
        val relativeDir = newRelativeDir("prealloc-resume")
        val displayName = downloadStore.reserveFileName("prealloc-resume-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        val head = "hello".toByteArray(Charsets.UTF_8)
        val tail = " world".toByteArray(Charsets.UTF_8)
        val remoteSize = 8L * 1024L
        var writtenOffset = 0L

        downloadStore.openPendingSink(uri, 0L).use { sink ->
            assertTrue(downloadStore.preallocate(uri, remoteSize))
            assertEquals("预分配后文件大小即远端大小", remoteSize, downloadStore.localFileSize(uri))
            sink.write(head)
            writtenOffset = head.size.toLong()
        }
        assertEquals(
            "写入前段数据不应改变预分配出来的文件大小",
            remoteSize,
            downloadStore.localFileSize(uri)
        )

        // 断点续传：偏移取真实的连续已写字节（head.size），不是磁盘大小（remoteSize）。
        downloadStore.openPendingSink(uri, writtenOffset).use { it.write(tail) }
        downloadStore.releaseFileName(displayName, relativeDir)

        val committed = downloadStore.commitAndGetUri(uri)
        val bytes = readAllBytes(committed)
        assertEquals("文件大小应保持不变", remoteSize.toInt(), bytes.size)
        assertEquals(
            "前段内容正确",
            head.toList(),
            bytes.copyOfRange(0, head.size).toList()
        )
        assertEquals(
            "续写内容从真实已写偏移继续",
            tail.toList(),
            bytes.copyOfRange(head.size, head.size + tail.size).toList()
        )
        assertEquals("其余部分应仍是预分配的空内容", 0, bytes[head.size + tail.size].toInt())
    }

    @Test
    fun preallocate_invalidSizeIsIgnoredSafely() {
        val relativeDir = newRelativeDir("prealloc-invalid")
        val displayName = downloadStore.reserveFileName("prealloc-invalid-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri

        assertFalse("未知/非法大小不应预分配", downloadStore.preallocate(uri, 0L))
        assertFalse("负大小不应预分配", downloadStore.preallocate(uri, -1L))
        assertEquals("非法预分配不应改变文件", 0L, downloadStore.localFileSize(uri))
        downloadStore.releaseFileName(displayName, relativeDir)
    }

    @Test
    fun truncateTo_shrinksPreallocatedFileToActualWrittenBytes() {
        // 远端列表里的 size 可能过期：实际写入字节少于预分配大小时裁掉尾部 0 填充。
        val relativeDir = newRelativeDir("truncate")
        val displayName = downloadStore.reserveFileName("truncate-device.bin", relativeDir)
        val uri = downloadStore.createPendingFile(displayName, relativeDir)
        createdUris += uri
        // 真实顺序：先打开 offset=0 的写入流（截断），再预分配，然后写入实际字节。
        downloadStore.openPendingSink(uri, 0L).use { sink ->
            assertTrue(downloadStore.preallocate(uri, 16L * 1024L))
            sink.write(ByteArray(100))
        }
        assertEquals("下载中断后文件仍保持预分配大小", 16L * 1024L, downloadStore.localFileSize(uri))

        assertTrue(downloadStore.truncateTo(uri, 100L))
        assertEquals("应裁掉预分配的空尾巴", 100L, downloadStore.localFileSize(uri))
        downloadStore.releaseFileName(displayName, relativeDir)
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

    private fun readAllBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取下载文件")

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

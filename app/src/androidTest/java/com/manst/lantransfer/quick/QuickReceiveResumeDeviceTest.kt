package com.manst.lantransfer.quick

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 快速传文件「接收断点续传」设备测试（只要求编译，由 Lead 统一跑）：
 * 真实起发送会话（SFTP 支持续传）→ 第一次接收在落盘 commit 阶段模拟中断（分片已下载完整）→
 * 把分片截断为一半制造断点 → 第二次接收应从断点续传并成功落盘、分片清理、文件大小完整。
 */
@RunWith(AndroidJUnit4::class)
class QuickReceiveResumeDeviceTest {

    private val tag = "QuickResumeTest"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val resolver get() = context.contentResolver

    private var sender: DefaultQuickSendManager? = null

    private val receivers = mutableListOf<DefaultQuickReceiveManager>()

    private val createdUris = mutableListOf<Uri>()

    private val partialDirectory: File
        get() = File(context.cacheDir, QuickPartialStore.DIRECTORY_NAME)

    @Before
    fun setUp() = runBlocking {
        runCatching { app.container.localServerManager.stop() }
        clearPartials()
        deleteTestDownloads()
    }

    @After
    fun tearDown() = runBlocking {
        receivers.forEach { receiver -> runCatching { receiver.leave() } }
        receivers.clear()
        runCatching { sender?.stopSession() }
        sender = null
        runCatching { app.container.localServerManager.stop() }
        createdUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdUris.clear()
        clearPartials()
        deleteTestDownloads()
    }

    @Test
    fun sftpResume_afterInterruptedDownload_continuesFromPartialAndCleansUp(): Unit = runBlocking {
        val stamp = System.currentTimeMillis()
        val prefix = "quick-resume-" + stamp
        val fileName = prefix + "-big.bin"
        val content = ByteArray(512 * 1024) { index -> (index % 251).toByte() }
        val sourceUri = createContentUri(fileName, content)

        val sendManager = DefaultQuickSendManager(
            context,
            app.container.localServerManager,
            app.container.sessionFileStore
        )
        sender = sendManager
        val started = sendManager.startSession(listOf(sourceUri))
        assertTrue("启动发送会话失败：" + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)
        val waiting = withTimeout(30_000L) {
            sendManager.state.first { state -> state.phase == QuickSendPhase.WAITING || state.error != null }
        }
        assertEquals("会话应进入等待状态：" + waiting.error, QuickSendPhase.WAITING, waiting.phase)
        val session = requireNotNull(waiting.sessionInfo) { "缺少会话信息" }
        val target = QuickJoinTarget(
            code = waiting.code,
            host = "127.0.0.1",
            protocol = TransferProtocol.SFTP,
            ftpPort = session.ftpPort,
            ftpsPort = session.ftpsPort,
            sshPort = session.sshPort
        )

        // 第一次：真实下载完成后在「落盘 commit」阶段模拟中断，分片应完整保留
        val flakySink = FailingCommitDownloadSink(app.container.quickDownloadSink)
        val firstReceiver = DefaultQuickReceiveManager(context, flakySink)
        receivers += firstReceiver
        assertTrue("首次连接失败", firstReceiver.join(target).isSuccess)
        val firstResult = withTimeout(90_000L) {
            firstReceiver.state.first { state ->
                state.failedFiles.isNotEmpty() || state.completedFiles.isNotEmpty()
            }
        }
        assertTrue("首次接收应模拟落盘失败，实际 completed=" + firstResult.completedFiles, firstResult.failedFiles.isNotEmpty())
        assertEquals(0, firstResult.completedFiles.size)
        firstReceiver.leave()
        receivers.remove(firstReceiver)

        // 找到断点分片：首次已下载完整（中断发生在分片完成后的落盘阶段）
        val partials = partialFiles()
        assertTrue("应保留断点分片，实际=" + partials.map { it.name }, partials.isNotEmpty())
        val partial = partials.maxByOrNull { it.length() }!!
        assertEquals("分片应包含完整内容", content.size.toLong(), partial.length())

        // 截断一半，制造「下载到一半」的断点，验证第二次从 offset 续传
        val resumeOffset = content.size / 2
        RandomAccessFile(partial, "rw").use { it.setLength(resumeOffset.toLong()) }
        Log.i(tag, "已把分片截断到 " + resumeOffset + " 字节，准备续传")

        // 第二次：真实 sink，SFTP 支持续传，应从断点继续并成功落盘
        val secondReceiver = DefaultQuickReceiveManager(context, app.container.quickDownloadSink)
        receivers += secondReceiver
        assertTrue("续传连接失败", secondReceiver.join(target).isSuccess)
        val completed = withTimeout(90_000L) {
            secondReceiver.state.first { state ->
                state.completedFiles.isNotEmpty() || state.failedFiles.isNotEmpty()
            }
        }
        assertEquals("续传接收失败：" + completed.error, emptyList<String>(), completed.failedFiles)
        assertEquals(1, completed.completedFiles.size)
        val displayName = completed.completedFiles.first()
        assertTrue("续传后应能在下载目录查到文件：" + displayName, queryDownloadDisplayNameExists(displayName))
        assertEquals("续传后文件大小应完整", content.size.toLong(), queryDownloadSize(displayName))
        assertTrue("成功后分片应被清理，实际=" + partialFiles().map { it.name }, partialFiles().isEmpty())
        Log.i(tag, "断点续传接收完成：" + displayName)
    }

    // ------------------------------------------------------------ 假 sink / 辅助方法

    /** 包装真实下载 sink：第一次 commit 故意失败，模拟「下载完成但落盘中断」。 */
    private class FailingCommitDownloadSink(private val delegate: QuickDownloadSink) : QuickDownloadSink {

        private var failNextCommit = true

        override fun open(displayName: String, mimeType: String?): QuickDownloadTarget =
            openInDir(displayName, null, mimeType)

        override fun openInDir(
            displayName: String,
            relativeDir: String?,
            mimeType: String?
        ): QuickDownloadTarget {
            val target = delegate.openInDir(displayName, relativeDir, mimeType)
            return object : QuickDownloadTarget {
                override val displayName: String = target.displayName
                override val outputStream: OutputStream = target.outputStream

                override fun commit(): Result<String> {
                    if (failNextCommit) {
                        failNextCommit = false
                        target.abort()
                        return Result.failure(IOException("模拟落盘中断（测试）"))
                    }
                    return target.commit()
                }

                override fun abort() = target.abort()
            }
        }
    }

    private fun partialFiles(): List<File> =
        partialDirectory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }.orEmpty()

    private fun clearPartials() {
        runCatching {
            partialDirectory.listFiles()?.forEach { file -> runCatching { file.delete() } }
        }
    }

    private fun createContentUri(displayName: String, content: ByteArray): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, TEST_SOURCE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建测试 content Uri" }
        createdUris += uri
        try {
            val output = requireNotNull(resolver.openOutputStream(uri)) { "无法写入测试 content Uri" }
            output.use { it.write(content) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun queryDownload(displayName: String, column: String): String? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(column),
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
        }
    }

    private fun queryDownloadDisplayNameExists(displayName: String): Boolean =
        queryDownload(displayName, MediaStore.Downloads.DISPLAY_NAME) != null

    private fun queryDownloadSize(displayName: String): Long {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.SIZE),
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor -> if (!cursor.moveToFirst()) -1L else cursor.getLong(0) } ?: -1L
    }

    private fun deleteTestDownloads() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(collection, MediaStore.Downloads.RELATIVE_PATH + " = ?", arrayOf(TEST_SOURCE_DIR))
        }
        runCatching {
            resolver.delete(collection, MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", arrayOf("quick-resume-%"))
        }
    }

    private companion object {
        val TEST_SOURCE_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickTest/"
    }
}
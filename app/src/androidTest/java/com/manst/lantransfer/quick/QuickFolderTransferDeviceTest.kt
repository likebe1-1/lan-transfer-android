package com.manst.lantransfer.quick

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 快捷传输「文件夹」能力的设备测试（只要求编译，由 Lead 统一跑）：
 *
 * 1. [receiveRecursiveFolder_savesUnderDownloadSubdirectories]：真实起发送会话（普通文件），
 *    在会话目录里手工建子目录模拟文件夹结构与对方上传，接收端递归扫描并用 openInDir 下载，
 *    断言文件落到 Download/<相对子目录>/ 且保留结构；随后断言发送端递归识别收到文件（relativePath）
 *    且 exportReceived 支持相对路径。
 * 2. [treeUriImport_keepsRelativePaths]：真实 SAF 目录树无法在仪器测试里授权，用注入的
 *    FakeTreeSessionFileStore 模拟 importDirectory（契约语义：relativePath = 相对会话目录真实路径，
 *    保留被选目录名），验证发送端 tree/file 混合导入、sendingFiles.relativePath 与真实本机服务器
 *    递归浏览/接收子目录文件。
 */
@RunWith(AndroidJUnit4::class)
class QuickFolderTransferDeviceTest {

    private val tag = "QuickFolderTest"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val resolver get() = context.contentResolver

    private lateinit var sender: DefaultQuickSendManager

    private val receivers = mutableListOf<DefaultQuickReceiveManager>()

    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setUp() = runBlocking {
        runCatching { app.container.localServerManager.stop() }
        sender = DefaultQuickSendManager(
            context,
            app.container.localServerManager,
            app.container.sessionFileStore
        )
        sender.stopSession()
        deleteTestDownloads()
    }

    @After
    fun tearDown() = runBlocking {
        receivers.forEach { receiver -> runCatching { receiver.leave() } }
        receivers.clear()
        runCatching { sender.stopSession() }
        runCatching { app.container.localServerManager.stop() }
        createdUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdUris.clear()
        deleteTestDownloads()
    }

    // ------------------------------------------------------------ 测试 1：递归接收

    @Test
    fun receiveRecursiveFolder_savesUnderDownloadSubdirectories() = runBlocking {
        val stamp = System.currentTimeMillis()
        val prefix = "quick-folder-" + stamp
        val topName = prefix + "-top.txt"
        val topUri = createContentUri(topName, "顶层文件内容".repeat(80).toByteArray(Charsets.UTF_8))

        val started = sender.startSession(listOf(topUri))
        assertTrue("启动发送会话失败：" + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)
        val waiting = withTimeout(30_000L) {
            sender.state.first { state -> state.phase == QuickSendPhase.WAITING || state.error != null }
        }
        assertEquals("会话应进入等待状态：" + waiting.error, QuickSendPhase.WAITING, waiting.phase)
        val session = requireNotNull(waiting.sessionInfo) { "缺少会话信息" }
        val sessionDirectory = File(waiting.sessionDirectory)
        assertTrue("会话目录应存在", sessionDirectory.isDirectory)

        // 模拟文件夹结构（真实场景由 SessionFileStore.importDirectory 落盘，这里直接在共享会话目录造子目录）
        val folderName = prefix + "-folder"
        val photoName = prefix + "-photo.txt"
        val notesName = prefix + "-notes.txt"
        writeSessionFile(sessionDirectory, folderName + "/2024/" + photoName, "照片内容".repeat(120).toByteArray())
        writeSessionFile(sessionDirectory, folderName + "/" + notesName, "笔记内容".repeat(30).toByteArray())

        val receiver = DefaultQuickReceiveManager(context, app.container.quickDownloadSink)
        receivers += receiver
        val joined = receiver.join(
            QuickJoinTarget(
                code = waiting.code,
                host = "127.0.0.1",
                protocol = QuickTransferDefaults.PROTOCOL_PREFERENCE.first(),
                ftpPort = session.ftpPort,
                ftpsPort = session.ftpsPort,
                sshPort = session.sshPort
            )
        )
        assertTrue("连接发送端失败：" + (joined.exceptionOrNull()?.message ?: ""), joined.isSuccess)
        assertEquals(QuickTransferDefaults.PROTOCOL_PREFERENCE.first(), receiver.state.value.activeProtocol)

        // 递归列目录：子目录文件应出现在 remoteFiles（path 保留层级）
        val remotePaths = receiver.state.value.remoteFiles.map { it.path }
        assertTrue(
            "递归扫描应包含子目录文件，实际=" + remotePaths,
            remotePaths.any { it.endsWith("/$folderName/2024/$photoName") }
        )

        val expectedNames = listOf(topName, photoName, notesName)
        val received = withTimeout(120_000L) {
            receiver.state.first { state ->
                state.phase == QuickReceivePhase.CONNECTED &&
                    state.transfer == null &&
                    (state.failedFiles.isNotEmpty() || expectedNames.all { expected ->
                        state.completedFiles.any { it.startsWith(expected.substringBeforeLast('.')) }
                    })
            }
        }
        assertEquals("接收失败：" + received.error, emptyList<String>(), received.failedFiles)
        val rows = queryDownloadRows(received.completedFiles)
        assertTrue(
            "顶层文件应直接落在 Download/ 根目录，实际=" + rows,
            rows.any { row -> row.displayName.startsWith(topName.substringBeforeLast('.')) && row.relativePath == "Download/" }
        )
        assertTrue(
            "子目录文件应保留目录结构（Download/$folderName/2024/），实际=" + rows,
            rows.any { row ->
                row.displayName.startsWith(photoName.substringBeforeLast('.')) &&
                    row.relativePath == "Download/" + folderName + "/2024/"
            }
        )
        assertTrue(
            "子目录文件应保留目录结构（Download/$folderName/），实际=" + rows,
            rows.any { row ->
                row.displayName.startsWith(notesName.substringBeforeLast('.')) &&
                    row.relativePath == "Download/" + folderName + "/"
            }
        )
        Log.i(tag, "递归接收完成：" + rows)

        // 互传：对方把文件上传到子目录后，发送端应递归识别（relativePath 保留）
        val backRelative = "回传-" + prefix + "/嵌套/" + prefix + "-back.txt"
        writeSessionFile(sessionDirectory, backRelative, "回传内容".repeat(40).toByteArray())
        var foundBack = false
        val detected = withTimeoutOrNull(20_000L) {
            while (!foundBack) {
                sender.refresh()
                foundBack = sender.state.value.receivedFiles.any {
                    it.relativePath == backRelative
                }
                if (!foundBack) delay(300L)
            }
            true
        }
        assertEquals(
            "发送端应递归识别子目录里的收到文件，实际=" +
                sender.state.value.receivedFiles.map { it.relativePath ?: it.name },
            true,
            detected
        )
        val exported = sender.exportReceived(backRelative)
        assertTrue("按相对路径导出收到文件失败：" + (exported.exceptionOrNull()?.message ?: ""), exported.isSuccess)
        assertTrue("导出的文件应能在 Download/ 中查到", queryDownloadDisplayNameExists(exported.getOrThrow()))
    }

    // ------------------------------------------------------------ 测试 2：tree Uri 文件夹导入

    @Test
    fun treeUriImport_keepsRelativePaths() = runBlocking {
        val stamp = System.currentTimeMillis()
        val prefix = "quick-folder-" + stamp
        val fakeRoot = File(context.cacheDir, "quick-folder-fake-" + stamp).apply { mkdirs() }
        val fakeStore = FakeTreeSessionFileStore(context, fakeRoot) { directory ->
            // 模拟 SessionFileStore.importDirectory：保留被选目录名称的顶层结构
            val folder = prefix + "-album"
            val relativeFiles = listOf(
                folder + "/2024/" + prefix + "-a.txt" to "A".repeat(200),
                folder + "/" + prefix + "-b.txt" to "B".repeat(120)
            )
            val imported = relativeFiles.map { (relativePath, text) ->
                val file = File(directory, relativePath.replace('/', File.separatorChar))
                file.parentFile?.mkdirs()
                file.writeText(text)
                QuickFile(
                    name = file.name,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    relativePath = relativePath
                )
            }
            SessionImportResult(imported = imported)
        }
        val fakeSender = DefaultQuickSendManager(context, app.container.localServerManager, fakeStore)
        try {
            val treeUri = Uri.parse("content://com.manst.lantransfer.fake/tree/fake-tree-1")
            val plainName = prefix + "-plain.txt"
            val plainUri = createContentUri(plainName, "普通文件内容".repeat(20).toByteArray())
            val started = fakeSender.startSession(listOf(treeUri, plainUri))
            assertTrue("tree Uri 混合导入应成功：" + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)
            val waiting = withTimeout(30_000L) {
                fakeSender.state.first { state -> state.phase == QuickSendPhase.WAITING || state.error != null }
            }
            assertEquals("会话应进入等待状态：" + waiting.error, QuickSendPhase.WAITING, waiting.phase)
            val relativePaths = waiting.sendingFiles.mapNotNull { it.relativePath }
            assertTrue(
                "文件夹导入应保留子目录结构（relativePath），实际=" + relativePaths,
                relativePaths.contains(prefix + "-album/2024/" + prefix + "-a.txt") &&
                    relativePaths.contains(prefix + "-album/" + prefix + "-b.txt")
            )
            assertTrue(
                "混合选择中的普通文件也应导入为顶层文件，实际=" + waiting.sendingFiles.map { it.name },
                waiting.sendingFiles.any { it.relativePath == null && it.name == plainName }
            )
            assertEquals("三种导入文件都应进入发送列表", 3, waiting.sendingFiles.size)

            // 真实本机服务器应能递归浏览到子目录文件
            val session = requireNotNull(waiting.sessionInfo) { "缺少会话信息" }
            val receiver = DefaultQuickReceiveManager(context, app.container.quickDownloadSink)
            receivers += receiver
            val joined = receiver.join(
                QuickJoinTarget(
                    code = waiting.code,
                    host = "127.0.0.1",
                    protocol = QuickTransferDefaults.PROTOCOL_PREFERENCE.first(),
                    ftpPort = session.ftpPort,
                    ftpsPort = session.ftpsPort,
                    sshPort = session.sshPort
                )
            )
            assertTrue("连接发送端失败：" + (joined.exceptionOrNull()?.message ?: ""), joined.isSuccess)
            val remotePaths = receiver.state.value.remoteFiles.map { it.path }
            assertTrue(
                "对端应能递归浏览到文件夹里的文件，实际=" + remotePaths,
                remotePaths.any { it.endsWith("/" + prefix + "-album/2024/" + prefix + "-a.txt") }
            )
            val received = withTimeout(120_000L) {
                receiver.state.first { state ->
                    state.phase == QuickReceivePhase.CONNECTED &&
                        state.transfer == null &&
                        (state.failedFiles.isNotEmpty() || state.completedFiles.size >= 3)
                }
            }
            assertEquals("接收失败：" + received.error, emptyList<String>(), received.failedFiles)
            val rows = queryDownloadRows(received.completedFiles)
            assertTrue(
                "文件夹内容应下载到 Download/<相册目录>/2024/，实际=" + rows,
                rows.any { row ->
                    row.displayName.startsWith(prefix + "-a") &&
                        row.relativePath == "Download/" + prefix + "-album/2024/"
                }
            )
        } finally {
            runCatching { fakeSender.stopSession() }
            runCatching { fakeRoot.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------ 测试辅助

    /** 注入的假会话仓库：只模拟文件夹导入结果与普通文件导入，其余能力保持最小实现。 */
    private class FakeTreeSessionFileStore(
        private val context: Context,
        private val rootDirectory: File,
        private val importDirectoryAction: (File) -> SessionImportResult
    ) : SessionFileStore {

        override fun directoryFor(code: String): File =
            File(rootDirectory, code).apply { mkdirs() }

        override suspend fun importFiles(directory: File, uris: List<Uri>): SessionImportResult {
            val imported = ArrayList<QuickFile>()
            for (uri in uris) {
                runCatching {
                    val name = queryDisplayName(uri) ?: ("fake-" + System.nanoTime() + ".txt")
                    val target = File(directory, name)
                    target.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取测试 Uri")
                    imported += QuickFile(name = target.name, size = target.length(), modifiedAt = target.lastModified())
                }.onFailure { Log.w("QuickFolderTest", "假仓库导入普通文件失败：" + uri, it) }
            }
            return SessionImportResult(imported = imported)
        }

        override suspend fun importDirectory(directory: File, treeUri: Uri): SessionImportResult =
            importDirectoryAction(directory)

        override fun listFiles(directory: File): List<QuickFile> =
            directory.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.map { QuickFile(name = it.name, size = it.length(), modifiedAt = it.lastModified()) }
                ?.sortedByDescending { it.modifiedAt }
                .orEmpty()

        override suspend fun exportToDownloads(file: File): Result<String> = Result.success(file.name)

        override fun clear(directory: File) {
            directory.deleteRecursively()
        }

        private fun queryDisplayName(uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                }
        }.getOrNull()
    }

    private fun writeSessionFile(directory: File, relativePath: String, content: ByteArray) {
        val file = File(directory, relativePath.replace('/', File.separatorChar))
        file.parentFile?.mkdirs()
        file.writeBytes(content)
        Log.i(tag, "写入会话目录文件：" + file.absolutePath + "（" + content.size + " 字节）")
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
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private data class DownloadRow(val displayName: String, val relativePath: String)

    private fun queryDownloadRows(displayNames: Collection<String>): List<DownloadRow> {
        if (displayNames.isEmpty()) return emptyList()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val placeholders = displayNames.joinToString(",") { "?" }
        val selection = MediaStore.Downloads.DISPLAY_NAME + " IN (" + placeholders + ")"
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
            selection,
            displayNames.toTypedArray(),
            null
        )?.use { cursor ->
            val rows = ArrayList<DownloadRow>(cursor.count)
            while (cursor.moveToNext()) {
                rows += DownloadRow(cursor.getString(0), cursor.getString(1) ?: "")
            }
            rows
        }.orEmpty()
    }

    private fun queryDownloadDisplayNameExists(displayName: String): Boolean {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor -> cursor.count > 0 } ?: false
    }

    private fun deleteTestDownloads() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(
                collection,
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(TEST_SOURCE_DIR)
            )
        }
        runCatching {
            resolver.delete(
                collection,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("quick-folder-%")
            )
        }
    }

    private companion object {
        val TEST_SOURCE_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickTest/"
    }
}

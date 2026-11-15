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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 快捷传输引擎的真机/模拟器仪器测试：
 * - 用 MediaStore 插入一个真实 content Uri 作为「用户选择的文件」；
 * - [QuickSendManager.startSession] 自动导入文件、启动四协议服务端并广播；
 * - 对 FTP / FTPS / SFTP / SCP 各自新建 [QuickReceiveManager] 连接 127.0.0.1 真实回环，
 *   断言连接协议、自动接收、系统下载目录落盘与内容大小；
 * - 接收端再用 [QuickReceiveManager.sendFiles] 把文件回传给发送端（互传），断言 sender.receivedFiles；
 * - 最后 [QuickSendManager.stopSession] 断言状态回到 IDLE。
 *
 * 说明：本测试只要求编译通过，设备执行由 Lead 统一调度。
 */
@RunWith(AndroidJUnit4::class)
class QuickTransferEngineTest {

    private val tag = "QuickEngineTest"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val resolver get() = context.contentResolver

    private lateinit var sender: DefaultQuickSendManager

    private val receivers = mutableListOf<DefaultQuickReceiveManager>()

    private val createdUris = mutableListOf<Uri>()

    private val createdDownloadNames = mutableListOf<String>()

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

    @Test
    fun fourProtocols_autoReceive_andReverseSend_roundTrip() = runBlocking {
        val stamp = System.currentTimeMillis()
        val prefix = "quick-engine-" + stamp
        val sendName = prefix + "-send.txt"
        val sendContent = "快捷发送内容 quick-send data。".repeat(200).toByteArray(Charsets.UTF_8)
        val sendUri = createContentUri(sendName, sendContent)

        val started = sender.startSession(listOf(sendUri))
        assertTrue(
            "启动快捷发送会话失败：" + (started.exceptionOrNull()?.message ?: ""),
            started.isSuccess
        )
        val waiting = withTimeout(30_000L) {
            sender.state.first { state -> state.phase == QuickSendPhase.WAITING || state.error != null }
        }
        assertEquals("快捷发送会话应进入等待状态：" + waiting.error, QuickSendPhase.WAITING, waiting.phase)
        assertEquals("一次会话必须四种协议全部可用", QuickTransferDefaults.PROTOCOLS, waiting.protocols)
        assertTrue("会话应列出待发送文件", waiting.sendingFiles.any { it.name == sendName })
        val code = waiting.code
        assertEquals(QuickTransferDefaults.CODE_LENGTH, code.length)
        assertTrue("接收码必须是 6 位数字：" + code, code.all { it.isDigit() })
        val session = requireNotNull(waiting.sessionInfo) { "等待状态下必须有会话信息" }
        assertEquals(code, session.code)
        assertTrue("FTP 端口应在合法范围内", session.ftpPort in 1024..65535)
        assertTrue("FTPS 端口应在合法范围内", session.ftpsPort in 1024..65535)
        assertTrue("SSH 端口应在合法范围内", session.sshPort in 1024..65535)
        assertTrue("三个端口必须互不相同", session.ftpPort != session.ftpsPort)
        assertTrue("SSH 端口不能与 FTP/FTPS 重复", session.sshPort != session.ftpPort && session.sshPort != session.ftpsPort)
        Log.i(tag, "发送端会话就绪 code=" + code + " endpoints=" + session.ftpPort + "/" + session.ftpsPort + "/" + session.sshPort)

        // 四种协议逐一以「另一台设备」身份连接并自动接收
        for (protocol in QuickTransferDefaults.PROTOCOLS) {
            val receiver = DefaultQuickReceiveManager(context, app.container.quickDownloadSink)
            receivers += receiver
            val target = QuickJoinTarget(
                code = code,
                host = "127.0.0.1",
                deviceName = session.deviceName,
                protocol = protocol,
                ftpPort = session.ftpPort,
                ftpsPort = session.ftpsPort,
                sshPort = session.sshPort
            )
            val joined = receiver.join(target)
            assertTrue(
                "通过 " + protocol.label + " 连接失败：" + (joined.exceptionOrNull()?.message ?: ""),
                joined.isSuccess
            )
            assertEquals(
                "应使用指定协议 " + protocol.label,
                protocol,
                receiver.state.value.activeProtocol
            )
            val received = withTimeout(90_000L) {
                receiver.state.first { state ->
                    state.phase == QuickReceivePhase.CONNECTED &&
                        state.transfer == null &&
                        (state.completedFiles.isNotEmpty() || state.failedFiles.isNotEmpty())
                }
            }
            assertEquals(
                "通过 " + protocol.label + " 接收失败：" + received.error,
                emptyList<String>(),
                received.failedFiles
            )
            assertTrue(
                "通过 " + protocol.label + " 接收后 completedFiles 不应为空",
                received.completedFiles.isNotEmpty()
            )
            val displayName = received.completedFiles.first()
            createdDownloadNames += displayName
            assertTrue(
                "系统下载目录中应能查到接收的文件 " + displayName,
                queryDownloadDisplayNameExists(displayName)
            )
            assertEquals(
                "接收文件大小应与发送端会话文件一致",
                sendContent.size.toLong(),
                queryDownloadSize(displayName)
            )
            Log.i(tag, protocol.label + " 接收完成：" + displayName)
            receiver.leave()
            receivers.remove(receiver)
        }

        // 互传：接收端把文件回传给发送端
        val backReceiver = DefaultQuickReceiveManager(context, app.container.quickDownloadSink)
        receivers += backReceiver
        val backProtocol = QuickTransferDefaults.PROTOCOL_PREFERENCE.first()
        val backJoined = backReceiver.join(
            QuickJoinTarget(
                code = code,
                host = "127.0.0.1",
                protocol = backProtocol,
                ftpPort = session.ftpPort,
                ftpsPort = session.ftpsPort,
                sshPort = session.sshPort
            )
        )
        assertTrue(
            "互传连接失败：" + (backJoined.exceptionOrNull()?.message ?: ""),
            backJoined.isSuccess
        )
        val backName = prefix + "-back.txt"
        val backContent = "对方回传内容 reverse transfer。".repeat(100).toByteArray(Charsets.UTF_8)
        val backUri = createContentUri(backName, backContent)
        val sent = backReceiver.sendFiles(listOf(backUri))
        assertTrue("回传文件失败：" + (sent.exceptionOrNull()?.message ?: ""), sent.isSuccess)
        assertTrue("发送成功后 sentFiles 应记录文件名", backReceiver.state.value.sentFiles.isNotEmpty())

        var receivedNames = emptyList<String>()
        val detected = withTimeoutOrNull(15_000L) {
            while (true) {
                sender.refresh()
                receivedNames = sender.state.value.receivedFiles.map { it.name }
                if (receivedNames.contains(backName)) return@withTimeoutOrNull true
                delay(500L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        assertEquals(
            "发送端应在 refresh 后识别到对方上传的文件，实际=" + receivedNames,
            true,
            detected
        )
        val receivedFile = sender.state.value.receivedFiles.first { it.name == backName }
        assertEquals("互传文件大小应一致", backContent.size.toLong(), receivedFile.size)
        Log.i(tag, "互传成功，sender.receivedFiles=" + receivedNames)
        backReceiver.leave()
        receivers.remove(backReceiver)

        // 结束会话：状态复位、会话目录清理
        val sessionDirectory = File(waiting.sessionDirectory)
        assertTrue("会话目录应存在", sessionDirectory.isDirectory)
        sender.stopSession()
        assertEquals(QuickSendPhase.IDLE, sender.state.value.phase)
        assertNull("结束会话后不应再暴露会话信息", sender.state.value.sessionInfo)
        assertFalse("结束会话后会话目录应被删除", sessionDirectory.exists())
    }

    @Test
    fun beacon_broadcastAndScan_workOnLoopback() = runBlocking {
        val expected = QuickSessionInfo(
            code = "246810",
            deviceName = "测试设备",
            addresses = listOf("127.0.0.1"),
            ftpPort = 3121,
            ftpsPort = 3122,
            sshPort = 3222
        )
        val received = CompletableDeferred<QuickSessionInfo>()
        // 使用一次性随机端口，避免与其它用例（接收页自动发现）抢占 47823 导致回环收不到包
        val port = 47000 + (System.nanoTime() % 1000).toInt()
        val scanner = UdpBeaconScanner(port = port)
        val broadcaster = UdpBeaconBroadcaster(targetHost = "127.0.0.1", port = port)
        try {
            scanner.start { session -> received.complete(session) }
            Thread.sleep(300)
            broadcaster.start(expected)
            val actual = withTimeout(15_000L) { received.await() }
            // v2 广播不再携带接收码：附近设备只能发现设备名/地址/端口/协议
            assertEquals("广播不应泄露接收码", "", actual.code)
            assertFalse("广播会话不应带接收码", actual.hasCode)
            assertEquals(expected.deviceName, actual.deviceName)
            assertEquals(expected.addresses, actual.addresses)
            assertEquals(expected.protocols, actual.protocols)
            assertEquals(expected.ftpPort, actual.ftpPort)
            assertEquals(expected.ftpsPort, actual.ftpsPort)
            assertEquals(expected.sshPort, actual.sshPort)
        } finally {
            broadcaster.stop()
            scanner.stop()
        }
    }

    // ------------------------------------------------------------ MediaStore 辅助方法

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

    private fun queryDownloadDisplayNameExists(displayName: String): Boolean {
        return queryDownloads(
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName)
        ) { cursor -> cursor.moveToFirst() }
    }

    private fun queryDownloadSize(displayName: String): Long {
        return queryDownloads(
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(displayName)
        ) { cursor ->
            if (!cursor.moveToFirst()) -1L else cursor.getLong(0)
        }
    }

    private fun <T> queryDownloads(selection: String, args: Array<String>, read: (android.database.Cursor) -> T): T {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(collection, arrayOf(MediaStore.Downloads.SIZE), selection, args, null)
            ?.use(read)
            ?: throw IllegalStateException("查询系统下载目录失败")
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
                arrayOf("quick-engine-%")
            )
        }
    }

    private companion object {
        val TEST_SOURCE_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickTest/"
    }
}
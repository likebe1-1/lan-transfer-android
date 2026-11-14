package com.manst.lantransfer.e2e

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.quick.QuickSendPhase
import com.manst.lantransfer.quick.QuickSessionCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 快捷传输的宿主端到端测试桥：
 * 设备上用 QuickSendManager 启动「快速传文件」会话（自动开四种协议服务端 + 广播），
 * 把会话信息写到外部私有目录 quick_session.json，等宿主脚本以「另一台设备」身份通过
 * adb forward 连接并用四种协议下载/上传，最后用 .quick-host-done 通知测试结束。
 *
 * 断言：宿主通过 FTP/FTPS/SFTP/SCP 上传的文件都出现在 sender.receivedFiles（互传）。
 */
@RunWith(AndroidJUnit4::class)
class QuickHostE2EBridge {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private fun log(message: String) {
        Log.e(tag, "QUICK-E2E " + message)
    }

    @Test
    fun serveQuickSessionForHost() = runBlocking {
        val send = app.container.quickSendManager
        val resolver = context.contentResolver
        send.stopSession()

        File(context.filesDir, ".quick-host-done").delete()
        File(context.filesDir, "quick_session.json").delete()
        externalSessionFile().delete()

        val textContent = "quick transfer file A\n".repeat(200).toByteArray(Charsets.UTF_8)
        val binaryContent = ByteArray(160 * 1024) { index -> (index % 251).toByte() }
        val textUri = createContentUri("quick-host-a.txt", textContent)
        val binUri = createContentUri("quick-host-b.bin", binaryContent)

        val expectedFiles = listOf(
            Triple("quick-host-a.txt", textContent.size.toLong(), sha256(textContent)),
            Triple("quick-host-b.bin", binaryContent.size.toLong(), sha256(binaryContent))
        )

        val started = send.startSession(listOf(textUri, binUri))
        log("START=" + started.isSuccess + " err=" + started.exceptionOrNull()?.message)
        assertTrue("快捷发送会话启动失败: " + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)

        val waiting = withTimeout(30_000L) {
            send.state.first { it.phase == QuickSendPhase.WAITING || it.error != null }
        }
        assertTrue("快捷发送会话未进入等待状态: " + waiting.error, waiting.phase == QuickSendPhase.WAITING)
        val session = requireNotNull(waiting.sessionInfo) { "缺少会话信息" }
        val credentials = QuickSessionCode.deriveCredentials(waiting.code)
        log("SESSION code=" + session.code + " addrs=" + session.addresses + " protocols=" + session.protocols)

        val payload = JSONObject()
            .put("code", session.code)
            .put("deviceName", session.deviceName)
            .put("username", credentials.username)
            .put("password", credentials.password)
            .put("ftpPort", session.ftpPort)
            .put("ftpsPort", session.ftpsPort)
            .put("sshPort", session.sshPort)
            .put("addresses", JSONArray(session.addresses))
            .put("protocols", JSONArray(session.protocols.map { it.name }))
            .put(
                "files",
                JSONArray().apply {
                    expectedFiles.forEach { (name, size, digest) ->
                        put(
                            JSONObject()
                                .put("name", name)
                                .put("size", size)
                                .put("sha256", digest)
                        )
                    }
                }
            )
        val jsonFile = externalSessionFile()
        jsonFile.parentFile?.mkdirs()
        jsonFile.writeText(payload.toString(2), Charsets.UTF_8)
        log("SESSION_JSON=" + jsonFile.absolutePath)

        val sentinel = File(context.filesDir, ".quick-host-done")
        log("WAITING_FOR_HOST")
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            if (sentinel.isFile) break
            delay(500)
        }
        assertTrue("等待宿主测试超时（未收到 .quick-host-done）", sentinel.isFile)

        send.refresh()
        val received = send.state.value.receivedFiles.map { it.name }
        log("RECEIVED_AFTER_HOST=" + received)
        send.stopSession()
        log("DONE")

        listOf("upload-ftp.bin", "upload-ftps.bin", "upload-sftp.bin", "upload-scp.bin").forEach { name ->
            assertTrue("宿主通过协议上传的文件应出现在 receivedFiles: " + name + " 实际=" + received, received.contains(name))
        }
    }

    private fun externalSessionFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "quick_session.json")
    }

    private fun createContentUri(displayName: String, content: ByteArray): android.net.Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanTransferQuickTest/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) { "无法创建测试 content Uri" }
        context.contentResolver.openOutputStream(uri)?.use { it.write(content) }
            ?: throw IllegalStateException("无法写入测试 content Uri")
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null
        )
        return uri
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

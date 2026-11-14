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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 两台真机测试 —— 发送端（手机 A）：
 * 启动真实「快速传文件」会话（四协议服务端 + 局域网广播），把会话信息写到
 * /sdcard/Android/data/<包名>/files/two_phone_session.json 供宿主机编排；
 * 等待手机 B 用 FTP/FTPS/SFTP/SCP 分别连接并回传文件，最后断言收到的 4 个文件。
 */
@RunWith(AndroidJUnit4::class)
class TwoPhoneSendBridge {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private fun log(message: String) {
        Log.e(tag, "TWO-PHONE " + message)
    }

    @Test
    fun serveTwoPhoneSession() = runBlocking {
        val send = app.container.quickSendManager
        send.stopSession()

        File(context.filesDir, ".two-phone-done").delete()
        externalSessionFile().delete()

        val binContent = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        val textContent = "两台真机互传测试文本\n".repeat(4000).toByteArray(Charsets.UTF_8)
        val binUri = createContentUri("two-phone-a.bin", binContent)
        val textUri = createContentUri("two-phone-b.txt", textContent)

        val started = send.startSession(listOf(binUri, textUri))
        log("START=" + started.isSuccess + " err=" + started.exceptionOrNull()?.message)
        assertTrue("发送会话启动失败：" + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)

        val waiting = withTimeout(30_000L) {
            send.state.first { it.phase == QuickSendPhase.WAITING || it.error != null }
        }
        assertTrue("会话未进入等待状态：" + waiting.error, waiting.phase == QuickSendPhase.WAITING)
        val session = requireNotNull(waiting.sessionInfo) { "缺少会话信息" }
        val credentials = QuickSessionCode.deriveCredentials(waiting.code)

        val files = JSONArray()
            .put(
                JSONObject()
                    .put("name", "two-phone-a.bin")
                    .put("size", binContent.size)
                    .put("sha256", sha256(binContent))
            )
            .put(
                JSONObject()
                    .put("name", "two-phone-b.txt")
                    .put("size", textContent.size)
                    .put("sha256", sha256(textContent))
            )
        val payload = JSONObject()
            .put("code", session.code)
            .put("deviceName", session.deviceName)
            .put("addresses", JSONArray(session.addresses))
            .put("ftpPort", session.ftpPort)
            .put("ftpsPort", session.ftpsPort)
            .put("sshPort", session.sshPort)
            .put("protocols", JSONArray(session.protocols.map { it.name }))
            .put("username", credentials.username)
            .put("password", credentials.password)
            .put("files", files)
        val sessionFile = externalSessionFile()
        sessionFile.parentFile?.mkdirs()
        sessionFile.writeText(payload.toString(2), Charsets.UTF_8)
        log("SESSION_JSON=" + sessionFile.absolutePath)
        log("WAITING_FOR_PEER code=" + session.code + " addrs=" + session.addresses +
            " ftp=" + session.ftpPort + " ftps=" + session.ftpsPort + " ssh=" + session.sshPort)

        val sentinel = File(context.filesDir, ".two-phone-done")
        val deadline = System.currentTimeMillis() + 300_000L
        while (System.currentTimeMillis() < deadline) {
            if (sentinel.isFile) break
            delay(500)
        }
        assertTrue("等待手机 B 测试超时（未收到 .two-phone-done）", sentinel.isFile)

        send.refresh()
        val received = send.state.value.receivedFiles.map { it.name }
        log("RECEIVED_AFTER_PEER=" + received)
        send.stopSession()

        val expected = listOf(
            "two-phone-FTP-back.bin",
            "two-phone-FTPS-back.bin",
            "two-phone-SFTP-back.bin",
            "two-phone-SCP-back.bin"
        )
        val missing = expected.filterNot { received.contains(it) }
        log("DONE missing=" + missing)
        assertTrue("手机 B 通过四种协议回传的文件应全部出现在收到的文件：缺少 " + missing, missing.isEmpty())
    }

    private fun externalSessionFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "two_phone_session.json")
    }

    private fun createContentUri(displayName: String, content: ByteArray): android.net.Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanTransferTwoPhone/")
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

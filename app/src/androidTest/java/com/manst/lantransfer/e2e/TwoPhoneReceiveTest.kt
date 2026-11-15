package com.manst.lantransfer.e2e

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.QuickJoinTarget
import com.manst.lantransfer.quick.QuickReceivePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 两台真机测试 —— 接收端（手机 B）：
 * 通过 instrumentation 参数接收手机 A 的 IP / 接收码 / 端口 / 指定协议，
 * 先走局域网发现（记录是否发现到 A），再连接、自动接收全部文件、校验大小，
 * 最后回传一个文件给 A（验证互传），并记录实际使用的协议。
 */
@RunWith(AndroidJUnit4::class)
class TwoPhoneReceiveTest {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private fun log(message: String) {
        Log.e(tag, "TWO-PHONE-RECV " + message)
    }

    @Test
    fun receiveFromTwoPhone() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val code = requireArg(args, "twoPhoneCode")
        val host = requireArg(args, "twoPhoneHost")
        val protocolName = args.getString("twoPhoneProtocol") ?: "AUTO"
        val ftpPort = args.getString("twoPhoneFtp")?.toIntOrNull() ?: 2121
        val ftpsPort = args.getString("twoPhoneFtps")?.toIntOrNull() ?: 2122
        val sshPort = args.getString("twoPhoneSsh")?.toIntOrNull() ?: 2222
        val expectedName = requireArg(args, "expectedName")
        val expectedSize = requireArg(args, "expectedSize").toLong()
        val forceProtocol = if (protocolName == "AUTO") null else TransferProtocol.valueOf(protocolName)

        deletePreviousDownloads()

        val receive = app.container.quickReceiveManager
        receive.leave()
        receive.startDiscovery()
        var discoveredFound = false
        withTimeoutOrNull(12_000L) {
            while (true) {
                // 广播已不再携带接收码：按 host/IP 判断是否发现发送端
                val found = receive.state.value.discovered.firstOrNull { info ->
                    info.primaryHost == host || info.addresses.contains(host)
                }
                if (found != null) {
                    discoveredFound = true
                    log("DISCOVERY_FOUND device=" + found.deviceName + " host=" + found.primaryHost)
                    break
                }
                delay(300L)
            }
            @Suppress("UNREACHABLE_CODE")
            Unit
        }
        val discoveredDevices = receive.state.value.discovered.map { it.deviceName + "@" + it.primaryHost }
        log("DISCOVERY result=" + discoveredFound + " devices=" + discoveredDevices)
        receive.stopDiscovery()

        val target = QuickJoinTarget(
            code = code,
            host = host,
            protocol = forceProtocol,
            ftpPort = ftpPort,
            ftpsPort = ftpsPort,
            sshPort = sshPort
        )
        val joined = receive.join(target)
        log("JOIN protocol=" + protocolName + " success=" + joined.isSuccess + " err=" + joined.exceptionOrNull()?.message)
        assertTrue("连接手机 A 失败：" + (joined.exceptionOrNull()?.message ?: ""), joined.isSuccess)
        assertEquals("实际使用协议应为指定协议", protocolName, receive.state.value.activeProtocol?.name ?: "AUTO")

        // 等到「期望文件」完成接收（A 的会话目录里可能还有之前回传的文件，completedFiles 会先出现它们）
        val expectedBaseName = expectedName.substringBeforeLast('.')
        val received = withTimeoutOrNull(120_000L) {
            while (true) {
                val state = receive.state.value
                if (state.completedFiles.any { it.startsWith(expectedBaseName) }) {
                    return@withTimeoutOrNull true
                }
                if (state.phase == QuickReceivePhase.FAILED) return@withTimeoutOrNull false
                delay(300L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        log("RECEIVED=" + receive.state.value.completedFiles + " failed=" + receive.state.value.failedFiles)
        assertTrue("手机 B 未完成接收", received == true)
        val actualSize = queryDownloadedSize(expectedName, expectedSize)
        log("SIZE expected=" + expectedSize + " actual=" + actualSize)
        assertEquals("接收文件大小应与发送端一致", expectedSize, actualSize)

        // 互传：B 回传一个文件给 A
        val backName = "two-phone-" + protocolName + "-back.bin"
        val backContent = ByteArray(128 * 1024) { index -> ((index + protocolName.length) % 251).toByte() }
        val backUri = createContentUri(backName, backContent)
        val sent = receive.sendFiles(listOf(backUri))
        log("SEND_BACK name=" + backName + " success=" + sent.isSuccess + " err=" + sent.exceptionOrNull()?.message)
        assertTrue("回传文件给手机 A 失败：" + (sent.exceptionOrNull()?.message ?: ""), sent.isSuccess)
        assertTrue("发送成功后应记录已发送文件", receive.state.value.sentFiles.isNotEmpty())

        receive.leave()
        log("DONE protocol=" + protocolName + " discovered=" + discoveredFound)
    }

    private fun requireArg(args: android.os.Bundle, key: String): String =
        requireNotNull(args.getString(key)) { "缺少 instrumentation 参数 " + key }

    private fun deletePreviousDownloads() {
        // 只清理本测试产生的文件，避免误删下载根目录下用户的文件（新版本直接写入 Download/ 根目录）
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            context.contentResolver.delete(
                collection,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("two-phone-%")
            )
        }
    }

    private fun queryDownloadedSize(displayName: String, expectedSize: Long): Long {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH)
        val baseName = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else "." + extension
        // 系统下载目录里可能已有同名文件，MediaStore 会去重成 "name (1).ext"，这里按前缀匹配
        // 文件直接写入系统下载根目录 Download/，只按文件名前缀匹配
        val selection = MediaStore.Downloads.DISPLAY_NAME + " LIKE ?"
        val selectionArgs = arrayOf(baseName + "%" + suffix)
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val size = cursor.getLong(0)
                if (size == expectedSize) return size
            }
        }
        return -1L
    }

    private fun createContentUri(displayName: String, content: ByteArray): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanTransferTwoPhone/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) { "无法创建回传测试 content Uri" }
        context.contentResolver.openOutputStream(uri)?.use { it.write(content) }
            ?: throw IllegalStateException("无法写入回传测试 content Uri")
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null
        )
        return uri
    }
}
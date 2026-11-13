package com.manst.lantransfer.e2e

import android.content.Context
import android.util.Log
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.RemoteFileSystems
import com.manst.lantransfer.transfer.TransferState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 真机/模拟器集成测试：连接宿主机（10.0.2.2）上由 scripts/e2e_servers.py 启动的
 * FTP / FTPS / SFTP / SCP 四种真实服务端，验证 Android 运行时下的协议实现与传输队列。
 *
 * 运行前先启动： python scripts/e2e_servers.py --masquerade 10.0.2.2
 * 运行： .\gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.manst.lantransfer.e2e.FourProtocolE2ETest
 */
@RunWith(AndroidJUnit4::class)
class FourProtocolE2ETest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private fun log(message: String) {
        Log.e("E2EDIAG", "E2E " + message)
    }

    private fun profileFor(protocol: TransferProtocol, password: String = "test"): ServerProfile {
        val port = when (protocol) {
            TransferProtocol.FTP -> 2121
            TransferProtocol.FTPS -> 2122
            else -> 2222
        }
        return ServerProfile(
            name = "E2E " + protocol.label,
            protocol = protocol,
            host = "10.0.2.2",
            port = port,
            username = "test",
            password = password,
            initialPath = "/",
            allowInsecureTls = true
        )
    }

    @Test
    fun allProtocols_connect_list_download_upload_delete() = runBlocking {
        val completed = withTimeoutOrNull(240_000L) {
            runProtocolLoop()
            true
        }
        assertTrue("协议端到端测试整体超时（240s）", completed == true)
    }

    private suspend fun runProtocolLoop() {
        val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
        val failures = mutableListOf<String>()

        for (protocol in TransferProtocol.values()) {
            val profile = profileFor(protocol)
            val fileSystem = RemoteFileSystems.create(profile)
            log(protocol.label + " start")
            try {
                fileSystem.connect()
                log(protocol.label + " connected")

                val entries = fileSystem.list("/")
                log(protocol.label + " list=" + entries.size)
                assertTrue("$protocol 根目录列表应包含 hello.txt", entries.any { it.name == "hello.txt" })

                val downloaded = ByteArrayOutputStream()
                fileSystem.download("/hello.txt", downloaded)
                log(protocol.label + " downloaded hello=" + downloaded.size())
                assertTrue(
                    "$protocol 下载内容不正确",
                    String(downloaded.toByteArray(), Charsets.UTF_8).contains("hello from lan transfer")
                )

                val remoteName = "/android-e2e-" + protocol.label.lowercase() + "-" +
                    UUID.randomUUID().toString().take(8) + ".bin"
                fileSystem.upload(payload.inputStream(), remoteName, payload.size.toLong())
                log(protocol.label + " uploaded " + remoteName)

                val roundTrip = ByteArrayOutputStream()
                fileSystem.download(remoteName, roundTrip)
                assertArrayEquals("$protocol 上传后回读内容不一致", payload, roundTrip.toByteArray())

                log(protocol.label + " roundtrip ok")
                fileSystem.delete(remoteName, false)
                log(protocol.label + " deleted")
            } catch (error: Throwable) {
                failures += "$protocol: " + (error.message ?: error.javaClass.simpleName)
            } finally {
                runCatching { fileSystem.close() }
            }
        }

        assertTrue("以下协议端到端失败: " + failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun transferManager_downloadsAndWritesMediaStore() = runBlocking {
        val manager = app.container.transferManager
        val profile = profileFor(TransferProtocol.FTP)

        val taskId = manager.enqueueDownload(
            profile,
            RemoteEntry(name = "hello.txt", path = "/hello.txt", isDirectory = false, size = 108L)
        )

        val finished = withTimeout(120_000L) {
            manager.tasks.first { tasks -> tasks.any { it.id == taskId && it.isFinished } }
        }
        val task = finished.first { it.id == taskId }
        assertEquals("传输任务应成功: " + task.error, TransferState.COMPLETED, task.state)
        assertNotNull("应记录本地文件名", task.localDisplayName)

        val resolvedName = task.localDisplayName
        if (resolvedName != null) {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.DISPLAY_NAME + "=?",
                arrayOf(resolvedName),
                null
            )
            cursor?.use {
                assertTrue("MediaStore 中应能查到下载的文件 " + resolvedName, it.count > 0)
            }
        }
    }

    @Test
    fun wrongPassword_mapsToAuthenticationException() = runBlocking {
        for (protocol in TransferProtocol.values()) {
            val profile = profileFor(protocol, password = "wrong-password")
            val fileSystem = RemoteFileSystems.create(profile)
            try {
                fileSystem.connect()
            } catch (error: Throwable) {
                assertTrue(
                    "$protocol 错误密码应抛出 AuthenticationException，实际: " + error.javaClass.name +
                        " / " + error.message,
                    error is AuthenticationException
                )
            } finally {
                runCatching { fileSystem.close() }
            }
        }
    }

    @Test
    fun closedPort_mapsToConnectionException() = runBlocking {
        val profile = ServerProfile(
            name = "closed-port",
            protocol = TransferProtocol.FTP,
            host = "10.0.2.2",
            port = 2199,
            username = "test",
            password = "test"
        )
        val fileSystem = RemoteFileSystems.create(profile)
        try {
            fileSystem.connect()
        } catch (error: Throwable) {
            assertTrue(
                "未监听端口应抛出 ConnectionException，实际: " + error.javaClass.name + " / " + error.message,
                error is ConnectionException
            )
        } finally {
            runCatching { fileSystem.close() }
        }
    }
}
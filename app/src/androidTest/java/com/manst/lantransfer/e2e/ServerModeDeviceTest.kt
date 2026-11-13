package com.manst.lantransfer.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.RemoteFileSystems
import com.manst.lantransfer.server.LocalServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 本机服务器设备测试：在同一个设备上启动 App 内服务器（FTP/FTPS/SFTP/SCP），
 * 再用本 App 的客户端通过 127.0.0.1 回环连接，验证四种协议都能收发文件。
 *
 * 覆盖「手机自己启动服务器、其他客户端连进来收文件」的核心链路。
 */
@RunWith(AndroidJUnit4::class)
class ServerModeDeviceTest {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private val server get() = app.container.localServerManager
    private val share get() = app.container.shareRepository

    private val ftpPort = 12121
    private val ftpsPort = 12122
    private val sshPort = 12222

    private fun log(message: String) {
        Log.e(tag, "SERVER-TEST " + message)
    }

    @Before
    fun cleanState() = runBlocking {
        server.stop()
        share.directory().apply {
            mkdirs()
            listFiles()?.forEach { if (it.isFile) it.delete() }
        }
        share.refresh()
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        server.stop()
        Unit
    }

    private fun profileFor(protocol: TransferProtocol): ServerProfile {
        val port = when (protocol) {
            TransferProtocol.FTP -> ftpPort
            TransferProtocol.FTPS -> ftpsPort
            else -> sshPort
        }
        return ServerProfile(
            name = "loopback " + protocol.label,
            protocol = protocol,
            host = "127.0.0.1",
            port = port,
            username = "test",
            password = "test",
            initialPath = "/",
            allowInsecureTls = true
        )
    }

    @Test
    fun startServer_fourProtocolsRoundTrip() = runBlocking {
        val shareRoot = share.directory().apply { mkdirs() }
        File(shareRoot, "hello.txt").writeText("hello from android server\n", Charsets.UTF_8)
        val binary = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        File(shareRoot, "sample.bin").writeBytes(binary)
        share.refresh()

        val config = LocalServerConfig(
            enableFtp = true,
            enableFtps = true,
            enableSftp = true,
            enableScp = true,
            ftpPort = ftpPort,
            ftpsPort = ftpsPort,
            sshPort = sshPort,
            username = "test",
            password = "test"
        )
        val started = managerStart(config)
        assertTrue("服务器启动失败: " + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)
        log("server started: " + server.state.value)

        val failures = mutableListOf<String>()
        for (protocol in TransferProtocol.values()) {
            val descriptor = profileFor(protocol)
            val fs = RemoteFileSystems.create(descriptor)
            try {
                fs.connect()
                log(protocol.label + " connected")
                val entries = fs.list("/")
                assertTrue(protocol.label + " 根目录应包含 hello.txt", entries.any { it.name == "hello.txt" })
                assertTrue(protocol.label + " 根目录应包含 sample.bin", entries.any { it.name == "sample.bin" })

                val hello = ByteArrayOutputStream()
                fs.download("/hello.txt", hello)
                assertTrue(
                    protocol.label + " 下载内容不符",
                    String(hello.toByteArray(), Charsets.UTF_8).contains("hello from android server")
                )

                val sample = ByteArrayOutputStream()
                fs.download("/sample.bin", sample)
                assertArrayEquals(protocol.label + " 大文件下载不一致", binary, sample.toByteArray())

                val payload = ByteArray(48 * 1024) { index -> (index % 199).toByte() }
                val remoteName = "/upload-" + protocol.label.lowercase() + ".bin"
                fs.upload(payload.inputStream(), remoteName, payload.size.toLong())
                val roundTrip = ByteArrayOutputStream()
                fs.download(remoteName, roundTrip)
                assertArrayEquals(protocol.label + " 上传回读不一致", payload, roundTrip.toByteArray())
                fs.delete(remoteName, false)
                log(protocol.label + " roundtrip ok")
            } catch (error: Throwable) {
                failures += protocol.label + ": " + (error.message ?: error.javaClass.simpleName)
                log(protocol.label + " FAILED: " + error)
            } finally {
                runCatching { fs.close() }
            }
        }
        assertTrue("协议失败: " + failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun clientUpload_appearsInShareDirectory() = runBlocking {
        val shareRoot = share.directory().apply { mkdirs() }
        val config = LocalServerConfig(
            enableFtp = true,
            enableFtps = false,
            enableSftp = false,
            enableScp = false,
            ftpPort = ftpPort,
            username = "test",
            password = "test"
        )
        val started = managerStart(config)
        assertTrue("FTP 服务器启动失败: " + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)

        val fs = RemoteFileSystems.create(profileFor(TransferProtocol.FTP))
        try {
            fs.connect()
            val payload = "received file content 12345".toByteArray()
            fs.upload(payload.inputStream(), "/received-from-client.txt", payload.size.toLong())
        } finally {
            runCatching { fs.close() }
        }

        share.refresh()
        val received = File(shareRoot, "received-from-client.txt")
        assertTrue("客户端上传的文件应出现在共享目录", received.isFile)
        assertEquals("received file content 12345", received.readText(Charsets.UTF_8))
        assertTrue("共享文件列表应包含收到的文件", share.files.value.any { it.name == "received-from-client.txt" })
    }

    @Test
    fun invalidConfig_isRejectedBeforeStart() = runBlocking {
        val errors = server.validate(
            LocalServerConfig(username = "", password = "", enableFtp = true)
        )
        assertTrue("空用户名应校验失败", errors.isNotEmpty())

        val duplicatePorts = LocalServerConfig(
            enableFtp = true,
            enableFtps = true,
            ftpPort = 2121,
            ftpsPort = 2121,
            username = "test",
            password = "test"
        )
        assertTrue("端口重复应校验失败", server.validate(duplicatePorts).isNotEmpty())

        val result = managerStart(duplicatePorts)
        assertTrue("非法配置不应启动", result.isFailure)
    }

    private suspend fun managerStart(config: LocalServerConfig): Result<Unit> {
        val result = server.start(config)
        if (result.isSuccess) {
            // 等待监听端口生效（start 返回时理论上已监听，留少量余量）
            withTimeoutOrNull(3_000) {
                while (!server.state.value.isRunning) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        return result
    }
}

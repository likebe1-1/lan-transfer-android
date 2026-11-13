package com.manst.lantransfer.server.ssh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.RemoteFileSystems
import com.manst.lantransfer.server.ServerBinding
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Random

/**
 * 设备端 SFTP/SCP 服务端回环测试：在 Android 运行时上启动 [SshProtocolServer]，
 * 用真实 JSch 客户端（本工程的 [SshRemoteFileSystem]）连接 127.0.0.1，验证
 * SSH 传输、SFTP 子系统、SCP 线协议与自定义 shell 元数据命令在 Android 上可用。
 *
 * 该测试只要求编译通过，实际由 Lead 在模拟器执行；重点排查 Android 上的
 * ServiceLoader/Java11 API/类裁剪问题。
 */
@RunWith(AndroidJUnit4::class)
class SshServerDeviceTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var baseDir: File
    private lateinit var shareRoot: File
    private lateinit var server: SshProtocolServer
    private var port: Int = 0

    @Before
    fun setUp() {
        baseDir = File(context.cacheDir, "ssh-server-device-" + System.nanoTime())
        shareRoot = File(baseDir, "share").apply { mkdirs() }
        File(shareRoot, "hello.txt").writeText("hello from device server", StandardCharsets.UTF_8)
        File(shareRoot, "docs").mkdirs()
        File(File(shareRoot, "docs"), "readme.md").writeText("readme", StandardCharsets.UTF_8)
        server = SshProtocolServer(File(baseDir, "hostkey"))
        port = ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { server.stop() } }
        baseDir.deleteRecursively()
    }

    @Test
    fun sftpLoopbackListUploadDownloadDelete() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = false)
            log("SFTP 服务端已启动，port=" + port)
            val client = RemoteFileSystems.create(profile(TransferProtocol.SFTP))
            client.use {
                it.connect()
                log("SFTP 已连接")
                val entries = it.list("/")
                assertTrue("应列出 hello.txt", entries.any { entry -> entry.name == "hello.txt" })
                assertTrue("应列出 docs 目录", entries.any { entry -> entry.name == "docs" && entry.isDirectory })

                val payload = randomBytes(128 * 1024, 11L)
                it.upload(ByteArrayInputStream(payload), "/device-upload.bin", payload.size.toLong())
                log("SFTP 上传完成")
                assertArrayEquals(payload, File(shareRoot, "device-upload.bin").readBytes())

                val download = ByteArrayOutputStream()
                it.download("/device-upload.bin", download)
                assertArrayEquals("上传后回读应字节一致", payload, download.toByteArray())

                it.mkdir("/device-dir")
                assertTrue(File(shareRoot, "device-dir").isDirectory)
                it.rename("/device-upload.bin", "/device-dir/renamed.bin")
                assertTrue(File(File(shareRoot, "device-dir"), "renamed.bin").isFile)
                it.delete("/device-dir/renamed.bin", isDirectory = false)
                it.delete("/device-dir", isDirectory = true)
                log("SFTP 删除完成")
            }
        }
    }

    @Test
    fun scpLoopbackListUploadDownloadDelete() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = false, enableScp = true)
            log("SCP 服务端已启动，port=" + port)
            val client = RemoteFileSystems.create(profile(TransferProtocol.SCP))
            client.use {
                it.connect()
                log("SCP 已连接")
                val entries = it.list("/")
                assertTrue("SCP 列表应包含 hello.txt", entries.any { entry -> entry.name == "hello.txt" })
                assertTrue("SCP 列表应包含 docs 目录", entries.any { entry -> entry.name == "docs" && entry.isDirectory })

                val payload = randomBytes(128 * 1024, 22L)
                it.upload(ByteArrayInputStream(payload), "/device-scp.bin", payload.size.toLong())
                log("SCP 上传完成")
                assertArrayEquals(payload, File(shareRoot, "device-scp.bin").readBytes())

                val download = ByteArrayOutputStream()
                it.download("/device-scp.bin", download)
                assertArrayEquals("SCP 上传后回读应字节一致", payload, download.toByteArray())

                it.mkdir("/device-scp-dir")
                assertTrue(File(shareRoot, "device-scp-dir").isDirectory)
                it.rename("/device-scp.bin", "/device-scp-dir/renamed.bin")
                assertTrue(File(File(shareRoot, "device-scp-dir"), "renamed.bin").isFile)
                it.delete("/device-scp-dir/renamed.bin", isDirectory = false)
                it.delete("/device-scp-dir", isDirectory = true)
                log("SCP 删除完成")
            }
        }
    }

    @Test
    fun wrongPasswordMapsToAuthenticationException() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = true)
            for (protocol in listOf(TransferProtocol.SFTP, TransferProtocol.SCP)) {
                val client = RemoteFileSystems.create(profile(protocol, password = "wrong-password"))
                val error = client.use {
                    runCatching { it.connect() }.exceptionOrNull()
                }
                log(protocol.label + " 错误密码结果=" + error)
                assertTrue(
                    protocol.label + " 错误密码应映射 AuthenticationException，实际：" + error,
                    error is AuthenticationException
                )
            }
        }
    }

    // ------------------------------------------------------------ 辅助

    private fun binding(): ServerBinding = ServerBinding(
        port = port,
        shareRoot = shareRoot,
        username = USERNAME,
        password = PASSWORD
    )

    private fun profile(
        protocol: TransferProtocol,
        password: String = PASSWORD
    ): ServerProfile = ServerProfile(
        name = "device-loopback-" + protocol.label,
        protocol = protocol,
        host = "127.0.0.1",
        port = port,
        username = USERNAME,
        password = password
    )

    private fun log(message: String) {
        Log.e(TAG, message)
    }

    private fun randomBytes(size: Int, seed: Long): ByteArray {
        val data = ByteArray(size)
        Random(seed).nextBytes(data)
        return data
    }

    private companion object {
        const val TAG = "SSHDIAG"
        const val USERNAME = "device"
        const val PASSWORD = "device-pass"
    }
}

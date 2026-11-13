package com.manst.lantransfer.server.ssh

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.SshRemoteFileSystem
import com.manst.lantransfer.server.ServerBinding
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Random

/**
 * SFTP / SCP 服务端的 JVM 真实走线测试：
 * 在回环地址上启动 [SshProtocolServer]，用本工程自己的 [SshRemoteFileSystem]（SFTP 与 SCP 两种 profile）
 * 作为真实客户端完成列目录、上传、下载回读、建目录、重命名、删除，并验证认证失败、端口占用、
 * 停服后端口释放与主机密钥持久化等行为。
 */
class SshProtocolServerTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private lateinit var workDir: Path
    private lateinit var shareRoot: Path
    private lateinit var hostKeyFile: File
    private lateinit var server: SshProtocolServer
    private lateinit var payload: ByteArray
    private var port: Int = 0

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("lantransfer-ssh-server-")
        shareRoot = workDir.resolve("share")
        Files.createDirectories(shareRoot)
        Files.write(shareRoot.resolve("hello.txt"), "hello from phone server".toByteArray(StandardCharsets.UTF_8))
        Files.createDirectory(shareRoot.resolve("docs"))
        Files.write(shareRoot.resolve("docs").resolve("readme.md"), "readme".toByteArray(StandardCharsets.UTF_8))
        Files.write(shareRoot.resolve("中文 测试.txt"), "中文内容".toByteArray(StandardCharsets.UTF_8))
        payload = randomBytes(300_000, 42L)
        hostKeyFile = workDir.resolve("hostkey").toFile()
        port = freeTcpPort()
        server = SshProtocolServer(hostKeyFile)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { server.stop() } }
        deleteRecursively(workDir)
    }

    @Test
    fun sftpListUploadDownloadAndFileOperations() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = false)
            val client = SshRemoteFileSystem(profile(TransferProtocol.SFTP))
            client.use {
                it.connect()
                val entries = it.list("/")
                assertTrue("应列出 hello.txt", entries.any { entry -> entry.name == "hello.txt" })
                assertTrue("应列出 UTF-8 文件名", entries.any { entry -> entry.name == "中文 测试.txt" })
                assertTrue("应列出 docs 目录", entries.any { entry -> entry.name == "docs" && entry.isDirectory })

                val downloaded = ByteArrayOutputStream()
                it.download("/hello.txt", downloaded)
                assertEquals("hello from phone server", String(downloaded.toByteArray(), StandardCharsets.UTF_8))

                it.upload(ByteArrayInputStream(payload), "/uploaded.bin", payload.size.toLong())
                assertArrayEquals(payload, Files.readAllBytes(shareRoot.resolve("uploaded.bin")))

                val roundTrip = ByteArrayOutputStream()
                it.download("/uploaded.bin", roundTrip)
                assertArrayEquals("上传后回读应字节一致", payload, roundTrip.toByteArray())

                it.mkdir("/newdir")
                assertTrue(Files.isDirectory(shareRoot.resolve("newdir")))
                it.rename("/uploaded.bin", "/newdir/renamed.bin")
                assertTrue(Files.exists(shareRoot.resolve("newdir").resolve("renamed.bin")))
                it.delete("/newdir/renamed.bin", isDirectory = false)
                it.delete("/newdir", isDirectory = true)
                assertFalse(Files.exists(shareRoot.resolve("newdir")))

                val error = runCatching {
                    it.download("/missing.bin", ByteArrayOutputStream())
                }.exceptionOrNull()
                assertTrue("下载不存在文件应映射 RemotePathException，实际：" + error, error is RemotePathException)
            }
        }
    }

    @Test
    fun scpListUploadDownloadAndFileOperations() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = false, enableScp = true)
            val client = SshRemoteFileSystem(profile(TransferProtocol.SCP))
            client.use {
                it.connect()
                val entries = it.list("/")
                assertTrue("SCP 列表应包含 hello.txt", entries.any { entry -> entry.name == "hello.txt" })
                assertTrue("SCP 列表应包含 UTF-8 文件名", entries.any { entry -> entry.name == "中文 测试.txt" })
                assertTrue("SCP 列表应包含 docs 目录", entries.any { entry -> entry.name == "docs" && entry.isDirectory })
                assertEquals("目录条目大小按契约为 0", 0L, entries.first { entry -> entry.name == "docs" }.size)

                val downloaded = ByteArrayOutputStream()
                it.download("/hello.txt", downloaded)
                assertEquals("hello from phone server", String(downloaded.toByteArray(), StandardCharsets.UTF_8))

                it.upload(ByteArrayInputStream(payload), "/uploaded.bin", payload.size.toLong())
                assertArrayEquals(payload, Files.readAllBytes(shareRoot.resolve("uploaded.bin")))

                it.mkdir("/newdir")
                assertTrue(Files.isDirectory(shareRoot.resolve("newdir")))
                it.rename("/uploaded.bin", "/newdir/renamed.bin")
                assertTrue(Files.exists(shareRoot.resolve("newdir").resolve("renamed.bin")))
                it.delete("/newdir/renamed.bin", isDirectory = false)
                it.delete("/newdir", isDirectory = true)
                assertFalse(Files.exists(shareRoot.resolve("newdir")))

                val error = runCatching { it.list("/missing-dir") }.exceptionOrNull()
                assertTrue("列不存在目录应映射 RemotePathException，实际：" + error, error is RemotePathException)
            }
        }
    }

    @Test
    fun sftpAndScpCanRunOnSamePort() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = true)
            val sftpClient = SshRemoteFileSystem(profile(TransferProtocol.SFTP))
            sftpClient.use {
                it.connect()
                assertTrue(it.list("/").any { entry -> entry.name == "hello.txt" })
            }
            val scpClient = SshRemoteFileSystem(profile(TransferProtocol.SCP))
            scpClient.use {
                it.connect()
                assertTrue(it.list("/").any { entry -> entry.name == "hello.txt" })
            }
        }
    }

    @Test
    fun wrongPasswordThrowsAuthenticationException() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = true)
            for (protocol in listOf(TransferProtocol.SFTP, TransferProtocol.SCP)) {
                val client = SshRemoteFileSystem(profile(protocol, password = "wrong-password"))
                val error = client.use {
                    runCatching { it.connect() }.exceptionOrNull()
                }
                assertTrue(
                    protocol.label + " 错误密码应映射 AuthenticationException，实际：" + error,
                    error is AuthenticationException
                )
            }
        }
    }

    @Test
    fun stopReleasesPortAndServerCanRestart() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = true)
            assertTcpConnectable(port)
            server.stop()
            // 端口应已释放：可以重新绑定
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
            // 同一端口的服务端应能再次启动并接受连接
            server.start(binding(), enableSftp = true, enableScp = false)
            assertTcpConnectable(port)
            server.stop()
        }
    }

    @Test
    fun hostKeyFileIsPersistedAndReusedAcrossRestarts() = runBlocking {
        withTimeout(60_000L) {
            server.start(binding(), enableSftp = true, enableScp = false)
            // 客户端握手后主机密钥才会被加载/写盘
            SshRemoteFileSystem(profile(TransferProtocol.SFTP)).use { it.connect() }
            server.stop()
            assertTrue("首次连接后应生成主机密钥文件", hostKeyFile.isFile)
            val firstKeyBytes = Files.readAllBytes(hostKeyFile.toPath())

            server.start(binding(), enableSftp = true, enableScp = false)
            SshRemoteFileSystem(profile(TransferProtocol.SFTP)).use { it.connect() }
            server.stop()
            val secondKeyBytes = Files.readAllBytes(hostKeyFile.toPath())
            assertArrayEquals("重启后应复用同一把主机密钥", firstKeyBytes, secondKeyBytes)
        }
    }

    @Test
    fun startFailsWithPortInUseMessage() = runBlocking {
        withTimeout(60_000L) {
            ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).use {
                val error = runCatching {
                    server.start(binding(), enableSftp = true, enableScp = true)
                }.exceptionOrNull()
                assertTrue("端口占用应抛 IOException，实际：" + error, error is IOException)
                assertTrue("错误信息应说明端口被占用，实际：" + error?.message, error?.message?.contains("端口已被占用") == true)
            }
        }
    }

    @Test
    fun bothProtocolsDisabledIsRejected() = runBlocking {
        val error = runCatching {
            server.start(binding(), enableSftp = false, enableScp = false)
        }.exceptionOrNull()
        assertTrue("应拒绝 SFTP/SCP 全部关闭，实际：" + error, error is IllegalArgumentException)
    }

    // ------------------------------------------------------------ 辅助

    private fun binding(port: Int = this.port): ServerBinding = ServerBinding(
        port = port,
        shareRoot = shareRoot.toFile(),
        username = USERNAME,
        password = PASSWORD
    )

    private fun profile(
        protocol: TransferProtocol,
        port: Int = this.port,
        password: String = PASSWORD
    ): ServerProfile = ServerProfile(
        name = "local-server-test",
        protocol = protocol,
        host = "127.0.0.1",
        port = port,
        username = USERNAME,
        password = password
    )

    private fun assertTcpConnectable(port: Int) {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5_000)
            assertTrue("TCP 连接应建立", socket.isConnected)
        }
    }

    private fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    private fun randomBytes(size: Int, seed: Long): ByteArray {
        val data = ByteArray(size)
        Random(seed).nextBytes(data)
        return data
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "s3cret"
    }
}

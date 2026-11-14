package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * FTPS 适配器真实走线测试：MockFtpServer 3.1.0 没有任何 TLS 能力，因此用
 * TestFtpsServer（BouncyCastle 自签名证书 + JDK SSLServerSocket）起本地 FTPS 服务端，
 * 覆盖显式/隐式 TLS 的登录、列表、上传下载与错误路径，全部有真实 TLS 握手与真实数据通道。
 */
class FtpsRemoteFileSystemTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var root: Path
    private lateinit var server: TestFtpsServer
    private lateinit var bigBytes: ByteArray
    private lateinit var uploadBytes: ByteArray

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lantransfer-ftps-")
        Files.createDirectory(root.resolve("docs"))
        bigBytes = randomBytes(1024 * 1024, 303L)
        uploadBytes = randomBytes(256 * 1024, 404L)
        Files.write(root.resolve("hello.txt"), "hello 世界 FTPS".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("empty.bin"), ByteArray(0))
        Files.write(root.resolve("big.bin"), bigBytes)
        Files.write(root.resolve("文档.txt"), "中文文件名".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("docs").resolve("readme.md"), "readme".toByteArray(StandardCharsets.UTF_8))
        server = TestFtpsServer(root, USERNAME, PASSWORD)
    }

    @After
    fun tearDown() {
        server.close()
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun explicitTlsListsAndDownloads() = runBlocking {
        withClient(profile()) { client ->
            val entries = client.list("/")
            val byName = entries.associateBy { it.name }
            assertTrue(byName.containsKey("docs"))
            assertTrue(byName.containsKey("hello.txt"))
            assertTrue(byName.containsKey("empty.bin"))
            assertTrue("MLSD 应返回 UTF-8 文件名", byName.containsKey("文档.txt"))
            assertTrue(byName.getValue("docs").isDirectory)
            assertEquals(bigBytes.size.toLong(), byName.getValue("big.bin").size)
            assertTrue(byName.getValue("big.bin").modifiedAt > 0L)

            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            client.download("/big.bin", sink) { progress.add(it) }
            assertArrayEquals(bigBytes, sink.toByteArray())
            assertEquals(bigBytes.size.toLong(), progress.last())

            val emptySink = ByteArrayOutputStream()
            client.download("/empty.bin", emptySink)
            assertEquals(0, emptySink.size())
        }
    }

    @Test
    fun explicitTlsUploadMkdirRenameDelete() = runBlocking {
        withClient(profile()) { client ->
            val progress = ArrayList<Long>()
            client.upload(
                ByteArrayInputStream(uploadBytes),
                "/uploaded.bin",
                uploadBytes.size.toLong()
            ) { progress.add(it) }
            assertArrayEquals(uploadBytes, Files.readAllBytes(root.resolve("uploaded.bin")))
            assertEquals(uploadBytes.size.toLong(), progress.last())

            client.mkdir("/newdir")
            assertTrue(Files.isDirectory(root.resolve("newdir")))
            client.mkdir("/newdir")

            client.rename("/uploaded.bin", "/newdir/renamed.bin")
            assertFalse(Files.exists(root.resolve("uploaded.bin")))
            assertTrue(Files.exists(root.resolve("newdir").resolve("renamed.bin")))

            client.delete("/newdir/renamed.bin", isDirectory = false)
            assertFalse(Files.exists(root.resolve("newdir").resolve("renamed.bin")))
            client.delete("/newdir", isDirectory = true)
            assertFalse(Files.exists(root.resolve("newdir")))
        }
    }

    @Test
    fun implicitTlsLists() = runBlocking {
        val implicitServer = TestFtpsServer(root, USERNAME, PASSWORD, implicitTls = true)
        try {
            withClient(profile(server = implicitServer, mode = FtpsMode.IMPLICIT)) { client ->
                val names = client.list("/").map { it.name }.toSet()
                assertTrue(names.contains("hello.txt"))
                assertTrue(names.contains("docs"))
                val sink = ByteArrayOutputStream()
                client.download("/hello.txt", sink)
                assertEquals("hello 世界 FTPS", String(sink.toByteArray(), StandardCharsets.UTF_8))
            }
        } finally {
            implicitServer.close()
        }
    }

    @Test
    fun selfSignedCertificateRejectedWhenStrictValidationEnabled() = runBlocking {
        val strictProfile = profile().copy(protocol = TransferProtocol.FTPS, allowInsecureTls = false)
        val error = FtpRemoteFileSystem(strictProfile).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("自签证书在严格校验下应连接失败，实际：$error", error is ConnectionException)
    }

    @Test
    fun wrongPasswordThrowsAuthenticationException() = runBlocking {
        val error = FtpRemoteFileSystem(profile(password = "wrong")).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("期望 AuthenticationException，实际：$error", error is AuthenticationException)
    }

    @Test
    fun missingFileThrowsRemotePathException() = runBlocking {
        withClient(profile()) { client ->
            val error = runCatching {
                client.download("/nope.bin", ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue("期望 RemotePathException，实际：$error", error is RemotePathException)
        }
    }

    @Test
    fun unreachablePortThrowsConnectionException() = runBlocking {
        val error = FtpRemoteFileSystem(profile(port = freeTcpPort())).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("期望 ConnectionException，实际：$error", error is ConnectionException)
    }

    // ------------------------------------------------------------ 断点续传

    @Test
    fun explicitTlsDownloadResumeAfterInterruption() = runBlocking {
        val payload = randomBytes(300_000, 905L)
        Files.write(root.resolve("resume.bin"), payload)
        val interruptedAt = 110_000
        val partial = ByteArrayOutputStream()

        FtpRemoteFileSystem(profile()).use { client ->
            client.connect()
            assertTrue("FTPS 应支持断点续传", client.supportsResume)
            val error = runCatching {
                client.download("/resume.bin", InterruptingOutputStream(partial, interruptedAt))
            }.exceptionOrNull()
            assertNotNull("第一段下载应因模拟中断而失败", error)
        }
        assertEquals(interruptedAt, partial.size())

        val resumed = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        FtpRemoteFileSystem(profile()).use { client ->
            client.connect()
            client.downloadPartial("/resume.bin", resumed, partial.size().toLong()) { progress.add(it) }
        }
        assertArrayEquals("断点续传后拼接内容应与源文件一致", payload, partial.toByteArray() + resumed.toByteArray())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue("进度应包含续传偏移", progress.all { it >= interruptedAt.toLong() })
    }

    @Test
    fun explicitTlsUploadResumeAfterPartialUpload() = runBlocking {
        val payload = randomBytes(256 * 1024, 906L)
        val half = payload.size / 2
        val remotePath = "/upload-resume.bin"

        FtpRemoteFileSystem(profile()).use { client ->
            client.connect()
            client.uploadPartial(
                ByteArrayInputStream(payload.copyOfRange(0, half)),
                remotePath,
                half.toLong(),
                0L
            )
        }
        assertEquals(half.toLong(), Files.size(root.resolve("upload-resume.bin")))

        val progress = ArrayList<Long>()
        FtpRemoteFileSystem(profile()).use { client ->
            client.connect()
            assertEquals(half.toLong(), client.size(remotePath))
            client.uploadPartial(
                ByteArrayInputStream(payload),
                remotePath,
                payload.size.toLong(),
                half.toLong()
            ) { progress.add(it) }
            assertEquals(payload.size.toLong(), progress.last())
            assertTrue(progress.all { it >= half.toLong() })
            val readBack = ByteArrayOutputStream()
            client.download(remotePath, readBack)
            assertArrayEquals("续传上传后回读应字节一致", payload, readBack.toByteArray())
        }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("upload-resume.bin")))
    }

    @Test
    fun uploadResumeRejectsMismatchedOffset() = runBlocking {
        val payload = randomBytes(64_000, 907L)
        val remotePath = "/mismatch-ftps.bin"
        FtpRemoteFileSystem(profile()).use { client ->
            client.connect()
            client.upload(ByteArrayInputStream(payload.copyOfRange(0, 10_000)), remotePath, 10_000L)
            val error = runCatching {
                client.uploadPartial(ByteArrayInputStream(payload), remotePath, payload.size.toLong(), 20_000L)
            }.exceptionOrNull()
            assertTrue("远端大小与续传偏移不一致应抛 IOException，实际：$error", error is IOException)
            assertTrue("错误信息应说明不一致", error?.message?.contains("不一致") == true)
        }
    }

    @Test
    fun sizeReturnsFileSizeOrMinusOne() = runBlocking {
        withClient(profile()) { client ->
            assertEquals(bigBytes.size.toLong(), client.size("/big.bin"))
            assertEquals(0L, client.size("/empty.bin"))
            assertEquals(-1L, client.size("/missing.bin"))
        }
    }

    // ------------------------------------------------------------ 辅助

    private suspend fun withClient(profile: ServerProfile, block: suspend (RemoteFileSystem) -> Unit) {
        FtpRemoteFileSystem(profile).use { client ->
            client.connect()
            block(client)
        }
    }

    private fun profile(
        server: TestFtpsServer = this.server,
        port: Int = server.port,
        password: String = PASSWORD,
        mode: FtpsMode = FtpsMode.EXPLICIT,
        allowInsecureTls: Boolean = true
    ): ServerProfile = ServerProfile(
        name = "ftps-test",
        protocol = TransferProtocol.FTPS,
        host = "127.0.0.1",
        port = port,
        username = USERNAME,
        password = password,
        ftpsMode = mode,
        allowInsecureTls = allowInsecureTls
    )

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "s3cret"
    }
}

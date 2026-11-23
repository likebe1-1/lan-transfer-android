package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
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
 * SFTP 适配器真实走线测试：Apache MINA SSHD 的 SftpSubsystemFactory 提供本地 SFTP 服务端，
 * JSch 客户端真实走 SSH 传输与 SFTP v3 报文，覆盖列表、上传下载、目录操作、UTF-8 文件名与错误路径。
 */
class SshRemoteFileSystemSftpTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var root: Path
    private lateinit var server: TestSshServer
    private lateinit var bigBytes: ByteArray
    private lateinit var uploadBytes: ByteArray

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lantransfer-sftp-")
        bigBytes = randomBytes(1024 * 1024, 707L)
        uploadBytes = randomBytes(300_000, 808L)
        Files.write(root.resolve("hello.txt"), "hello 世界 SFTP".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("empty.bin"), ByteArray(0))
        Files.write(root.resolve("big.bin"), bigBytes)
        Files.write(root.resolve("中文文件名.txt"), "中文内容".toByteArray(StandardCharsets.UTF_8))
        Files.createDirectory(root.resolve("docs"))
        Files.write(root.resolve("docs").resolve("readme.md"), "readme".toByteArray(StandardCharsets.UTF_8))
        server = TestSshServer(root, enableSftp = true)
    }

    @After
    fun tearDown() {
        server.close()
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun listsRootAndSubdirectory() = runBlocking {
        withClient(profile()) { client ->
            val byName = client.list("/").associateBy { it.name }
            assertEquals(
                setOf("hello.txt", "empty.bin", "big.bin", "中文文件名.txt", "docs"),
                byName.keys
            )
            assertTrue(byName.getValue("docs").isDirectory)
            assertEquals("/docs", byName.getValue("docs").path)
            val big = byName.getValue("big.bin")
            assertTrue(big.isFile)
            assertEquals(bigBytes.size.toLong(), big.size)
            assertTrue("应解析出修改时间", big.modifiedAt > 0L)
            assertTrue("应解析出权限串", big.permissions.isNotEmpty())
            assertEquals(listOf("readme.md"), client.list("/docs").map { it.name })
        }
    }

    @Test
    fun downloadBigFileIsByteIdenticalAndReportsProgress() = runBlocking {
        withClient(profile()) { client ->
            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            client.download("/big.bin", sink) { progress.add(it) }
            assertArrayEquals(bigBytes, sink.toByteArray())
            assertTrue(progress.isNotEmpty())
            assertEquals(bigBytes.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test
    fun downloadEmptyFileProducesNoBytes() = runBlocking {
        withClient(profile()) { client ->
            val sink = ByteArrayOutputStream()
            client.download("/empty.bin", sink)
            assertEquals(0, sink.size())
        }
    }

    @Test
    fun uploadFileIsByteIdentical() = runBlocking {
        withClient(profile()) { client ->
            val progress = ArrayList<Long>()
            client.upload(
                ByteArrayInputStream(uploadBytes),
                "/uploaded.bin",
                uploadBytes.size.toLong()
            ) { progress.add(it) }
            assertArrayEquals(uploadBytes, Files.readAllBytes(root.resolve("uploaded.bin")))
            assertEquals(uploadBytes.size.toLong(), progress.last())
        }
    }

    @Test
    fun uploadBigFileWith128KiBBufferedSourceRoundTrips() = runBlocking {
        // ≥4 MiB，且大小不是 128 KiB 缓冲的整数倍：覆盖缓冲边界填充、最后一块与回读一致性
        val payload = randomBytes(4 * 1024 * 1024 + 65_537, 1_717L)
        val source = RecordingInputStream(payload)
        val progress = ArrayList<Long>()
        withClient(profile()) { client ->
            client.upload(source, "/big-upload.bin", payload.size.toLong()) { progress.add(it) }
            assertEquals(
                "SFTP 上传源应以 128 KiB BufferedInputStream 读取（JSch 内部 32 KiB 包读源的小读被合并）",
                SFTP_UPLOAD_BUFFER_SIZE,
                source.maxRequestedLength
            )
            assertEquals("进度末值应等于文件大小", payload.size.toLong(), progress.last())
            assertEquals(payload.size.toLong(), client.size("/big-upload.bin"))
            val readBack = ByteArrayOutputStream()
            client.download("/big-upload.bin", readBack)
            assertArrayEquals("大文件上传后回读应与源字节一致", payload, readBack.toByteArray())
        }
        assertArrayEquals(
            "服务端落盘内容应与源字节一致",
            payload,
            Files.readAllBytes(root.resolve("big-upload.bin"))
        )
    }

    @Test
    fun mkdirRenameDeleteWork() = runBlocking {
        withClient(profile()) { client ->
            client.mkdir("/newdir")
            assertTrue(Files.isDirectory(root.resolve("newdir")))
            client.mkdir("/newdir")

            client.rename("/hello.txt", "/newdir/hello-renamed.txt")
            assertFalse(Files.exists(root.resolve("hello.txt")))
            assertTrue(Files.exists(root.resolve("newdir").resolve("hello-renamed.txt")))

            client.delete("/newdir/hello-renamed.txt", isDirectory = false)
            assertFalse(Files.exists(root.resolve("newdir").resolve("hello-renamed.txt")))
            client.delete("/newdir", isDirectory = true)
            assertFalse(Files.exists(root.resolve("newdir")))
        }
    }

    @Test
    fun downloadMissingFileThrowsRemotePathException() = runBlocking {
        withClient(profile()) { client ->
            val error = runCatching {
                client.download("/missing.bin", ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue("期望 RemotePathException，实际：$error", error is RemotePathException)
        }
    }

    @Test
    fun wrongPasswordThrowsAuthenticationException() = runBlocking {
        val error = SshRemoteFileSystem(profile(password = "wrong")).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("期望 AuthenticationException，实际：$error", error is AuthenticationException)
    }

    @Test
    fun unreachablePortThrowsConnectionException() = runBlocking {
        val error = SshRemoteFileSystem(profile(port = freeTcpPort())).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("期望 ConnectionException，实际：$error", error is ConnectionException)
    }

    // ------------------------------------------------------------ 断点续传

    @Test
    fun downloadResumeAfterInterruption() = runBlocking {
        val payload = randomBytes(300_000, 909L)
        Files.write(root.resolve("resume.bin"), payload)
        val interruptedAt = 100_000
        val partial = ByteArrayOutputStream()

        SshRemoteFileSystem(profile()).use { client ->
            client.connect()
            assertTrue("SFTP 应支持断点续传", client.supportsResume)
            val error = runCatching {
                client.download("/resume.bin", InterruptingOutputStream(partial, interruptedAt))
            }.exceptionOrNull()
            assertNotNull("第一段下载应因模拟中断而失败", error)
        }
        assertEquals(interruptedAt, partial.size())

        val resumed = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        SshRemoteFileSystem(profile()).use { client ->
            client.connect()
            client.downloadPartial("/resume.bin", resumed, partial.size().toLong()) { progress.add(it) }
        }
        assertArrayEquals("断点续传后拼接内容应与源文件一致", payload, partial.toByteArray() + resumed.toByteArray())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue("进度应包含续传偏移", progress.all { it >= interruptedAt.toLong() })
    }

    @Test
    fun uploadResumeAfterPartialUpload() = runBlocking {
        val payload = randomBytes(300_000, 910L)
        val half = payload.size / 2
        val remotePath = "/upload-resume.bin"

        SshRemoteFileSystem(profile()).use { client ->
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
        SshRemoteFileSystem(profile()).use { client ->
            client.connect()
            assertEquals(half.toLong(), client.size(remotePath))
            client.uploadPartial(
                ByteArrayInputStream(payload),
                remotePath,
                payload.size.toLong(),
                half.toLong()
            ) { progress.add(it) }
            assertEquals("进度应按 offset+已写累计", payload.size.toLong(), progress.last())
            assertTrue(progress.all { it >= half.toLong() })
            assertEquals(payload.size.toLong(), client.size(remotePath))

            val readBack = ByteArrayOutputStream()
            client.download(remotePath, readBack)
            assertArrayEquals("续传上传后回读应字节一致", payload, readBack.toByteArray())
        }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("upload-resume.bin")))
    }

    @Test
    fun uploadResumeRejectsMismatchedOffset() = runBlocking {
        val payload = randomBytes(64_000, 911L)
        val remotePath = "/mismatch.bin"
        SshRemoteFileSystem(profile()).use { client ->
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
            assertEquals(-1L, client.size("/docs"))
        }
    }

    // ------------------------------------------------------------ 辅助

    private suspend fun withClient(profile: ServerProfile, block: suspend (RemoteFileSystem) -> Unit) {
        SshRemoteFileSystem(profile).use { client ->
            client.connect()
            block(client)
        }
    }

    private fun profile(
        port: Int = server.port,
        password: String = "s3cret"
    ): ServerProfile = ServerProfile(
        name = "sftp-test",
        protocol = TransferProtocol.SFTP,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = password
    )

    /** 记录底层请求的最大读取长度，用于验证 SFTP 上传源确实被 128 KiB BufferedInputStream 包裹。 */
    private class RecordingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var maxRequestedLength = 0
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len > maxRequestedLength) maxRequestedLength = len
            return super.read(b, off, len)
        }
    }

    @Test
    fun downloadsLargeFileThroughMultichannelPath() = runBlocking {
        // 9 MiB > MULTICHANNEL_MIN_SIZE：走多通道并行下载（内部写临时文件后拷贝到 sink）
        val large = randomBytes(9 * 1024 * 1024, 909L)
        Files.write(root.resolve("large.bin"), large)
        val sink = ByteArrayOutputStream()
        var lastProgress = 0L
        withClient(profile()) { client ->
            client.download("large.bin", sink) { lastProgress = it }
        }
        assertArrayEquals(large, sink.toByteArray())
        assertEquals(large.size.toLong(), lastProgress)
    }

    @Test
    fun downloadsSmallFileWithSingleChannel() = runBlocking {
        // 1 MiB < MULTICHANNEL_MIN_SIZE：仍走单通道，行为与旧实现一致
        val sink = ByteArrayOutputStream()
        withClient(profile()) { client ->
            client.download("big.bin", sink) {}
        }
        assertArrayEquals(bigBytes, sink.toByteArray())
    }

}

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * SCP 适配器真实走线测试：Apache MINA SSHD 提供本地 SSH 服务端与真实的 scp 服务端实现，
 * 覆盖 SCP 线协议下载/上传（含未知大小与空文件）、shell 辅助的列表/建目录/重命名/删除与错误路径。
 */
class SshRemoteFileSystemScpTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var root: Path
    private lateinit var server: TestSshServer
    private lateinit var bigBytes: ByteArray
    private lateinit var uploadBytes: ByteArray

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lantransfer-scp-")
        bigBytes = randomBytes(1024 * 1024, 505L)
        uploadBytes = randomBytes(300_000, 606L)
        Files.write(root.resolve("hello.txt"), "hello SCP".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("empty.bin"), ByteArray(0))
        Files.write(root.resolve("big.bin"), bigBytes)
        Files.createDirectory(root.resolve("docs"))
        Files.write(root.resolve("docs").resolve("readme.md"), "readme".toByteArray(StandardCharsets.UTF_8))
        server = TestSshServer(root)
    }

    @After
    fun tearDown() {
        server.close()
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun listRootAndSubdirectory() = runBlocking {
        withClient(profile()) { client ->
            val byName = client.list("/").associateBy { it.name }
            assertEquals(setOf("hello.txt", "empty.bin", "big.bin", "docs"), byName.keys)
            assertTrue(byName.getValue("docs").isDirectory)
            assertEquals("/docs", byName.getValue("docs").path)
            val big = byName.getValue("big.bin")
            assertTrue(big.isFile)
            assertEquals(bigBytes.size.toLong(), big.size)
            assertTrue("ls -la 应解析出修改时间", big.modifiedAt > 0L)
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
    fun uploadBigFileOverScpIsPipelinedAndByteIdentical() = runBlocking {
        // ≥4 MiB 且大小不是 256 KiB 读块的整数倍：覆盖流水线最后一块与「数据结束 ACK」协议点。
        // 若数据阶段错误地逐块等待 ACK（或多发/少发 ACK），本用例会在 Timeout 内挂起或内容校验失败。
        val payload = randomBytes(4 * 1024 * 1024 + 123_457, 1_415L)
        val progress = ArrayList<Long>()
        withClient(profile()) { client ->
            client.upload(
                ByteArrayInputStream(payload),
                "/big-upload.bin",
                payload.size.toLong()
            ) { progress.add(it) }
            // 同一连接紧接着再上传一个小文件：验证大文件流水线结束后协议状态（ACK/EOF）没有错位
            client.upload(ByteArrayInputStream(uploadBytes), "/after-big.bin", uploadBytes.size.toLong())
        }
        assertArrayEquals(
            "大文件上传内容应字节一致",
            payload,
            Files.readAllBytes(root.resolve("big-upload.bin"))
        )
        assertEquals(
            "服务端落盘大小应与源一致",
            payload.size.toLong(),
            Files.size(root.resolve("big-upload.bin"))
        )
        assertArrayEquals(
            "大文件之后的第二个上传也应完整",
            uploadBytes,
            Files.readAllBytes(root.resolve("after-big.bin"))
        )
        assertTrue("大文件上传应有进度回调", progress.isNotEmpty())
        assertEquals("进度末值应等于文件大小", payload.size.toLong(), progress.last())
        assertTrue("进度应单调不减", progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun uploadWithUnknownSizeSpoolsAndWorks() = runBlocking {
        withClient(profile()) { client ->
            client.upload(ByteArrayInputStream(uploadBytes), "/unknown-size.bin")
            assertArrayEquals(uploadBytes, Files.readAllBytes(root.resolve("unknown-size.bin")))
        }
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
    fun listMissingDirectoryThrowsRemotePathException() = runBlocking {
        withClient(profile()) { client ->
            val error = runCatching { client.list("/missing-dir") }.exceptionOrNull()
            assertTrue("期望 RemotePathException，实际：$error", error is RemotePathException)
        }
    }

    @Test
    fun operationsAfterCloseThrowTransferCancelledException() = runBlocking {
        val client = SshRemoteFileSystem(profile())
        client.connect()
        client.close()
        val error = runCatching { client.list("/") }.exceptionOrNull()
        assertTrue("期望 TransferCancelledException，实际：$error", error is com.manst.lantransfer.core.TransferCancelledException)
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

    // ------------------------------------------------------------ 断点续传能力

    @Test
    fun scpDoesNotSupportResume() = runBlocking {
        withClient(profile()) { client ->
            assertFalse("SCP 协议不支持断点续传", client.supportsResume)
            assertEquals("SCP 不支持 SIZE，按契约返回 -1", -1L, client.size("/big.bin"))
        }
    }

    @Test
    fun partialDownloadFallsBackToFullTransferForScp() = runBlocking {
        withClient(profile()) { client ->
            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            // SCP 无偏移读能力：忽略 startOffset，从 0 全量重传，进度也不带偏移语义
            client.downloadPartial("/big.bin", sink, 500_000L) { progress.add(it) }
            assertArrayEquals(bigBytes, sink.toByteArray())
            assertEquals(bigBytes.size.toLong(), progress.last())
            assertTrue("进度应从 0 开始累计", progress.all { it <= bigBytes.size.toLong() })
        }
    }

    @Test
    fun partialUploadFallsBackToFullTransferForScp() = runBlocking {
        val payload = randomBytes(64_000, 912L)
        withClient(profile()) { client ->
            // SCP 无追加写能力：忽略 startOffset，完整重传
            client.uploadPartial(ByteArrayInputStream(payload), "/scp-partial.bin", payload.size.toLong(), 10_000L)
        }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("scp-partial.bin")))
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
        name = "scp-test",
        protocol = TransferProtocol.SCP,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = password
    )
}

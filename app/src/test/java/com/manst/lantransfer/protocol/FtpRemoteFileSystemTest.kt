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
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.Permissions
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Date

/**
 * FTP 适配器真实走线测试：用 MockFtpServer 3.1.0 起本地 FTP 服务端，
 * 覆盖列表（MLSD 不支持时回退 LIST）、下载、上传、建目录、重命名、删除、主动模式与错误路径。
 */
class FtpRemoteFileSystemTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private lateinit var server: FakeFtpServer
    private lateinit var fileSystem: UnixFakeFileSystem
    private lateinit var bigBytes: ByteArray
    private lateinit var uploadBytes: ByteArray
    private var port: Int = 0

    @Before
    fun setUp() {
        bigBytes = randomBytes(1024 * 1024, 101L)
        uploadBytes = randomBytes(300_000, 202L)

        fileSystem = UnixFakeFileSystem()
        fileSystem.add(directoryEntry("/testroot"))
        fileSystem.add(directoryEntry("/testroot/docs"))
        fileSystem.add(directoryEntry("/testroot/emptydir"))
        fileSystem.add(fileEntry("/testroot/hello.txt", "hello FTP".toByteArray()))
        fileSystem.add(fileEntry("/testroot/empty.bin", ByteArray(0)))
        fileSystem.add(fileEntry("/testroot/big.bin", bigBytes))
        fileSystem.add(fileEntry("/testroot/docs/readme.txt", "readme".toByteArray()))

        server = FakeFtpServer()
        server.setServerControlPort(0)
        server.addUserAccount(UserAccount("tester", "tester", "/testroot"))
        server.fileSystem = fileSystem
        server.start()
        port = server.serverControlPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun listsRootAndSubdirectory() = runBlocking {
        withClient(profile()) { client ->
            val entries = client.list("/testroot")
            val byName = entries.associateBy { it.name }
            assertEquals(setOf("docs", "emptydir", "hello.txt", "empty.bin", "big.bin"), byName.keys)

            val docs = byName.getValue("docs")
            assertTrue(docs.isDirectory)
            assertEquals("/testroot/docs", docs.path)
            assertEquals(0L, docs.size)

            val big = byName.getValue("big.bin")
            assertTrue(big.isFile)
            assertEquals(bigBytes.size.toLong(), big.size)
            assertTrue("目录列表应解析出修改时间", big.modifiedAt > 0L)
            assertTrue("目录列表应解析出权限串", big.permissions.isNotEmpty())

            val subEntries = client.list("/testroot/docs")
            assertEquals(listOf("readme.txt"), subEntries.map { it.name })
        }
    }

    @Test
    fun downloadBigFileIsByteIdenticalAndReportsProgress() = runBlocking {
        withClient(profile()) { client ->
            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            client.download("/testroot/big.bin", sink) { progress.add(it) }

            assertArrayEquals(bigBytes, sink.toByteArray())
            assertTrue("应至少回调一次进度", progress.isNotEmpty())
            assertEquals(bigBytes.size.toLong(), progress.last())
            assertTrue("进度必须单调不减", progress.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test
    fun downloadEmptyFileProducesNoBytes() = runBlocking {
        withClient(profile()) { client ->
            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            client.download("/testroot/empty.bin", sink) { progress.add(it) }
            assertEquals(0, sink.size())
            assertTrue(progress.all { it == 0L })
        }
    }

    @Test
    fun uploadFileIsByteIdentical() = runBlocking {
        withClient(profile()) { client ->
            val progress = ArrayList<Long>()
            client.upload(
                ByteArrayInputStream(uploadBytes),
                "/testroot/uploaded.bin",
                uploadBytes.size.toLong()
            ) { progress.add(it) }

            val uploaded = fileSystem.getEntry("/testroot/uploaded.bin") as FileEntry
            assertArrayEquals(uploadBytes, uploaded.createInputStream().readBytes())
            assertEquals(uploadBytes.size.toLong(), progress.last())
        }
    }

    @Test
    fun uploadWithUnknownSizeStillWorks() = runBlocking {
        withClient(profile()) { client ->
            client.upload(ByteArrayInputStream(uploadBytes), "/testroot/unknown-size.bin")
            val uploaded = fileSystem.getEntry("/testroot/unknown-size.bin") as FileEntry
            assertArrayEquals(uploadBytes, uploaded.createInputStream().readBytes())
        }
    }

    @Test
    fun mkdirIsIdempotentAndRenameDeleteWork() = runBlocking {
        withClient(profile()) { client ->
            client.mkdir("/testroot/newdir")
            assertTrue(fileSystem.isDirectory("/testroot/newdir"))
            // 已存在时按契约应视为成功
            client.mkdir("/testroot/newdir")

            client.rename("/testroot/hello.txt", "/testroot/renamed.txt")
            assertFalse(fileSystem.exists("/testroot/hello.txt"))
            assertTrue(fileSystem.exists("/testroot/renamed.txt"))

            client.delete("/testroot/renamed.txt", isDirectory = false)
            assertFalse(fileSystem.exists("/testroot/renamed.txt"))

            client.delete("/testroot/newdir", isDirectory = true)
            assertFalse(fileSystem.exists("/testroot/newdir"))
        }
    }

    @Test
    fun activeModeListsAndDownloads() = runBlocking {
        withClient(profile(passiveMode = false)) { client ->
            val names = client.list("/testroot").map { it.name }.toSet()
            assertTrue(names.contains("big.bin"))
            val sink = ByteArrayOutputStream()
            client.download("/testroot/big.bin", sink)
            assertArrayEquals(bigBytes, sink.toByteArray())
        }
    }

    @Test
    fun downloadMissingFileThrowsRemotePathException() = runBlocking {
        withClient(profile()) { client ->
            val error = runCatching {
                client.download("/testroot/missing.bin", ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue("期望 RemotePathException，实际：$error", error is RemotePathException)
        }
    }

    @Test
    fun listEmptyDirectoryReturnsEmptyList() = runBlocking {
        withClient(profile()) { client ->
            // MockFtpServer 对不存在目录的 LIST 也返回 226 空列表（非标准行为），
            // 因此这里只验证真实空目录的语义；真实服务端的 550 由下载/上传路径覆盖。
            assertTrue(client.list("/testroot/emptydir").isEmpty())
        }
    }

    @Test
    fun operationsAfterCloseThrowTransferCancelledException() = runBlocking {
        val client = FtpRemoteFileSystem(profile())
        client.connect()
        client.close()
        val error = runCatching { client.list("/testroot") }.exceptionOrNull()
        assertTrue("期望 TransferCancelledException，实际：$error", error is com.manst.lantransfer.core.TransferCancelledException)
    }

    @Test
    fun wrongPasswordThrowsAuthenticationException() = runBlocking {
        val error = FtpRemoteFileSystem(profile(password = "wrong-password")).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("期望 AuthenticationException，实际：$error", error is AuthenticationException)
    }

    @Test
    fun unreachablePortThrowsConnectionException() = runBlocking {
        val deadPort = freeTcpPort()
        val error = FtpRemoteFileSystem(profile(port = deadPort)).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("期望 ConnectionException，实际：$error", error is ConnectionException)
    }

    // ------------------------------------------------------------ 断点续传

    @Test
    fun downloadResumeAfterInterruption() = runBlocking {
        // MockFtpServer 的 FakeFtpServer 不真正实现 REST（仅回 350 不记偏移），
        // 因此这里用纯 FTP 模式的 TestFtpsServer 验证真实的 REST 偏移下载。
        withPlainFtpServer { resumeProfile, resumeRoot ->
            val payload = randomBytes(300_000, 901L)
            Files.write(resumeRoot.resolve("resume.bin"), payload)
            val interruptedAt = 120_000
            val partial = ByteArrayOutputStream()

            FtpRemoteFileSystem(resumeProfile).use { client ->
                client.connect()
                assertTrue("FTP 应支持断点续传", client.supportsResume)
                val error = runCatching {
                    client.download("/resume.bin", InterruptingOutputStream(partial, interruptedAt))
                }.exceptionOrNull()
                assertNotNull("第一段下载应因模拟中断而失败", error)
            }
            assertEquals(interruptedAt, partial.size())

            val resumed = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            FtpRemoteFileSystem(resumeProfile).use { client ->
                client.connect()
                client.downloadPartial("/resume.bin", resumed, partial.size().toLong()) { progress.add(it) }
            }

            assertArrayEquals("断点续传后拼接内容应与源文件一致", payload, partial.toByteArray() + resumed.toByteArray())
            assertEquals(payload.size.toLong(), progress.last())
            assertTrue("进度应包含续传偏移", progress.all { it >= interruptedAt.toLong() })
        }
    }

    @Test
    fun uploadResumeAfterPartialUpload() = runBlocking {
        withPlainFtpServer { resumeProfile, resumeRoot ->
            val payload = randomBytes(300_000, 902L)
            val half = payload.size / 2
            val remotePath = "/upload-resume.bin"

            // 先上传前一半：远端已有 half 字节
            FtpRemoteFileSystem(resumeProfile).use { client ->
                client.connect()
                client.uploadPartial(
                    ByteArrayInputStream(payload.copyOfRange(0, half)),
                    remotePath,
                    half.toLong(),
                    0L
                )
            }
            assertEquals(half.toLong(), Files.size(resumeRoot.resolve("upload-resume.bin")))

            // 用完整文件流 + 续传偏移上传剩余一半
            val progress = ArrayList<Long>()
            FtpRemoteFileSystem(resumeProfile).use { client ->
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
            assertArrayEquals(payload, Files.readAllBytes(resumeRoot.resolve("upload-resume.bin")))
        }
    }

    @Test
    fun uploadResumeRejectsMismatchedOffset() = runBlocking {
        withPlainFtpServer { resumeProfile, _ ->
            val payload = randomBytes(64_000, 903L)
            val remotePath = "/mismatch.bin"
            FtpRemoteFileSystem(resumeProfile).use { client ->
                client.connect()
                client.upload(ByteArrayInputStream(payload.copyOfRange(0, 10_000)), remotePath, 10_000L)
                val error = runCatching {
                    client.uploadPartial(
                        ByteArrayInputStream(payload),
                        remotePath,
                        payload.size.toLong(),
                        20_000L
                    )
                }.exceptionOrNull()
                assertTrue("远端大小与续传偏移不一致应抛 IOException，实际：$error", error is IOException)
                assertTrue("错误信息应说明不一致", error?.message?.contains("不一致") == true)
            }
        }
    }

    @Test
    fun sizeReturnsFileSizeOrMinusOne() = runBlocking {
        // MockFtpServer 3.2.0 起实现了 SIZE 命令，会返回真实大小；
        // 契约仍允许 -1（不支持 SIZE 的服务端），因此只断言「真实大小或 -1」。
        withClient(profile()) { client ->
            val size = client.size("/testroot/big.bin")
            assertTrue("SIZE 应返回真实大小或 -1，实际：$size", size == -1L || size > 0L)
        }
        // 真实 FTP 服务端：SIZE 正常工作，缺失/目录返回 -1
        withPlainFtpServer { resumeProfile, resumeRoot ->
            val payload = randomBytes(12_345, 904L)
            Files.write(resumeRoot.resolve("sized.bin"), payload)
            FtpRemoteFileSystem(resumeProfile).use { client ->
                client.connect()
                assertEquals(payload.size.toLong(), client.size("/sized.bin"))
                assertEquals(-1L, client.size("/missing.bin"))
            }
        }
    }

    // ------------------------------------------------------------ 辅助

    private suspend fun withPlainFtpServer(block: suspend (ServerProfile, Path) -> Unit) {
        val resumeRoot = Files.createTempDirectory("lantransfer-ftp-resume-")
        val plainServer = TestFtpsServer(resumeRoot, "tester", "tester", tlsEnabled = false)
        try {
            val plainProfile = ServerProfile(
                name = "ftp-resume",
                protocol = TransferProtocol.FTP,
                host = "127.0.0.1",
                port = plainServer.port,
                username = "tester",
                password = "tester"
            )
            block(plainProfile, resumeRoot)
        } finally {
            plainServer.close()
            Files.walk(resumeRoot).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private suspend fun withClient(profile: ServerProfile, block: suspend (RemoteFileSystem) -> Unit) {
        FtpRemoteFileSystem(profile).use { client ->
            client.connect()
            block(client)
        }
    }

    private fun profile(
        protocol: TransferProtocol = TransferProtocol.FTP,
        port: Int = this.port,
        password: String = "tester",
        passiveMode: Boolean = true
    ): ServerProfile = ServerProfile(
        name = "ftp-test",
        protocol = protocol,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = password,
        passiveMode = passiveMode
    )

    private fun fileEntry(path: String, content: ByteArray): FileEntry {
        val entry = FileEntry(path)
        entry.setContents(content)
        entry.permissions = Permissions.ALL
        entry.owner = "tester"
        entry.group = "users"
        entry.lastModified = Date(System.currentTimeMillis() - 60_000L)
        return entry
    }

    private fun directoryEntry(path: String): DirectoryEntry {
        return DirectoryEntry(path).apply {
            permissions = Permissions.ALL
            owner = "tester"
            group = "users"
            lastModified = Date(System.currentTimeMillis() - 120_000L)
        }
    }
}

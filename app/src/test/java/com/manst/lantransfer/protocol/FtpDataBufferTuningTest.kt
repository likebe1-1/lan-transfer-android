package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * FTP/FTPS 数据连接缓冲区调优测试：
 * 1. 配置函数把 128 KiB 同时应用到数据流缓冲与数据 socket 的收发缓冲；
 * 2. connect() 确实完成配置，且目录列表、下载、上传在调优后行为不变；
 * 3. FTPS 加密数据连接同样生效（FTPSClient 覆写的 _openDataConnection_ 也读取这两个数据 socket 缓冲值）。
 */
class FtpDataBufferTuningTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lantransfer-buffer-")
    }

    @After
    fun tearDown() {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun configAppliesStreamAndDataSocketBuffers() {
        val ftp = FTPClient()
        applyDataTransferBufferTuning(ftp)

        assertEquals("数据流缓冲应使用调优值", DATA_TRANSFER_BUFFER_SIZE, ftp.bufferSize)
        assertEquals("数据 socket 接收缓冲应使用调优值", DATA_TRANSFER_BUFFER_SIZE, ftp.receiveDataSocketBufferSize)
        assertEquals("数据 socket 发送缓冲应使用调优值", DATA_TRANSFER_BUFFER_SIZE, ftp.sendDataSocketBufferSize)
        assertEquals("缓冲区常量应保持 128 KiB", 128 * 1024, DATA_TRANSFER_BUFFER_SIZE)
        assertTrue(
            "缓冲区应落在 128 KiB~256 KiB 的建议区间",
            DATA_TRANSFER_BUFFER_SIZE in (128 * 1024)..(256 * 1024)
        )
    }

    @Test
    fun configAppliesToFtpsClientToo() {
        val ftps = FTPSClient()
        applyDataTransferBufferTuning(ftps)

        assertEquals(DATA_TRANSFER_BUFFER_SIZE, ftps.bufferSize)
        assertEquals(DATA_TRANSFER_BUFFER_SIZE, ftps.receiveDataSocketBufferSize)
        assertEquals(DATA_TRANSFER_BUFFER_SIZE, ftps.sendDataSocketBufferSize)
    }

    @Test
    fun ftpConnectAppliesTuningAndKeepsListDownloadUploadWorking() = runBlocking {
        val payload = randomBytes(256 * 1024, 811L)
        val uploadPayload = randomBytes(96 * 1024, 812L)
        Files.write(root.resolve("payload.bin"), payload)
        Files.createDirectory(root.resolve("sub"))
        Files.write(root.resolve("sub").resolve("note.txt"), "note".toByteArray())

        val server = TestFtpsServer(root, "tester", "tester", tlsEnabled = false)
        try {
            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                assertBufferTuningCaptured(client)

                // 目录列表走独立数据连接，调优后仍应正确解析
                val names = client.list("/").map { it.name }.toSet()
                assertTrue("目录列表应包含普通文件与子目录，实际：$names", names.containsAll(setOf("payload.bin", "sub")))

                // 下载（数据流包装的 BufferedInputStream）字节级一致
                val sink = ByteArrayOutputStream()
                client.download("/payload.bin", sink)
                assertArrayEquals("下载内容应与源文件一致", payload, sink.toByteArray())

                // 上传（数据流包装的 BufferedOutputStream）落盘一致
                client.upload(ByteArrayInputStream(uploadPayload), "/uploaded.bin", uploadPayload.size.toLong())
                assertArrayEquals(uploadPayload, Files.readAllBytes(root.resolve("uploaded.bin")))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun ftpsConnectAppliesTuningAndEncryptedTransferWorks() = runBlocking {
        val payload = randomBytes(192 * 1024, 813L)
        Files.write(root.resolve("enc.bin"), payload)

        val server = TestFtpsServer(root, "tester", "s3cret")
        try {
            FtpRemoteFileSystem(profile(TransferProtocol.FTPS, server.port, "s3cret")).use { client ->
                client.connect()
                assertBufferTuningCaptured(client)
                assertTrue("FTPS 路径应使用 FTPSClient", client.ftpForTest() is FTPSClient)

                val sink = ByteArrayOutputStream()
                client.download("/enc.bin", sink)
                assertArrayEquals("FTPS 加密数据连接下载应字节一致", payload, sink.toByteArray())
            }
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------ 辅助

    /** 通过反射读取真实使用的 FTPClient，验证 connect() 已把调优配置落实到客户端实例。 */
    private fun FtpRemoteFileSystem.ftpForTest(): FTPClient {
        val field = FtpRemoteFileSystem::class.java.getDeclaredField("ftp")
        field.isAccessible = true
        return field.get(this) as FTPClient
    }

    private fun assertBufferTuningCaptured(client: FtpRemoteFileSystem) {
        val ftp = client.ftpForTest()
        assertEquals("connect() 应设置数据流缓冲", DATA_TRANSFER_BUFFER_SIZE, ftp.bufferSize)
        assertEquals("connect() 应设置数据 socket 接收缓冲", DATA_TRANSFER_BUFFER_SIZE, ftp.receiveDataSocketBufferSize)
        assertEquals("connect() 应设置数据 socket 发送缓冲", DATA_TRANSFER_BUFFER_SIZE, ftp.sendDataSocketBufferSize)
    }

    private fun profile(protocol: TransferProtocol, port: Int, password: String): ServerProfile = ServerProfile(
        name = "ftp-buffer-test",
        protocol = protocol,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = password,
        ftpsMode = FtpsMode.EXPLICIT,
        allowInsecureTls = true
    )
}

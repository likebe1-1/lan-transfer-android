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
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Comparator
import javax.net.SocketFactory

/**
 * FTP/FTPS 数据连接缓冲区调优测试：
 * 1. 配置函数把 128 KiB 同时应用到数据流缓冲与数据 socket 的收发缓冲，且不误改控制连接缓冲；
 * 2. connect() 确实完成配置，且目录列表、下载、上传在调优后行为不变；
 * 3. 上传方向（storeFile/appendFile）的 App 侧拷贝缓冲与下载方向共用同一个 128 KiB 常量：
 *    用记录真实 read 请求长度的输入流断言，防止 Commons Net 升级后悄悄退回 8 KiB 默认值；
 * 4. 上传/下载的真实数据 socket 上 SO_SNDBUF/SO_RCVBUF 已生效（捕获数据 socket 后读取）；
 * 5. 续传上传（APPE）语义不变：进度从 offset 累计、不残留 REST 偏移，
 *    且 skip 不可用的来源也按 128 KiB 缓冲跳过已上传前缀，不会退化成逐字节读取；
 * 6. FTPS 加密数据连接同样生效（FTPSClient 覆写的 _openDataConnection_ 也读取这两个数据 socket 缓冲值）。
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
        // 控制连接只传命令，调大其 SO_RCVBUF/SO_SNDBUF 没有吞吐收益，配置函数不应误改
        assertEquals("不应改动控制连接接收缓冲", -1, socketClientBufferHint(ftp, "receiveBufferSize"))
        assertEquals("不应改动控制连接发送缓冲", -1, socketClientBufferHint(ftp, "sendBufferSize"))
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

    // ------------------------------------------------------------ 上传拷贝缓冲（App 侧 128 KiB）

    @Test
    fun uploadCopyBufferUsesDataTransferBufferSize() = runBlocking {
        val payload = randomBytes(512 * 1024, 821L)

        val server = TestFtpsServer(root, "tester", "tester", tlsEnabled = false)
        try {
            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                assertEquals(
                    "数据传输前应已配置 128 KiB 拷贝缓冲",
                    DATA_TRANSFER_BUFFER_SIZE,
                    client.ftpForTest().bufferSize
                )

                // 上传：App 侧 InputStream 收到的 read 请求长度即 Commons Net 的拷贝缓冲（storeFile）
                val source = RecordingInputStream(payload)
                val progress = ArrayList<Long>()
                client.upload(source, "/upload-copy.bin", payload.size.toLong()) { progress.add(it) }

                assertArrayEquals(payload, Files.readAllBytes(root.resolve("upload-copy.bin")))
                assertEquals(
                    "上传方向 App 侧拷贝缓冲应为 DATA_TRANSFER_BUFFER_SIZE",
                    DATA_TRANSFER_BUFFER_SIZE,
                    source.maxReadRequestLength
                )
                assertTrue(
                    "上传的每次 read 请求都不应小于 32 KiB（8/32 KiB 小缓冲会退化为更小值），实际最小：${source.minReadRequestLength}",
                    source.minReadRequestLength >= 32 * 1024
                )
                assertEquals("上传进度应累计到完整文件大小", payload.size.toLong(), progress.last())
                assertTrue(
                    "上传进度应按 128 KiB 分块回调，次数应远小于逐字节，实际：${progress.size}",
                    progress.size <= payload.size / DATA_TRANSFER_BUFFER_SIZE + 2
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun ftpsUploadCopyBufferMatchesDataTransferBufferSize() = runBlocking {
        val payload = randomBytes(384 * 1024, 825L)

        val server = TestFtpsServer(root, "tester", "s3cret")
        try {
            FtpRemoteFileSystem(profile(TransferProtocol.FTPS, server.port, "s3cret")).use { client ->
                client.connect()
                assertBufferTuningCaptured(client)

                val source = RecordingInputStream(payload)
                client.upload(source, "/ftps-upload-copy.bin", payload.size.toLong())

                assertArrayEquals(payload, Files.readAllBytes(root.resolve("ftps-upload-copy.bin")))
                assertEquals(
                    "FTPS 加密数据连接的上传拷贝缓冲应与下载方向一致为 DATA_TRANSFER_BUFFER_SIZE",
                    DATA_TRANSFER_BUFFER_SIZE,
                    source.maxReadRequestLength
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun dataConnectionSocketsApplySendAndReceiveBufferTuning() = runBlocking {
        val payload = randomBytes(384 * 1024, 826L)
        Files.write(root.resolve("socket-download.bin"), payload)

        val server = TestFtpsServer(root, "tester", "tester", tlsEnabled = false)
        try {
            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                val ftp = client.ftpForTest()
                // 控制连接已建立，此时替换 SocketFactory 只影响后续数据连接
                // （Commons Net 用 _socketFactory_ 创建被动模式数据 socket，并在 connect() 之前设置 SO_RCVBUF/SO_SNDBUF）。
                val factory = CapturingSocketFactory()
                ftp.setSocketFactory(factory)

                var uploadSendBuffer = -1
                var uploadReceiveBuffer = -1
                client.upload(ByteArrayInputStream(payload), "/socket-upload.bin", payload.size.toLong()) {
                    if (uploadSendBuffer < 0) {
                        val socket = factory.sockets.lastOrNull() ?: return@upload
                        uploadSendBuffer = socket.sendBufferSize
                        uploadReceiveBuffer = socket.receiveBufferSize
                    }
                }
                assertArrayEquals(payload, Files.readAllBytes(root.resolve("socket-upload.bin")))
                assertTrue(
                    "上传数据 socket 的 SO_SNDBUF 应不小于 DATA_TRANSFER_BUFFER_SIZE，实际：$uploadSendBuffer",
                    uploadSendBuffer >= DATA_TRANSFER_BUFFER_SIZE
                )
                assertTrue(
                    "上传数据 socket 的 SO_RCVBUF 应不小于 DATA_TRANSFER_BUFFER_SIZE，实际：$uploadReceiveBuffer",
                    uploadReceiveBuffer >= DATA_TRANSFER_BUFFER_SIZE
                )

                val socketsBeforeDownload = factory.sockets.size
                var downloadReceiveBuffer = -1
                var downloadSendBuffer = -1
                client.download("/socket-download.bin", ByteArrayOutputStream()) {
                    if (downloadReceiveBuffer < 0 && factory.sockets.size > socketsBeforeDownload) {
                        val socket = factory.sockets.last()
                        downloadReceiveBuffer = socket.receiveBufferSize
                        downloadSendBuffer = socket.sendBufferSize
                    }
                }
                assertTrue(
                    "下载数据 socket 的 SO_RCVBUF 应不小于 DATA_TRANSFER_BUFFER_SIZE，实际：$downloadReceiveBuffer",
                    downloadReceiveBuffer >= DATA_TRANSFER_BUFFER_SIZE
                )
                assertTrue(
                    "下载数据 socket 的 SO_SNDBUF 应不小于 DATA_TRANSFER_BUFFER_SIZE，实际：$downloadSendBuffer",
                    downloadSendBuffer >= DATA_TRANSFER_BUFFER_SIZE
                )
            }
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------ 续传上传（APPE）语义

    @Test
    fun resumeUploadAppendUsesDataTransferBufferSizeAndKeepsAppeSemantics() = runBlocking {
        val payload = randomBytes(320 * 1024, 822L)
        val half = payload.size / 2
        val remotePath = "/append-copy.bin"

        val server = TestFtpsServer(root, "tester", "tester", tlsEnabled = false)
        try {
            // 先用 STOR 写入前半段，模拟上传中断后的远端状态
            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                client.upload(ByteArrayInputStream(payload.copyOfRange(0, half)), remotePath, half.toLong())
            }
            assertEquals(half.toLong(), Files.size(root.resolve("append-copy.bin")))

            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                val ftp = client.ftpForTest()
                val restarts = ArrayList<Long>()
                val progress = ArrayList<Long>()
                val source = RecordingInputStream(payload)

                client.uploadPartial(source, remotePath, payload.size.toLong(), half.toLong()) { reported ->
                    progress.add(reported)
                    // 续传上传走 APPE，任何进度回调时刻都不应残留 REST 偏移
                    restarts.add(ftp.restartOffset)
                }

                assertArrayEquals(
                    "APPE 追加后完整文件应与源内容一致",
                    payload,
                    Files.readAllBytes(root.resolve("append-copy.bin"))
                )
                assertEquals(payload.size.toLong(), client.size(remotePath))
                assertEquals("续传上传不应设置 REST 偏移", listOf(0L), restarts.distinct())
                assertEquals(0L, ftp.restartOffset)
                assertEquals("进度应从续传偏移起累计", payload.size.toLong(), progress.last())
                assertTrue("进度不应小于续传偏移", progress.all { it >= half.toLong() })
                assertEquals(
                    "续传上传的 App 侧拷贝缓冲应与全量上传一致",
                    DATA_TRANSFER_BUFFER_SIZE,
                    source.maxReadRequestLength
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun resumeUploadSkipsAlreadyUploadedPrefixWithLargeBufferWhenStreamCannotSkip() = runBlocking {
        val payload = randomBytes(300_000, 823L)
        val half = 150_000
        val remotePath = "/skip-prefix.bin"

        val server = TestFtpsServer(root, "tester", "tester", tlsEnabled = false)
        try {
            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                client.upload(ByteArrayInputStream(payload.copyOfRange(0, half)), remotePath, half.toLong())
            }
            assertEquals(half.toLong(), Files.size(root.resolve("skip-prefix.bin")))

            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                // 模拟 skip 恒返回 0 的 ContentResolver/管道流（SAF 云盘流常见）
                val source = RecordingInputStream(payload, skippable = false)
                val progress = ArrayList<Long>()

                client.uploadPartial(source, remotePath, payload.size.toLong(), half.toLong()) { progress.add(it) }

                assertArrayEquals(payload, Files.readAllBytes(root.resolve("skip-prefix.bin")))
                assertEquals("上传结束后来源流应已被完整消费", payload.size, source.position)
                assertEquals("进度应从续传偏移起累计", payload.size.toLong(), progress.last())
                assertEquals(
                    "skip 不可用时也应用 128 KiB 缓冲读取以跳过前缀",
                    DATA_TRANSFER_BUFFER_SIZE,
                    source.maxReadRequestLength
                )
                assertEquals("跳过前缀不应退化成逐字节 read()", 0, source.singleByteReads)
                assertTrue(
                    "跳过 150000 字节前缀应只用少量缓冲读取，实际：${source.readCallCount}",
                    source.readCallCount <= 8
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun resumeUploadFailsWhenSourceShorterThanOffset() = runBlocking {
        val offset = 100_000
        val shortPayload = randomBytes(50_000, 824L)
        val remotePath = "/short-source.bin"

        val server = TestFtpsServer(root, "tester", "tester", tlsEnabled = false)
        try {
            // 远端已有 offset 字节（与调用方给出的续传偏移一致），但本地流更短
            Files.write(root.resolve("short-source.bin"), randomBytes(offset, 825L))
            FtpRemoteFileSystem(profile(TransferProtocol.FTP, server.port, "tester")).use { client ->
                client.connect()
                val error = runCatching {
                    client.uploadPartial(
                        RecordingInputStream(shortPayload),
                        remotePath,
                        offset.toLong(),
                        offset.toLong()
                    )
                }.exceptionOrNull()
                assertTrue("来源不足时应抛 IOException，实际：$error", error is java.io.IOException)
                assertTrue(
                    "错误信息应说明本地文件长度不足，实际：${error?.message}",
                    error?.message?.contains("本地文件长度不足") == true
                )
            }
            // 失败时不应破坏已存在的远端前缀
            assertEquals(offset.toLong(), Files.size(root.resolve("short-source.bin")))
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

    /** 读取 SocketClient 私有的控制连接缓冲提示字段（-1 表示未设置，即控制连接不受调优影响）。 */
    private fun socketClientBufferHint(client: FTPClient, fieldName: String): Int {
        var type: Class<*>? = client.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.getInt(client)
            } catch (e: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw AssertionError("未找到 SocketClient 字段：$fieldName")
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

    /**
     * 记录 read 请求长度的上传来源流：Commons Net 的 storeFile/appendFile 按拷贝缓冲大小发起 read，
     * 因此最大请求长度即上传方向的 App 侧拷贝缓冲。[skippable] = false 时模拟 skip 恒返回 0 的来源。
     */
    private class RecordingInputStream(
        private val payload: ByteArray,
        private val skippable: Boolean = true
    ) : InputStream() {

        private val readLengths = ArrayList<Int>()

        var position: Int = 0
            private set

        var singleByteReads: Int = 0
            private set

        val maxReadRequestLength: Int
            get() = readLengths.maxOrNull() ?: 0

        val minReadRequestLength: Int
            get() = readLengths.minOrNull() ?: 0

        val readCallCount: Int
            get() = readLengths.size

        override fun read(): Int {
            singleByteReads++
            if (position >= payload.size) return -1
            return payload[position++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readLengths.add(length)
            if (length == 0) return 0
            if (position >= payload.size) return -1
            val count = minOf(length, payload.size - position)
            System.arraycopy(payload, position, buffer, offset, count)
            position += count
            return count
        }

        override fun skip(count: Long): Long {
            if (!skippable) return 0L
            val skipped = minOf(count, (payload.size - position).toLong())
            position += skipped.toInt()
            return skipped
        }

        override fun available(): Int = payload.size - position
    }

    /** 捕获 FTP 数据连接实际创建的 socket，用于断言 SO_RCVBUF/SO_SNDBUF 已生效。 */
    private class CapturingSocketFactory : SocketFactory() {

        val sockets: MutableList<Socket> = Collections.synchronizedList(mutableListOf<Socket>())

        override fun createSocket(): Socket = Socket().also { sockets.add(it) }

        override fun createSocket(host: String, port: Int): Socket =
            Socket(host, port).also { sockets.add(it) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Socket(host, port, localHost, localPort).also { sockets.add(it) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            Socket(host, port).also { sockets.add(it) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = Socket(address, port, localAddress, localPort).also { sockets.add(it) }
    }
}

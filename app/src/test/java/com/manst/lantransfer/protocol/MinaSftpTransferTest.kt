package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.SshHostKeyProbe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale

/**
 * MINA SSHD SFTP 传输引擎真实走线测试：TestSshServer 提供真实 SFTP 服务端，
 * 覆盖 1 MiB / 8 MiB 双向传输、断点续传、主机密钥指纹固定、
 * packetSize/windowSize 是否真的写入客户端配置，以及与 JSch 的 8 MiB 下载计时对比。
 */
class MinaSftpTransferTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private lateinit var root: Path
    private lateinit var server: TestSshServer

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mina-sftp-")
        server = TestSshServer(root, enableSftp = true)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ------------------------------------------------------------ 下载 / 上传

    @Test
    fun downloads1MiBAnd8MiBWithMatchingSha256() = runBlocking {
        val oneMiB = randomBytes(1024 * 1024, 101L)
        val eightMiB = randomBytes(8 * 1024 * 1024, 102L)
        Files.write(root.resolve("one.bin"), oneMiB)
        Files.write(root.resolve("eight.bin"), eightMiB)

        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            assertTrue("连接后应为已连接状态", transfer.isConnected)
            assertDownloadMatches(transfer, "/one.bin", oneMiB)
            assertDownloadMatches(transfer, "/eight.bin", eightMiB)
        }
    }

    @Test
    fun uploadsRoundTrip1MiBAnd8MiB() = runBlocking {
        val oneMiB = randomBytes(1024 * 1024, 201L)
        val eightMiB = randomBytes(8 * 1024 * 1024, 202L)
        val payloads = listOf("upload-one.bin" to oneMiB, "upload-eight.bin" to eightMiB)

        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            for ((name, payload) in payloads) {
                val progress = ArrayList<Long>()
                transfer.upload(
                    ByteArrayInputStream(payload),
                    "/" + name,
                    payload.size.toLong()
                ) { progress.add(it) }
                assertEquals("进度末值应等于文件大小", payload.size.toLong(), progress.last())
                assertEquals("远端大小应等于源数据长度", payload.size.toLong(), transfer.size("/" + name))
                assertEquals(
                    "服务端落盘内容 SHA-256 应与源一致",
                    sha256Hex(payload),
                    sha256Hex(Files.readAllBytes(root.resolve(name)))
                )

                val readBack = ByteArrayOutputStream(payload.size)
                transfer.download("/" + name, readBack)
                assertEquals(
                    "上传后回读 SHA-256 应与源一致",
                    sha256Hex(payload),
                    sha256Hex(readBack.toByteArray())
                )
            }
        }
    }

    @Test
    fun downloadResumesAfterInterruption() = runBlocking {
        val payload = randomBytes(300_000, 404L)
        Files.write(root.resolve("resume.bin"), payload)
        val interruptedAt = 100_000
        val partial = ByteArrayOutputStream()

        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            val error = runCatching {
                transfer.download("/resume.bin", InterruptingOutputStream(partial, interruptedAt))
            }.exceptionOrNull()
            assertNotNull("第一段下载应因模拟中断而失败", error)
            assertTrue("中断应映射为统一传输异常，实际：" + error, error is LanTransferException)
        }
        assertEquals("中断前应保留已写入的前缀", interruptedAt, partial.size())

        val resumed = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            transfer.downloadPartial("/resume.bin", resumed, partial.size().toLong()) { progress.add(it) }
        }

        assertArrayEquals(
            "断点续传拼接后内容应与源文件一致",
            payload,
            partial.toByteArray() + resumed.toByteArray()
        )
        assertEquals("续传进度末值应等于远端文件大小", payload.size.toLong(), progress.last())
        assertTrue("续传进度应包含起始偏移", progress.all { it >= interruptedAt.toLong() })
    }

    @Test
    fun downloadPartialReadsFromRequestedOffset() = runBlocking {
        val payload = randomBytes(200_000, 405L)
        Files.write(root.resolve("partial.bin"), payload)
        val offset = 123_456L
        val sink = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            transfer.downloadPartial("/partial.bin", sink, offset) { progress.add(it) }
        }
        assertArrayEquals(
            "从偏移继续读的内容应等于源文件后缀",
            payload.copyOfRange(offset.toInt(), payload.size),
            sink.toByteArray()
        )
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue("进度应从续传偏移起累计", progress.first() >= offset)
    }

    @Test
    fun downloadPartialRejectsOffsetBeyondRemoteSize() = runBlocking {
        Files.write(root.resolve("tiny.bin"), randomBytes(16, 406L))
        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            val error = runCatching {
                transfer.downloadPartial("/tiny.bin", ByteArrayOutputStream(), 1_000L)
            }.exceptionOrNull()
            assertTrue("偏移超过远端大小时应抛 RemotePathException，实际：" + error, error is RemotePathException)
        }
    }

    @Test
    fun uploadResumesFromOffsetWithFullSource() = runBlocking {
        val payload = randomBytes(300_000, 505L)
        val half = payload.size / 2

        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            transfer.upload(
                ByteArrayInputStream(payload.copyOfRange(0, half)),
                "/upload-resume.bin",
                half.toLong()
            )
            assertEquals(half.toLong(), transfer.size("/upload-resume.bin"))

            val progress = ArrayList<Long>()
            transfer.uploadPartial(
                ByteArrayInputStream(payload),
                "/upload-resume.bin",
                payload.size.toLong(),
                half.toLong()
            ) { progress.add(it) }

            assertEquals("续传进度末值应等于完整文件大小", payload.size.toLong(), progress.last())
            assertTrue("续传进度应包含起始偏移", progress.all { it >= half.toLong() })
            assertEquals("续传后远端大小应等于完整文件大小", payload.size.toLong(), transfer.size("/upload-resume.bin"))

            val readBack = ByteArrayOutputStream(payload.size)
            transfer.download("/upload-resume.bin", readBack)
            assertArrayEquals("续传上传后回读应字节一致", payload, readBack.toByteArray())
        }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("upload-resume.bin")))
    }

    @Test
    fun uploadResumeRejectsMismatchedOffset() = runBlocking {
        val payload = randomBytes(64_000, 506L)
        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            transfer.upload(ByteArrayInputStream(payload.copyOfRange(0, 10_000)), "/mismatch.bin", 10_000L)
            val error = runCatching {
                transfer.uploadPartial(
                    ByteArrayInputStream(payload),
                    "/mismatch.bin",
                    payload.size.toLong(),
                    20_000L
                )
            }.exceptionOrNull()
            assertTrue("远端大小与续传偏移不一致应抛 IOException，实际：" + error, error is IOException)
            assertTrue(
                "错误信息应说明不一致：" + error?.message,
                error?.message?.contains("不一致") == true
            )
        }
    }

    // ------------------------------------------------------------ 主机密钥指纹

    @Test
    fun pinnedHostKeyMatchingConnectsAndWrongIsRejected() = runBlocking {
        Files.write(root.resolve("pinned.txt"), "pinned sftp".toByteArray(StandardCharsets.UTF_8))
        val pin = SshHostKeyProbe.fingerprintSha256(
            port = server.port,
            username = "tester",
            password = "s3cret"
        )
        assertNotNull("应能探测到服务端主机密钥指纹", pin)
        assertTrue("指纹应为合法 SHA-256 十六进制：" + pin, SshHostKeyPinning.isValidFingerprint(pin))

        val sink = ByteArrayOutputStream()
        MinaSftpTransfer(profile(pin = pin)).use { transfer ->
            transfer.connect()
            transfer.download("/pinned.txt", sink)
            assertEquals("应记录到真实主机密钥指纹", pin, transfer.lastSeenHostKeyFingerprint)
        }
        assertEquals("pinned sftp", String(sink.toByteArray(), StandardCharsets.UTF_8))

        val realPin = pin!!
        val wrongPin = realPin.dropLast(1) + if (realPin.last() == 'a') "b" else "a"
        val error = runCatching {
            MinaSftpTransfer(profile(pin = wrongPin)).use { transfer -> transfer.connect() }
        }.exceptionOrNull()
        assertTrue("错误指纹必须拒绝连接，实际：" + error, error is ConnectionException)
        val message = error?.message.orEmpty()
        assertTrue(
            "错误信息应说明主机密钥/指纹不匹配：" + message,
            message.contains("指纹") || message.contains("主机密钥")
        )
    }

    // ------------------------------------------------------------ 配置与错误映射

    @Test
    fun packetAndWindowSizesAreAppliedBeforeSession() = runBlocking {
        assertEquals("默认 SFTP 包大小应为 64 KiB", 64 * 1024, MinaSftpTransfer.DEFAULT_PACKET_SIZE)
        assertEquals("默认 SSH 窗口应为 4 MiB", 4L * 1024L * 1024L, MinaSftpTransfer.DEFAULT_WINDOW_SIZE)

        val probe = randomBytes(4_096, 77L)
        Files.write(root.resolve("probe.bin"), probe)
        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            assertEquals("默认包大小字段", 64 * 1024, transfer.packetSize)
            assertEquals("默认窗口字段", 4L * 1024L * 1024L, transfer.windowSize)
            assertEquals("WINDOW_SIZE 应写入 MINA 客户端", 4L * 1024L * 1024L, transfer.resolvedWindowSize())
            assertEquals("MAX_PACKET_SIZE 应随 packetSize 设置", 64L * 1024L, transfer.resolvedMaxPacketSize())
            assertEquals("COPY_BUF_SIZE 应取 packetSize", 64 * 1024, transfer.resolvedCopyBufferSize())
            assertEquals("WRITE_CHUNK_SIZE 应取 packetSize", 64 * 1024, transfer.resolvedWriteChunkSize())

            val sink = ByteArrayOutputStream()
            transfer.download("/probe.bin", sink)
            assertArrayEquals("按默认配置传输内容应字节一致", probe, sink.toByteArray())
        }

        MinaSftpTransfer(profile(), packetSize = 32 * 1024, windowSize = 8L * 1024L * 1024L).use { transfer ->
            transfer.connect()
            assertEquals("自定义 WINDOW_SIZE 应生效", 8L * 1024L * 1024L, transfer.resolvedWindowSize())
            assertEquals("自定义 MAX_PACKET_SIZE 应生效", 32L * 1024L, transfer.resolvedMaxPacketSize())
            assertEquals("自定义 COPY_BUF_SIZE 应生效", 32 * 1024, transfer.resolvedCopyBufferSize())
        }
    }

    @Test
    fun invalidTuningArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MinaSftpTransfer(profile(), packetSize = 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinaSftpTransfer(profile(), packetSize = 512 * 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinaSftpTransfer(profile(), windowSize = 1024L)
        }
    }

    @Test
    fun wrongPasswordIsMappedToAuthenticationException() = runBlocking {
        val error = runCatching {
            MinaSftpTransfer(profile(password = "wrong-password")).use { transfer -> transfer.connect() }
        }.exceptionOrNull()
        assertTrue("错误密码应映射为 AuthenticationException，实际：" + error, error is AuthenticationException)
    }

    @Test
    fun unreachablePortIsMappedToConnectionException() = runBlocking {
        val error = runCatching {
            MinaSftpTransfer(profile(port = freeTcpPort())).use { transfer -> transfer.connect() }
        }.exceptionOrNull()
        assertTrue("不可达端口应映射为 ConnectionException，实际：" + error, error is ConnectionException)
    }

    @Test
    fun missingFileMapsToRemotePathExceptionAndSizeHandlesMissing() = runBlocking {
        Files.write(root.resolve("three.bin"), byteArrayOf(1, 2, 3))
        MinaSftpTransfer(profile()).use { transfer ->
            transfer.connect()
            val error = runCatching {
                transfer.download("/missing.bin", ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue("不存在文件应映射为 RemotePathException，实际：" + error, error is RemotePathException)
            assertEquals(3L, transfer.size("/three.bin"))
            assertEquals("不存在文件的大小应为 -1", -1L, transfer.size("/missing.bin"))
        }
    }

    @Test
    fun closeIsIdempotentAndOperationsAfterCloseAreCancelled() = runBlocking {
        val transfer = MinaSftpTransfer(profile())
        transfer.connect()
        assertTrue(transfer.isConnected)
        transfer.close()
        transfer.close()
        assertFalse("关闭后不应再报告已连接", transfer.isConnected)

        val downloadError = runCatching {
            transfer.download("/anything.bin", ByteArrayOutputStream())
        }.exceptionOrNull()
        assertTrue("关闭后下载应抛 TransferCancelledException，实际：" + downloadError, downloadError is TransferCancelledException)

        val connectError = runCatching { transfer.connect() }.exceptionOrNull()
        assertTrue("关闭后重连应抛 TransferCancelledException，实际：" + connectError, connectError is TransferCancelledException)
    }

    // ------------------------------------------------------------ 8 MiB 下载计时对比

    @Test
    fun benchmark8MiBDownloadMinaVsJsch() = runBlocking {
        val payload = randomBytes(8 * 1024 * 1024, 909L)
        Files.write(root.resolve("bench.bin"), payload)

        // 预热：排除首次类加载与 JIT 抖动
        SshRemoteFileSystem(profile()).use { jsch ->
            jsch.connect()
            jsch.download("/bench.bin", ByteArrayOutputStream(payload.size))
        }
        MinaSftpTransfer(profile()).use { mina ->
            mina.connect()
            mina.download("/bench.bin", ByteArrayOutputStream(payload.size))
        }

        val jschSink = ByteArrayOutputStream(payload.size)
        val jschStarted = System.nanoTime()
        SshRemoteFileSystem(profile()).use { jsch ->
            jsch.connect()
            jsch.download("/bench.bin", jschSink)
        }
        val jschMillis = (System.nanoTime() - jschStarted) / 1_000_000L
        assertEquals("JSch 基准下载内容应字节一致", sha256Hex(payload), sha256Hex(jschSink.toByteArray()))

        val minaSink = ByteArrayOutputStream(payload.size)
        val minaStarted = System.nanoTime()
        MinaSftpTransfer(profile()).use { mina ->
            mina.connect()
            mina.download("/bench.bin", minaSink)
        }
        val minaMillis = (System.nanoTime() - minaStarted) / 1_000_000L
        assertEquals("MINA 下载内容应字节一致", sha256Hex(payload), sha256Hex(minaSink.toByteArray()))

        val speedup = if (minaMillis > 0L) jschMillis.toDouble() / minaMillis.toDouble() else 0.0
        println(
            "[benchmark] 8 MiB SFTP 下载（含连接握手）：JSch=" + jschMillis + "ms, MINA=" + minaMillis +
                "ms, JSch/MINA=" + String.format(Locale.US, "%.2f", speedup) + "x" +
                "（packetSize=" + MinaSftpTransfer.DEFAULT_PACKET_SIZE +
                ", windowSize=" + MinaSftpTransfer.DEFAULT_WINDOW_SIZE + "）"
        )

        // 宽松断言：仅防止 MINA 引擎出现数量级回退，避免 CI 计时抖动误报
        assertTrue(
            "MINA 不应比 JSch 慢 3 倍以上（JSch=" + jschMillis + "ms, MINA=" + minaMillis + "ms）",
            minaMillis <= jschMillis * 3L + 2_000L
        )
    }

    // ------------------------------------------------------------ 辅助

    private suspend fun assertDownloadMatches(
        transfer: MinaSftpTransfer,
        remotePath: String,
        expected: ByteArray
    ) {
        val progress = ArrayList<Long>()
        val sink = ByteArrayOutputStream(expected.size)
        transfer.download(remotePath, sink) { progress.add(it) }
        val actual = sink.toByteArray()
        assertEquals("文件大小应一致", expected.size, actual.size)
        assertEquals("下载内容 SHA-256 应一致（" + remotePath + "）", sha256Hex(expected), sha256Hex(actual))
        assertTrue("应产生进度回调", progress.isNotEmpty())
        assertEquals("进度末值应等于文件大小", expected.size.toLong(), progress.last())
        assertTrue("进度应单调递增", progress.zipWithNext().all { (a, b) -> b >= a })
    }

    private fun profile(
        port: Int = server.port,
        password: String = "s3cret",
        pin: String? = null
    ): ServerProfile = ServerProfile(
        name = "mina-sftp-test",
        protocol = TransferProtocol.SFTP,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = password,
        pinnedSshHostKeySha256 = pin
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }
}

package com.manst.lantransfer.protocol

import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpProgressMonitor
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale

/**
 * SFTP/SCP 传输吞吐调优测试：
 * 1. 自有拷贝缓冲区常量为 128 KiB，落在建议区间内且是 32 KiB 的整数倍；
 * 2. 分块上限纯函数同时受剩余字节数与缓冲区大小约束；
 * 3. 续传进度语义：下载 RESUME 的 skip 只计一次，上传 RESUME 由 initialOffset 补正；
 * 4. SCP 上传真实走线时按新缓冲区粒度读取，内容与进度不变。
 */
class SshTransferTuningTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lantransfer-ssh-buffer-")
    }

    @After
    fun tearDown() {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ------------------------------------------------------------ 缓冲区常量

    @Test
    fun transferBufferIs128KiB() {
        // 128 vs 256 KiB 的真实 SCP 下载对照见 sshTransferBuffer128Vs256KiBEndToEndBenchmark：
        // 三次采样方向不稳定（+10%/+18%/-31%），没有稳定收益，故保留 128 KiB。
        assertEquals("SFTP/SCP 自有拷贝缓冲区应为 128 KiB", 128 * 1024, SSH_TRANSFER_BUFFER_SIZE)
        assertTrue(
            "缓冲区应落在 128 KiB~256 KiB 的建议区间",
            SSH_TRANSFER_BUFFER_SIZE in (128 * 1024)..(256 * 1024)
        )
    }

    // ------------------------------------------------------------ 上传调优常量

    @Test
    fun scpUploadChunkIs256To512KiB() {
        assertEquals("SCP 上传读块应为 256 KiB", 256 * 1024, SCP_UPLOAD_CHUNK_SIZE)
        assertTrue(
            "SCP 上传读块应落在 256 KiB~512 KiB 的建议区间",
            SCP_UPLOAD_CHUNK_SIZE in (256 * 1024)..(512 * 1024)
        )
        assertEquals(
            "上传读块应为 32 KiB 的整数倍，便于按页搬运",
            0,
            SCP_UPLOAD_CHUNK_SIZE % (32 * 1024)
        )
        assertTrue(
            "上传读块应明显大于旧的 32 KiB 协议块",
            SCP_UPLOAD_CHUNK_SIZE >= 8 * 32 * 1024
        )
    }

    @Test
    fun scpUploadStreamBufferIsAtLeast128KiBAndLargerThanChunk() {
        assertTrue(
            "带缓冲输出流应不小于 128 KiB",
            SCP_UPLOAD_STREAM_BUFFER_SIZE >= 128 * 1024
        )
        assertTrue(
            "输出缓冲必须大于读块，否则 BufferedOutputStream 会跳过缓冲直接透传到底层通道",
            SCP_UPLOAD_STREAM_BUFFER_SIZE > SCP_UPLOAD_CHUNK_SIZE
        )
        assertEquals(
            "输出缓冲应为 32 KiB 的整数倍",
            0,
            SCP_UPLOAD_STREAM_BUFFER_SIZE % (32 * 1024)
        )
    }

    @Test
    fun sftpUploadBufferIs128KiB() {
        assertEquals("SFTP 上传源流缓冲应为 128 KiB", 128 * 1024, SFTP_UPLOAD_BUFFER_SIZE)
        assertTrue("SFTP 上传源流缓冲不应小于 128 KiB", SFTP_UPLOAD_BUFFER_SIZE >= 128 * 1024)
    }

    @Test
    fun transferBufferIsPageFriendlyAndCoversGlobalCopyBuffer() {
        // DefaultTransferManager.COPY_BUFFER_SIZE 为 64 KiB；SSH 侧不应小于全局拷贝缓冲
        assertTrue("SSH 缓冲区不应小于全局拷贝缓冲 64 KiB", SSH_TRANSFER_BUFFER_SIZE >= 64 * 1024)
        assertEquals(
            "缓冲区应为 32 KiB 的整数倍，便于按页搬运",
            0,
            SSH_TRANSFER_BUFFER_SIZE % (32 * 1024)
        )
    }

    // ------------------------------------------------------------ 分块上限

    @Test
    fun chunkLimitClampsToRemainingBytes() {
        assertEquals(1_000, sshChunkLimit(1_000L))
        assertEquals(1, sshChunkLimit(1L))
        assertEquals(0, sshChunkLimit(0L))
        assertEquals("剩余字节数为负时不应返回负数", 0, sshChunkLimit(-5L))
    }

    @Test
    fun chunkLimitClampsToBufferSize() {
        assertEquals(SSH_TRANSFER_BUFFER_SIZE, sshChunkLimit(Long.MAX_VALUE))
        assertEquals(SSH_TRANSFER_BUFFER_SIZE, sshChunkLimit(SSH_TRANSFER_BUFFER_SIZE.toLong()))
        assertEquals(
            "剩余字节数略大于缓冲区时应整块返回",
            SSH_TRANSFER_BUFFER_SIZE + 1,
            sshChunkLimit(SSH_TRANSFER_BUFFER_SIZE + 1L, SSH_TRANSFER_BUFFER_SIZE + 1)
        )
    }

    @Test
    fun chunkLimitRejectsNonPositiveBufferSize() {
        assertThrows(IllegalArgumentException::class.java) { sshChunkLimit(10L, 0) }
        assertThrows(IllegalArgumentException::class.java) { sshChunkLimit(10L, -1) }
    }

    // ------------------------------------------------------------ 续传进度语义

    @Test
    fun progressStartsAtInitialOffsetAndAccumulates() {
        val seen = ArrayList<Long>()
        val monitor = SftpResumeProgressMonitor({ seen.add(it) }, initialOffset = 10_000L)

        assertEquals("未传输前应等于初始偏移", 10_000L, monitor.totalBytes)
        assertTrue(monitor.count(4_096L))
        assertTrue(monitor.count(4_096L))

        assertEquals(listOf(14_096L, 18_192L), seen)
        assertEquals(18_192L, monitor.totalBytes)
    }

    @Test
    fun progressInitRebasesTotalToInitialOffset() {
        val seen = ArrayList<Long>()
        val monitor = SftpResumeProgressMonitor({ seen.add(it) }, initialOffset = 1_000L)

        monitor.count(500L)
        monitor.init(SftpProgressMonitor.PUT, "-", "/remote.bin", SftpProgressMonitor.UNKNOWN_SIZE)
        assertEquals("init 应把累计值重置到初始偏移，避免重复计数", 1_000L, monitor.totalBytes)

        monitor.count(250L)
        assertEquals(1_250L, monitor.totalBytes)
        assertEquals(listOf(1_500L, 1_250L), seen)
    }

    @Test
    fun downloadResumeSkipIsCountedExactlyOnce() {
        // JSch 的 get(..., RESUME, skip) 会先回调 count(skip) 再按实际读取回调，initialOffset 必须为 0
        val seen = ArrayList<Long>()
        val monitor = SftpResumeProgressMonitor({ seen.add(it) })
        monitor.init(SftpProgressMonitor.GET, "/remote.bin", "??", 140_000L)

        monitor.count(100_000L) // 续传偏移（skip）
        monitor.count(32_755L) // 第一个 SSH_FXP_READ 数据块
        monitor.count(7_245L)

        assertEquals("进度末值应等于远端文件总长度且偏移不重复计数", 140_000L, monitor.totalBytes)
        assertEquals(140_000L, seen.last())
    }

    @Test
    fun progressNeverAsksToStopAndEndEmitsNothing() {
        val seen = ArrayList<Long>()
        val monitor = SftpResumeProgressMonitor({ seen.add(it) })

        assertTrue("进度回调应始终返回 true（取消由连接关闭触发）", monitor.count(1L))
        monitor.end()

        assertEquals(listOf(1L), seen)
        assertEquals(1L, monitor.totalBytes)
    }

    // ------------------------------------------------------------ SCP 真实走线

    @Test
    fun scpUploadReadsInTunedChunksAndStaysByteIdentical() = runBlocking {
        val payload = randomBytes(700_000, 1_301L)
        val recording = RecordingInputStream(payload)
        val server = TestSshServer(root)
        try {
            val progress = ArrayList<Long>()
            SshRemoteFileSystem(profile(server.port)).use { client ->
                client.connect()
                client.upload(recording, "/buffer-probe.bin", payload.size.toLong()) { progress.add(it) }
            }
            assertArrayEquals(
                "调大缓冲区后 SCP 上传内容应字节一致",
                payload,
                Files.readAllBytes(root.resolve("buffer-probe.bin"))
            )
            assertEquals("进度末值应等于文件大小", payload.size.toLong(), progress.last())
            assertEquals(
                "SCP 上传应按 256 KiB 读块读取源流",
                SCP_UPLOAD_CHUNK_SIZE,
                recording.maxRequestedLength
            )
            assertTrue("单次读取应明显大于旧的 32 KiB 缓冲区", recording.maxRequestedLength > 32 * 1024)
            // 数据阶段不逐块 flush/等 ACK：进度回调次数应恰好等于读块数（700_000 / 256 KiB = 3 块）
            assertEquals(
                "进度回调次数应等于读块数，数据阶段不应产生额外同步点",
                (payload.size + SCP_UPLOAD_CHUNK_SIZE - 1) / SCP_UPLOAD_CHUNK_SIZE,
                progress.size
            )
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------ 128 KiB vs 256 KiB 端到端对照

    /**
     * SSH 自有拷贝缓冲 128 KiB vs 256 KiB 的真实端到端对照（SCP 下载，8 MiB）。
     *
     * 该常量只作用于本类自有拷贝循环（SCP 线协议接收、ls 输出读取、未知大小上传落盘）：
     * SFTP get/put 的线上分片由 JSch 硬编码 32 KiB 决定，SCP 上传读块由 SCP_UPLOAD_CHUNK_SIZE 决定，
     * 因此这里用 SCP 下载测量该常量的真实影响。不写死绝对耗时，仅打印结果与相对比值。
     */
    @Test
    fun sshTransferBuffer128Vs256KiBEndToEndBenchmark() = runBlocking {
        val payload = randomBytes(BENCHMARK_PAYLOAD_SIZE, 1_302L)
        Files.write(root.resolve(BENCHMARK_DOWNLOAD_FILE.removePrefix("/")), payload)
        val server = TestSshServer(root)
        try {
            val samples = LinkedHashMap<Int, MutableList<Double>>()
            // 正反两遍顺序，抵消「后测的候选继承 JIT/页缓存热」的顺序偏差；每个候选取两遍中的最快值。
            for (pass in 0 until BENCHMARK_PASSES) {
                val sizes = if (pass == 0) listOf(128 * 1024, 256 * 1024) else listOf(256 * 1024, 128 * 1024)
                for (bufferSize in sizes) {
                    SshRemoteFileSystem(profile(server.port), bufferSize).use { client ->
                        client.connect()
                        // 预热（不计时）
                        client.download(BENCHMARK_DOWNLOAD_FILE, OutputStream.nullOutputStream())
                        if (pass == 0) {
                            // 第一遍用真实 sink 校验内容；计时轮用 null sink，避免内存拷贝干扰
                            val verifiedSink = ByteArrayOutputStream()
                            client.download(BENCHMARK_DOWNLOAD_FILE, verifiedSink)
                            assertArrayEquals(
                                "SCP 下载内容应与源文件一致（" + bufferSize / 1024 + " KiB）",
                                payload,
                                verifiedSink.toByteArray()
                            )
                        }
                        var best = Double.MAX_VALUE
                        repeat(BENCHMARK_ROUNDS) {
                            val millis = measureMillis {
                                client.download(BENCHMARK_DOWNLOAD_FILE, OutputStream.nullOutputStream())
                            }
                            best = minOf(best, millis)
                        }
                        samples.getOrPut(bufferSize) { ArrayList() }.add(best)
                    }
                }
            }
            for (bufferSize in listOf(128 * 1024, 256 * 1024)) {
                val best = samples.getValue(bufferSize).min()
                println(
                    "[SSH 缓冲对照] SCP 下载 " + (bufferSize / 1024) + " KiB: " +
                        formatMillis(best) + " (" + formatThroughput(payload.size, best) + " MB/s)"
                )
            }
            val base = samples.getValue(128 * 1024).min()
            val large = samples.getValue(256 * 1024).min()
            println(
                "[SSH 缓冲对照] 256 KiB / 128 KiB 吞吐比=" + formatRatio(base, large) +
                    "（>1 表示 256 KiB 更快；回环测试噪声约 ±5%）"
            )
            println(
                "[SSH 缓冲对照] 最终保留 SSH_TRANSFER_BUFFER_SIZE=" +
                    (SSH_TRANSFER_BUFFER_SIZE / 1024) + " KiB（依据见常量注释与 docs/ARCHITECTURE.md）"
            )
            assertTrue("SSH 缓冲对照每档应有正反两遍样本", samples.values.all { it.size == BENCHMARK_PASSES })
            assertTrue("SSH 缓冲对照耗时应为正数", samples.values.flatten().all { it > 0.0 })
        } finally {
            server.close()
        }
    }

    /**
     * SSH TCP_NODELAY 结论验证：JSch 0.2.26 的 Session.connect() 在拿到 socket 后无条件调用
     * socket.setTcpNoDelay(true)（Session.java:242），默认路径如此，自定义 SocketFactory 路径同样如此。
     * 这里用只记录 socket、不自行设置 TCP_NODELAY 的公开 SocketFactory 验证 JSch 确实开启了它，
     * 说明 SSH 侧无需应用层代码、也不需要反射。
     */
    @Test
    fun jschTransportSocketEnablesTcpNoDelayByDefault() {
        val server = TestSshServer(root)
        try {
            val jsch = JSch()
            val session = jsch.getSession("tester", "127.0.0.1", server.port)
            session.setPassword("s3cret")
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password")
            session.setTimeout(10_000)
            val factory = CapturingJschSocketFactory()
            session.setSocketFactory(factory)
            session.connect(10_000)
            try {
                assertEquals("握手应只创建一条传输 socket", 1, factory.sockets.size)
                val socket = factory.sockets.single()
                assertTrue(
                    "JSch 应在建连时开启 TCP_NODELAY（Session.java:242），实际：" + socket.tcpNoDelay,
                    socket.tcpNoDelay
                )
            } finally {
                session.disconnect()
            }
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------ 辅助


    private fun profile(port: Int): ServerProfile = ServerProfile(
        name = "ssh-buffer-test",
        protocol = TransferProtocol.SCP,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = "s3cret"
    )

    /** 记录上层请求的最大读取长度，用于验证 SCP 上传确实使用了调优后的缓冲区。 */
    private class RecordingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var maxRequestedLength = 0
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len > maxRequestedLength) maxRequestedLength = len
            return super.read(b, off, len)
        }
    }

    private suspend fun measureMillis(block: suspend () -> Unit): Double {
        val startedAt = System.nanoTime()
        block()
        return (System.nanoTime() - startedAt) / 1_000_000.0
    }

    private fun formatMillis(millis: Double): String = String.format(Locale.ROOT, "%.1f ms", millis)

    private fun formatThroughput(bytes: Int, millis: Double): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return String.format(Locale.ROOT, "%.1f", megabytes / (millis / 1000.0))
    }

    /** 基准耗时 / 候选耗时：>1 表示候选更快（吞吐更高）。 */
    private fun formatRatio(baseMillis: Double, candidateMillis: Double): String {
        if (candidateMillis <= 0.0) return "n/a"
        return String.format(Locale.ROOT, "%.2f×", baseMillis / candidateMillis)
    }

    /** 只记录 JSch 创建的传输 socket，不修改 TCP_NODELAY；用于验证库自身已开启该选项。 */
    private class CapturingJschSocketFactory : com.jcraft.jsch.SocketFactory {

        val sockets: MutableList<Socket> = ArrayList()

        override fun createSocket(host: String, port: Int): Socket =
            Socket(host, port).also { sockets.add(it) }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    private companion object {
        const val BENCHMARK_PAYLOAD_SIZE = 8 * 1024 * 1024
        const val BENCHMARK_ROUNDS = 2
        const val BENCHMARK_PASSES = 2
        const val BENCHMARK_DOWNLOAD_FILE = "/scp-benchmark.bin"
    }
}

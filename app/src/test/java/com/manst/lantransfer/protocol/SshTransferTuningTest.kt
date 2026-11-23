package com.manst.lantransfer.protocol

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

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
        assertEquals("SFTP/SCP 拷贝缓冲区应由 32 KiB 提升到 128 KiB", 128 * 1024, SSH_TRANSFER_BUFFER_SIZE)
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
}

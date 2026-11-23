package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.ConnectionException
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

/**
 * SFTP 单文件多通道并行下载测试。
 *
 * 覆盖：
 * 1. 纯逻辑：分块计划（chunkSize/N 归一化、小文件单块、非法参数）、
 *    有序随机写（乱序完成、完成状态、进度单调、取消、关闭后拒绝写入）；
 * 2. 真实协议走线：复用 [TestSshServer]（Apache MINA SSHD 的 SFTP 子系统）启动本机 SFTP 服务端，
 *    放一个 >= 8 MiB 的随机文件，用 [SftpMultichannel] 分别以 N=1 与 N=3 下载，
 *    断言下载文件 SHA-256 与源文件一致、分块全部完成、通道数符合预期、无协议错误；
 * 3. 安全：复用 [SshHostKeyPinning.PinnedRepository] 的主机密钥指纹固定（正确指纹通过、错误指纹拒绝）。
 */
class SftpMultichannelTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private lateinit var root: Path
    private lateinit var server: TestSshServer

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lantransfer-sftp-multichannel-")
        server = TestSshServer(root, enableSftp = true)
    }

    @After
    fun tearDown() {
        server.close()
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ------------------------------------------------------------ 分块计划（纯逻辑）

    @Test
    fun plansSingleChunkForEmptyAndSmallFiles() {
        assertTrue("空文件不应产生分块", SftpChunkPlanner.plan(0L).isEmpty())
        assertEquals(
            "1 MiB 小文件应走单通道整文件分块",
            listOf(SftpChunk(index = 0, offset = 0L, length = 1L * 1024L * 1024L)),
            SftpChunkPlanner.plan(1L * 1024L * 1024L)
        )
        assertEquals(
            "恰好小于 2 MiB 阈值时仍应单通道",
            listOf(SftpChunk(0, 0L, SftpChunkPlanner.MIN_MULTICHANNEL_SIZE - 1L)),
            SftpChunkPlanner.plan(SftpChunkPlanner.MIN_MULTICHANNEL_SIZE - 1L)
        )
        assertEquals(
            "小于一个默认分块（4 MiB）时应单通道",
            listOf(SftpChunk(0, 0L, 3L * 1024L * 1024L)),
            SftpChunkPlanner.plan(3L * 1024L * 1024L)
        )
    }

    @Test
    fun plansChunksByChunkSize() {
        val fileSize = 9L * 1024L * 1024L
        val chunks = SftpChunkPlanner.plan(fileSize, SftpChunkPlanner.DEFAULT_CHUNK_SIZE, 3)
        assertEquals("9 MiB / 4 MiB 应切成 3 段", 3, chunks.size)
        assertEquals(
            listOf(
                SftpChunk(0, 0L, 4L * 1024L * 1024L),
                SftpChunk(1, 4L * 1024L * 1024L, 4L * 1024L * 1024L),
                SftpChunk(2, 8L * 1024L * 1024L, 1L * 1024L * 1024L)
            ),
            chunks
        )
        assertEquals("分块序号应连续", listOf(0, 1, 2), chunks.map { it.index })
        assertEquals("分块应无缝覆盖整个文件", fileSize, chunks.sumOf { it.length })
        assertEquals(fileSize, chunks.last().endExclusive)
        assertTrue("首段从 0 开始", chunks.first().offset == 0L)
    }

    @Test
    fun plansSingleChunkWhenChannelsIsOne() {
        val fileSize = 9L * 1024L * 1024L
        assertEquals(
            "显式 N=1 时应整文件单通道",
            listOf(SftpChunk(0, 0L, fileSize)),
            SftpChunkPlanner.plan(fileSize, SftpChunkPlanner.DEFAULT_CHUNK_SIZE, 1)
        )
    }

    @Test
    fun effectiveChannelsClampsToSupportedRange() {
        assertEquals(1, SftpChunkPlanner.effectiveChannels(0))
        assertEquals(1, SftpChunkPlanner.effectiveChannels(1))
        assertEquals(2, SftpChunkPlanner.effectiveChannels(2))
        assertEquals(3, SftpChunkPlanner.effectiveChannels(3))
        assertEquals(4, SftpChunkPlanner.effectiveChannels(4))
        assertEquals("超过 4 的请求应被限制为 4", 4, SftpChunkPlanner.effectiveChannels(9))
    }

    @Test
    fun planRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) { SftpChunkPlanner.plan(-1L) }
        assertThrows(IllegalArgumentException::class.java) { SftpChunkPlanner.plan(1024L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { SftpChunkPlanner.plan(1024L, -4L) }
        assertThrows(IllegalArgumentException::class.java) { SftpChunkPlanner.plan(1024L, 512L, 0) }
    }

    // ------------------------------------------------------------ 有序随机写（纯逻辑）

    @Test
    fun orderedWriterWritesChunksOutOfOrderWithoutCorruption() {
        val payload = randomBytes(200_003, 701L)
        val target = File(root.toFile(), "ordered.bin")
        val ranges = listOf(
            SftpChunk(0, 0L, 50_000L),
            SftpChunk(1, 50_000L, 50_001L),
            SftpChunk(2, 100_001L, 50_000L),
            SftpChunk(3, 150_001L, 50_002L)
        )
        assertEquals(payload.size.toLong(), ranges.sumOf { it.length })
        val progress = ArrayList<Long>()

        OrderedRangeWriter(target, payload.size.toLong(), ranges.size).use { writer ->
            writer.onProgress = { progress.add(it) }
            assertFalse("写入前不应有完成段", writer.allChunksComplete())
            // 乱序写入：2、0、3、1
            for (chunk in listOf(ranges[2], ranges[0], ranges[3], ranges[1])) {
                writer.writeAt(chunk.offset, payload, chunk.offset.toInt(), chunk.length.toInt())
                writer.markChunkComplete(chunk.index)
                assertTrue("段 " + chunk.index + " 应标记完成", writer.isChunkComplete(chunk.index))
            }
            assertTrue(writer.allChunksComplete())
            assertEquals(4, writer.completedChunkCount())
            assertEquals(payload.size.toLong(), writer.progress)
        }

        assertArrayEquals(
            "乱序并发写入后本地文件应与源数据字节一致",
            payload,
            Files.readAllBytes(target.toPath())
        )
        assertEquals(
            "进度回调数值应随写入量单调递增",
            listOf(50_000L, 100_000L, 150_002L, 200_003L),
            progress
        )
    }

    @Test
    fun orderedWriterPreallocatesTargetSize() {
        val target = File(root.toFile(), "preallocated.bin")
        OrderedRangeWriter(target, 12_345L, 2).use { writer ->
            assertEquals("目标文件应预置为远端大小", 12_345L, target.length())
            writer.markChunkComplete(0)
            assertFalse("只完成一段时不应判定全部完成", writer.allChunksComplete())
            assertEquals(1, writer.completedChunkCount())
        }
    }

    @Test
    fun orderedWriterCancelRejectsFurtherWrites() {
        val target = File(root.toFile(), "cancelled.bin")
        val writer = OrderedRangeWriter(target, 16L, 1)
        try {
            writer.writeAt(0L, ByteArray(8) { 7 }, 0, 8)
            assertEquals(8L, writer.progress)
            writer.cancel()
            assertTrue(writer.isCancelled)
            val error = assertThrows(TransferCancelledException::class.java) {
                writer.writeAt(8L, ByteArray(8) { 9 }, 0, 8)
            }
            assertEquals("传输已取消", error.message)
            assertEquals("取消后不应再增加已写字节", 8L, writer.progress)
        } finally {
            writer.close()
        }
    }

    @Test
    fun orderedWriterRejectsWritesAfterClose() {
        val target = File(root.toFile(), "closed.bin")
        val writer = OrderedRangeWriter(target, 8L, 1)
        writer.close()
        assertThrows(IOException::class.java) {
            writer.writeAt(0L, ByteArray(4), 0, 4)
        }
        writer.close()
    }

    // ------------------------------------------------------------ 真实 SFTP 走线

    @Test
    fun downloadsLargeFileWithThreeChannelsAndMatchingSha256() = runBlocking {
        val payload = writeRemoteFile("nine-mib.bin", 9 * 1024 * 1024, 9_001L)
        val target = File(root.toFile(), "downloaded-n3.bin")
        val progress = ArrayList<Long>()

        val result = SftpMultichannel(profile(), chunkSize = SftpChunkPlanner.DEFAULT_CHUNK_SIZE, channels = 3)
            .use { client ->
                client.download("/nine-mib.bin", target) { progress.add(it) }
            }

        assertEquals("远端文件大小应通过 SFTP stat 获取", payload.size.toLong(), result.fileSize)
        assertEquals("9 MiB / 4 MiB 应生成 3 个分块", 3, result.chunkCount)
        assertEquals("N=3 应实际使用 3 条 SFTP 通道", 3, result.usedChannels)
        assertTrue("耗时应为非负数", result.elapsedMillis >= 0L)
        assertEquals("本地文件长度应等于远端大小", payload.size.toLong(), target.length())
        assertArrayEquals("多通道下载内容应与源文件字节一致", payload, Files.readAllBytes(target.toPath()))
        assertEquals("多通道下载 SHA-256 应与源文件一致", sha256(payload), sha256File(target))
        assertEquals("进度末值应等于文件大小", payload.size.toLong(), progress.last())
        assertTrue(
            "进度应单调递增（并发写入由写锁串行回调）",
            progress.zipWithNext().all { (a, b) -> b >= a }
        )
    }

    @Test
    fun downloadsLargeFileWithSingleChannelAndMatchingSha256() = runBlocking {
        val payload = writeRemoteFile("eight-mib.bin", 8 * 1024 * 1024, 8_002L)
        val target = File(root.toFile(), "downloaded-n1.bin")
        val progress = ArrayList<Long>()

        val client = SftpMultichannel(profile(), channels = 1)
        try {
            val result = client.download("/eight-mib.bin", target) { progress.add(it) }
            assertEquals("N=1 时整文件作为单分块", 1, result.chunkCount)
            assertEquals("N=1 应只使用 1 条通道", 1, result.usedChannels)
            assertEquals(payload.size.toLong(), result.fileSize)
            assertArrayEquals("单通道下载内容应与源文件字节一致", payload, Files.readAllBytes(target.toPath()))
            assertEquals("单通道下载 SHA-256 应与源文件一致", sha256(payload), sha256File(target))
            assertEquals("进度末值应等于文件大小", payload.size.toLong(), progress.last())

            // 同一实例重复下载：验证 Session/Channel 被彻底关闭、没有状态泄漏
            val secondTarget = File(root.toFile(), "downloaded-n1-again.bin")
            val secondResult = client.download("/eight-mib.bin", secondTarget)
            assertEquals(payload.size.toLong(), secondResult.fileSize)
            assertEquals(
                "同一实例第二次下载 SHA-256 仍应一致（无通道/会话泄漏）",
                sha256(payload),
                sha256File(secondTarget)
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun downloadsEmptyRemoteFile() = runBlocking {
        Files.write(root.resolve("empty.bin"), ByteArray(0))
        val target = File(root.toFile(), "empty-downloaded.bin")
        val result = SftpMultichannel(profile(), channels = 3).use { client ->
            client.download("/empty.bin", target)
        }
        assertEquals(0L, result.fileSize)
        assertEquals(0, result.chunkCount)
        assertEquals(0, result.usedChannels)
        assertTrue("空文件也应创建本地目标文件", target.exists())
        assertEquals(0L, target.length())
    }

    @Test
    fun downloadMissingRemoteFileThrowsRemotePathException() = runBlocking {
        val target = File(root.toFile(), "missing-downloaded.bin")
        val error = SftpMultichannel(profile(), channels = 3).use { client ->
            runCatching { client.download("/missing.bin", target) }.exceptionOrNull()
        }
        assertTrue("期望 RemotePathException，实际：" + error, error is RemotePathException)
    }

    @Test
    fun cancelBeforeDownloadThrowsTransferCancelled() = runBlocking {
        val target = File(root.toFile(), "cancelled-downloaded.bin")
        val client = SftpMultichannel(profile())
        client.cancel()
        val error = runCatching { client.download("/anything.bin", target) }.exceptionOrNull()
        assertTrue("取消后应抛 TransferCancelledException，实际：" + error, error is TransferCancelledException)
        client.close()
    }

    // ------------------------------------------------------------ 主机密钥固定

    @Test
    fun pinnedHostKeyFingerprintIsEnforced() = runBlocking {
        val payload = writeRemoteFile("pinned.bin", 3 * 1024 * 1024, 3_003L)
        val fingerprint = SshHostKeyProbe.fingerprintSha256(
            port = server.port,
            username = "tester",
            password = "s3cret"
        )
        assertTrue("应能探测到测试服务端主机密钥指纹", fingerprint != null && fingerprint.isNotBlank())

        // 正确指纹：下载成功且内容一致
        val target = File(root.toFile(), "pinned-downloaded.bin")
        SftpMultichannel(profile(pinnedSshHostKeySha256 = fingerprint), channels = 2).use { client ->
            client.download("/pinned.bin", target)
        }
        assertEquals(sha256(payload), sha256File(target))

        // 错误指纹：连接阶段被拒绝
        val wrongPin = "0".repeat(64)
        val error = SftpMultichannel(profile(pinnedSshHostKeySha256 = wrongPin), channels = 2).use { client ->
            runCatching { client.download("/pinned.bin", File(root.toFile(), "wrong-pin.bin")) }.exceptionOrNull()
        }
        assertTrue("主机密钥指纹不匹配应拒绝连接，实际：" + error, error is ConnectionException)
    }

    // ------------------------------------------------------------ 辅助

    private fun writeRemoteFile(name: String, size: Int, seed: Long): ByteArray {
        val payload = randomBytes(size, seed)
        Files.write(root.resolve(name), payload)
        return payload
    }

    private fun profile(
        port: Int = server.port,
        password: String = "s3cret",
        pinnedSshHostKeySha256: String? = null
    ): ServerProfile = ServerProfile(
        name = "sftp-multichannel-test",
        protocol = TransferProtocol.SFTP,
        host = "127.0.0.1",
        port = port,
        username = "tester",
        password = password,
        pinnedSshHostKeySha256 = pinnedSshHostKeySha256
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

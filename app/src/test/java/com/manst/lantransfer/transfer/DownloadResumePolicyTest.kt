package com.manst.lantransfer.transfer

import com.manst.lantransfer.transfer.DownloadResumePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载断点续传决策的纯 JVM 单元测试（不依赖 Android，可在 testGithubDebugUnitTest 中执行）。
 *
 * 覆盖「直接写 MediaStore pending 文件」方案里最关键的边界：什么时候可以续写、什么时候
 * 必须重新下载、以及「上次写满但没来得及 commit」时应当直接提交。
 */
class DownloadResumePolicyTest {

    @Test
    fun protocolWithoutResume_alwaysRestarts() {
        assertEquals(Decision.Restart, DownloadResumePolicy.decide(false, 1024L, 2048L))
        assertEquals(Decision.Restart, DownloadResumePolicy.decide(false, 2048L, 2048L))
        assertEquals(Decision.Restart, DownloadResumePolicy.decide(false, null, 2048L))
    }

    @Test
    fun noPendingFile_startsFromZero() {
        assertEquals(Decision.Resume(0L), DownloadResumePolicy.decide(true, null, 2048L))
    }

    @Test
    fun emptyPendingFile_startsFromZero() {
        assertEquals(Decision.Resume(0L), DownloadResumePolicy.decide(true, 0L, 2048L))
        assertEquals(Decision.Resume(0L), DownloadResumePolicy.decide(true, -1L, 2048L))
    }

    @Test
    fun partialPendingFile_resumesFromDiskSize() {
        assertEquals(Decision.Resume(1024L), DownloadResumePolicy.decide(true, 1024L, 2048L))
        assertEquals(Decision.Resume(1L), DownloadResumePolicy.decide(true, 1L, 2L))
    }

    @Test
    fun completePendingFile_commitsWithoutTransfer() {
        assertEquals(Decision.AlreadyComplete, DownloadResumePolicy.decide(true, 2048L, 2048L))
    }

    @Test
    fun pendingLargerThanRemote_restarts() {
        // 远端文件被替换成更小的版本：旧 pending 不能续写，否则会拼出损坏文件。
        assertEquals(Decision.Restart, DownloadResumePolicy.decide(true, 4096L, 2048L))
    }

    @Test
    fun unknownRemoteSize_restartsWhenLocalBytesExist() {
        assertEquals(Decision.Restart, DownloadResumePolicy.decide(true, 1024L, -1L))
        assertEquals(Decision.Restart, DownloadResumePolicy.decide(true, 1024L, 0L))
        // 没有任何本地字节时仍可正常从头下载。
        assertEquals(Decision.Resume(0L), DownloadResumePolicy.decide(true, null, -1L))
    }

    @Test
    fun decisionsAreValueEqual() {
        assertTrue(DownloadResumePolicy.decide(true, 10L, 20L) == Decision.Resume(10L))
    }
}

/**
 * 下载「预分配 + 续传定位」决策的纯 JVM 单元测试（不依赖 Android）。
 *
 * 核心风险：下载前按远端大小 ftruncate 预分配后，pending 文件的磁盘大小立即等于远端大小，
 * 不能把预分配出来的文件大小当成「已下载完成」；真实已写偏移取
 * min(磁盘大小, 任务快照记录的连续已写字节)。
 */
class DownloadPreallocationPolicyTest {

    @Test
    fun newFileWithKnownRemoteSize_preallocatesWholeFileFromZero() {
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 4096L,
            recordedBytes = 0L,
            pendingDiskBytes = null,
            protocolSupportsResume = true
        )
        assertEquals(
            DownloadPreallocationPolicy.Plan(
                reusePending = false,
                startOffset = 0L,
                preallocateBytes = 4096L,
                needsTransfer = true
            ),
            plan
        )
    }

    @Test
    fun newFileWithUnknownRemoteSize_doesNotPreallocate() {
        // 远端大小未知时无法确定要预分配多少，也不该把已有字节当成同一份内容续写。
        assertEquals(
            DownloadPreallocationPolicy.Plan(
                reusePending = false,
                startOffset = 0L,
                preallocateBytes = 0L,
                needsTransfer = true
            ),
            DownloadPreallocationPolicy.plan(
                remoteBytes = -1L,
                recordedBytes = 0L,
                pendingDiskBytes = null,
                protocolSupportsResume = true
            )
        )
    }

    @Test
    fun pendingFileWithoutPreallocation_resumesFromDiskSize() {
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 2048L,
            recordedBytes = 1024L,
            pendingDiskBytes = 1024L,
            protocolSupportsResume = true
        )
        assertEquals(true, plan.reusePending)
        assertEquals(1024L, plan.startOffset)
        assertEquals(0L, plan.preallocateBytes)
        assertEquals(true, plan.needsTransfer)
    }

    @Test
    fun preallocatedFileWithPartialProgress_resumesFromRecordedBytesNotFileSize() {
        // 关键风险用例：文件大小已被预分配成远端大小 2048，但真实只写了 1024；
        // 绝不能判定为「已下载完成」去提交一个尾部全是 0 的文件。
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 2048L,
            recordedBytes = 1024L,
            pendingDiskBytes = 2048L,
            protocolSupportsResume = true
        )
        assertEquals(true, plan.reusePending)
        assertEquals(1024L, plan.startOffset)
        assertEquals(0L, plan.preallocateBytes)
        assertEquals(true, plan.needsTransfer)
    }

    @Test
    fun preallocatedFileWithoutProgress_restartsFromZeroAndReallocates() {
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 2048L,
            recordedBytes = 0L,
            pendingDiskBytes = 2048L,
            protocolSupportsResume = true
        )
        assertEquals(true, plan.reusePending)
        assertEquals(0L, plan.startOffset)
        // 从 0 开始写会先截断，所以要重新预分配。
        assertEquals(2048L, plan.preallocateBytes)
        assertEquals(true, plan.needsTransfer)
    }

    @Test
    fun preallocatedFileWithRecordedComplete_proceedsWithoutTransfer() {
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 2048L,
            recordedBytes = 2048L,
            pendingDiskBytes = 2048L,
            protocolSupportsResume = true
        )
        assertEquals(true, plan.reusePending)
        assertEquals(2048L, plan.startOffset)
        assertEquals(0L, plan.preallocateBytes)
        assertEquals(false, plan.needsTransfer)
    }

    @Test
    fun recordedBytesAheadOfContiguousWrites_usesDiskSizeAsSafeLowerBound() {
        // 多通道 SFTP 等乱序写实现的上报值可能来自临时文件、超过已写进目标文件的前缀；
        // 磁盘大小才是连续已写字节的上界，两者取小才不会在文件中间留空洞。
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 8192L,
            recordedBytes = 4096L,
            pendingDiskBytes = 1024L,
            protocolSupportsResume = true
        )
        assertEquals(1024L, plan.startOffset)
        assertEquals(true, plan.needsTransfer)
    }

    @Test
    fun diskBytesLargerThanRemote_restartsWithNewPreallocatedTarget() {
        // 远端文件被替换成更小版本：旧 pending（哪怕已被预分配成旧大小）不能续写。
        assertEquals(
            DownloadPreallocationPolicy.Plan(
                reusePending = false,
                startOffset = 0L,
                preallocateBytes = 2048L,
                needsTransfer = true
            ),
            DownloadPreallocationPolicy.plan(
                remoteBytes = 2048L,
                recordedBytes = 2048L,
                pendingDiskBytes = 4096L,
                protocolSupportsResume = true
            )
        )
    }

    @Test
    fun unknownRemoteSizeWithPendingFile_restartsWithoutPreallocation() {
        assertEquals(
            DownloadPreallocationPolicy.Plan(
                reusePending = false,
                startOffset = 0L,
                preallocateBytes = 0L,
                needsTransfer = true
            ),
            DownloadPreallocationPolicy.plan(
                remoteBytes = 0L,
                recordedBytes = 1024L,
                pendingDiskBytes = 2048L,
                protocolSupportsResume = true
            )
        )
    }

    @Test
    fun protocolWithoutResume_startsWithNewPreallocatedTarget() {
        assertEquals(
            DownloadPreallocationPolicy.Plan(
                reusePending = false,
                startOffset = 0L,
                preallocateBytes = 2048L,
                needsTransfer = true
            ),
            DownloadPreallocationPolicy.plan(
                remoteBytes = 2048L,
                recordedBytes = 1024L,
                pendingDiskBytes = 2048L,
                protocolSupportsResume = false
            )
        )
    }

    @Test
    fun pendingFileCompleteOnDiskButRecordedLags_rewritesTailSafely() {
        // 进程在最后一次进度上报之后被杀：记录值落后于磁盘，最多重复写少量尾部字节（安全），
        // 但不能直接从 0 重下，否则大文件断点续传退化为全量重传。
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 2048L,
            recordedBytes = 1024L,
            pendingDiskBytes = 2048L,
            protocolSupportsResume = true
        )
        assertEquals(1024L, plan.startOffset)
        assertEquals(true, plan.needsTransfer)
    }

    @Test
    fun negativeRecordedBytes_areTreatedAsZero() {
        val plan = DownloadPreallocationPolicy.plan(
            remoteBytes = 2048L,
            recordedBytes = -1L,
            pendingDiskBytes = 2048L,
            protocolSupportsResume = true
        )
        assertEquals(0L, plan.startOffset)
        assertEquals(2048L, plan.preallocateBytes)
        assertEquals(true, plan.needsTransfer)
    }
}

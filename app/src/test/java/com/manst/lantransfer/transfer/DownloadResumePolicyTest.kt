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

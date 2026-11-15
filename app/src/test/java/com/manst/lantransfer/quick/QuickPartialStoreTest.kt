package com.manst.lantransfer.quick

import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [QuickPartialPlan] 与 [QuickPartialStore] 的 JVM 单元测试：续传偏移决策、分片 key 稳定性与孤儿清理。 */
class QuickPartialStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 1_700_000_000_000L

    private fun profile(
        host: String = "192.168.1.8",
        port: Int = 2222,
        protocol: TransferProtocol = TransferProtocol.SFTP
    ) = ServerProfile(
        name = "快捷传输",
        protocol = protocol,
        host = host,
        port = port,
        username = "lt123456",
        password = "secret"
    )

    private fun entry(
        name: String = "photo.jpg",
        path: String = "/相册/photo.jpg",
        size: Long = 1024L,
        modifiedAt: Long = 1_700_000_000_000L
    ) = RemoteEntry(name = name, path = path, isDirectory = false, size = size, modifiedAt = modifiedAt)

    private fun store(root: File = temporaryFolder.newFolder("quick_partial")) =
        QuickPartialStore(root) { now }

    // ------------------------------------------------------------ 续传决策

    @Test
    fun plan_startsFromZeroWhenRemoteSizeUnknown() {
        val decision = QuickPartialPlan.decide(partLength = 512L, remoteSize = -1L, supportsResume = true)
        assertEquals(0L, decision.offset)
        assertFalse(decision.alreadyComplete)
    }

    @Test
    fun plan_treatsFullLengthAsComplete() {
        val decision = QuickPartialPlan.decide(partLength = 1024L, remoteSize = 1024L, supportsResume = false)
        assertEquals(1024L, decision.offset)
        assertTrue("分片已完整时无需重新下载", decision.alreadyComplete)
    }

    @Test
    fun plan_restartsWhenProtocolDoesNotSupportResume() {
        val decision = QuickPartialPlan.decide(partLength = 512L, remoteSize = 1024L, supportsResume = false)
        assertEquals(0L, decision.offset)
        assertFalse(decision.alreadyComplete)
    }

    @Test
    fun plan_resumesFromValidPartialLength() {
        val decision = QuickPartialPlan.decide(partLength = 512L, remoteSize = 1024L, supportsResume = true)
        assertEquals(512L, decision.offset)
        assertFalse(decision.alreadyComplete)
    }

    @Test
    fun plan_restartsWhenPartialIsInvalid() {
        assertEquals(0L, QuickPartialPlan.decide(0L, 1024L, true).offset)
        assertEquals(0L, QuickPartialPlan.decide(-5L, 1024L, true).offset)
        assertEquals(0L, QuickPartialPlan.decide(2048L, 1024L, true).offset)
    }

    // ------------------------------------------------------------ 分片 key

    @Test
    fun partialFile_isStableForSameRemoteFile() {
        val store = store()
        val first = store.partialFile(profile(), entry())
        val second = store.partialFile(profile(), entry())
        assertEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.name.endsWith(".part"))
        assertEquals(first.parentFile?.absolutePath, store.partialFile(profile(), entry()).parentFile?.absolutePath)
    }

    @Test
    fun partialFile_differsWhenRemoteFileChanges() {
        val store = store()
        val base = store.partialFile(profile(), entry())
        assertNotEquals(base.name, store.partialFile(profile(host = "192.168.1.9"), entry()).name)
        assertNotEquals(base.name, store.partialFile(profile(port = 2223), entry()).name)
        assertNotEquals(base.name, store.partialFile(profile(protocol = TransferProtocol.FTP), entry()).name)
        assertNotEquals(base.name, store.partialFile(profile(), entry(path = "/相册/other.jpg")).name)
        assertNotEquals(base.name, store.partialFile(profile(), entry(size = 2048L)).name)
        assertNotEquals(base.name, store.partialFile(profile(), entry(modifiedAt = 1_700_000_000_001L)).name)
    }

    @Test
    fun delete_removesOnlyTargetPartial() {
        val store = store()
        val target = store.partialFile(profile(), entry())
        val other = store.partialFile(profile(), entry(path = "/相册/other.jpg"))
        target.parentFile?.mkdirs()
        target.writeText("partial")
        other.writeText("other")
        assertTrue(store.delete(profile(), entry()))
        assertFalse(target.exists())
        assertTrue(other.exists())
        assertFalse("已删除的分片再次删除返回 false", store.delete(profile(), entry()))
    }

    // ------------------------------------------------------------ 孤儿分片清理

    @Test
    fun cleanupExpired_removesOnlyOldPartials() {
        val root = temporaryFolder.newFolder("quick_partial")
        val store = QuickPartialStore(root) { now }
        val oldFile = File(root, "old.part").apply { writeText("old") }
        val freshFile = File(root, "fresh.part").apply { writeText("fresh") }
        val otherFile = File(root, "keep.txt").apply { writeText("keep") }
        oldFile.setLastModified(now - QuickPartialStore.DEFAULT_MAX_AGE_MILLIS - 1_000L)
        freshFile.setLastModified(now - 1_000L)
        otherFile.setLastModified(now - QuickPartialStore.DEFAULT_MAX_AGE_MILLIS - 1_000L)

        assertEquals(1, store.cleanupExpired())
        assertFalse(oldFile.exists())
        assertTrue(freshFile.exists())
        assertTrue("非 .part 文件不清理", otherFile.exists())
    }

    @Test
    fun cleanupExpired_keepsPartialsWithinSevenDays() {
        val root = temporaryFolder.newFolder("quick_partial")
        val store = QuickPartialStore(root) { now }
        val recent = File(root, "recent.part").apply { writeText("data") }
        recent.setLastModified(now - QuickPartialStore.DEFAULT_MAX_AGE_MILLIS + 60_000L)
        assertEquals(0, store.cleanupExpired())
        assertTrue(recent.exists())
    }

    @Test
    fun cleanupExpired_onMissingDirectoryIsSafe() {
        val missing = File(temporaryFolder.root, "not-exists")
        val store = QuickPartialStore(missing) { now }
        assertEquals(0, store.cleanupExpired())
    }
}

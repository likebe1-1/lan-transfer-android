package com.manst.lantransfer.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 传输引擎持久化/恢复的设备测试（只要求编译，Lead 在模拟器执行）。
 *
 * 用内存版 [TransferStore] 精确控制“进程重启前”的任务快照，验证：
 * 1) 已结束任务恢复为历史、clearHistory 清空并回写持久化；
 * 2) 未完成的下载任务恢复为 QUEUED 并自动继续（连接失败触发重试后 attempts 增长）；
 * 3) 未完成的上传任务在源文件不可读时恢复为 FAILED 并给出中文原因；
 * 4) 入队/取消/移除会节流持久化（快照含 profileSnapshot）。
 */
@RunWith(AndroidJUnit4::class)
class TransferPersistenceDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val managers = CopyOnWriteArrayList<DefaultTransferManager>()

    @After
    fun tearDown() {
        managers.forEach { manager ->
            runCatching { manager.clearHistory() }
            runCatching { manager.tasks.value.forEach { manager.cancel(it.id) } }
        }
        managers.clear()
    }

    @Test
    fun restore_finishedTasksAsHistoryAndClearHistoryPersists() {
        val store = FakeTransferStore(
            listOf(
                finishedTask("history-completed", TransferState.COMPLETED),
                finishedTask("history-failed", TransferState.FAILED)
            )
        )
        val manager = createManager(store)

        assertEquals(2, manager.tasks.value.size)
        assertEquals(TransferState.COMPLETED, manager.tasks.value.first { it.id == "history-completed" }.state)
        assertEquals(TransferState.FAILED, manager.tasks.value.first { it.id == "history-failed" }.state)

        manager.clearHistory()
        assertTrue(manager.tasks.value.isEmpty())
        awaitUntil("历史清空后持久化") { store.saved.isEmpty() }
    }

    @Test
    fun restore_unfinishedDownloadBecomesQueuedAndAutoRetries() {
        val store = FakeTransferStore(listOf(unfinishedTask("resume-download", TransferDirection.DOWNLOAD)))
        val manager = createManager(store)

        // 恢复为 QUEUED 并自动开始；连接 127.0.0.1:1 必然失败，因此应看到重试计数增长
        awaitUntil("恢复任务自动继续并进入自动重试", 20_000L) {
            val task = manager.tasks.value.firstOrNull { it.id == "resume-download" }
            task != null && (task.attempts >= 1 || task.state == TransferState.RUNNING)
        }
        val task = manager.tasks.value.first { it.id == "resume-download" }
        assertFalse(task.state == TransferState.CANCELLED)
        assertNotNull(task.profileSnapshot)

        manager.cancel(task.id)
        awaitUntil("取消后进入 CANCELLED", 20_000L) {
            manager.tasks.value.firstOrNull { it.id == task.id }?.state == TransferState.CANCELLED
        }
    }

    @Test
    fun restore_unfinishedUploadWithUnreadableSourceBecomesFailedWithReason() {
        val store = FakeTransferStore(
            listOf(
                unfinishedTask(
                    id = "resume-upload",
                    direction = TransferDirection.UPLOAD,
                    sourceUri = "content://com.manst.lantransfer.nonexistent/files/missing.txt"
                )
            )
        )
        val manager = createManager(store)

        val task = manager.tasks.value.first { it.id == "resume-upload" }
        assertEquals(TransferState.FAILED, task.state)
        assertNotNull(task.error)
        assertTrue(task.error!!.contains("无法读取") || task.error!!.contains("授权"))
    }

    @Test
    fun enqueueAndRemove_arePersistedWithProfileSnapshot() {
        val store = FakeTransferStore()
        val manager = createManager(store)
        val profile = testProfile()
        val taskId = manager.enqueueDownload(
            profile,
            RemoteEntry(name = "persist.txt", path = "/persist.txt", isDirectory = false, size = 10L)
        )

        awaitUntil("入队任务被持久化") {
            store.saved.any { it.id == taskId && it.profileSnapshot != null }
        }
        manager.cancel(taskId)
        manager.remove(taskId)
        assertTrue(manager.tasks.value.none { it.id == taskId })
        awaitUntil("移除后持久化快照不再包含该任务") {
            store.saved.none { it.id == taskId }
        }
    }

    // ------------------------------------------------------------ 辅助

    private fun createManager(store: TransferStore): DefaultTransferManager {
        val manager = DefaultTransferManager(context, FakeServerRepository(), store)
        managers += manager
        return manager
    }

    private fun testProfile(): ServerProfile = ServerProfile(
        id = "device-test-profile",
        name = "设备测试服务器",
        protocol = TransferProtocol.FTP,
        host = "127.0.0.1",
        port = 1,
        username = "test",
        password = "test"
    )

    private fun finishedTask(id: String, state: TransferState): TransferTask = TransferTask(
        id = id,
        profileId = "device-test-profile",
        serverName = "设备测试服务器",
        protocolLabel = "FTP",
        direction = TransferDirection.DOWNLOAD,
        displayName = id + ".txt",
        remotePath = "/" + id + ".txt",
        totalBytes = 100L,
        transferredBytes = 100L,
        state = state,
        error = if (state == TransferState.FAILED) "连接超时" else null,
        profileSnapshot = testProfile(),
        createdAt = 1L,
        startedAt = 2L,
        finishedAt = 3L
    )

    private fun unfinishedTask(
        id: String,
        direction: TransferDirection,
        sourceUri: String? = null
    ): TransferTask = TransferTask(
        id = id,
        profileId = "device-test-profile",
        serverName = "设备测试服务器",
        protocolLabel = "FTP",
        direction = direction,
        displayName = id + ".bin",
        remotePath = "/" + id + ".bin",
        sourceUri = sourceUri,
        totalBytes = 100L,
        transferredBytes = 0L,
        state = TransferState.QUEUED,
        profileSnapshot = testProfile(),
        createdAt = 1L
    )

    private fun awaitUntil(reason: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        assertTrue("等待超时：" + reason, condition())
    }

    /** 内存版 TransferStore。 */
    private class FakeTransferStore(initial: List<TransferTask> = emptyList()) : TransferStore {
        private val stored = initial.toMutableList()

        @Volatile
        var saved: List<TransferTask> = initial.toList()
            private set

        @Synchronized
        override fun load(): List<TransferTask> = stored.toList()

        @Synchronized
        override fun save(tasks: List<TransferTask>) {
            stored.clear()
            stored.addAll(tasks)
            saved = stored.toList()
        }

        @Synchronized
        override fun clear() {
            stored.clear()
            saved = emptyList()
        }
    }

    /** 内存版 ServerRepository（不触碰磁盘配置）。 */
    private class FakeServerRepository : ServerRepository {
        private val flow = MutableStateFlow<List<ServerProfile>>(emptyList())
        private val byId = LinkedHashMap<String, ServerProfile>()

        override val profiles: StateFlow<List<ServerProfile>> = flow

        @Synchronized
        override fun find(id: String): ServerProfile? = byId[id]

        override suspend fun save(profile: ServerProfile) {
            synchronized(this) {
                byId[profile.id] = profile
                flow.value = byId.values.toList()
            }
        }

        override suspend fun delete(id: String) {
            synchronized(this) {
                byId.remove(id)
                flow.value = byId.values.toList()
            }
        }

        override suspend fun markUsed(id: String) = Unit
    }
}

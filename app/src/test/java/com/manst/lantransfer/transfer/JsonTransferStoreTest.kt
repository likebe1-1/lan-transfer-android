package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [JsonTransferStore] 的 JVM 单元测试：往返、损坏回退、历史上限、原子写。 */
class JsonTransferStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun storageFile(): File = File(temporaryFolder.root, "transfer_tasks.json")

    private fun sampleProfile() = ServerProfile(
        id = "profile-1",
        name = "家里的 NAS",
        protocol = TransferProtocol.SFTP,
        host = "192.168.1.8",
        port = 2222,
        username = "alice",
        password = "密码 p@ss",
        initialPath = "/home/alice",
        ftpsMode = FtpsMode.EXPLICIT,
        passiveMode = false,
        allowInsecureTls = false,
        lastUsedAt = 1234L
    )

    private fun sampleTask(
        id: String = "task-1",
        state: TransferState = TransferState.QUEUED,
        finishedAt: Long = 0L
    ) = TransferTask(
        id = id,
        profileId = "profile-1",
        serverName = "家里的 NAS",
        protocolLabel = "SFTP",
        direction = TransferDirection.DOWNLOAD,
        displayName = "照片.jpg",
        remotePath = "/home/alice/照片.jpg",
        sourceUri = "content://test/source/1",
        localDisplayName = "照片 (1).jpg",
        totalBytes = 4096L,
        transferredBytes = 1024L,
        state = state,
        error = if (state == TransferState.FAILED) "连接超时" else null,
        bytesPerSecond = 512L,
        localUri = "content://media/external/downloads/1",
        localRelativeDir = "相册/2024",
        attempts = 2,
        resumable = false,
        profileSnapshot = sampleProfile(),
        createdAt = 100L,
        startedAt = 200L,
        finishedAt = finishedAt
    )

    @Test
    fun `保存后可以完整往返所有字段`() {
        val file = storageFile()
        val store = JsonTransferStore(file)
        val task = sampleTask()
        store.save(listOf(task))

        val loaded = JsonTransferStore(file).load()

        assertEquals(1, loaded.size)
        val restored = loaded.single()
        assertEquals(task.id, restored.id)
        assertEquals(task.profileId, restored.profileId)
        assertEquals(task.serverName, restored.serverName)
        assertEquals(task.protocolLabel, restored.protocolLabel)
        assertEquals(task.direction, restored.direction)
        assertEquals(task.displayName, restored.displayName)
        assertEquals(task.remotePath, restored.remotePath)
        assertEquals(task.sourceUri, restored.sourceUri)
        assertEquals(task.localDisplayName, restored.localDisplayName)
        assertEquals(task.totalBytes, restored.totalBytes)
        assertEquals(task.transferredBytes, restored.transferredBytes)
        assertEquals(task.state, restored.state)
        assertEquals(task.error, restored.error)
        assertEquals(task.localUri, restored.localUri)
        assertEquals(task.localRelativeDir, restored.localRelativeDir)
        assertEquals(task.attempts, restored.attempts)
        assertEquals(task.resumable, restored.resumable)
        assertEquals(task.createdAt, restored.createdAt)
        assertEquals(task.startedAt, restored.startedAt)
        assertEquals(task.finishedAt, restored.finishedAt)
        assertEquals(0L, restored.bytesPerSecond)
        assertEquals(sampleProfile(), restored.profileSnapshot)
        // 磁盘上是合法 JSON
        assertNotNull(JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("tasks"))
        // 原子写不残留临时文件
        assertFalse(File(temporaryFolder.root, "transfer_tasks.json.tmp").exists())
    }

    @Test
    fun `损坏文件降级为空并保留 bak`() {
        val file = storageFile()
        val garbage = "{ 这不是合法的 JSON"
        file.writeText(garbage, Charsets.UTF_8)

        val store = JsonTransferStore(file)

        assertTrue(store.load().isEmpty())
        assertNotNull(store.lastLoadError)
        assertFalse(file.exists())
        val backup = File(temporaryFolder.root, "transfer_tasks.json.bak")
        assertTrue(backup.exists())
        assertEquals(garbage, backup.readText(Charsets.UTF_8))
    }

    @Test
    fun `单条损坏记录被跳过`() {
        val file = storageFile()
        file.writeText(
            "{\"version\":1,\"tasks\":[" +
                "{\"id\":\"ok\",\"direction\":\"DOWNLOAD\",\"state\":\"QUEUED\",\"displayName\":\"a\",\"remotePath\":\"/a\"}," +
                "{\"id\":\"\",\"direction\":\"DOWNLOAD\",\"state\":\"QUEUED\"}," +
                "{\"id\":\"bad-direction\",\"direction\":\"TELEPORT\",\"state\":\"QUEUED\"}," +
                "\"不是对象\"" +
                "]}",
            Charsets.UTF_8
        )

        val loaded = JsonTransferStore(file).load()

        assertEquals(listOf("ok"), loaded.map { it.id })
    }

    @Test
    fun `历史超过上限时丢弃最旧的记录但保留未完成任务`() {
        val file = storageFile()
        val finished = (1..205).map { index ->
            sampleTask(
                id = "finished-" + index,
                state = TransferState.COMPLETED,
                finishedAt = index.toLong()
            )
        }
        val active = listOf(
            sampleTask(id = "active-queued", state = TransferState.QUEUED),
            sampleTask(id = "active-running", state = TransferState.RUNNING)
        )

        JsonTransferStore(file).save(active + finished)
        val loaded = JsonTransferStore(file).load()

        val loadedFinished = loaded.filter { it.isFinished }
        assertEquals(TransferHistoryLimit.DEFAULT_MAX_HISTORY, loadedFinished.size)
        assertTrue(loaded.any { it.id == "active-queued" })
        assertTrue(loaded.any { it.id == "active-running" })
        // 保留结束时间最新的 200 条：finished-6 到 finished-205
        assertFalse(loaded.any { it.id == "finished-1" })
        assertFalse(loaded.any { it.id == "finished-5" })
        assertTrue(loaded.any { it.id == "finished-6" })
        assertTrue(loaded.any { it.id == "finished-205" })
    }

    @Test
    fun `空列表保存后可以读回且 clear 删除文件`() {
        val file = storageFile()
        val store = JsonTransferStore(file)
        store.save(emptyList())
        assertTrue(file.exists())
        assertTrue(JsonTransferStore(file).load().isEmpty())

        store.clear()
        assertFalse(file.exists())
        assertTrue(JsonTransferStore(file).load().isEmpty())
    }

    @Test
    fun `文件不存在时返回空列表`() {
        val store = JsonTransferStore(storageFile())
        assertTrue(store.load().isEmpty())
        assertNull(store.lastLoadError)
    }
}

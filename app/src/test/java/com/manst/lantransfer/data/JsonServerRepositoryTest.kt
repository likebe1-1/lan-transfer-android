package com.manst.lantransfer.data

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [JsonServerRepository] 的 JVM 单元测试：真实临时文件读写，不依赖 Android 运行时。 */
class JsonServerRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun storageFile(): File = File(temporaryFolder.root, "servers.json")

    private fun sampleProfile(
        id: String = "id-1",
        name: String = "家里的 NAS",
        protocol: TransferProtocol = TransferProtocol.FTPS,
        lastUsedAt: Long = 111L,
        password: String = "p@ss 密码"
    ) = ServerProfile(
        id = id,
        name = name,
        protocol = protocol,
        host = "192.168.1.10",
        port = 2121,
        username = "alice",
        password = password,
        initialPath = "/home/alice/照片",
        ftpsMode = FtpsMode.IMPLICIT,
        passiveMode = false,
        allowInsecureTls = false,
        lastUsedAt = lastUsedAt
    )

    @Test
    fun `配置文件不存在时为空且不抛异常`() {
        val file = storageFile()
        val repository = JsonServerRepository(file)
        assertTrue(repository.profiles.value.isEmpty())
        assertNull(repository.find("missing"))
        assertFalse(file.exists())
        assertNull(repository.lastLoadError)
    }

    @Test
    fun `保存后重新打开可以完整往返所有字段`() = runBlocking {
        val file = storageFile()
        val profile = sampleProfile()
        JsonServerRepository(file).save(profile)

        val reloaded = JsonServerRepository(file)
        assertEquals(listOf(profile), reloaded.profiles.value)
        assertEquals(profile, reloaded.find(profile.id))

        // 磁盘上必须是合法 JSON，且包含版本号与 servers 数组
        val root = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals(1, root.getInt("version"))
        val servers = root.getJSONArray("servers")
        assertEquals(1, servers.length())
        val json = servers.getJSONObject(0)
        assertEquals("id-1", json.getString("id"))
        assertEquals("FTPS", json.getString("protocol"))
        assertEquals("IMPLICIT", json.getString("ftpsMode"))
        assertEquals(2121, json.getInt("port"))
        assertEquals(false, json.getBoolean("passiveMode"))
        assertEquals(false, json.getBoolean("allowInsecureTls"))
        assertEquals(111L, json.getLong("lastUsedAt"))
        assertEquals("/home/alice/照片", json.getString("initialPath"))
    }

    @Test
    fun `四种协议与两种FTPS模式都能往返`() = runBlocking {
        val file = storageFile()
        val repository = JsonServerRepository(file)
        val protocols = TransferProtocol.entries
        protocols.forEachIndexed { index, protocol ->
            repository.save(
                sampleProfile(id = "id-$protocol", name = "服务器-$protocol", protocol = protocol, lastUsedAt = index.toLong())
            )
        }
        val modes = FtpsMode.entries
        modes.forEachIndexed { index, mode ->
            val profile = sampleProfile(id = "mode-$mode", name = "模式-$mode", lastUsedAt = 100L + index)
                .copy(ftpsMode = mode)
            repository.save(profile)
        }

        val reloaded = JsonServerRepository(file)
        assertEquals(protocols.size + modes.size, reloaded.profiles.value.size)
        protocols.forEach { protocol ->
            assertEquals(protocol, reloaded.find("id-$protocol")?.protocol)
        }
        modes.forEach { mode ->
            assertEquals(mode, reloaded.find("mode-$mode")?.ftpsMode)
        }
    }

    @Test
    fun `save 是 upsert 语义不会重复`() = runBlocking {
        val repository = JsonServerRepository(storageFile())
        repository.save(sampleProfile(name = "旧名字"))
        repository.save(sampleProfile(name = "新名字").copy(username = "bob"))

        assertEquals(1, repository.profiles.value.size)
        assertEquals("新名字", repository.profiles.value.single().name)
        assertEquals("bob", repository.profiles.value.single().username)
        assertEquals(1, JsonServerRepository(storageFile()).profiles.value.size)
    }

    @Test
    fun `删除是幂等操作`() = runBlocking {
        val file = storageFile()
        val repository = JsonServerRepository(file)
        repository.save(sampleProfile(id = "keep", name = "保留", lastUsedAt = 2L))
        repository.save(sampleProfile(id = "drop", name = "删除", lastUsedAt = 1L))

        repository.delete("drop")
        repository.delete("drop")
        repository.delete("never-existed")

        assertEquals(listOf("keep"), repository.profiles.value.map { it.id })
        assertEquals(listOf("keep"), JsonServerRepository(file).profiles.value.map { it.id })
    }

    @Test
    fun `最近使用倒序且同时间按名称排序`() = runBlocking {
        val repository = JsonServerRepository(storageFile())
        repository.save(sampleProfile(id = "a", name = "Beta", lastUsedAt = 500L))
        repository.save(sampleProfile(id = "b", name = "alpha", lastUsedAt = 500L))
        repository.save(sampleProfile(id = "c", name = "Gamma", lastUsedAt = 900L))
        repository.save(sampleProfile(id = "d", name = "从未使用", lastUsedAt = 0L))

        assertEquals(listOf("Gamma", "alpha", "Beta", "从未使用"), repository.profiles.value.map { it.name })
    }

    @Test
    fun `markUsed 更新最近使用时间并重新排序`() = runBlocking {
        val file = storageFile()
        val repository = JsonServerRepository(file)
        repository.save(sampleProfile(id = "old", name = "旧", lastUsedAt = 1L))
        repository.save(sampleProfile(id = "new", name = "新", lastUsedAt = 2L))
        assertEquals(listOf("new", "old"), repository.profiles.value.map { it.id })

        repository.markUsed("old")

        val used = repository.profiles.value
        assertEquals("old", used.first().id)
        assertTrue(used.first().lastUsedAt > 2L)
        assertEquals("old", JsonServerRepository(file).profiles.value.first().id)

        // 不存在的 id 不抛异常也不改文件
        val before = file.readText(Charsets.UTF_8)
        repository.markUsed("ghost")
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `损坏文件降级为空并保留 bak`() {
        val file = storageFile()
        val garbage = "{ 这不是合法的 JSON \u0000"
        file.writeText(garbage, Charsets.UTF_8)

        val repository = JsonServerRepository(file)

        assertTrue(repository.profiles.value.isEmpty())
        assertNotNull(repository.lastLoadError)
        assertFalse("损坏文件应被移走", file.exists())
        val backup = File(temporaryFolder.root, "servers.json.bak")
        assertTrue("应保留损坏现场", backup.exists())
        assertEquals(garbage, backup.readText(Charsets.UTF_8))
    }

    @Test
    fun `损坏的单个条目被跳过而正常条目保留`() = runBlocking {
        val file = storageFile()
        JsonServerRepository(file).save(sampleProfile(id = "good", name = "正常"))

        val root = JSONObject(file.readText(Charsets.UTF_8))
        val servers = root.getJSONArray("servers")
        servers.put(JSONObject().put("id", "broken").put("protocol", "FTP"))
        servers.put("不是对象")
        servers.put(JSONObject().put("id", "unknown-enum").put("name", "未知枚举").put("host", "10.0.0.2")
            .put("protocol", "TELNET").put("ftpsMode", "WHATEVER").put("port", 70000))
        file.writeText(root.toString(2), Charsets.UTF_8)

        val repository = JsonServerRepository(file)

        assertNull(repository.lastLoadError)
        assertEquals(listOf("good", "unknown-enum"), repository.profiles.value.map { it.id })
        val unknown = repository.find("unknown-enum")!!
        assertEquals(TransferProtocol.FTP, unknown.protocol)
        assertEquals(FtpsMode.EXPLICIT, unknown.ftpsMode)
        assertEquals(TransferProtocol.FTP.defaultPort, unknown.port)
        assertEquals(0L, unknown.lastUsedAt)
    }

    @Test
    fun `空文件按空配置处理`() {
        val file = storageFile()
        file.writeText("   \n", Charsets.UTF_8)
        val repository = JsonServerRepository(file)
        assertTrue(repository.profiles.value.isEmpty())
        assertNull(repository.lastLoadError)
        assertTrue("空文件不属于损坏，不需要 bak", file.exists())
    }

    @Test
    fun `并发保存不会损坏文件且全部保留`() = runBlocking {
        val file = storageFile()
        val repository = JsonServerRepository(file)

        val jobs = (1..24).map { index ->
            launch(Dispatchers.Default) {
                repository.save(sampleProfile(id = "id-$index", name = "服务器 $index", lastUsedAt = index.toLong()))
            }
        }
        jobs.joinAll()

        assertEquals(24, repository.profiles.value.size)
        // 磁盘文件仍是合法 JSON，重新加载后数据一致
        val reloaded = JsonServerRepository(file)
        assertNull(reloaded.lastLoadError)
        assertEquals(24, reloaded.profiles.value.size)
        JSONObject(file.readText(Charsets.UTF_8))
        // 原子写不残留临时文件
        assertFalse(File(temporaryFolder.root, "servers.json.tmp").exists())
    }

    @Test
    fun `写盘失败抛出中文异常且内存快照不变`() = runBlocking {
        val notADirectory = File(temporaryFolder.root, "blocked")
        notADirectory.writeText("我是普通文件", Charsets.UTF_8)
        val brokenStorage = File(notADirectory, "servers.json")
        val repository = JsonServerRepository(brokenStorage)

        try {
            repository.save(sampleProfile())
            fail("写盘失败应抛出异常")
        } catch (e: LanTransferException) {
            assertTrue(e.message!!.startsWith("保存服务器配置失败"))
        }
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun passwordIsEncryptedAtRestAndDecryptedOnLoad() = kotlinx.coroutines.runBlocking {
        val file = storageFile()
        val repository = JsonServerRepository(file, TestSecretCipher)
        repository.save(sampleProfile(id = "secure", name = "加密", password = "super-secret"))
        val raw = file.readText()
        assertTrue("磁盘上不应出现明文密码：" + raw, !raw.contains("super-secret"))
        assertTrue("应写入密文前缀", raw.contains(SecretCipher.PREFIX))
        val reloaded = JsonServerRepository(file, TestSecretCipher)
        assertEquals("super-secret", reloaded.profiles.value.first { it.id == "secure" }.password)
    }

    @Test
    fun legacyPlaintextPasswordStillLoads() {
        val file = storageFile()
        val legacy = """{"version":1,"servers":[{"id":"old","name":"旧","protocol":"SFTP","host":"1.2.3.4","port":22,"password":"plain-old"}]}"""
        file.writeText(legacy, Charsets.UTF_8)
        val repository = JsonServerRepository(file, TestSecretCipher)
        assertEquals("plain-old", repository.profiles.value.first().password)
    }
}

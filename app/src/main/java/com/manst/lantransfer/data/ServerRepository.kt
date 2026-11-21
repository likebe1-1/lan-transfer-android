package com.manst.lantransfer.data

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 服务器配置持久化仓库。
 *
 * 实现要求：
 * - 构造时从磁盘同步加载已有配置（配置文件很小，允许阻塞）；
 * - [profiles] 始终为按 [ServerProfile.lastUsedAt] 倒序(最近使用优先)或添加顺序的稳定快照；
 * - [save] 为 upsert 语义，[delete] 幂等。
 */
interface ServerRepository {
    val profiles: StateFlow<List<ServerProfile>>

    fun find(id: String): ServerProfile?

    suspend fun save(profile: ServerProfile)

    suspend fun delete(id: String)

    /** 记录最近使用时间，用于列表排序。 */
    suspend fun markUsed(id: String)
}

/**
 * 基于 JSON 文件的服务器配置仓库。
 *
 * 设计要点：
 * - 构造时同步读取 [storageFile]（配置体量很小，允许阻塞主线程一次）；
 * - 原子写：先写入同目录临时文件并 fsync，再 rename 覆盖正式文件，避免进程被杀导致半截文件；
 * - 损坏降级：文件无法解析时把原文件改名为 `*.bak` 保留现场，仓库以空列表启动，不崩溃；
 * - 单条记录字段缺失或非法时跳过该条，未知枚举值回退到默认值；
 * - 所有写操作由 [writeMutex] 串行化，保证并发 [save] 不会互相覆盖或写坏文件；
 * - 内存快照先持久化成功再对外发布，写盘失败时保留旧快照并抛出中文可读异常。
 */
class JsonServerRepository(private val storageFile: File) : ServerRepository {

    private val writeMutex = Mutex()

    /** 最近一次读取失败的原因（用于诊断，损坏文件场景下非空）。 */
    var lastLoadError: Throwable? = null
        private set

    private val mutableProfiles = MutableStateFlow(sortProfiles(loadProfiles()))

    override val profiles: StateFlow<List<ServerProfile>> = mutableProfiles.asStateFlow()

    override fun find(id: String): ServerProfile? = mutableProfiles.value.firstOrNull { it.id == id }

    override suspend fun save(profile: ServerProfile) {
        writeMutex.withLock {
            val current = mutableProfiles.value
            val exists = current.any { it.id == profile.id }
            val updated = if (exists) {
                current.map { if (it.id == profile.id) profile else it }
            } else {
                current + profile
            }
            val sorted = sortProfiles(updated)
            persistOrThrow(sorted)
            mutableProfiles.value = sorted
        }
    }

    override suspend fun delete(id: String) {
        writeMutex.withLock {
            val current = mutableProfiles.value
            if (current.none { it.id == id }) return@withLock
            val remaining = sortProfiles(current.filterNot { it.id == id })
            persistOrThrow(remaining)
            mutableProfiles.value = remaining
        }
    }

    override suspend fun markUsed(id: String) {
        writeMutex.withLock {
            val current = mutableProfiles.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@withLock
            val updated = current.toMutableList()
            updated[index] = updated[index].copy(lastUsedAt = System.currentTimeMillis())
            val sorted = sortProfiles(updated)
            persistOrThrow(sorted)
            mutableProfiles.value = sorted
        }
    }

    // ---------------------------------------------------------------- 读取

    private fun loadProfiles(): List<ServerProfile> {
        if (!storageFile.exists()) return emptyList()
        val text = try {
            storageFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            lastLoadError = e
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        return try {
            val root = JSONObject(text)
            val array = root.optJSONArray(KEY_SERVERS) ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    parseProfile(array.optJSONObject(index))?.let(::add)
                }
            }
        } catch (e: JSONException) {
            quarantineCorruptedFile(e)
            emptyList()
        }
    }

    /** 单条记录解析：缺少 id/名称/主机的记录视为损坏条目并跳过。 */
    private fun parseProfile(json: JSONObject?): ServerProfile? {
        if (json == null) return null
        val id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val name = json.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: return null
        val host = json.optString(KEY_HOST).takeIf { it.isNotBlank() } ?: return null
        val protocol = parseEnum(json.optString(KEY_PROTOCOL), TransferProtocol.FTP)
        val rawPort = json.optInt(KEY_PORT, protocol.defaultPort)
        val port = if (rawPort in PORT_RANGE) rawPort else protocol.defaultPort
        return ServerProfile(
            id = id,
            name = name,
            protocol = protocol,
            host = host,
            port = port,
            username = json.optString(KEY_USERNAME),
            password = json.optString(KEY_PASSWORD),
            initialPath = RemotePath.normalize(json.optString(KEY_INITIAL_PATH, RemotePath.ROOT)),
            ftpsMode = parseEnum(json.optString(KEY_FTPS_MODE), FtpsMode.EXPLICIT),
            passiveMode = json.optBoolean(KEY_PASSIVE_MODE, true),
            // 安全默认：历史配置未显式开启时按“严格校验”处理，避免静默降级信任任意证书
            allowInsecureTls = json.optBoolean(KEY_ALLOW_INSECURE_TLS, false),
            lastUsedAt = json.optLong(KEY_LAST_USED_AT, 0L).coerceAtLeast(0L)
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback

    private fun quarantineCorruptedFile(cause: JSONException) {
        lastLoadError = cause
        val parent = storageFile.absoluteFile.parentFile ?: return
        val backup = File(parent, BACKUP_FILE_NAME)
        try {
            if (backup.exists() && !backup.delete()) {
                backup.writeText(storageFile.readText(Charsets.UTF_8), Charsets.UTF_8)
                storageFile.delete()
                return
            }
            if (!storageFile.renameTo(backup)) {
                storageFile.copyTo(backup, overwrite = true)
                storageFile.delete()
            }
        } catch (e: IOException) {
            // 降级路径：备份失败也要保证仓库可用，记录原因供诊断。
            lastLoadError = e
        }
    }

    // ---------------------------------------------------------------- 写入

    private suspend fun persistOrThrow(snapshot: List<ServerProfile>) {
        try {
            persist(snapshot)
        } catch (e: IOException) {
            throw LanTransferException("保存服务器配置失败：${e.message ?: "本地存储不可写"}", e)
        }
    }

    private suspend fun persist(snapshot: List<ServerProfile>) = withContext(Dispatchers.IO) {
        val target = storageFile.absoluteFile
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw IOException("无法创建配置目录：${parent.path}")
        }
        val tempFile = File(parent, target.name + TEMP_FILE_SUFFIX)
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(encode(snapshot).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveAtomically(tempFile, target)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(snapshot: List<ServerProfile>): String {
        val servers = JSONArray()
        for (profile in snapshot) {
            servers.put(encodeProfile(profile))
        }
        return JSONObject()
            .put(KEY_VERSION, FILE_VERSION)
            .put(KEY_SERVERS, servers)
            .toString(2)
    }

    private fun encodeProfile(profile: ServerProfile): JSONObject = JSONObject().apply {
        put(KEY_ID, profile.id)
        put(KEY_NAME, profile.name)
        put(KEY_PROTOCOL, profile.protocol.name)
        put(KEY_HOST, profile.host)
        put(KEY_PORT, profile.port)
        put(KEY_USERNAME, profile.username)
        put(KEY_PASSWORD, profile.password)
        put(KEY_INITIAL_PATH, profile.initialPath)
        put(KEY_FTPS_MODE, profile.ftpsMode.name)
        put(KEY_PASSIVE_MODE, profile.passiveMode)
        put(KEY_ALLOW_INSECURE_TLS, profile.allowInsecureTls)
        put(KEY_LAST_USED_AT, profile.lastUsedAt)
    }

    /** 最近使用优先；同一时间按名称不区分大小写；最后按 id 保证顺序稳定。 */
    private fun sortProfiles(list: List<ServerProfile>): List<ServerProfile> =
        list.sortedWith(
            compareByDescending<ServerProfile> { it.lastUsedAt }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )

    private companion object {
        const val FILE_VERSION = 1
        const val BACKUP_FILE_NAME = "servers.json.bak"
        const val TEMP_FILE_SUFFIX = ".tmp"
        val PORT_RANGE = 1..65535

        const val KEY_VERSION = "version"
        const val KEY_SERVERS = "servers"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_INITIAL_PATH = "initialPath"
        const val KEY_FTPS_MODE = "ftpsMode"
        const val KEY_PASSIVE_MODE = "passiveMode"
        const val KEY_ALLOW_INSECURE_TLS = "allowInsecureTls"
        const val KEY_LAST_USED_AT = "lastUsedAt"
    }
}

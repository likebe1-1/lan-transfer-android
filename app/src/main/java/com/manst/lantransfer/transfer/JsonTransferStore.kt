package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 传输任务持久化默认实现：应用私有目录下的 transfer_tasks.json。
 *
 * - 原子写：临时文件 + fsync + rename，避免进程被杀留下半截文件；
 * - 损坏回退：文件无法解析时改名为 .bak 保留现场并返回空列表，不崩溃；
 * - 历史裁剪：已结束的任务最多保留 [TransferHistoryLimit.DEFAULT_MAX_HISTORY] 条（按结束时间保留最近），
 *   未结束的队列任务始终保留；
 * - 保存任务快照（含 profileSnapshot），供进程重启后恢复队列。
 */
class JsonTransferStore(
    private val storageFile: File,
    private val secretCipher: com.manst.lantransfer.data.SecretCipher = com.manst.lantransfer.data.PlaintextSecretCipher
) : TransferStore {

    /** 最近一次读取失败的原因（诊断用）。 */
    var lastLoadError: Throwable? = null
        private set

    @Synchronized
    override fun load(): List<TransferTask> {
        val target = storageFile.absoluteFile
        if (!target.isFile) return emptyList()
        val text = try {
            target.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            lastLoadError = e
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        return try {
            val root = JSONObject(text)
            val array = root.optJSONArray(KEY_TASKS) ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    parseTask(array.optJSONObject(index))?.let(::add)
                }
            }
        } catch (e: JSONException) {
            quarantineCorruptedFile(e)
            emptyList()
        }
    }

    @Synchronized
    override fun save(tasks: List<TransferTask>) {
        val limited = TransferHistoryLimit.apply(tasks, TransferHistoryLimit.DEFAULT_MAX_HISTORY)
        val target = storageFile.absoluteFile
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw IOException("无法创建传输记录目录：" + parent.path)
        }
        val temp = File(parent, target.name + TEMP_FILE_SUFFIX)
        try {
            FileOutputStream(temp).use { output ->
                output.write(encode(limited).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @Synchronized
    override fun clear() {
        val target = storageFile.absoluteFile
        target.delete()
        File(target.parentFile, target.name + TEMP_FILE_SUFFIX).delete()
    }

    // ------------------------------------------------------------ 编解码

    private fun encode(tasks: List<TransferTask>): String {
        val array = JSONArray()
        for (task in tasks) array.put(encodeTask(task))
        return JSONObject()
            .put(KEY_VERSION, FILE_VERSION)
            .put(KEY_TASKS, array)
            .toString(2)
    }

    private fun encodeTask(task: TransferTask): JSONObject = JSONObject().apply {
        put(KEY_ID, task.id)
        put(KEY_PROFILE_ID, task.profileId)
        put(KEY_SERVER_NAME, task.serverName)
        put(KEY_PROTOCOL_LABEL, task.protocolLabel)
        put(KEY_DIRECTION, task.direction.name)
        put(KEY_DISPLAY_NAME, task.displayName)
        put(KEY_REMOTE_PATH, task.remotePath)
        task.sourceUri?.let { put(KEY_SOURCE_URI, it) }
        task.localDisplayName?.let { put(KEY_LOCAL_DISPLAY_NAME, it) }
        put(KEY_TOTAL_BYTES, task.totalBytes)
        put(KEY_TRANSFERRED_BYTES, task.transferredBytes)
        put(KEY_STATE, task.state.name)
        task.error?.let { put(KEY_ERROR, it) }
        put(KEY_BYTES_PER_SECOND, task.bytesPerSecond)
        task.localUri?.let { put(KEY_LOCAL_URI, it) }
        task.localRelativeDir?.let { put(KEY_LOCAL_RELATIVE_DIR, it) }
        put(KEY_ATTEMPTS, task.attempts)
        put(KEY_RESUMABLE, task.resumable)
        task.profileSnapshot?.let { put(KEY_PROFILE_SNAPSHOT, encodeProfile(it)) }
        put(KEY_CREATED_AT, task.createdAt)
        put(KEY_STARTED_AT, task.startedAt)
        put(KEY_FINISHED_AT, task.finishedAt)
    }

    private fun encodeProfile(profile: ServerProfile): JSONObject = JSONObject().apply {
        put(P_ID, profile.id)
        put(P_NAME, profile.name)
        put(P_PROTOCOL, profile.protocol.name)
        put(P_HOST, profile.host)
        put(P_PORT, profile.port)
        put(P_USERNAME, profile.username)
        put(P_PASSWORD, secretCipher.encrypt(profile.password))
        put(P_INITIAL_PATH, profile.initialPath)
        put(P_FTPS_MODE, profile.ftpsMode.name)
        put(P_PASSIVE_MODE, profile.passiveMode)
        put(P_ALLOW_INSECURE_TLS, profile.allowInsecureTls)
        put(P_PINNED_CERT_SHA256, profile.pinnedCertificateSha256)
        put(P_LAST_USED_AT, profile.lastUsedAt)
    }

    private fun parseTask(json: JSONObject?): TransferTask? {
        if (json == null) return null
        val id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val direction = enumValueOrNull<TransferDirection>(json.optString(KEY_DIRECTION)) ?: return null
        val state = enumValueOrNull<TransferState>(json.optString(KEY_STATE)) ?: TransferState.QUEUED
        return TransferTask(
            id = id,
            profileId = json.optString(KEY_PROFILE_ID),
            serverName = json.optString(KEY_SERVER_NAME),
            protocolLabel = json.optString(KEY_PROTOCOL_LABEL),
            direction = direction,
            displayName = json.optString(KEY_DISPLAY_NAME),
            remotePath = json.optString(KEY_REMOTE_PATH),
            sourceUri = json.optString(KEY_SOURCE_URI).takeIf { it.isNotBlank() },
            localDisplayName = json.optString(KEY_LOCAL_DISPLAY_NAME).takeIf { it.isNotBlank() },
            totalBytes = json.optLong(KEY_TOTAL_BYTES, -1L),
            transferredBytes = json.optLong(KEY_TRANSFERRED_BYTES, 0L).coerceAtLeast(0L),
            state = state,
            error = json.optString(KEY_ERROR).takeIf { it.isNotBlank() },
            bytesPerSecond = 0L,
            localUri = json.optString(KEY_LOCAL_URI).takeIf { it.isNotBlank() },
            localRelativeDir = json.optString(KEY_LOCAL_RELATIVE_DIR).takeIf { it.isNotBlank() },
            attempts = json.optInt(KEY_ATTEMPTS, 0).coerceAtLeast(0),
            resumable = json.optBoolean(KEY_RESUMABLE, true),
            profileSnapshot = parseProfile(json.optJSONObject(KEY_PROFILE_SNAPSHOT)),
            createdAt = json.optLong(KEY_CREATED_AT, 0L),
            startedAt = json.optLong(KEY_STARTED_AT, 0L),
            finishedAt = json.optLong(KEY_FINISHED_AT, 0L)
        )
    }

    private fun parseProfile(json: JSONObject?): ServerProfile? {
        if (json == null) return null
        val id = json.optString(P_ID).takeIf { it.isNotBlank() } ?: return null
        val name = json.optString(P_NAME).takeIf { it.isNotBlank() } ?: return null
        val host = json.optString(P_HOST).takeIf { it.isNotBlank() } ?: return null
        val protocol = enumValueOrNull<TransferProtocol>(json.optString(P_PROTOCOL)) ?: TransferProtocol.FTP
        val rawPort = json.optInt(P_PORT, protocol.defaultPort)
        return ServerProfile(
            id = id,
            name = name,
            protocol = protocol,
            host = host,
            port = if (rawPort in 1..65535) rawPort else protocol.defaultPort,
            username = json.optString(P_USERNAME),
            password = secretCipher.decrypt(json.optString(P_PASSWORD)),
            initialPath = RemotePath.normalize(json.optString(P_INITIAL_PATH, RemotePath.ROOT)),
            ftpsMode = enumValueOrNull<FtpsMode>(json.optString(P_FTPS_MODE)) ?: FtpsMode.EXPLICIT,
            passiveMode = json.optBoolean(P_PASSIVE_MODE, true),
            // 与 ServerRepository 保持一致：历史配置未显式开启时按严格校验处理
            allowInsecureTls = json.optBoolean(P_ALLOW_INSECURE_TLS, false),
            pinnedCertificateSha256 = json.optString(P_PINNED_CERT_SHA256, "").takeIf { it.isNotBlank() },
            lastUsedAt = json.optLong(P_LAST_USED_AT, 0L).coerceAtLeast(0L)
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun quarantineCorruptedFile(cause: JSONException) {
        lastLoadError = cause
        val target = storageFile.absoluteFile
        val parent = target.parentFile ?: return
        val backup = File(parent, target.name + BACKUP_FILE_SUFFIX)
        try {
            if (backup.exists()) backup.delete()
            if (!target.renameTo(backup)) {
                target.copyTo(backup, overwrite = true)
                target.delete()
            }
        } catch (e: IOException) {
            lastLoadError = e
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

    private companion object {
        const val FILE_VERSION = 1
        const val TEMP_FILE_SUFFIX = ".tmp"
        const val BACKUP_FILE_SUFFIX = ".bak"

        const val KEY_VERSION = "version"
        const val KEY_TASKS = "tasks"
        const val KEY_ID = "id"
        const val KEY_PROFILE_ID = "profileId"
        const val KEY_SERVER_NAME = "serverName"
        const val KEY_PROTOCOL_LABEL = "protocolLabel"
        const val KEY_DIRECTION = "direction"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_REMOTE_PATH = "remotePath"
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_LOCAL_DISPLAY_NAME = "localDisplayName"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_TRANSFERRED_BYTES = "transferredBytes"
        const val KEY_STATE = "state"
        const val KEY_ERROR = "error"
        const val KEY_BYTES_PER_SECOND = "bytesPerSecond"
        const val KEY_LOCAL_URI = "localUri"
        const val KEY_LOCAL_RELATIVE_DIR = "localRelativeDir"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_RESUMABLE = "resumable"
        const val KEY_PROFILE_SNAPSHOT = "profileSnapshot"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_STARTED_AT = "startedAt"
        const val KEY_FINISHED_AT = "finishedAt"

        const val P_ID = "id"
        const val P_NAME = "name"
        const val P_PROTOCOL = "protocol"
        const val P_HOST = "host"
        const val P_PORT = "port"
        const val P_USERNAME = "username"
        const val P_PASSWORD = "password"
        const val P_INITIAL_PATH = "initialPath"
        const val P_FTPS_MODE = "ftpsMode"
        const val P_PASSIVE_MODE = "passiveMode"
        const val P_ALLOW_INSECURE_TLS = "allowInsecureTls"
    const val P_PINNED_CERT_SHA256 = "pinnedCertificateSha256"
        const val P_LAST_USED_AT = "lastUsedAt"
    }
}

/**
 * 传输历史裁剪（纯 JVM 逻辑，可单元测试）：
 * 已结束任务超过上限时只保留结束时间最近的若干条，未结束任务始终保留且顺序不变。
 */
internal object TransferHistoryLimit {

    const val DEFAULT_MAX_HISTORY = 200

    fun apply(tasks: List<TransferTask>, maxFinished: Int = DEFAULT_MAX_HISTORY): List<TransferTask> {
        if (maxFinished < 0) return tasks
        val finished = tasks.filter { it.isFinished }
        if (finished.size <= maxFinished) return tasks
        val keep = finished
            .sortedByDescending { it.finishedAt }
            .take(maxFinished)
            .mapTo(HashSet()) { it.id }
        return tasks.filter { !it.isFinished || it.id in keep }
    }
}

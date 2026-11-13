package com.manst.lantransfer.data

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.server.LocalServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 本机服务器配置的持久化（记住端口、帐号、协议开关）。 */
interface LocalServerConfigStore {
    fun load(): LocalServerConfig?

    suspend fun save(config: LocalServerConfig)
}

class JsonLocalServerConfigStore(private val storageFile: File) : LocalServerConfigStore {

    private val writeMutex = Mutex()

    override fun load(): LocalServerConfig? {
        return runCatching {
            if (!storageFile.isFile) return@runCatching null
            val root = JSONObject(storageFile.readText(Charsets.UTF_8))
            LocalServerConfig(
                enableFtp = root.optBoolean(KEY_ENABLE_FTP, true),
                enableFtps = root.optBoolean(KEY_ENABLE_FTPS, true),
                enableSftp = root.optBoolean(KEY_ENABLE_SFTP, true),
                enableScp = root.optBoolean(KEY_ENABLE_SCP, true),
                ftpPort = root.optInt(KEY_FTP_PORT, 2121),
                ftpsPort = root.optInt(KEY_FTPS_PORT, 2122),
                sshPort = root.optInt(KEY_SSH_PORT, 2222),
                username = root.optString(KEY_USERNAME, "android"),
                password = root.optString(KEY_PASSWORD, ""),
                ftpsMode = runCatching {
                    FtpsMode.valueOf(root.optString(KEY_FTPS_MODE, FtpsMode.EXPLICIT.name))
                }.getOrDefault(FtpsMode.EXPLICIT)
            )
        }.getOrNull()
    }

    override suspend fun save(config: LocalServerConfig) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val root = JSONObject()
                    .put(KEY_ENABLE_FTP, config.enableFtp)
                    .put(KEY_ENABLE_FTPS, config.enableFtps)
                    .put(KEY_ENABLE_SFTP, config.enableSftp)
                    .put(KEY_ENABLE_SCP, config.enableScp)
                    .put(KEY_FTP_PORT, config.ftpPort)
                    .put(KEY_FTPS_PORT, config.ftpsPort)
                    .put(KEY_SSH_PORT, config.sshPort)
                    .put(KEY_USERNAME, config.username)
                    .put(KEY_PASSWORD, config.password)
                    .put(KEY_FTPS_MODE, config.ftpsMode.name)
                storageFile.parentFile?.mkdirs()
                val temp = File(storageFile.parentFile, storageFile.name + ".tmp")
                temp.writeText(root.toString(), Charsets.UTF_8)
                if (!temp.renameTo(storageFile)) {
                    storageFile.delete()
                    if (!temp.renameTo(storageFile)) {
                        throw IllegalStateException("保存本机服务器配置失败")
                    }
                }
            }
        }
    }

    private companion object {
        const val KEY_ENABLE_FTP = "enableFtp"
        const val KEY_ENABLE_FTPS = "enableFtps"
        const val KEY_ENABLE_SFTP = "enableSftp"
        const val KEY_ENABLE_SCP = "enableScp"
        const val KEY_FTP_PORT = "ftpPort"
        const val KEY_FTPS_PORT = "ftpsPort"
        const val KEY_SSH_PORT = "sshPort"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_FTPS_MODE = "ftpsMode"
    }
}

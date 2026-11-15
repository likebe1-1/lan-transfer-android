package com.manst.lantransfer.quick

import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.ServerProfile
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 快速传文件接收断点续传的分片决策（纯 JVM 逻辑，便于单元测试）。
 *
 * 规则：
 * - 远端大小未知（<=0）时无法校验，整文件重下；
 * - 分片长度恰好等于远端大小时视为已完整，无需再下载（省掉一次网络传输）；
 * - 协议不支持续传（如 SCP）或分片长度非法（<=0 / 超过远端大小）时从 0 开始；
 * - 其余情况从已有分片长度处续传。
 */
internal object QuickPartialPlan {

    data class Decision(val offset: Long, val alreadyComplete: Boolean)

    fun decide(partLength: Long, remoteSize: Long, supportsResume: Boolean): Decision {
        val length = partLength.coerceAtLeast(0L)
        if (remoteSize <= 0L) return Decision(0L, false)
        if (length == remoteSize) return Decision(length, true)
        if (!supportsResume) return Decision(0L, false)
        if (length <= 0L || length > remoteSize) return Decision(0L, false)
        return Decision(length, false)
    }
}

/**
 * 快速传文件接收断点续传的分片文件仓库。
 *
 * 分片放在应用私有缓存目录（默认 cacheDir/quick_partial/<sha256>.part），
 * key 由「协议 + host + 端口 + 远端路径 + 远端大小 + 远端修改时间」哈希得到：
 * 同一个远端文件在多次重试/重新连接后仍能命中同一个分片，而远端文件内容/时间变化后会换新的 key，
 * 避免把不同文件的分片串用。分片只在本 App 的 cacheDir 内，无存储权限问题。
 *
 * 清理策略：成功提交后由调用方删除对应分片；[cleanupExpired] 清理超过 7 天的孤儿分片，
 * 由接收管理器初始化/结束会话时调用，避免分片无限增长。
 */
class QuickPartialStore(
    private val rootDirectory: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** 远端文件对应的分片文件（尚未创建）。 */
    fun partialFile(profile: ServerProfile, entry: RemoteEntry): File {
        val key = buildString {
            append(profile.protocol.name)
            append('|')
            append(profile.host)
            append('|')
            append(profile.port)
            append('|')
            append(entry.path)
            append('|')
            append(entry.size)
            append('|')
            append(entry.modifiedAt)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
        return File(rootDirectory, digest.substring(0, HASH_NAME_LENGTH) + PART_SUFFIX)
    }

    /** 删除某个远端文件对应的分片（成功提交后调用）；返回是否删除了文件。 */
    fun delete(profile: ServerProfile, entry: RemoteEntry): Boolean {
        return try {
            val file = partialFile(profile, entry)
            file.isFile && file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /** 清理超过 [maxAgeMillis]（默认 7 天）没有更新的孤儿分片，返回删除数量。 */
    fun cleanupExpired(maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): Int {
        if (maxAgeMillis <= 0L) return 0
        val deadline = clock() - maxAgeMillis
        val files = try {
            rootDirectory.listFiles() ?: return 0
        } catch (e: Exception) {
            return 0
        }
        var removed = 0
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(PART_SUFFIX)) continue
            if (file.lastModified() >= deadline) continue
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    companion object {
        /** 分片缓存目录名（位于 App cacheDir 下）。 */
        const val DIRECTORY_NAME = "quick_partial"

        /** 孤儿分片保留时长：7 天。 */
        const val DEFAULT_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L

        private const val PART_SUFFIX = ".part"
        private const val HASH_NAME_LENGTH = 32
    }
}

package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 解析 `LC_ALL=C ls -la` 的输出，供 SCP 模式使用（SCP 协议本身没有目录列表能力）。
 *
 * 只接受 Unix 长列表格式，例如：
 * ```
 * total 12
 * drwxr-xr-x 2 tester users 4096 Nov 13 20:45 docs
 * -rw-r--r-- 1 tester users 1234 Nov 13 20:45 report.bin
 * lrwxrwxrwx 1 tester users    7 Jan  2  2025 latest -> docs
 * ```
 * 解析失败的行直接跳过，不打断整个目录的展示。
 */
internal object ScpListingParser {

    private val whitespace = Regex("\\s+")

    private val monthNumbers = mapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4,
        "May" to 5, "Jun" to 6, "Jul" to 7, "Aug" to 8,
        "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12
    )

    fun parse(
        output: String,
        basePath: String,
        now: Long = System.currentTimeMillis()
    ): List<RemoteEntry> {
        val entries = ArrayList<RemoteEntry>()
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val tokens = line.trim().split(whitespace)
            if (tokens.size < 9) continue

            val permissions = tokens[0]
            if (permissions.length != 10) continue
            val typeChar = permissions[0]
            if (typeChar !in "dl-bcps") continue

            val size = tokens[4].toLongOrNull() ?: continue
            val isDirectory = typeChar == 'd'
            val isSymlink = typeChar == 'l'

            val rawName = tokens.drop(8).joinToString(" ")
            val name = if (isSymlink) rawName.substringBefore(" -> ").trim() else rawName.trim()
            if (name.isEmpty() || name == "." || name == "..") continue

            // 时间列无法解析说明该行不是合法的 ls -la 输出，整行跳过
            val modifiedAt = parseModifiedAt(tokens[5], tokens[6], tokens[7], now) ?: continue
            entries.add(
                RemoteEntry(
                    name = name,
                    path = RemotePath.join(basePath, name),
                    isDirectory = isDirectory,
                    isSymlink = isSymlink,
                    size = if (isDirectory) 0L else size,
                    modifiedAt = modifiedAt,
                    permissions = permissions
                )
            )
        }
        return entries
    }

    /** 解析 ls 的三种时间写法："Nov 13 20:45"（近半年）、"Nov 13  2025"（更早）。 */
    private fun parseModifiedAt(monthToken: String, dayToken: String, timeOrYear: String, now: Long): Long? {
        val month = monthNumbers[monthToken] ?: return null
        val day = dayToken.toIntOrNull() ?: return null
        val current = LocalDateTime.now(ZoneId.systemDefault())
        var year = current.year
        var hour = 0
        var minute = 0
        if (timeOrYear.contains(':')) {
            val parts = timeOrYear.split(':')
            if (parts.size != 2) return null
            hour = parts[0].toIntOrNull() ?: return null
            minute = parts[1].toIntOrNull() ?: return null
            // ls 用 "HH:mm" 表示最近半年的文件；若按今年解析落在未来，则属于跨年的旧文件
            val candidate = safeEpoch(year, month, day, hour, minute) ?: return null
            if (candidate > now + DAY_MILLIS) {
                year -= 1
            }
        } else {
            year = timeOrYear.toIntOrNull() ?: return null
        }
        return safeEpoch(year, month, day, hour, minute)
    }

    private fun safeEpoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long? {
        return try {
            LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}

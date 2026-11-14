package com.manst.lantransfer.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI 使用的格式化工具。 */
object Format {
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    /** 人类可读的文件大小，例如 "1.2 MB"。 */
    fun bytes(size: Long): String {
        if (size < 0) return "--"
        if (size < 1024) return "$size B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = size.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, units[unitIndex])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    /** 传输速度，例如 "3.4 MB/s"。 */
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "--"
        return bytes(bytesPerSecond) + "/s"
    }

    /** 按当前速度估算剩余秒数（向上取整）；速度未知/大小未知返回 -1。 */
    fun remainingSeconds(remainingBytes: Long, bytesPerSecond: Long): Long {
        if (remainingBytes < 0L || bytesPerSecond <= 0L) return -1L
        if (remainingBytes == 0L) return 0L
        return (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
    }

    /** 时长文案：如 "45 秒"、"2 分 05 秒"、"1 小时 02 分"；负数返回 "--"。 */
    fun duration(seconds: Long): String {
        if (seconds < 0L) return "--"
        if (seconds < 60L) return "$seconds 秒"
        val minutes = seconds / 60L
        if (minutes < 60L) {
            val rest = seconds % 60L
            return if (rest == 0L) {
                "$minutes 分钟"
            } else {
                "$minutes 分 " + String.format(Locale.US, "%02d", rest) + " 秒"
            }
        }
        val hours = minutes / 60L
        val restMinutes = minutes % 60L
        return if (restMinutes == 0L) {
            "$hours 小时"
        } else {
            "$hours 小时 " + String.format(Locale.US, "%02d", restMinutes) + " 分"
        }
    }

    /** 预计剩余时间文案：如 "剩余约 2 分 05 秒"；为零时 "即将完成"，速度未知时 "剩余时间未知"。 */
    fun remainingTime(remainingBytes: Long, bytesPerSecond: Long): String {
        val seconds = remainingSeconds(remainingBytes, bytesPerSecond)
        return when {
            seconds < 0L -> "剩余时间未知"
            seconds == 0L -> "即将完成"
            else -> "剩余约 " + duration(seconds)
        }
    }

    /** 时间戳（毫秒）-> "yyyy-MM-dd HH:mm"；0 表示未知。 */
    fun date(epochMillis: Long): String {
        if (epochMillis <= 0L) return "--"
        return dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }
}

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

    /** 时间戳（毫秒）-> "yyyy-MM-dd HH:mm"；0 表示未知。 */
    fun date(epochMillis: Long): String {
        if (epochMillis <= 0L) return "--"
        return dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }
}

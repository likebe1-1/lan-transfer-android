package com.manst.lantransfer.core

/**
 * 远端路径工具。所有协议统一使用 "/" 分隔的绝对路径（FTP 的相对家目录路径也在使用时归一化为绝对路径）。
 */
object RemotePath {
    const val ROOT = "/"

    /** 归一化路径：修复重复斜杠、"."、".."，并保证结果是绝对路径。 */
    fun normalize(path: String?): String {
        if (path.isNullOrBlank()) return ROOT
        val raw = path.replace('\\', '/')
        val parts = ArrayList<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        val body = parts.joinToString("/")
        return when {
            body.isEmpty() -> ROOT
            else -> "/" + body
        }
    }

    /** 拼接路径，name 为绝对路径时直接返回归一化结果。 */
    fun join(base: String, name: String): String {
        if (name.startsWith("/")) return normalize(name)
        val normalizedBase = normalize(base)
        val combined = if (normalizedBase == ROOT) "/" + name else normalizedBase + "/" + name
        return normalize(combined)
    }

    /** 父目录；根目录返回 null。 */
    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == ROOT) return null
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) ROOT else normalized.substring(0, index)
    }

    /** 路径最后一段名称；根目录返回 "/"。 */
    fun name(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ROOT
        return normalized.substringAfterLast('/')
    }

    /** 该路径是否是目录路径（以 "/" 结尾，或为根目录）。 */
    fun looksLikeDirectory(path: String?): Boolean {
        if (path.isNullOrBlank()) return true
        val trimmed = path.trim()
        return trimmed == "/" || trimmed.endsWith("/")
    }
}
package com.manst.lantransfer.transfer

import java.util.Locale

/**
 * 本地/远端文件名清洗与重名处理（纯 JVM 逻辑，可单元测试）。
 *
 * 规则：
 * - 去掉首尾空白，把路径分隔符、Windows 非法字符与控制字符替换为下划线；
 * - 去掉结尾的点和空格（部分文件系统不接受）；
 * - 空名、".""、".." 回退为 [FALLBACK_NAME]；
 * - 超长时截断文件名主体并保留扩展名；
 * - 重名时追加 " (1)"、" (2)"……（比较忽略大小写，兼容大小写不敏感的存储）。
 */
object TransferFileNames {

    const val FALLBACK_NAME = "未命名文件"

    /** 文件名最大长度，避免超过常见文件系统限制。 */
    const val MAX_NAME_LENGTH = 120

    private const val MAX_SEQUENCE = 9999
    private const val EXTENSION_MAX_LENGTH = 16

    private val ILLEGAL_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** 清洗文件名；永远返回非空、可作为单个文件名使用的字符串。 */
    fun sanitize(rawName: String?): String {
        val cleaned = buildString {
            for (ch in rawName?.trim().orEmpty()) append(sanitizeChar(ch))
        }.trim().trimEnd('.', ' ')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return FALLBACK_NAME
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

        val dot = cleaned.lastIndexOf('.')
        val keepExtension = dot in 1 until cleaned.length - 1 && cleaned.length - dot <= EXTENSION_MAX_LENGTH
        val extension = if (keepExtension) cleaned.substring(dot) else ""
        val body = if (keepExtension) cleaned.substring(0, dot) else cleaned
        val allowed = (MAX_NAME_LENGTH - extension.length).coerceAtLeast(1)
        val truncated = body.take(allowed).trimEnd('.', ' ')
        return (truncated.ifEmpty { FALLBACK_NAME }) + extension
    }

    /** 在扩展名之前插入序号：photo.jpg + 1 -> photo (1).jpg；无扩展名则直接追加。 */
    fun withSequenceSuffix(name: String, index: Int): String {
        require(index > 0) { "序号必须从 1 开始" }
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length - 1) {
            name.substring(0, dot) + " (" + index + ")" + name.substring(dot)
        } else {
            name + " (" + index + ")"
        }
    }

    /** 在 [existingNames] 中查重后返回可用文件名；比较忽略大小写。 */
    fun uniqueName(rawName: String?, existingNames: Collection<String>): String {
        val base = sanitize(rawName)
        val taken = existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (base.lowercase(Locale.ROOT) !in taken) return base
        for (index in 1..MAX_SEQUENCE) {
            val candidate = withSequenceSuffix(base, index)
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        }
        return withSequenceSuffix(base, MAX_SEQUENCE + 1)
    }

    private fun sanitizeChar(ch: Char): Char = when {
        ch in ILLEGAL_CHARS -> '_'
        ch.code < 0x20 || ch.code == 0x7F -> '_'
        else -> ch
    }
}

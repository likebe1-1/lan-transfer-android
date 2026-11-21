package com.manst.lantransfer.update

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger

/**
 * 更新清单签名校验（Ed25519）。
 *
 * 设计：`update.json` 的规范文本（去掉 `sig` 字段后重新序列化）由发布者私钥签名，
 * App 内置公钥校验。镜像/代理即使篡改清单内容，也无法伪造签名，因此：
 * - 清单来源可以是任意（含第三方镜像）；
 * - 下载的 APK 仍会再校验 SHA-256 与签名（双重保障）。
 *
 * 兼容策略：未签名的旧清单仍可通过（返回 UNSIGNED），但只允许 HTTPS 来源，
 * 并记录日志，便于后续强制签名后再收紧。
 */
object UpdateManifestVerifier {

    /**
     * 发布公钥（Ed25519，X.509 SubjectPublicKeyInfo 的 Base64）。
     * 使用 `scripts/gen_update_signing_key.py` 生成后替换此常量。
     */
    const val PUBLIC_KEY_BASE64: String = "MCowBQYDK2VwAyEA+c6+GXh9FWik1lUYSnb9d95dLSrzGeQy7L52AV9Ftuw="

    enum class Status { VALID, UNSIGNED, INVALID, KEY_MISSING }

    data class Result(val status: Status, val reason: String? = null) {
        val acceptable: Boolean get() = status == Status.VALID || status == Status.UNSIGNED
    }

    /**
     * 校验清单文本。
     * @param jsonText 原始清单文本
     * @param signatureBase64 清单里的签名（缺省时从 JSON 的 "sig" 字段读取）
     */
    fun verify(jsonText: String, signatureBase64: String? = null): Result {
        val json = try {
            JSONObject(jsonText.trim())
        } catch (e: Exception) {
            return Result(Status.INVALID, "清单不是合法 JSON")
        }
        val signatureText = signatureBase64 ?: json.optString("sig", "")
        if (signatureText.isBlank()) {
            return Result(Status.UNSIGNED, "清单未签名")
        }
        if (PUBLIC_KEY_BASE64.startsWith("REPLACE_WITH_")) {
            return Result(Status.KEY_MISSING, "构建未内置更新签名公钥")
        }
        val payload = canonicalPayload(json)
        return try {
            val publicKeyRaw = extractRawPublicKey(Base64.decode(PUBLIC_KEY_BASE64, Base64.NO_WRAP))
            val signature = Base64.decode(signatureText, Base64.NO_WRAP)
            val ok = Ed25519.verify(publicKeyRaw, payload.toByteArray(Charsets.UTF_8), signature)
            if (ok) Result(Status.VALID) else Result(Status.INVALID, "清单签名不匹配（可能被篡改）")
        } catch (e: Exception) {
            Result(Status.INVALID, "校验签名失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 从 X.509 SubjectPublicKeyInfo 中取出 32 字节原始公钥。
     * 结构：30 2a 30 05 06 03 2b 65 70 03 21 00 || 32 字节公钥（共 44 字节）
     */
    fun extractRawPublicKey(spki: ByteArray): ByteArray {
        require(spki.size == 44) { "公钥长度异常：" + spki.size }
        return spki.copyOfRange(12, 44)
    }

    /**
     * 规范化载荷：与签名端（scripts/canonical_manifest.py）完全一致的规则——
     * 递归删除所有 "sig" 键、对象键按字典序排序、紧凑 JSON（无空格）、保留非 ASCII 原字符。
     */
    fun canonicalPayload(json: JSONObject): String = buildString { appendCanonical(json, this) }

    private fun appendCanonical(value: Any?, builder: StringBuilder) {
        when (value) {
            null, JSONObject.NULL -> builder.append("null")
            is JSONObject -> {
                val keys = value.keys().asSequence().filter { it != "sig" }.sorted()
                builder.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) builder.append(',')
                    appendJsonString(key, builder)
                    builder.append(':')
                    appendCanonical(value.opt(key), builder)
                }
                builder.append('}')
            }
            is org.json.JSONArray -> {
                builder.append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) builder.append(',')
                    appendCanonical(value.opt(index), builder)
                }
                builder.append(']')
            }
            is Boolean -> builder.append(value.toString())
            is Int, is Long, is Double, is Float -> builder.append(value.toString())
            else -> appendJsonString(value.toString(), builder)
        }
    }

    private fun appendJsonString(text: String, builder: StringBuilder) {
        builder.append('"')
        for (char in text) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (char < ' ') {
                        builder.append(String.format("\\u%04x", char.code))
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        builder.append('"')
    }
}

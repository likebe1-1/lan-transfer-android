package com.manst.lantransfer.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * 多源拉取 update.json（jsDelivr / GitHub raw / ghproxy 镜像依次尝试），
 * 第一个成功且能解析出可用 APK 的来源生效。纯 JVM 实现，便于用本地 HTTP 服务做单元测试。
 */
object UpdateManifestFetcher {

    /** 可注入的日志函数（默认 no-op，App 侧可设置为 android.util.Log，单测中保持安静）。 */
    @Volatile
    var logger: ((String) -> Unit)? = null

    private fun warn(message: String) {
        logger?.invoke(message)
    }

    private const val MAX_MANIFEST_BYTES = 512 * 1024

    /**
     * 依次尝试各来源，返回第一个「可解析且签名可接受」的清单。
     *
     * 安全策略：
     * - 只接受 HTTPS 来源（防止明文链路被篡改）；
     * - 已签名清单必须验签通过；
     * - 未签名清单仅在 [allowUnsigned] 为 true 时接受（过渡期，默认关闭）。
     */
    fun fetchFirst(
        urls: List<String>,
        supportedAbis: List<String>,
        connectTimeoutMillis: Int = 6_000,
        readTimeoutMillis: Int = 8_000,
        allowUnsigned: Boolean = false,
        /** 仅测试使用：允许 HTTP 来源（生产路径强制 HTTPS）。 */
        allowNonHttps: Boolean = false
    ): UpdateInfo? {
        for (url in urls) {
            if (!allowNonHttps && !isHttps(url)) {
                warn("跳过非 HTTPS 更新源：" + url)
                continue
            }
            val text = fetchText(url, connectTimeoutMillis, readTimeoutMillis) ?: continue
            val verification = UpdateManifestVerifier.verify(text)
            if (verification.status == UpdateManifestVerifier.Status.INVALID) {
                warn("更新清单签名无效，已拒绝：" + url + "（" + verification.reason + "）")
                continue
            }
            if (verification.status != UpdateManifestVerifier.Status.VALID && !allowUnsigned) {
                warn("更新清单未签名，已跳过：" + url + "（" + verification.reason + "）")
                continue
            }
            val info = UpdateManifestParser.parse(text, supportedAbis)
            if (info != null) return info
        }
        return null
    }

    /** 仅允许 HTTPS（更新清单必须走加密链路）。 */
    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    fun fetchText(
        url: String,
        connectTimeoutMillis: Int = 6_000,
        readTimeoutMillis: Int = 8_000
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LanLink-Updater/1.0")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (total < MAX_MANIFEST_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES - total))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

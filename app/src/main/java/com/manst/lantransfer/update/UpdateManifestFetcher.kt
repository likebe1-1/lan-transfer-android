package com.manst.lantransfer.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * 多源拉取 update.json（jsDelivr / GitHub raw / ghproxy 镜像依次尝试），
 * 第一个成功且能解析出可用 APK 的来源生效。纯 JVM 实现，便于用本地 HTTP 服务做单元测试。
 */
object UpdateManifestFetcher {

    private const val MAX_MANIFEST_BYTES = 512 * 1024

    fun fetchFirst(
        urls: List<String>,
        supportedAbis: List<String>,
        connectTimeoutMillis: Int = 6_000,
        readTimeoutMillis: Int = 8_000
    ): UpdateInfo? {
        for (url in urls) {
            val text = fetchText(url, connectTimeoutMillis, readTimeoutMillis) ?: continue
            val info = UpdateManifestParser.parse(text, supportedAbis)
            if (info != null) return info
        }
        return null
    }

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

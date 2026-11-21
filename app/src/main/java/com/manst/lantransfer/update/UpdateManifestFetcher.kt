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
        allowNonHttps: Boolean = false,
        /** 每次失败的可读原因（用于界面显示与日志排查）。 */
        onDiagnostic: ((String) -> Unit)? = null
    ): UpdateInfo? {
        for (url in urls) {
            if (!allowNonHttps && !isHttps(url)) {
                report(onDiagnostic, url, "非 HTTPS 来源，已跳过")
                continue
            }
            val text = try {
                fetchTextOrThrow(url, connectTimeoutMillis, readTimeoutMillis)
            } catch (e: Exception) {
                report(onDiagnostic, url, describeFailure(e))
                continue
            }
            val verification = UpdateManifestVerifier.verify(text)
            if (verification.status == UpdateManifestVerifier.Status.INVALID) {
                report(onDiagnostic, url, "清单签名无效（" + verification.reason + "）")
                continue
            }
            if (verification.status != UpdateManifestVerifier.Status.VALID && !allowUnsigned) {
                report(onDiagnostic, url, "清单未签名，已跳过")
                continue
            }
            val info = UpdateManifestParser.parse(text, supportedAbis)
            if (info != null) return info
            report(onDiagnostic, url, "清单格式无法解析或缺少可用 APK")
        }
        return null
    }

    private fun report(onDiagnostic: ((String) -> Unit)?, url: String, reason: String) {
        val host = url.substringAfter("://", url).substringBefore('/')
        warn(host + "：" + reason)
        onDiagnostic?.invoke(host + "：" + reason)
    }

    /** 把异常翻译成用户可理解的原因（DNS / TLS / 超时 / HTTP）。 */
    fun describeFailure(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "域名解析失败（DNS/网络不可用）"
        is java.net.SocketTimeoutException -> "连接或读取超时"
        is javax.net.ssl.SSLException -> "TLS 握手失败（证书/中间人）"
        is java.net.ConnectException -> "连接被拒绝（网络不可达或被拦截）"
        is HttpStatusException -> "服务器返回 HTTP " + error.code
        else -> "请求失败：" + (error.message ?: error.javaClass.simpleName)
    }

    class HttpStatusException(val code: Int) : java.io.IOException("HTTP " + code)

    /** 仅允许 HTTPS（更新清单必须走加密链路）。 */
    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    fun fetchText(
        url: String,
        connectTimeoutMillis: Int = 6_000,
        readTimeoutMillis: Int = 8_000
    ): String? = try {
        fetchTextOrThrow(url, connectTimeoutMillis, readTimeoutMillis)
    } catch (e: Exception) {
        null
    }

    private fun fetchTextOrThrow(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int
    ): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LanLink-Updater/1.0")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw HttpStatusException(connection.responseCode)
            }
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
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection?.disconnect()
        }
    }
}

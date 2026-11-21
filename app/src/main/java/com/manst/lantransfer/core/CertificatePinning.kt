package com.manst.lantransfer.core

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * FTPS 证书固定（certificate pinning）。
 *
 * 快速传文件的 FTPS 服务端使用**运行时自签名证书**（无 CA），无法走系统信任链；
 * 但发送方可以把证书的 SHA-256 指纹通过二维码/局域网广播告知接收方，
 * 接收方只接受指纹完全一致的服务端证书——既保留自签证书的可用性，
 * 又避免"信任任意证书"被中间人利用（中间人无法出示相同指纹的证书，除非持有私钥）。
 */
object CertificatePinning {

    /** 证书 DER 编码的 SHA-256（小写十六进制）。 */
    fun sha256Hex(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    /** 规范化指纹文本（去空格/冒号、转小写）。 */
    fun normalizeFingerprint(text: String?): String =
        text.orEmpty().replace(":", "").replace(" ", "").trim().lowercase(Locale.US)

    /** 是否为合法的 SHA-256 指纹（64 位十六进制）。 */
    fun isValidFingerprint(text: String?): Boolean =
        normalizeFingerprint(text).matches(Regex("[0-9a-f]{64}"))

    /** 构造只信任指定指纹证书的 TrustManager（校验证书链第一张 = 服务端证书）。 */
    fun createTrustManager(pinnedFingerprint: String): X509TrustManager {
        val expected = normalizeFingerprint(pinnedFingerprint)
        require(isValidFingerprint(expected)) { "证书指纹格式错误，应为 64 位十六进制" }
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull()
                    ?: throw CertificateException("服务端未提供证书")
                val actual = sha256Hex(certificate)
                if (actual != expected) {
                    throw CertificateException(
                        "服务端证书指纹不匹配（可能是中间人）：期望 " + expected.take(16) + "…，实际 " + actual.take(16) + "…"
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    /** 便捷封装：构造只信任该指纹的 SSLContext。 */
    fun createSslContext(pinnedFingerprint: String): javax.net.ssl.SSLContext =
        javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(createTrustManager(pinnedFingerprint)), java.security.SecureRandom())
        }
}

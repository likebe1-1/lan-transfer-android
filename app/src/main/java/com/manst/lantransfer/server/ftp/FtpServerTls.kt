package com.manst.lantransfer.server.ftp

import com.manst.lantransfer.server.LanAddress
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.LinkedHashSet
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * FTPS 服务端 TLS 支撑（纯 JVM）。
 *
 * - 运行时用 BouncyCastle 现场生成 RSA 2048 自签名证书，SAN 覆盖本机全部 IPv4 地址与
 *   `localhost`，因此不需要把私钥/证书硬编码进 APK；
 * - 证书与 [SSLContext] 在进程内只生成一次并缓存，FTP 与 FTPS 两个服务端实例共享；
 * - 同一套逻辑同时服务于显式 TLS（AUTH TLS 升级控制连接）与隐式 TLS（accept 后立即握手），
 *   以及 PROT P 时的被动数据通道加密；
 * - 不把 BouncyCastle 注册为 JCA Provider：Android 内置的是旧版、被重打包的 BC，注册会冲突；
 *   这里只使用 BouncyCastle 的 ASN.1/证书构建 API，实际 SHA256withRSA 签名走平台默认实现。
 */
internal object FtpServerTls {

    private const val CERT_ALIAS = "lantransfer-ftps"
    private const val KEY_STORE_PASSWORD = "lantransfer-ftps"
    private const val RSA_KEY_SIZE = 2048
    private const val CN = "LanTransfer Local Server"
    private val CERT_VALIDITY: Duration = Duration.ofDays(3650)

    @Volatile
    private var cachedContext: SSLContext? = null

    @Volatile
    private var cachedCertificate: X509Certificate? = null

    /** 进程内共享的 [SSLContext]；首次调用时生成自签名证书（后续调用直接命中缓存）。 */
    fun sharedContext(): SSLContext {
        val existing = cachedContext
        if (existing != null) return existing
        synchronized(this) {
            val cached = cachedContext
            if (cached != null) return cached
            val created = createContext()
            cachedContext = created
            return created
        }
    }

    /** 当前自签名证书（首次生成后缓存）；用于把指纹通过二维码/广播告知接收方，实现证书固定。 */
    fun certificate(): X509Certificate {
        sharedContext()
        return requireNotNull(cachedCertificate) { "FTPS 证书尚未生成" }
    }

    /** 证书 SHA-256 指纹（小写十六进制），接收方据此固定服务端证书，避免信任任意证书。 */
    fun certificateSha256Hex(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate().encoded)
        return digest.joinToString("") { byte -> "%02x".format(java.util.Locale.US, byte) }
    }

    private fun createContext(): SSLContext {
        val now = Instant.now()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSA_KEY_SIZE, SecureRandom())
        }.generateKeyPair()

        val subject = X500Name("CN=$CN, O=LanTransfer, C=CN")
        val serialNumber = BigInteger(64, SecureRandom()).abs() + BigInteger.ONE
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(CERT_VALIDITY)),
            subject,
            keyPair.public
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            )
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(subjectAlternativeNames().toTypedArray())
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate: X509Certificate =
            JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer))
        cachedCertificate = certificate

        val password = KEY_STORE_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(CERT_ALIAS, keyPair.private, password, arrayOf(certificate))
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
    }

    /** 自签名证书的 SAN：本机全部 IPv4 + 127.0.0.1 + localhost（+ 本机主机名）。 */
    private fun subjectAlternativeNames(): List<GeneralName> {
        val ipAddresses = LinkedHashSet<String>()
        ipAddresses.add(LOOPBACK_IPV4)
        ipAddresses.addAll(LanAddress.localIpv4Addresses())
        val names = ArrayList<GeneralName>(ipAddresses.size + 2)
        ipAddresses.forEach { names.add(GeneralName(GeneralName.iPAddress, it)) }
        names.add(GeneralName(GeneralName.dNSName, "localhost"))
        localHostName()?.let { names.add(GeneralName(GeneralName.dNSName, it)) }
        return names
    }

    private fun localHostName(): String? = try {
        val hostName = InetAddress.getLocalHost().hostName
        hostName?.takeIf { name ->
            name.isNotBlank() &&
                !name.equals("localhost", ignoreCase = true) &&
                // SAN 的 DNS 名使用 IA5String，只接受 ASCII 且不含空白
                name.all { it.code in 33..126 } &&
                name.contains('.')
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 把已接受的明文 [raw] 包装成服务端模式 [SSLSocket] 并完成握手。
     *
     * 返回的套接字接管原始连接的关闭（autoClose），握手失败时关闭底层连接；
     * soTimeout 沿用调用方在 [raw] 上设置的值（TLS 握手同样受该超时约束）。
     */
    fun layerServerSocket(context: SSLContext, raw: Socket): SSLSocket {
        val ssl = try {
            val host = raw.inetAddress?.hostAddress ?: LOOPBACK_IPV4
            context.socketFactory.createSocket(raw, host, raw.port, true) as SSLSocket
        } catch (e: IOException) {
            closeQuietly(raw)
            throw e
        }
        try {
            ssl.useClientMode = false
            ssl.needClientAuth = false
            ssl.wantClientAuth = false
            // 显式启用 TLS 1.2/1.3（两者交集，避免个别设备不支持 TLS 1.3 时设置失败）
            val supported = ssl.supportedProtocols.orEmpty().toSet()
            val enabled = SUPPORTED_TLS_PROTOCOLS.filter { it in supported }
            if (enabled.isNotEmpty()) ssl.enabledProtocols = enabled.toTypedArray()
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            closeQuietly(ssl)
            throw if (e is IOException) e else IOException("TLS 握手失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // 关闭失败不影响调用方
        }
    }

    private const val LOOPBACK_IPV4 = "127.0.0.1"
    private val SUPPORTED_TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
}

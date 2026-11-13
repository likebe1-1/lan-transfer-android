package com.manst.lantransfer.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 测试用 TLS 支撑：用 BouncyCastle 现场生成自签名服务端证书，构造 FTPS 测试服务端的 SSLContext。
 * 证书 CN/SAN 覆盖 127.0.0.1 与 localhost，便于验证严格校验（allowInsecureTls=false）路径。
 */
internal object TestTlsSupport {

    private val keyStorePassword = charArrayOf('l', 'a', 'n', 't', 'e', 's', 't')

    fun serverContext(): SSLContext {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = Instant.now()
        val subject = X500Name("CN=127.0.0.1, O=LanTransfer Test, C=CN")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(3650, ChronoUnit.DAYS)),
            subject,
            keyPair.public
        )
        certificateBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        certificateBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        certificateBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(
                arrayOf(
                    GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    GeneralName(GeneralName.dNSName, "localhost")
                )
            )
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certificateBuilder.build(signer))
        certificate.verify(keyPair.public)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("ftps-test", keyPair.private, keyStorePassword, arrayOf(certificate))
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, keyStorePassword)

        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
    }
}

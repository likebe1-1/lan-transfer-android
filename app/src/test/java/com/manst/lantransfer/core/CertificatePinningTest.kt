package com.manst.lantransfer.core

import com.manst.lantransfer.server.ftp.FtpServerTls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException

/**
 * FTPS 证书固定：用本机服务器实际生成的自签名证书验证指纹计算与 TrustManager 行为，
 * 确保「快速传文件」不再依赖「信任任意证书」。
 */
class CertificatePinningTest {

    @Test
    fun serverCertificateFingerprint_isStableAndWellFormed() {
        val cert = FtpServerTls.certificate()
        val fingerprint = FtpServerTls.certificateSha256Hex()
        assertEquals(64, fingerprint.length)
        assertTrue("指纹必须是 64 位十六进制：" + fingerprint, CertificatePinning.isValidFingerprint(fingerprint))
        assertEquals(fingerprint, CertificatePinning.sha256Hex(cert))
        // 证书在进程内缓存，多次读取指纹必须一致
        assertEquals(fingerprint, FtpServerTls.certificateSha256Hex())
    }

    @Test
    fun trustManager_acceptsMatchingCertificate() {
        val cert = FtpServerTls.certificate()
        val pin = FtpServerTls.certificateSha256Hex()
        val trustManager = CertificatePinning.createTrustManager(pin)
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun trustManager_rejectsDifferentCertificate() {
        val cert = FtpServerTls.certificate()
        // 用一个不同的合法指纹（翻转最后一位），模拟中间人证书
        val realPin = FtpServerTls.certificateSha256Hex()
        val wrongPin = realPin.dropLast(1) + if (realPin.last() == 'a') "b" else "a"
        val trustManager = CertificatePinning.createTrustManager(wrongPin)
        var rejected = false
        try {
            trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        } catch (e: CertificateException) {
            rejected = true
        }
        assertTrue("指纹不匹配时必须拒绝证书", rejected)
    }

    @Test
    fun invalidFingerprint_isRejectedEarly() {
        assertFalse(CertificatePinning.isValidFingerprint(null))
        assertFalse(CertificatePinning.isValidFingerprint("abc"))
        assertFalse(CertificatePinning.isValidFingerprint("zz".repeat(32)))
        var thrown = false
        try {
            CertificatePinning.createTrustManager("not-a-fingerprint")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}

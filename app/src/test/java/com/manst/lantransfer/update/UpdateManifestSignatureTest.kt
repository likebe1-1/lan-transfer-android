package com.manst.lantransfer.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 用真实发布密钥验证「签名端(Python) ↔ 验签端(Kotlin canonicalPayload)」一致性。
 *
 * 注意：公钥与 update.json 的实际签名由发布流程保证；这里校验签名结构、
 * 以及 canonicalPayload 与 Python 端规则的等价性（对固定样例逐字节比较）。
 */
class UpdateManifestSignatureTest {

    private val publicKeyB64 = "MCowBQYDK2VwAyEA+c6+GXh9FWik1lUYSnb9d95dLSrzGeQy7L52AV9Ftuw="

    @Test
    fun publishedManifestSignature_matchesCanonicalPayload() {
        // 与 docs/update.json 的签名对应；若发布密钥轮换需同步更新此处样例
        val signatureB64 = SAMPLE_SIG
        val manifest = SAMPLE_MANIFEST
        val json = JSONObject(manifest)
        json.put("sig", signatureB64)
        val key = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(UpdateManifestVerifier.canonicalPayload(json).toByteArray(Charsets.UTF_8))
        assertEquals(true, verifier.verify(Base64.getDecoder().decode(signatureB64)))
    }

    private companion object {
        const val SAMPLE_MANIFEST = "{\"versionCode\":11,\"versionName\":\"2.3.0\"}"
        // 由 scripts/sign_update_manifest.py 用发布密钥对上述样例签名生成
        const val SAMPLE_SIG = "IBL6jxn0QxUXAKwK7OJRWj3g+KZu9GzJePVGyT/ySKZbzZCgeJ9pkVq+BDDc4RzBbCMHulZDQMO0YvsaP8aPAg=="
    }
}

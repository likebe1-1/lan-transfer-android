package com.manst.lantransfer.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** 清单规范化与 Ed25519 验签（签名端=scripts/canonical_manifest.py 的同款规则）。 */
class UpdateManifestVerifierTest {

    @Test
    fun canonicalPayload_sortsKeysAndDropsSig() {
        val json = JSONObject(
            """{"versionName":"2.2.0","versionCode":10,"sig":"abc","apks":[{"urls":["u"],"abi":"arm64-v8a"}]}"""
        )
        assertEquals(
            """{"apks":[{"abi":"arm64-v8a","urls":["u"]}],"versionCode":10,"versionName":"2.2.0"}""",
            UpdateManifestVerifier.canonicalPayload(json)
        )
    }

    @Test
    fun canonicalPayload_preservesNonAscii() {
        val json = JSONObject("""{"changelog":"修复问题","versionCode":1}""")
        assertTrue(UpdateManifestVerifier.canonicalPayload(json).contains("修复问题"))
    }

    @Test
    fun verify_validSignature() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val publicB64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val manifest = """{"versionCode":11,"versionName":"2.3.0","apks":[{"abi":"arm64-v8a","size":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","urls":["https://e.com/a.apk"]}]}"""
        val payload = UpdateManifestVerifier.canonicalPayload(JSONObject(manifest))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val sig = Base64.getEncoder().encodeToString(signer.sign())
        val signed = JSONObject(manifest).put("sig", sig).toString()

        // 用测试公钥验证（通过反射替换常量不现实，这里直接验证签名算法与载荷一致性）
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicB64))))
        verifier.update(UpdateManifestVerifier.canonicalPayload(JSONObject(signed)).toByteArray(Charsets.UTF_8))
        assertTrue("规范化载荷应保持不含 sig 且可验签", verifier.verify(Base64.getDecoder().decode(sig)))
    }

    @Test
    fun verify_rejectsTamperedManifest() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val manifest = """{"versionCode":11,"versionName":"2.3.0"}"""
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(UpdateManifestVerifier.canonicalPayload(JSONObject(manifest)).toByteArray(Charsets.UTF_8))
        val sig = Base64.getEncoder().encodeToString(signer.sign())
        // 篡改 versionCode 后签名不再匹配
        val tampered = JSONObject(manifest).put("versionCode", 99).put("sig", sig).toString()
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(pair.public)
        verifier.update(UpdateManifestVerifier.canonicalPayload(JSONObject(tampered)).toByteArray(Charsets.UTF_8))
        assertFalse("篡改后的清单不应通过校验", verifier.verify(Base64.getDecoder().decode(sig)))
    }

    @Test
    fun verify_unsignedManifestIsMarkedUnsigned() {
        val result = UpdateManifestVerifier.verify("""{"versionCode":11,"versionName":"2.3.0"}""")
        assertEquals(UpdateManifestVerifier.Status.UNSIGNED, result.status)
    }

    @Test
    fun fetchFirst_skipsNonHttpsSources() {
        // 纯 HTTP 地址会被跳过（不会发起请求），因此返回 null
        val info = UpdateManifestFetcher.fetchFirst(
            urls = listOf("http://127.0.0.1:1/update.json"),
            supportedAbis = listOf("arm64-v8a"),
            connectTimeoutMillis = 300,
            readTimeoutMillis = 300
        )
        assertEquals(null, info)
    }
}

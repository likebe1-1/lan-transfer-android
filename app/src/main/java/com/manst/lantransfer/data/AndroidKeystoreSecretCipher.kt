package com.manst.lantransfer.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore 的 AES-256-GCM 凭据加密。
 *
 * 设计要点：
 * - 密钥生成后保存在 Keystore（硬件级/TEE 优先），**不可导出**，即使应用私有目录被拷走也无法解密；
 * - 每条密文随机 12 字节 IV，格式：Base64(IV || ciphertext || tag)，前缀 `enc:v1:`；
 * - 解密失败（例如恢复备份、密钥失效）时返回空串，让用户重新输入密码，而不是崩溃；
 * - 加密失败（极端情况）时退回明文并记录日志，保证「保存服务器」不因安全组件异常而永久失败。
 */
class AndroidKeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SecretCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            SecretCipher.PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "凭据加密失败，退回明文保存（请检查设备 Keystore 状态）", e)
            plaintext
        }
    }

    override fun decrypt(stored: String): String {
        if (!SecretCipher.isEncrypted(stored)) return stored
        return try {
            val payload = Base64.decode(stored.removePrefix(SecretCipher.PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_SIZE) { "密文长度异常" }
            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "凭据解密失败（密钥可能已失效），将要求重新输入密码", e)
            ""
        }
    }

    private companion object {
        const val TAG = "SecretCipher"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "lanlink_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

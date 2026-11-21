package com.manst.lantransfer.data

import java.util.Base64

/** 单测用可逆 cipher：格式与生产实现一致（前缀 + Base64），但不依赖 Android Keystore。 */
object TestSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        val reversed = plaintext.reversed().toByteArray(Charsets.UTF_8)
        return SecretCipher.PREFIX + Base64.getEncoder().encodeToString(reversed)
    }

    override fun decrypt(stored: String): String {
        if (!SecretCipher.isEncrypted(stored)) return stored
        val bytes = Base64.getDecoder().decode(stored.removePrefix(SecretCipher.PREFIX))
        return String(bytes, Charsets.UTF_8).reversed()
    }
}

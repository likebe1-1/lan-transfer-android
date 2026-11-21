package com.manst.lantransfer.data

/**
 * 凭据加密接口：用于把服务器密码等敏感字段以密文形式落盘。
 *
 * 生产实现见 [AndroidKeystoreSecretCipher]（密钥保存在 Android Keystore，不可导出）。
 * JVM 单测默认使用 [PlaintextSecretCipher]，避免依赖 Android Keystore。
 */
interface SecretCipher {

    /** 加密（返回带前缀的密文；空串原样返回）。失败时应抛出异常，由调用方决定降级策略。 */
    fun encrypt(plaintext: String): String

    /** 解密；若输入不是本实现产生的密文（例如历史明文），应原样返回，便于平滑迁移。 */
    fun decrypt(stored: String): String

    companion object {
        /** 密文前缀：用于识别是否需要解密（同时便于后续算法升级）。 */
        const val PREFIX = "enc:v1:"

        /** 判断字符串是否为本模块产生的密文。 */
        fun isEncrypted(text: String): Boolean = text.startsWith(PREFIX)
    }
}

/** 测试/降级用：不做任何转换（仅用于 JVM 单测或 Keystore 不可用时）。 */
object PlaintextSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(stored: String): String = stored
}

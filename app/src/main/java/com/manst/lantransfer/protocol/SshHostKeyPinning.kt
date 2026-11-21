package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.CertificatePinning
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Locale

/**
 * SSH 主机密钥固定（host key pinning）。
 *
 * 快速传文件的 SSH 服务端使用运行时生成的主机密钥（无 CA）；
 * 发送方把主机密钥指纹随二维码/广播下发，接收方只接受指纹一致的主机密钥，
 * 从而避免「首次信任」被同网段中间人冒充。
 *
 * 指纹算法：SHA-256(SSH 公钥线格式 blob)，小写十六进制。
 * JSch 的 [HostKey.key] 正是该线格式 blob，因此收发两端可直接比对。
 */
object SshHostKeyPinning {

    /** 计算 JSch 主机密钥指纹（与发送方广告值一致）。 */
    fun sha256Hex(keyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    fun normalizeFingerprint(text: String?): String =
        CertificatePinning.normalizeFingerprint(text)

    fun isValidFingerprint(text: String?): Boolean = CertificatePinning.isValidFingerprint(text)

    /** 只接受指定主机密钥指纹的 [HostKeyRepository]；不匹配时 JSch 会抛 HostKeyException。 */
    class PinnedRepository(expectedFingerprint: String) : HostKeyRepository {
        private val expected = normalizeFingerprint(expectedFingerprint)

        init {
            require(isValidFingerprint(expected)) { "SSH 主机密钥指纹格式错误，应为 64 位十六进制" }
        }

        override fun check(host: String, key: ByteArray): Int =
            if (sha256Hex(key) == expected) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

        override fun remove(host: String?, type: String?) = Unit

        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

        override fun getKnownHostsRepositoryID(): String = "lanlink-pinned"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}

package com.manst.lantransfer.quick

import com.manst.lantransfer.core.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuickTransferDefaults.PROTOCOL_PREFERENCE] 的 JVM 单元测试：
 * 自动回退顺序必须是「速度优先」的 FTPS → SFTP → SCP，且永远不包含明文 FTP。
 *
 * 安全性不变的前提（见 [com.manst.lantransfer.quick.DefaultQuickReceiveManager]）：
 * FTPS 目标的证书指纹由发送方广播下发并固定（target.ftpsFingerprintSha256 →
 * ServerProfile.pinnedCertificateSha256）；旧版本对端没有指纹时才退化为 allowInsecureTls 并提示。
 */
class QuickTransferDefaultsTest {

    @Test
    fun protocolPreference_isFtpsThenSftpThenScp() {
        assertEquals(
            "自动回退顺序必须是 FTPS → SFTP → SCP（FTPS 实测更快且已做证书指纹固定）",
            listOf(TransferProtocol.FTPS, TransferProtocol.SFTP, TransferProtocol.SCP),
            QuickTransferDefaults.PROTOCOL_PREFERENCE
        )
    }

    @Test
    fun protocolPreference_startsWithFtpsForSpeed() {
        assertEquals(TransferProtocol.FTPS, QuickTransferDefaults.PROTOCOL_PREFERENCE.first())
    }

    @Test
    fun protocolPreference_neverContainsPlainFtp() {
        assertFalse(
            "明文 FTP 会把接收码派生的临时密码以明文发到局域网，不能参与自动回退",
            QuickTransferDefaults.PROTOCOL_PREFERENCE.contains(TransferProtocol.FTP)
        )
        assertTrue(
            "自动回退只使用加密协议",
            QuickTransferDefaults.PROTOCOL_PREFERENCE.all { it != TransferProtocol.FTP }
        )
    }

    @Test
    fun protocolPreference_isExactlyTheEncryptedProtocols() {
        assertEquals(
            QuickTransferDefaults.PROTOCOLS.filter { it != TransferProtocol.FTP }.toSet(),
            QuickTransferDefaults.PROTOCOL_PREFERENCE.toSet()
        )
        assertEquals(
            "自动回退不能重复尝试同一协议",
            QuickTransferDefaults.PROTOCOL_PREFERENCE.size,
            QuickTransferDefaults.PROTOCOL_PREFERENCE.toSet().size
        )
        assertEquals(3, QuickTransferDefaults.PROTOCOL_PREFERENCE.size)
    }

    @Test
    fun protocolPreference_entriesAreAdvertisedBySender() {
        // 发送端在广播里广告四种协议；自动回退只能从中挑选，否则必然连接失败
        assertTrue(
            QuickTransferDefaults.PROTOCOLS.containsAll(QuickTransferDefaults.PROTOCOL_PREFERENCE)
        )
    }

    @Test
    fun ftps_isStillAutoAttemptedWhenLegacyPeerHasNoFingerprint() {
        // 旧版本发送方不广播 FTPS 证书指纹：此时连接目标指纹为 null，
        // DefaultQuickReceiveManager 仍会尝试 FTPS（createProfile 里 allowInsecureTls = 指纹为空），
        // 界面会提示「仅加密不认证」。这里守住「FTPS 仍在自动回退里 + 默认指纹可为空」两个前提。
        val legacyTarget = QuickJoinTarget(code = "12345678", host = "192.168.1.20")
        assertNull(legacyTarget.ftpsFingerprintSha256)
        assertNull(legacyTarget.sshHostKeySha256)
        assertEquals(TransferProtocol.FTPS, QuickTransferDefaults.PROTOCOL_PREFERENCE.first())
        assertTrue(QuickTransferDefaults.PROTOCOL_PREFERENCE.contains(TransferProtocol.FTPS))
    }
}

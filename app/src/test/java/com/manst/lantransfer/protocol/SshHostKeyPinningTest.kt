package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.SshHostKeyProbe
import com.manst.lantransfer.server.ServerBinding
import com.manst.lantransfer.server.ssh.SshProtocolServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * SFTP/SCP 主机密钥固定端到端（纯 JVM）：
 * 发送方探测到的指纹写入接收方 profile 后，只能连上同一台 SSH 服务端；
 * 指纹不匹配（模拟中间人）必须连接失败。
 */
class SshHostKeyPinningTest {

    private lateinit var shareRoot: File
    private lateinit var hostKey: File
    private lateinit var server: SshProtocolServer
    private var port: Int = 0

    @Before
    fun setUp() {
        shareRoot = Files.createTempDirectory("ssh-pin-share").toFile()
        File(shareRoot, "hello.txt").writeText("pinned ssh", Charsets.UTF_8)
        hostKey = File(Files.createTempDirectory("ssh-hostkey").toFile(), "hostkey")
        port = ServerSocket(0).use { it.localPort }
        server = SshProtocolServer(hostKey)
        runBlocking { server.start(ServerBinding(port, shareRoot, "pinner", "pin-pass"), enableSftp = true, enableScp = true) }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { server.stop() } }
        shareRoot.deleteRecursively()
        hostKey.parentFile?.deleteRecursively()
    }

    private fun profile(pin: String?): ServerProfile = ServerProfile(
        name = "ssh-pin",
        protocol = TransferProtocol.SFTP,
        host = "127.0.0.1",
        port = port,
        username = "pinner",
        password = "pin-pass",
        pinnedSshHostKeySha256 = pin
    )

    @Test
    fun probeReturnsStableFingerprint() {
        val first = SshHostKeyProbe.fingerprintSha256(port = port, username = "pinner", password = "pin-pass")
        val second = SshHostKeyProbe.fingerprintSha256(port = port, username = "pinner", password = "pin-pass")
        assertNotNull("应能探测到主机密钥指纹", first)
        assertTrue("指纹应为 64 位十六进制：" + first, SshHostKeyPinning.isValidFingerprint(first))
        assertTrue("同一服务端指纹应稳定", first == second)
    }

    @Test
    fun matchingFingerprint_connects() = runBlocking {
        val pin = SshHostKeyProbe.fingerprintSha256(port = port, username = "pinner", password = "pin-pass")
        assertNotNull(pin)
        val client = RemoteFileSystems.create(profile(pin))
        client.use {
            it.connect()
            assertTrue(it.list("/").any { entry -> entry.name == "hello.txt" })
        }
    }

    @Test
    fun wrongFingerprint_isRejected() = runBlocking {
        val real = SshHostKeyProbe.fingerprintSha256(port = port, username = "pinner", password = "pin-pass")!!
        val wrong = real.dropLast(1) + if (real.last() == 'a') "b" else "a"
        val client = RemoteFileSystems.create(profile(wrong))
        var failed = false
        try {
            client.use { it.connect() }
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("主机密钥指纹不匹配时必须连接失败", failed)
    }

    @Test
    fun pinnedRepository_rejectsUnknownKey() {
        val repo = SshHostKeyPinning.PinnedRepository("ab".repeat(32))
        assertFalse(SshHostKeyPinning.isValidFingerprint(null))
        assertTrue(repo.check("127.0.0.1", ByteArray(32)) == com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED)
    }
}

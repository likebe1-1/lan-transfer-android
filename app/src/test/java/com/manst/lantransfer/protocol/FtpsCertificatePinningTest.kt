package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.server.ServerBinding
import com.manst.lantransfer.server.ftp.FtpProtocolServer
import com.manst.lantransfer.server.ftp.FtpServerTls
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * FTPS 证书固定端到端（纯 JVM）：
 * 服务端使用运行时自签名证书，客户端只信任「发送方广告的指纹」。
 * - 指纹正确 → 能连接并列为文件；
 * - 指纹错误 → TLS 握手/证书校验失败，不能连接。
 */
class FtpsCertificatePinningTest {

    private lateinit var shareRoot: File
    private lateinit var server: FtpProtocolServer
    private var port: Int = 0

    @Before
    fun setUp() {
        shareRoot = Files.createTempDirectory("ftps-pin-share").toFile()
        File(shareRoot, "hello.txt").writeText("pinned ftps", Charsets.UTF_8)
        port = ServerSocket(0).use { it.localPort }
        server = FtpProtocolServer(TransferProtocol.FTPS)
        runBlocking {
            server.start(
                ServerBinding(
                    port = port,
                    shareRoot = shareRoot,
                    username = "pinner",
                    password = "pin-pass",
                    ftpsMode = FtpsMode.EXPLICIT
                )
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { server.stop() } }
        shareRoot.deleteRecursively()
    }

    private fun profile(pin: String?): ServerProfile = ServerProfile(
        name = "ftps-pin",
        protocol = TransferProtocol.FTPS,
        host = "127.0.0.1",
        port = port,
        username = "pinner",
        password = "pin-pass",
        ftpsMode = FtpsMode.EXPLICIT,
        allowInsecureTls = pin == null,
        pinnedCertificateSha256 = pin
    )

    @Test
    fun matchingFingerprint_connectsAndLists() = runBlocking {
        val pin = FtpServerTls.certificateSha256Hex()
        val client = RemoteFileSystems.create(profile(pin))
        client.use {
            it.connect()
            val entries = it.list("/")
            assertTrue("应列出 hello.txt：$entries", entries.any { entry -> entry.name == "hello.txt" })
        }
    }

    @Test
    fun wrongFingerprint_isRejected() = runBlocking {
        val realPin = FtpServerTls.certificateSha256Hex()
        val wrongPin = realPin.dropLast(1) + if (realPin.last() == 'a') "b" else "a"
        val client = RemoteFileSystems.create(profile(wrongPin))
        var failed = false
        try {
            client.use { it.connect() }
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("指纹不匹配时必须连接失败，而不是静默降级", failed)
    }
}

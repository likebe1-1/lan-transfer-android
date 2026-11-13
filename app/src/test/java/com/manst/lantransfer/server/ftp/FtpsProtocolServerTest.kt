package com.manst.lantransfer.server.ftp

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.FtpRemoteFileSystem
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLSocket

/**
 * FTPS 服务端真实走线测试：分别以显式 TLS（AUTH TLS）与隐式 TLS（accept 后立即握手）启动
 * [FtpProtocolServer]，用项目自带 [FtpRemoteFileSystem]（allowInsecureTls=true）完成
 * 登录、列表、上传下载与错误映射；并用原生 [FTPSClient] 断言 PROT C 明文数据通道、
 * 自签名证书的 SAN/TLS 版本，以及证书在进程内只生成一次。
 */
class FtpsProtocolServerTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private lateinit var shareRoot: TestShareRoot
    private var server: RunningFtpServer? = null
    private lateinit var bigBytes: ByteArray
    private lateinit var uploadBytes: ByteArray

    @Before
    fun setUp() {
        shareRoot = TestShareRoot()
        bigBytes = FtpTestSupport.randomBytes(1024 * 1024 + 321, 404L)
        uploadBytes = FtpTestSupport.randomBytes(512 * 1024 + 17, 505L)
        shareRoot.writeText("hello.txt", "hello FTPS 世界")
        shareRoot.write("empty.bin", ByteArray(0))
        shareRoot.write("big.bin", bigBytes)
        shareRoot.writeText("文档.txt", "中文文件名")
        shareRoot.mkdir("docs")
        shareRoot.writeText("docs/readme.md", "readme")
        shareRoot.mkdir("emptydir")
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        shareRoot.close()
    }

    private fun start(mode: FtpsMode): RunningFtpServer =
        RunningFtpServer(TransferProtocol.FTPS, shareRoot.root, mode).start().also { server = it }

    private fun profile(
        runningServer: RunningFtpServer,
        mode: FtpsMode,
        password: String = TEST_FTP_PASSWORD,
        allowInsecureTls: Boolean = true
    ): ServerProfile = testServerProfile(
        protocol = TransferProtocol.FTPS,
        port = runningServer.port,
        ftpsMode = mode,
        password = password,
        allowInsecureTls = allowInsecureTls
    )

    // ------------------------------------------------------------ 显式 TLS

    @Test
    fun explicitTlsListDownloadUploadAndDirectoryOperations() = runBlocking {
        val runningServer = start(FtpsMode.EXPLICIT)
        withFtpClient(profile(runningServer, FtpsMode.EXPLICIT)) { client ->
            val byName = client.list("/").associateBy { it.name }
            assertEquals(expectedRootNames, byName.keys)
            assertTrue(byName.getValue("docs").isDirectory)

            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            client.download("/big.bin", sink) { progress.add(it) }
            assertArrayEquals(bigBytes, sink.toByteArray())
            assertEquals(bigBytes.size.toLong(), progress.last())

            client.upload(
                ByteArrayInputStream(uploadBytes),
                "/tls-upload.bin",
                uploadBytes.size.toLong()
            )
            assertArrayEquals(uploadBytes, shareRoot.file("tls-upload.bin").readBytes())

            client.mkdir("/tls-dir")
            client.rename("/tls-upload.bin", "/tls-dir/moved.bin")
            assertTrue(shareRoot.file("tls-dir/moved.bin").isFile)
            client.delete("/tls-dir/moved.bin", isDirectory = false)
            client.delete("/tls-dir", isDirectory = true)
            assertFalse(shareRoot.file("tls-dir").exists())
        }
    }

    @Test
    fun explicitTlsDownloadsUtf8FileName() = runBlocking {
        val runningServer = start(FtpsMode.EXPLICIT)
        withFtpClient(profile(runningServer, FtpsMode.EXPLICIT)) { client ->
            val sink = ByteArrayOutputStream()
            client.download("/文档.txt", sink)
            assertEquals("中文文件名", String(sink.toByteArray(), StandardCharsets.UTF_8))
            assertEquals(listOf("readme.md"), client.list("/docs").map { it.name })
            assertTrue(client.list("/emptydir").isEmpty())
        }
    }

    @Test
    fun explicitTlsWrongPasswordMapsToAuthenticationException() = runBlocking {
        val runningServer = start(FtpsMode.EXPLICIT)
        val error = FtpRemoteFileSystem(
            profile(runningServer, FtpsMode.EXPLICIT, password = "wrong-password")
        ).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("错误密码应抛 AuthenticationException，实际：$error", error is AuthenticationException)
    }

    @Test
    fun selfSignedCertificateInStrictModeIsRejected() = runBlocking {
        val runningServer = start(FtpsMode.EXPLICIT)
        val error = FtpRemoteFileSystem(
            profile(runningServer, FtpsMode.EXPLICIT, allowInsecureTls = false)
        ).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("严格校验下自签证书应连接失败，实际：$error", error is ConnectionException)
    }

    @Test
    fun explicitTlsProtCAllowsClearDataChannel() {
        val runningServer = start(FtpsMode.EXPLICIT)
        val ftps = FTPSClient(false, FtpTestSupport.trustAllSslContext())
        try {
            // 与 App 客户端一致：控制/列表数据统一 UTF-8
            ftps.controlEncoding = "UTF-8"
            ftps.connect("127.0.0.1", runningServer.port)
            assertTrue("FTPS 连接应成功：${ftps.replyString}", FTPReply.isPositiveCompletion(ftps.replyCode))
            assertTrue("登录应成功：${ftps.replyString}", ftps.login(TEST_FTP_USER, TEST_FTP_PASSWORD))
            ftps.execPBSZ(0L)
            ftps.execPROT("C")
            ftps.enterLocalPassiveMode()
            ftps.setFileType(FTP.BINARY_FILE_TYPE)

            val files = ftps.listFiles("/")?.filterNotNull().orEmpty()
            assertTrue("PROT C 下 LIST 应可用", files.any { it.name == "docs" && it.isDirectory })
            assertEquals(expectedRootNames, files.map { it.name }.toSet())

            val names = ftps.listNames("/")?.toSet() ?: error("PROT C 下 NLST 失败：${ftps.replyString}")
            assertEquals(expectedRootNames, names)

            val sink = ByteArrayOutputStream()
            assertTrue("PROT C 下 RETR 应成功：${ftps.replyString}", ftps.retrieveFile("/hello.txt", sink))
            assertEquals("hello FTPS 世界", String(sink.toByteArray(), StandardCharsets.UTF_8))
        } finally {
            runCatching { ftps.disconnect() }
        }
    }

    // ------------------------------------------------------------ 隐式 TLS

    @Test
    fun implicitTlsListDownloadUpload() = runBlocking {
        val runningServer = start(FtpsMode.IMPLICIT)
        withFtpClient(profile(runningServer, FtpsMode.IMPLICIT)) { client ->
            val byName = client.list("/").associateBy { it.name }
            assertEquals(expectedRootNames, byName.keys)

            val sink = ByteArrayOutputStream()
            client.download("/big.bin", sink)
            assertArrayEquals(bigBytes, sink.toByteArray())

            client.upload(
                ByteArrayInputStream(uploadBytes),
                "/implicit-upload.bin",
                uploadBytes.size.toLong()
            )
            assertArrayEquals(uploadBytes, shareRoot.file("implicit-upload.bin").readBytes())

            client.mkdir("/implicit-dir")
            client.rename("/implicit-upload.bin", "/implicit-dir/moved.bin")
            client.delete("/implicit-dir/moved.bin", isDirectory = false)
            client.delete("/implicit-dir", isDirectory = true)
        }
    }

    @Test
    fun implicitTlsWrongPasswordMapsToAuthenticationException() = runBlocking {
        val runningServer = start(FtpsMode.IMPLICIT)
        val error = FtpRemoteFileSystem(
            profile(runningServer, FtpsMode.IMPLICIT, password = "wrong-password")
        ).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("错误密码应抛 AuthenticationException，实际：$error", error is AuthenticationException)
    }

    // ------------------------------------------------------------ 自签名证书

    @Test
    fun selfSignedCertificateHasLanTransferSubjectAndLocalSan() {
        val runningServer = start(FtpsMode.IMPLICIT)
        fetchServerCertificate(runningServer.port).use { sslSocket ->
            val certificate = sslSocket.session.peerCertificates.first() as X509Certificate
            assertEquals("RSA", certificate.publicKey.algorithm)
            assertNotNull(certificate.subjectX500Principal)
            assertTrue(
                "证书 CN 应包含 LanTransfer：${certificate.subjectX500Principal.name}",
                certificate.subjectX500Principal.name.contains("LanTransfer")
            )
            val sanValues = certificate.subjectAlternativeNames.orEmpty()
                .mapNotNull { entry -> entry.getOrNull(1)?.toString() }
            assertTrue("SAN 应包含 127.0.0.1：$sanValues", sanValues.contains("127.0.0.1"))
            assertTrue("SAN 应包含 localhost：$sanValues", sanValues.contains("localhost"))
            assertTrue("证书应已生效", certificate.notBefore.before(Date()))
            assertTrue("证书应未过期", certificate.notAfter.after(Date()))
            val protocol = sslSocket.session.protocol
            assertTrue("TLS 版本应为 1.2/1.3，实际：$protocol", protocol == "TLSv1.2" || protocol == "TLSv1.3")
        }
    }

    @Test
    fun selfSignedCertificateIsCachedAcrossServerInstances() {
        val firstServer = start(FtpsMode.IMPLICIT)
        val firstCertificate = fetchServerCertificate(firstServer.port).use { sslSocket ->
            val certificate = sslSocket.session.peerCertificates.first() as X509Certificate
            certificate.encoded
        }
        firstServer.close()
        server = null

        val secondServer = start(FtpsMode.IMPLICIT)
        val secondCertificate = fetchServerCertificate(secondServer.port).use { sslSocket ->
            val certificate = sslSocket.session.peerCertificates.first() as X509Certificate
            certificate.encoded
        }
        assertArrayEquals("自签名证书应在进程内只生成一次", firstCertificate, secondCertificate)
    }

    private companion object {
        val expectedRootNames = setOf(
            "hello.txt",
            "empty.bin",
            "big.bin",
            "文档.txt",
            "docs",
            "emptydir"
        )
    }
}

package com.manst.lantransfer.server.ftp

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.FtpRemoteFileSystem
import com.manst.lantransfer.server.ServerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * FTP 服务端真实走线测试：在 127.0.0.1 随机端口启动真实的 [FtpProtocolServer]，
 * 用项目自带的 [FtpRemoteFileSystem] 客户端（与 App 中完全同一实现）完成列表、下载、上传、
 * 目录操作与错误映射；另外用裸 Socket 与 Commons Net FTPClient 断言报文细节
 * （PASV/EPSV、Unix 长格式 LIST、NLST、SIZE/MDTM、路径穿越 550、未知命令 502）。
 */
class FtpProtocolServerTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private lateinit var shareRoot: TestShareRoot
    private lateinit var server: RunningFtpServer
    private lateinit var bigBytes: ByteArray
    private lateinit var uploadBytes: ByteArray

    @Before
    fun setUp() {
        shareRoot = TestShareRoot()
        bigBytes = FtpTestSupport.randomBytes(2 * 1024 * 1024 + 123, 101L)
        uploadBytes = FtpTestSupport.randomBytes(1024 * 1024 + 77, 202L)
        shareRoot.writeText("hello.txt", "hello FTP 世界")
        shareRoot.write("empty.bin", ByteArray(0))
        shareRoot.write("big.bin", bigBytes)
        shareRoot.writeText("文档 说明.txt", "中文文件名")
        shareRoot.mkdir("docs")
        shareRoot.writeText("docs/readme.md", "readme")
        shareRoot.mkdir("emptydir")
        server = RunningFtpServer(TransferProtocol.FTP, shareRoot.root).start()
    }

    @After
    fun tearDown() {
        server.close()
        shareRoot.close()
    }

    private fun profile(port: Int = server.port) =
        testServerProfile(TransferProtocol.FTP, port)

    // ------------------------------------------------------------ 真实客户端走线

    @Test
    fun clientListsRootAndSubdirectories() = runBlocking {
        withFtpClient(profile()) { client ->
            val entries = client.list("/")
            val byName = entries.associateBy { it.name }
            assertEquals(expectedRootNames, byName.keys)

            val docs = byName.getValue("docs")
            assertTrue("docs 应为目录", docs.isDirectory)
            assertEquals("/docs", docs.path)
            assertFalse("emptydir 应为目录", byName.getValue("emptydir").isFile)

            val big = byName.getValue("big.bin")
            assertTrue(big.isFile)
            assertEquals(bigBytes.size.toLong(), big.size)
            assertTrue("列表应带回修改时间", big.modifiedAt > 0L)

            val utf8 = byName.getValue("文档 说明.txt")
            assertEquals("/文档 说明.txt", utf8.path)
            assertEquals(FtpTestSupport.utf8("中文文件名").size.toLong(), utf8.size)

            assertEquals(listOf("readme.md"), client.list("/docs").map { it.name })
            assertTrue("空目录应返回空列表", client.list("/emptydir").isEmpty())
        }
    }

    @Test
    fun clientDownloadsLargeFileByteIdenticalAndReportsProgress() = runBlocking {
        withFtpClient(profile()) { client ->
            val sink = ByteArrayOutputStream()
            val progress = ArrayList<Long>()
            client.download("/big.bin", sink) { progress.add(it) }
            assertArrayEquals(bigBytes, sink.toByteArray())
            assertTrue("应至少回调一次进度", progress.isNotEmpty())
            assertEquals(bigBytes.size.toLong(), progress.last())
            assertTrue("进度必须单调不减", progress.zipWithNext().all { (a, b) -> b >= a })

            val emptySink = ByteArrayOutputStream()
            client.download("/empty.bin", emptySink)
            assertEquals(0, emptySink.size())

            val textSink = ByteArrayOutputStream()
            client.download("/文档 说明.txt", textSink)
            assertEquals("中文文件名", String(textSink.toByteArray(), StandardCharsets.UTF_8))
        }
    }

    @Test
    fun clientUploadsByteIdenticalAndReplacesExistingFile() = runBlocking {
        withFtpClient(profile()) { client ->
            val progress = ArrayList<Long>()
            client.upload(
                ByteArrayInputStream(uploadBytes),
                "/uploaded.bin",
                uploadBytes.size.toLong()
            ) { progress.add(it) }
            assertArrayEquals(uploadBytes, shareRoot.file("uploaded.bin").readBytes())
            assertEquals(uploadBytes.size.toLong(), progress.last())

            // 覆盖已有文件：先写临时文件再原子替换
            val replacement = FtpTestSupport.randomBytes(64 * 1024 + 5, 303L)
            client.upload(ByteArrayInputStream(replacement), "/uploaded.bin")
            assertArrayEquals(replacement, shareRoot.file("uploaded.bin").readBytes())

            // 上传到子目录、以及带 UTF-8 的文件名
            client.upload(ByteArrayInputStream(uploadBytes), "/docs/子目录上传.bin")
            assertArrayEquals(uploadBytes, shareRoot.file("docs/子目录上传.bin").readBytes())

            // 不残留临时文件
            val leftovers = shareRoot.root.listFiles().orEmpty()
                .filter { it.name.startsWith(".lantransfer-upload-") }
            assertTrue("上传结束后不应残留临时文件：$leftovers", leftovers.isEmpty())
        }
    }

    @Test
    fun clientMkdirRenameDeleteWork() = runBlocking {
        withFtpClient(profile()) { client ->
            client.mkdir("/newdir")
            assertTrue(shareRoot.file("newdir").isDirectory)
            // 已存在目录按客户端契约应视为成功（MKD 550 后客户端用 CWD 探测）
            client.mkdir("/newdir")

            client.rename("/hello.txt", "/newdir/hello.txt")
            assertFalse(shareRoot.file("hello.txt").exists())
            assertTrue(shareRoot.file("newdir/hello.txt").isFile)

            client.delete("/newdir/hello.txt", isDirectory = false)
            assertFalse(shareRoot.file("newdir/hello.txt").exists())
            client.delete("/newdir", isDirectory = true)
            assertFalse(shareRoot.file("newdir").exists())
        }
    }

    @Test
    fun clientErrorsMapToLanTransferExceptions() = runBlocking {
        withFtpClient(profile()) { client ->
            val missingDownload = runCatching {
                client.download("/missing.bin", ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue("下载不存在文件应抛 RemotePathException，实际：$missingDownload", missingDownload is RemotePathException)

            val missingList = runCatching { client.list("/missing-dir") }.exceptionOrNull()
            assertTrue("列出不存在目录应抛 RemotePathException，实际：$missingList", missingList is RemotePathException)

            val missingUpload = runCatching {
                client.upload(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "/missing-dir/x.bin")
            }.exceptionOrNull()
            assertTrue("上传到不存在目录应抛 RemotePathException，实际：$missingUpload", missingUpload is RemotePathException)

            val missingDelete = runCatching {
                client.delete("/missing.bin", isDirectory = false)
            }.exceptionOrNull()
            assertTrue("删除不存在文件应抛 RemotePathException，实际：$missingDelete", missingDelete is RemotePathException)

            val deleteDirectoryAsFile = runCatching {
                client.delete("/docs", isDirectory = false)
            }.exceptionOrNull()
            assertTrue("用 DELE 删除目录应失败，实际：$deleteDirectoryAsFile", deleteDirectoryAsFile is RemotePathException)
        }

        val authError = FtpRemoteFileSystem(profile().copy(password = "wrong-password")).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertTrue("错误密码应抛 AuthenticationException，实际：$authError", authError is AuthenticationException)
    }

    @Test
    fun concurrentClientsTransferIndependently() = runBlocking {
        val results = coroutineScope {
            (0 until 3).map {
                async(Dispatchers.IO) {
                    withFtpClient(profile()) { client ->
                        val sink = ByteArrayOutputStream()
                        client.download("/big.bin", sink) { }
                        val names = client.list("/").map { entry -> entry.name }.toSet()
                        sink.toByteArray() to names
                    }
                }
            }.awaitAll()
        }
        results.forEach { (bytes, names) ->
            assertArrayEquals(bigBytes, bytes)
            assertEquals(expectedRootNames, names)
        }
    }

    // ------------------------------------------------------------ 裸 Socket / Commons Net 报文断言

    @Test
    fun rawProtocolResponsesAndPathTraversal() {
        RawFtpClient(port = server.port).use { raw ->
            val greeting = raw.readReply()
            assertNotNull(greeting)
            assertTrue("问候语应为 220：$greeting", greeting!!.startsWith("220"))

            assertEquals(215, raw.replyCode(raw.command("SYST")))
            val features = raw.command("FEAT")
            assertTrue("FEAT 应为多行 211：$features", features.startsWith("211-") && features.endsWith("211 End"))
            assertTrue("FEAT 应声明 UTF8：$features", features.contains(" UTF8"))
            assertTrue("FEAT 应声明 MLSD：$features", features.contains("MLSD"))
            assertEquals(200, raw.replyCode(raw.command("OPTS UTF8 ON")))
            assertEquals(502, raw.replyCode(raw.command("AUTH TLS")))

            raw.login()
            assertEquals(257, raw.replyCode(raw.command("PWD")))
            assertEquals(200, raw.replyCode(raw.command("TYPE I")))
            assertEquals(200, raw.replyCode(raw.command("NOOP")))

            // 合法相对路径仍可归一化
            assertEquals(250, raw.replyCode(raw.command("CWD /docs")))
            assertEquals(250, raw.replyCode(raw.command("CDUP")))
            assertEquals(257, raw.replyCode(raw.command("PWD")))

            // 路径穿越：必须回 550，且不能触碰共享目录之外
            assertEquals(550, raw.replyCode(raw.command("CWD ../..")))
            assertEquals(550, raw.replyCode(raw.command("CWD /../etc")))
            assertEquals(550, raw.replyCode(raw.command("CWD /docs/../../etc")))
            assertEquals(550, raw.replyCode(raw.command("RETR ../../etc/passwd")))
            assertEquals(550, raw.replyCode(raw.command("SIZE ..")))
            assertEquals(550, raw.replyCode(raw.command("DELE ../hello.txt")))

            // 未知命令与不存在路径
            assertEquals(502, raw.replyCode(raw.command("FOOBAR")))
            assertEquals(550, raw.replyCode(raw.command("SIZE /missing.bin")))
            assertEquals(550, raw.replyCode(raw.command("MDTM /missing.bin")))
            assertEquals(550, raw.replyCode(raw.command("DELE /missing.bin")))
            assertEquals(550, raw.replyCode(raw.command("RMD /missing")))
            assertEquals(550, raw.replyCode(raw.command("RNFR /missing")))
            assertEquals(550, raw.replyCode(raw.command("MKD /docs")))

            // ABOR：无传输时 225，有挂起的 PASV 时 226
            assertEquals(225, raw.replyCode(raw.command("ABOR")))
            raw.pasv()
            assertEquals(226, raw.replyCode(raw.command("ABOR")))
            // FileZilla 兼容：CLNT 不报错
            assertEquals(200, raw.replyCode(raw.command("CLNT FileZilla")))

            // 重命名往返
            assertEquals(350, raw.replyCode(raw.command("RNFR /hello.txt")))
            assertEquals(250, raw.replyCode(raw.command("RNTO /docs/hello-renamed.txt")))
            assertEquals(350, raw.replyCode(raw.command("RNFR /docs/hello-renamed.txt")))
            assertEquals(250, raw.replyCode(raw.command("RNTO /hello.txt")))

            assertEquals(221, raw.replyCode(raw.command("QUIT")))
        }

        // 未登录会话：数据命令与需要登录的命令回 530，FTPS 命令回 502
        RawFtpClient(port = server.port).use { raw ->
            raw.readReply()
            assertEquals(530, raw.replyCode(raw.command("LIST")))
            assertEquals(530, raw.replyCode(raw.command("PASV")))
            assertEquals(530, raw.replyCode(raw.command("PWD")))
            assertEquals(502, raw.replyCode(raw.command("PROT P")))
            assertEquals(502, raw.replyCode(raw.command("PBSZ 0")))
        }
    }

    @Test
    fun rawPassiveListNlstAndEpsv() {
        RawFtpClient(port = server.port).use { raw ->
            raw.readReply()
            raw.login()

            // 227 中的 IP 应使用控制连接的本地地址（本机回环即 127.0.0.1），端口可连接
            val passive = raw.pasv()
            assertEquals("127.0.0.1", passive.host)
            assertTrue("PASV 端口应有效：${passive.port}", passive.port in 1..65535)

            val listing = raw.dataCommand("LIST /")
            assertEquals(150, listing.firstCode)
            assertEquals(226, raw.replyCode(listing.lastReply ?: error("LIST 没有最终应答")))
            assertEquals(expectedRootNames.size, listing.lines.size)
            assertTrue(listing.lines.any { it.startsWith("-rw-r--r--") && it.endsWith(" big.bin") })
            assertTrue(listing.lines.any { it.startsWith("drwxr-xr-x") && it.endsWith(" docs") })
            assertTrue("LIST 应包含 UTF-8 文件名", listing.lines.any { it.endsWith(" 文档 说明.txt") })

            val emptyListing = raw.dataCommand("LIST /emptydir")
            assertEquals(150, emptyListing.firstCode)
            assertEquals(226, raw.replyCode(emptyListing.lastReply ?: error("LIST 没有最终应答")))
            assertTrue("空目录应返回空数据", emptyListing.lines.isEmpty())

            val nlst = raw.dataCommand("NLST /")
            assertEquals(150, nlst.firstCode)
            assertEquals(226, raw.replyCode(nlst.lastReply ?: error("NLST 没有最终应答")))
            assertEquals(expectedRootNames, nlst.lines.toSet())

            val missing = raw.dataCommand("LIST /missing-dir")
            assertEquals(550, missing.firstCode)

            // EPSV 数据连接
            val epsvPort = raw.epsv()
            val dataSocket = Socket().apply {
                soTimeout = 20_000
                connect(InetSocketAddress("127.0.0.1", epsvPort), 5_000)
            }
            try {
                raw.sendLine("NLST /")
                assertEquals(150, raw.replyCode(raw.readReply() ?: error("NLST 没有 150")))
                val epsvNames = DataTransferResult.splitListing(
                    String(dataSocket.getInputStream().readBytes(), StandardCharsets.UTF_8)
                ).toSet()
                assertEquals(expectedRootNames, epsvNames)
                assertEquals(226, raw.replyCode(raw.readReply() ?: error("NLST 没有 226")))
            } finally {
                runCatching { dataSocket.close() }
            }
        }

        // 未先 PASV/EPSV 直接 LIST
        RawFtpClient(port = server.port).use { raw ->
            raw.readReply()
            raw.login()
            assertEquals(425, raw.replyCode(raw.command("LIST /")))
        }
    }

    @Test
    fun commonsNetParsesUnixListNlstSizeAndMdtm() {
        val client = FTPClient()
        try {
            client.controlEncoding = "UTF-8"
            client.connect("127.0.0.1", server.port)
            assertTrue("连接应成功：${client.replyString}", FTPReply.isPositiveCompletion(client.replyCode))
            assertTrue("登录应成功：${client.replyString}", client.login(TEST_FTP_USER, TEST_FTP_PASSWORD))
            assertTrue(client.setFileType(FTP.BINARY_FILE_TYPE))
            client.enterLocalPassiveMode()

            val files = client.listFiles("/")?.filterNotNull().orEmpty()
            val byName = files.associateBy { it.name }
            assertEquals(expectedRootNames, byName.keys)

            val big = byName.getValue("big.bin")
            assertTrue(big.isFile)
            assertEquals(bigBytes.size.toLong(), big.size)
            assertTrue("Unix LIST 应解析出修改时间", (big.timestamp?.timeInMillis ?: 0L) > 0L)
            assertEquals("-rw-r--r--", big.rawListing?.substring(0, 10))

            val docs = byName.getValue("docs")
            assertTrue(docs.isDirectory)
            assertEquals("drwxr-xr-x", docs.rawListing?.substring(0, 10))

            assertTrue("Unix LIST 应解析出 UTF-8 文件名", byName.containsKey("文档 说明.txt"))

            val names = client.listNames("/")?.toSet() ?: error("NLST 失败：${client.replyString}")
            assertEquals(expectedRootNames, names)

            // FTP.size(String) 返回的是应答码 213；文件大小用 getSize 取 213 应答的载荷
            assertEquals(213, client.size("/big.bin"))
            assertEquals(bigBytes.size.toString(), client.getSize("/big.bin"))
            val mdtm = client.getModificationTime("/big.bin")
            assertNotNull("MDTM 应返回时间戳", mdtm)
            assertTrue("MDTM 应为 yyyyMMddHHmmss：$mdtm", mdtm!!.matches(Regex("\\d{14}")))
            val expectedMillis = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(mdtm)!!.time
            val actualMillis = shareRoot.file("big.bin").lastModified()
            assertTrue(
                "MDTM 时间与文件 mtime 不一致：$mdtm vs $actualMillis",
                abs(expectedMillis - actualMillis) < 2_000L
            )

            assertNull(client.getSize("/missing.bin"))
            assertEquals(550, client.replyCode)
            assertNull(client.getModificationTime("/missing.bin"))
            assertEquals(550, client.replyCode)
            assertNull(client.listNames("/missing-dir"))
            assertEquals(550, client.replyCode)

            // MLSD（App 客户端 list() 优先走这个命令）
            val mlist = client.mlistDir("/")?.associateBy { it.name } ?: error("MLSD 失败：${client.replyString}")
            assertEquals(expectedRootNames, mlist.keys)
            assertEquals(bigBytes.size.toLong(), mlist.getValue("big.bin").size)
            assertTrue(mlist.getValue("docs").isDirectory)
            assertTrue((mlist.getValue("big.bin").timestamp?.timeInMillis ?: 0L) > 0L)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun stopReleasesPortAndServerCanRestart() = runBlocking {
        val standalonePort = FtpTestSupport.freeTcpPort()
        val standalone = FtpProtocolServer(TransferProtocol.FTP)
        standalone.start(ServerBinding(standalonePort, shareRoot.root, TEST_FTP_USER, TEST_FTP_PASSWORD))
        withFtpClient(testServerProfile(TransferProtocol.FTP, standalonePort)) { client ->
            assertTrue(client.list("/").any { it.name == "hello.txt" })
        }

        standalone.stop()
        standalone.stop() // 幂等

        // 端口已释放：可以重新绑定
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            probe.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), standalonePort))
        }

        // 停止后无法再连接
        val connectError = FtpRemoteFileSystem(
            testServerProfile(TransferProtocol.FTP, standalonePort)
        ).use { client ->
            runCatching { client.connect() }.exceptionOrNull()
        }
        assertNotNull("停止后应无法连接", connectError)

        // 同一实例可以重新启动
        standalone.start(ServerBinding(standalonePort, shareRoot.root, TEST_FTP_USER, TEST_FTP_PASSWORD))
        withFtpClient(testServerProfile(TransferProtocol.FTP, standalonePort)) { client ->
            assertTrue(client.list("/").any { it.name == "docs" })
        }
        standalone.stop()
    }

    private companion object {
        val expectedRootNames = setOf(
            "hello.txt",
            "empty.bin",
            "big.bin",
            "文档 说明.txt",
            "docs",
            "emptydir"
        )
    }
}

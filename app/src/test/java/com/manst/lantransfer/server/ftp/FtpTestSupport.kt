package com.manst.lantransfer.server.ftp

import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.FtpRemoteFileSystem
import com.manst.lantransfer.server.ServerBinding
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Random
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * FTP/FTPS 服务端 JVM 测试的公共支撑：
 * - [TestShareRoot] 临时共享目录；
 * - [RunningFtpServer] 在 127.0.0.1 的随机空闲端口上启动真实的 [FtpProtocolServer]；
 * - [RawFtpClient] 裸 TCP 控制连接（用于断言应答码、LIST/NLST 数据格式与路径穿越拒绝）；
 * - [trustAllSslContext] 与 App 客户端 allowInsecureTls=true 等价的 TLS 上下文。
 */
internal const val TEST_FTP_USER = "tester"
internal const val TEST_FTP_PASSWORD = "s3cret"

internal object FtpTestSupport {

    fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    fun randomBytes(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    fun trustAllSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }
}

/** 临时共享目录，测试结束递归删除。 */
internal class TestShareRoot : AutoCloseable {
    val root: File = Files.createTempDirectory("lantransfer-ftp-server-").toFile()

    fun write(relativePath: String, content: ByteArray): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
        return file
    }

    fun writeText(relativePath: String, content: String): File = write(relativePath, FtpTestSupport.utf8(content))

    fun mkdir(relativePath: String): File = File(root, relativePath).apply { mkdirs() }

    fun file(relativePath: String): File = File(root, relativePath)

    override fun close() {
        runCatching {
            root.walkBottomUp().toList().forEach { path ->
                runCatching { path.delete() }
            }
        }
    }
}

/** 真实启动的 FTP/FTPS 服务端，随机空闲端口 + 127.0.0.1。 */
internal class RunningFtpServer(
    private val protocol: TransferProtocol,
    private val shareRoot: File,
    private val ftpsMode: FtpsMode = FtpsMode.EXPLICIT,
    private val username: String = TEST_FTP_USER,
    private val password: String = TEST_FTP_PASSWORD
) : AutoCloseable {

    val port: Int = FtpTestSupport.freeTcpPort()

    private val server = FtpProtocolServer(protocol)
    private var started = false

    fun start(): RunningFtpServer {
        runBlocking { server.start(ServerBinding(port, shareRoot, username, password, ftpsMode)) }
        started = true
        return this
    }

    override fun close() {
        if (!started) return
        started = false
        runBlocking { server.stop() }
    }
}

internal fun testServerProfile(
    protocol: TransferProtocol,
    port: Int,
    ftpsMode: FtpsMode = FtpsMode.EXPLICIT,
    password: String = TEST_FTP_PASSWORD,
    allowInsecureTls: Boolean = true,
    passiveMode: Boolean = true
): ServerProfile = ServerProfile(
    name = "local-ftp-server-test",
    protocol = protocol,
    host = "127.0.0.1",
    port = port,
    username = TEST_FTP_USER,
    password = password,
    ftpsMode = ftpsMode,
    passiveMode = passiveMode,
    allowInsecureTls = allowInsecureTls
)

/** 用项目自带的真实客户端连接并执行 block，结束后关闭连接（block 的返回值原样带回）。 */
internal suspend fun <T> withFtpClient(
    profile: ServerProfile,
    block: suspend (FtpRemoteFileSystem) -> T
): T {
    FtpRemoteFileSystem(profile).use { client ->
        client.connect()
        return block(client)
    }
}

internal data class PassiveModeReply(val host: String, val port: Int)

internal data class DataTransferResult(
    val firstCode: Int,
    val firstReply: String,
    val data: ByteArray,
    val lastReply: String?
) {
    val lines: List<String> get() = splitListing(String(data, StandardCharsets.UTF_8))

    companion object {
        fun splitListing(text: String): List<String> =
            text.split("\r\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }
}

/**
 * 裸 TCP FTP 控制连接（不升级 TLS），用于断言真实报文细节。
 */
internal class RawFtpClient(
    private val host: String = "127.0.0.1",
    port: Int
) : AutoCloseable {

    private val socket = Socket().apply {
        soTimeout = RAW_TIMEOUT_MS
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    }
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
    private val writer = socket.getOutputStream()

    fun readReply(): String? {
        val firstLine = reader.readLine() ?: return null
        val builder = StringBuilder(firstLine)
        if (firstLine.length >= 4 && firstLine[3] == '-') {
            val code = firstLine.substring(0, 3)
            while (true) {
                val line = reader.readLine() ?: break
                builder.append('\n').append(line)
                if (line.length >= 4 && line.startsWith(code) && line[3] == ' ') break
            }
        }
        return builder.toString()
    }

    fun sendLine(command: String) {
        writer.write((command + "\r\n").toByteArray(StandardCharsets.UTF_8))
        writer.flush()
    }

    fun command(command: String): String {
        sendLine(command)
        return readReply() ?: error("服务器在回复 $command 前关闭了连接")
    }

    fun replyCode(reply: String): Int = reply.substring(0, 3).toInt()

    fun expectCode(command: String, expectedCode: Int): String {
        val reply = command(command)
        val actual = replyCode(reply)
        check(actual == expectedCode) { "命令 [$command] 期望应答 $expectedCode，实际：$reply" }
        return reply
    }

    fun login(username: String = TEST_FTP_USER, password: String = TEST_FTP_PASSWORD) {
        expectCode("USER $username", 331)
        expectCode("PASS $password", 230)
    }

    /** 发送 PASV 并解析 227 中的 IP 与端口。 */
    fun pasv(): PassiveModeReply {
        val reply = expectCode("PASV", 227)
        val match = PASV_REGEX.find(reply) ?: error("无法解析 PASV 应答：$reply")
        val values = match.groupValues
        val host = values[1] + "." + values[2] + "." + values[3] + "." + values[4]
        val port = values[5].toInt() * 256 + values[6].toInt()
        return PassiveModeReply(host, port)
    }

    fun epsv(): Int {
        val reply = expectCode("EPSV", 229)
        val match = EPSV_REGEX.find(reply) ?: error("无法解析 EPSV 应答：$reply")
        return match.groupValues[1].toInt()
    }

    /** 标准客户端顺序：先 PASV、连接数据端口，再发送数据命令。 */
    fun dataCommand(command: String): DataTransferResult {
        val passive = pasv()
        val dataSocket = Socket().apply {
            soTimeout = RAW_TIMEOUT_MS
            connect(InetSocketAddress(host, passive.port), CONNECT_TIMEOUT_MS)
        }
        try {
            sendLine(command)
            val firstReply = readReply() ?: error("数据命令 $command 没有应答")
            val firstCode = replyCode(firstReply)
            if (firstCode != 125 && firstCode != 150) {
                return DataTransferResult(firstCode, firstReply, ByteArray(0), null)
            }
            val data = dataSocket.getInputStream().readBytes()
            val lastReply = readReply()
            return DataTransferResult(firstCode, firstReply, data, lastReply)
        } finally {
            runCatching { dataSocket.close() }
        }
    }

    /** 通过被动数据连接上传字节，返回最终应答。 */
    fun storeBytes(command: String, payload: ByteArray): String {
        val passive = pasv()
        val dataSocket = Socket().apply {
            soTimeout = RAW_TIMEOUT_MS
            connect(InetSocketAddress(host, passive.port), CONNECT_TIMEOUT_MS)
        }
        try {
            sendLine(command)
            val firstReply = readReply() ?: error("数据命令 $command 没有应答")
            if (replyCode(firstReply) != 150) return firstReply
            dataSocket.getOutputStream().write(payload)
            dataSocket.getOutputStream().flush()
            dataSocket.close()
            return readReply() ?: error("上传 $command 没有最终应答")
        } finally {
            runCatching { dataSocket.close() }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val RAW_TIMEOUT_MS = 20_000
        val PASV_REGEX = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
        val EPSV_REGEX = Regex("\\(\\|\\|\\|(\\d+)\\|\\)")
    }
}

/** 连接到 FTPS 服务端并取回服务端证书（用于断言自签名证书内容）。 */
internal fun fetchServerCertificate(port: Int, context: SSLContext = FtpTestSupport.trustAllSslContext()): SSLSocket {
    val sslSocket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    sslSocket.soTimeout = 10_000
    sslSocket.startHandshake()
    return sslSocket
}

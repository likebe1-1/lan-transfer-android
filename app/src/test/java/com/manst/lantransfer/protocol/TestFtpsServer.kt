package com.manst.lantransfer.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Collectors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 测试用的最小 FTPS 服务端。
 *
 * MockFtpServer 3.1.0 不提供任何 TLS 能力（jar 内没有 FtpsFakeFtpServer 之类的类），
 * 因此这里用 BouncyCastle 的自签名证书 + JDK 的 SSLServerSocket 自建一个真实走线的 FTPS 服务端：
 * - 支持显式 TLS（AUTH TLS 后升级控制连接）与隐式 TLS（连接即 SSL）；
 * - 数据连接在收到 PROT P 后走 TLS；
 * - 实现本工程用到的命令子集（USER/PASS/SYST/FEAT/OPTS/PWD/CWD/TYPE/PASV/EPSV/MLSD/LIST/
 *   RETR/STOR/MKD/RMD/DELE/RNFR/RNTO/SIZE/PBSZ/PROT/AUTH/QUIT/NOOP）；
 * - 根目录映射到测试临时目录，路径解析为 / 分隔的绝对虚拟路径。
 *
 * 该类仅用于 JVM 单元测试，不进入 Android 产物。
 */
internal class TestFtpsServer(
    private val rootDir: Path,
    private val username: String,
    private val password: String,
    private val implicitTls: Boolean = false
) : AutoCloseable {

    private val sslContext: SSLContext = TestTlsSupport.serverContext()
    private val controlServer: ServerSocket
    private val sessions = CopyOnWriteArrayList<Session>()
    private val workers = CopyOnWriteArrayList<Thread>()

    @Volatile
    private var running = true

    val port: Int

    init {
        val address = InetAddress.getByName(HOST)
        controlServer = if (implicitTls) {
            sslContext.serverSocketFactory.createServerSocket(0, 20, address)
        } else {
            ServerSocket(0, 20, address)
        }
        port = controlServer.localPort
        val acceptor = Thread { acceptLoop() }.apply {
            isDaemon = true
            name = "test-ftps-acceptor"
        }
        workers.add(acceptor)
        acceptor.start()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                controlServer.accept()
            } catch (e: IOException) {
                if (!running) break else continue
            }
            socket.soTimeout = CONTROL_TIMEOUT_MS
            val session = Session(socket)
            sessions.add(session)
            val worker = Thread { session.run() }.apply {
                isDaemon = true
                name = "test-ftps-session"
            }
            workers.add(worker)
            worker.start()
        }
    }

    override fun close() {
        running = false
        try {
            controlServer.close()
        } catch (e: IOException) {
            // 关闭测试服务端时的异常无需上报
        }
        for (session in sessions) {
            session.shutdown()
        }
        for (worker in workers) {
            worker.join(2_000L)
        }
    }

    private inner class Session(private var socket: Socket) : Runnable {

        private var input: InputStream = socket.getInputStream()
        private var output: OutputStream = socket.getOutputStream()
        private var cwd: String = "/"
        private var loggedIn = false
        private var pendingUser: String? = null
        private var protectedData = false
        private var dataListener: ServerSocket? = null
        private var renameFrom: String? = null

        fun shutdown() {
            closeDataListener()
            try {
                socket.close()
            } catch (e: IOException) {
                // 关闭会话套接字时的异常无需上报
            }
        }

        override fun run() {
            try {
                if (implicitTls) {
                    (socket as SSLSocket).startHandshake()
                }
                writeReply("220 LanTransfer Test FTPS ready")
                while (running) {
                    val line = readControlLine() ?: break
                    if (line.isEmpty()) continue
                    val space = line.indexOf(' ')
                    val command = if (space < 0) line else line.substring(0, space)
                    val argument = if (space < 0) "" else line.substring(space + 1)
                    if (!dispatch(command.uppercase(Locale.ROOT), argument)) break
                }
            } catch (e: IOException) {
                // 客户端断开/超时在测试场景属于正常结束
            } catch (e: RuntimeException) {
                // 协议实现中的意外错误：尽力返回 451，避免线程无提示退出
                try {
                    writeReply("451 Requested action aborted: ${e.message ?: "test server error"}")
                } catch (ignored: IOException) {
                    // 连接已不可用时无法回复
                }
            } finally {
                shutdown()
            }
        }

        private fun dispatch(command: String, argument: String): Boolean {
            when (command) {
                "AUTH" -> {
                    val mode = argument.trim().uppercase(Locale.ROOT)
                    if (mode == "TLS" || mode == "SSL") {
                        writeReply("234 AUTH TLS successful")
                        upgradeToTls()
                    } else {
                        writeReply("504 Unsupported AUTH type")
                    }
                }
                "PBSZ" -> writeReply("200 PBSZ=0")
                "PROT" -> when (argument.trim().uppercase(Locale.ROOT)) {
                    "P" -> {
                        protectedData = true
                        writeReply("200 Protection level set to P")
                    }
                    "C" -> {
                        protectedData = false
                        writeReply("200 Protection level set to C")
                    }
                    else -> writeReply("504 Unsupported PROT level")
                }
                "USER" -> {
                    pendingUser = argument.trim()
                    if (pendingUser.isNullOrEmpty()) {
                        writeReply("501 USER requires a name")
                    } else {
                        writeReply("331 Password required for $pendingUser")
                    }
                }
                "PASS" -> {
                    if (pendingUser == null) {
                        writeReply("503 Login with USER first")
                    } else if (pendingUser == username && argument == password) {
                        loggedIn = true
                        cwd = "/"
                        writeReply("230 Login successful")
                    } else {
                        writeReply("530 Login incorrect")
                    }
                }
                "SYST" -> writeReply("215 UNIX Type: L8")
                "FEAT" -> writeFeatures()
                "OPTS" -> {
                    if (argument.trim().uppercase(Locale.ROOT).startsWith("UTF8")) {
                        writeReply("200 UTF8 enabled")
                    } else {
                        writeReply("501 Unsupported option")
                    }
                }
                "PWD" -> if (requireLogin()) writeReply("257 \"$cwd\" is the current directory")
                "CWD" -> changeDirectory(argument)
                "TYPE" -> writeReply("200 Type set to I")
                "PASV" -> enterPassiveMode()
                "EPSV" -> enterExtendedPassiveMode()
                "MLSD" -> listing(argument, mlsd = true)
                "LIST" -> listing(argument, mlsd = false)
                "RETR" -> retrieve(argument)
                "STOR" -> store(argument)
                "MKD" -> makeDirectory(argument)
                "RMD" -> removeDirectory(argument)
                "DELE" -> deleteFile(argument)
                "SIZE" -> fileSize(argument)
                "RNFR" -> renameFromCommand(argument)
                "RNTO" -> renameToCommand(argument)
                "NOOP" -> writeReply("200 NOOP ok")
                "QUIT" -> {
                    writeReply("221 Bye")
                    return false
                }
                else -> writeReply("502 Command not implemented")
            }
            return true
        }

        private fun requireLogin(): Boolean {
            if (loggedIn) return true
            writeReply("530 Please login with USER and PASS")
            return false
        }

        private fun changeDirectory(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (Files.isDirectory(target)) {
                cwd = virtual
                writeReply("250 Directory changed to $virtual")
            } else {
                writeReply("550 $virtual: No such directory")
            }
        }

        private fun enterPassiveMode() {
            if (!requireLogin()) return
            val listener = openPassiveListener()
            dataListener = listener
            val dataPort = listener.localPort
            writeReply("227 Entering Passive Mode (127,0,0,1,${dataPort / 256},${dataPort % 256})")
        }

        private fun enterExtendedPassiveMode() {
            if (!requireLogin()) return
            val listener = openPassiveListener()
            dataListener = listener
            writeReply("229 Entering Extended Passive Mode (|||${listener.localPort}|)")
        }

        private fun openPassiveListener(): ServerSocket {
            closeDataListener()
            val address = InetAddress.getByName(HOST)
            val listener = if (protectedData) {
                sslContext.serverSocketFactory.createServerSocket(0, 1, address)
            } else {
                ServerSocket(0, 1, address)
            }
            listener.soTimeout = PASSIVE_ACCEPT_TIMEOUT_MS
            return listener
        }

        private fun acceptDataConnection(): Socket {
            val listener = dataListener ?: throw IOException("no passive data listener")
            val raw = listener.accept()
            closeDataListener()
            raw.soTimeout = DATA_TIMEOUT_MS
            if (!protectedData) return raw
            val ssl = raw as? SSLSocket ?: throw IOException("expected TLS data connection")
            ssl.useClientMode = false
            ssl.startHandshake()
            return ssl
        }

        private fun listing(argument: String, mlsd: Boolean) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (!Files.isDirectory(target)) {
                writeReply("550 $virtual: No such file or directory")
                return
            }
            writeReply("150 Here comes the directory listing")
            try {
                acceptDataConnection().use { data ->
                    val stream = data.getOutputStream()
                    val entries = Files.list(target).use { list ->
                        list.sorted().collect(Collectors.toList())
                    }
                    if (mlsd) writeMlsd(stream, target, entries) else writeUnixListing(stream, target, entries)
                    stream.flush()
                }
                writeReply("226 Directory send OK")
            } catch (e: IOException) {
                writeReply("426 Connection closed; transfer aborted")
            }
        }

        private fun writeMlsd(stream: OutputStream, target: Path, entries: List<Path>) {
            val directoryTime = formatMlsdTime(Files.getLastModifiedTime(target).toInstant())
            stream.write("type=cdir;modify=$directoryTime; .\r\n".toByteArray(StandardCharsets.UTF_8))
            stream.write("type=pdir;modify=$directoryTime; ..\r\n".toByteArray(StandardCharsets.UTF_8))
            for (entry in entries) {
                val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java)
                val modified = formatMlsdTime(attributes.lastModifiedTime().toInstant())
                val name = entry.fileName.toString()
                val line = if (attributes.isDirectory) {
                    "type=dir;modify=$modified; $name\r\n"
                } else {
                    "type=file;size=${attributes.size()};modify=$modified; $name\r\n"
                }
                stream.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        }

        private fun writeUnixListing(stream: OutputStream, target: Path, entries: List<Path>) {
            val now = Instant.now()
            stream.write("total 0\r\n".toByteArray(StandardCharsets.UTF_8))
            stream.write(
                "drwxr-xr-x 2 tester users 4096 ${formatLsTime(Files.getLastModifiedTime(target).toInstant(), now)} .\r\n"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            stream.write(
                "drwxr-xr-x 2 tester users 4096 ${formatLsTime(Files.getLastModifiedTime(target).toInstant(), now)} ..\r\n"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            for (entry in entries) {
                val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java)
                val permissions = if (attributes.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                val size = if (attributes.isDirectory) 4096L else attributes.size()
                stream.write(
                    "$permissions 1 tester users $size ${formatLsTime(attributes.lastModifiedTime().toInstant(), now)} ${entry.fileName}\r\n"
                        .toByteArray(StandardCharsets.UTF_8)
                )
            }
        }

        private fun retrieve(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (!Files.isRegularFile(target)) {
                writeReply("550 $virtual: No such file or directory")
                return
            }
            writeReply("150 Opening BINARY mode data connection")
            try {
                acceptDataConnection().use { data ->
                    Files.copy(target, data.getOutputStream())
                }
                writeReply("226 Transfer complete")
            } catch (e: IOException) {
                writeReply("426 Connection closed; transfer aborted")
            }
        }

        private fun store(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (Files.isDirectory(target)) {
                writeReply("550 $virtual: Is a directory")
                return
            }
            val parent = target.parent
            if (parent == null || !Files.isDirectory(parent)) {
                writeReply("550 $virtual: No such directory")
                return
            }
            writeReply("150 Ok to send data")
            try {
                acceptDataConnection().use { data ->
                    data.getInputStream().use { stream ->
                        Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                writeReply("226 Transfer complete")
            } catch (e: IOException) {
                writeReply("426 Connection closed; transfer aborted")
            }
        }

        private fun makeDirectory(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (Files.exists(target)) {
                writeReply("550 $virtual: File exists")
                return
            }
            try {
                Files.createDirectory(target)
                writeReply("257 \"$virtual\" created")
            } catch (e: IOException) {
                writeReply("550 $virtual: Cannot create directory")
            }
        }

        private fun removeDirectory(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (!Files.isDirectory(target)) {
                writeReply("550 $virtual: Not a directory")
                return
            }
            try {
                Files.delete(target)
                writeReply("250 Directory removed")
            } catch (e: IOException) {
                writeReply("550 $virtual: Directory not empty or not removable")
            }
        }

        private fun deleteFile(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            try {
                Files.deleteIfExists(target)
                writeReply("250 File deleted")
            } catch (e: IOException) {
                writeReply("550 $virtual: Cannot delete file")
            }
        }

        private fun fileSize(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            val target = resolveReal(virtual)
            if (!Files.isRegularFile(target)) {
                writeReply("550 $virtual: No such file")
                return
            }
            writeReply("213 ${Files.size(target)}")
        }

        private fun renameFromCommand(argument: String) {
            if (!requireLogin()) return
            val virtual = resolveVirtual(cwd, argument.trim())
            if (!Files.exists(resolveReal(virtual))) {
                writeReply("550 $virtual: No such file or directory")
                return
            }
            renameFrom = virtual
            writeReply("350 Ready for RNTO")
        }

        private fun renameToCommand(argument: String) {
            if (!requireLogin()) return
            val from = renameFrom
            if (from == null) {
                writeReply("503 RNFR required first")
                return
            }
            val virtual = resolveVirtual(cwd, argument.trim())
            try {
                Files.move(resolveReal(from), resolveReal(virtual), StandardCopyOption.REPLACE_EXISTING)
                renameFrom = null
                writeReply("250 Rename successful")
            } catch (e: IOException) {
                writeReply("550 Rename failed")
            }
        }

        private fun writeFeatures() {
            output.write("211-Features:\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(" MLST type*;size*;modify*;\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(" MLSD\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write(" UTF8\r\n".toByteArray(StandardCharsets.UTF_8))
            output.write("211 End\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        private fun upgradeToTls() {
            val current = socket
            val ssl = sslContext.socketFactory.createSocket(
                current,
                current.inetAddress.hostAddress,
                current.port,
                true
            ) as SSLSocket
            ssl.useClientMode = false
            ssl.startHandshake()
            socket = ssl
            input = ssl.getInputStream()
            output = ssl.getOutputStream()
        }

        private fun writeReply(reply: String) {
            output.write((reply + "\r\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        private fun readControlLine(): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return null
                if (value == '\n'.code) break
                if (value != '\r'.code) buffer.write(value)
                if (buffer.size() > MAX_CONTROL_LINE) throw IOException("control line too long")
            }
            return String(buffer.toByteArray(), StandardCharsets.UTF_8)
        }

        private fun closeDataListener() {
            try {
                dataListener?.close()
            } catch (e: IOException) {
                // 数据监听套接字关闭失败无影响
            }
            dataListener = null
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val CONTROL_TIMEOUT_MS = 60_000
        const val DATA_TIMEOUT_MS = 30_000
        const val PASSIVE_ACCEPT_TIMEOUT_MS = 15_000
        const val MAX_CONTROL_LINE = 8 * 1024
        val MLSD_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(ZoneOffset.UTC)
        val LS_RECENT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(ZoneOffset.UTC)
        val LS_OLD_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d  yyyy", Locale.US).withZone(ZoneOffset.UTC)

        fun formatMlsdTime(instant: Instant): String = MLSD_TIME_FORMAT.format(instant)

        fun formatLsTime(instant: Instant, now: Instant): String {
            val sixMonthsAgo = now.minusSeconds(183L * 24L * 60L * 60L)
            return if (instant.isAfter(sixMonthsAgo)) LS_RECENT_FORMAT.format(instant) else LS_OLD_FORMAT.format(instant)
        }

        fun resolveVirtual(cwd: String, argument: String): String {
            val raw = when {
                argument.isBlank() -> cwd
                argument.startsWith("/") -> argument
                cwd == "/" -> "/$argument"
                else -> "$cwd/$argument"
            }
            val parts = ArrayList<String>()
            for (segment in raw.replace('\\', '/').split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(segment)
                }
            }
            return "/" + parts.joinToString("/")
        }
    }

    private fun resolveReal(virtual: String): Path {
        val base = rootDir.toAbsolutePath().normalize()
        val resolved = base.resolve(virtual.removePrefix("/")).normalize()
        require(resolved.startsWith(base)) { "路径越界：$virtual" }
        return resolved
    }
}

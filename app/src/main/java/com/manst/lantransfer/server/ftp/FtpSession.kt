package com.manst.lantransfer.server.ftp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条 FTP/FTPS 控制连接，一个客户端一个实例，运行在独立守护线程中。
 *
 * 线程模型：命令串行处理；每个数据命令在同一个线程里依次完成 150 应答、accept 被动数据连接、
 * 传输与 226 应答，因此不需要额外的数据传输线程。PASV/EPSV 打开的数据监听在下一个 PASV/EPSV、
 * 数据命令完成或会话关闭时释放。
 *
 * 路径安全：所有参数都先归一化为共享目录内的虚拟绝对路径（POSIX 风格，根为 /），
 * 任何试图用 .. 越过根目录的请求直接以 550 拒绝；虚拟路径再映射为共享目录内的本地文件。
 */
internal class FtpSession(
    private var controlSocket: Socket,
    private val serverContext: FtpServerContext
) {

    private var input: InputStream = controlSocket.getInputStream()
    private var output: OutputStream = controlSocket.getOutputStream()

    private var cwd: String = ROOT
    private var pendingUser: String? = null
    private var loggedIn = false
    private var tlsActive = false

    /** true 表示数据通道使用 TLS（显式 FTPS 需 PROT P；隐式 FTPS 默认加密）。 */
    private var protectedData = serverContext.implicitTls

    private var renameFrom: String? = null
    private var dataListener: ServerSocket? = null
    private var dataSocket: Socket? = null

    private val closed = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    fun attachThread(thread: Thread) {
        worker = thread
    }

    fun awaitTermination(timeoutMs: Long) {
        val thread = worker ?: return
        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 主循环：先完成隐式 TLS（如有），再循环读取命令直到 QUIT/EOF/异常。 */
    fun run() {
        try {
            controlSocket.soTimeout = CONTROL_IDLE_TIMEOUT_MS
            if (serverContext.implicitTls) {
                upgradeControlToTls()
            }
            sendReply("220 ${serverContext.displayName} ready")
            while (!closed.get()) {
                val line = readControlLine() ?: break
                if (line.isEmpty()) continue
                if (!dispatch(line)) break
            }
        } catch (e: SocketTimeoutException) {
            quietReply("421 Idle timeout; closing control connection")
        } catch (e: IOException) {
            // 客户端断开或 TLS 连接已关闭：正常结束
        } catch (e: Exception) {
            quietReply("451 Requested action aborted: ${e.message ?: "internal error"}")
        } finally {
            close()
        }
    }

    /** 幂等关闭：释放数据监听、数据连接与控制连接。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeDataListener()
        closeDataSocket(null)
        try {
            controlSocket.close()
        } catch (e: IOException) {
            // 关闭控制连接失败无副作用
        }
    }

    // ------------------------------------------------------------ 命令分发

    private fun dispatch(rawLine: String): Boolean {
        val separator = rawLine.indexOf(' ')
        val command = (if (separator < 0) rawLine else rawLine.substring(0, separator))
            .trim()
            .uppercase(Locale.ROOT)
        val argument = if (separator < 0) "" else rawLine.substring(separator + 1)

        when (command) {
            "USER" -> handleUser(argument)
            "PASS" -> handlePass(argument)
            "QUIT" -> {
                sendReply("221 Goodbye")
                return false
            }
            "NOOP" -> sendReply("200 NOOP ok")
            "SYST" -> sendReply("215 UNIX Type: L8")
            "FEAT" -> sendFeatures()
            "OPTS" -> handleOpts(argument)
            "AUTH" -> handleAuth(argument)
            "PBSZ" -> handlePbsz()
            "PROT" -> handleProt(argument)
            "PWD", "XPWD" -> if (requireLogin()) {
                sendReply("257 \"${quote(cwd)}\" is the current directory")
            }
            "CWD", "XCWD" -> changeDirectory(argument)
            "CDUP" -> changeDirectory("..")
            "TYPE" -> handleType(argument)
            "PASV" -> handlePasv()
            "EPSV" -> handleEpsv()
            "LIST" -> handleListing(argument, ListingFormat.LIST)
            "NLST" -> handleListing(argument, ListingFormat.NLST)
            "MLSD" -> handleListing(argument, ListingFormat.MLSD)
            "MLST" -> handleMlst(argument)
            "SIZE" -> handleSize(argument)
            "MDTM" -> handleMdtm(argument)
            "RETR" -> handleRetr(argument)
            "STOR" -> handleStor(argument)
            "ALLO" -> if (requireLogin()) {
                // App 自带客户端上传前会先发 ALLO 做空间预分配提示，这里直接接受
                sendReply("200 ALLO command successful")
            }
            "DELE" -> handleDele(argument)
            "MKD", "XMKD" -> handleMkd(argument)
            "RMD", "XRMD" -> handleRmd(argument)
            "RNFR" -> handleRnfr(argument)
            "RNTO" -> handleRnto(argument)
            "ABOR" -> handleAbor()
            // FileZilla 等客户端登录后会发 CLNT 上报客户端名；接受以避免兼容性日志噪音
            "CLNT" -> sendReply("200 Client name accepted")
            else -> sendReply("502 Command not implemented")
        }
        return true
    }

    private fun requireLogin(): Boolean {
        if (loggedIn) return true
        sendReply("530 Please login with USER and PASS")
        return false
    }

    // ------------------------------------------------------------ 登录与 TLS 协商

    private fun handleUser(argument: String) {
        val user = argument.trim()
        pendingUser = user.ifEmpty { null }
        loggedIn = false
        if (user.isEmpty()) {
            sendReply("501 USER requires a user name")
        } else {
            sendReply("331 Password required for $user")
        }
    }

    private fun handlePass(argument: String) {
        val user = pendingUser
        if (user == null) {
            sendReply("503 Login with USER first")
            return
        }
        pendingUser = null
        if (user == serverContext.username && argument == serverContext.password) {
            loggedIn = true
            cwd = ROOT
            sendReply("230 Login successful")
        } else {
            loggedIn = false
            sendReply("530 Login incorrect")
        }
    }

    private fun sendFeatures() {
        val features = mutableListOf(
            " UTF8",
            " MLST type*;size*;modify*;",
            " MLSD",
            " EPSV",
            " PASV",
            " SIZE",
            " MDTM"
        )
        if (serverContext.tlsEnabled) {
            if (serverContext.explicitTls) features.add(" AUTH TLS")
            features.add(" PBSZ")
            features.add(" PROT")
        }
        sendMultiline("211-Features:", features, "211 End")
    }

    private fun handleOpts(argument: String) {
        val option = argument.trim().uppercase(Locale.ROOT)
        when {
            option.startsWith("UTF8") -> sendReply("200 UTF8 mode enabled")
            option.startsWith("MLST") -> sendReply("200 MLST options accepted")
            else -> sendReply("501 Option not supported")
        }
    }

    private fun handleAuth(argument: String) {
        if (!serverContext.explicitTls) {
            sendReply("502 AUTH not supported")
            return
        }
        if (tlsActive) {
            sendReply("503 Control connection is already secured")
            return
        }
        val mechanism = argument.trim().uppercase(Locale.ROOT)
        if (mechanism != "TLS" && mechanism != "SSL") {
            sendReply("504 Unsupported AUTH type")
            return
        }
        // 先回复 234 再升级：客户端收到 234 后立即发起 TLS 握手
        sendReply("234 AUTH $mechanism successful")
        upgradeControlToTls()
    }

    private fun handlePbsz() {
        if (!serverContext.tlsEnabled) {
            sendReply("502 PBSZ requires FTPS")
            return
        }
        if (!tlsActive) {
            sendReply("503 PBSZ requires AUTH TLS first")
            return
        }
        sendReply("200 PBSZ=0")
    }

    private fun handleProt(argument: String) {
        if (!serverContext.tlsEnabled) {
            sendReply("502 PROT requires FTPS")
            return
        }
        if (!tlsActive) {
            sendReply("503 PROT requires AUTH TLS first")
            return
        }
        when (argument.trim().uppercase(Locale.ROOT)) {
            "P" -> {
                protectedData = true
                sendReply("200 Protection level set to P")
            }
            "C" -> {
                protectedData = false
                sendReply("200 Protection level set to C")
            }
            else -> sendReply("536 Protection level not supported")
        }
    }

    private fun upgradeControlToTls() {
        val sslContext = serverContext.sslContext ?: throw IOException("FTPS TLS context unavailable")
        val sslSocket = FtpServerTls.layerServerSocket(sslContext, controlSocket)
        controlSocket = sslSocket
        input = sslSocket.getInputStream()
        output = sslSocket.getOutputStream()
        try {
            sslSocket.soTimeout = CONTROL_IDLE_TIMEOUT_MS
        } catch (e: IOException) {
            // 个别实现不支持在分层套接字上设置超时：继续使用底层套接字的超时
        }
        tlsActive = true
    }

    // ------------------------------------------------------------ 路径与目录

    private fun changeDirectory(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (local.isDirectory) {
            cwd = virtual
            sendReply("250 Directory changed to $virtual")
        } else {
            sendReply("550 $virtual: No such directory")
        }
    }

    private fun handleType(argument: String) {
        when (argument.trim().uppercase(Locale.ROOT)) {
            "I", "L 8", "L8" -> sendReply("200 Type set to I")
            "A", "A N" -> sendReply("200 Type set to A")
            else -> sendReply("504 Unsupported TYPE")
        }
    }

    private fun handlePasv() {
        if (!requireLogin()) return
        val listener = openDataListener() ?: return sendReply("425 Can't open data connection")
        val hostAddress = controlSocket.localAddress?.hostAddress
        val octets = hostAddress?.split('.')
        if (octets == null || octets.size != 4) {
            closeDataListener()
            return sendReply("425 Passive mode requires an IPv4 control connection")
        }
        val port = listener.localPort
        sendReply("227 Entering Passive Mode (${octets.joinToString(",")},${port / 256},${port % 256})")
    }

    private fun handleEpsv() {
        if (!requireLogin()) return
        val listener = openDataListener() ?: return sendReply("425 Can't open data connection")
        sendReply("229 Entering Extended Passive Mode (|||${listener.localPort}|)")
    }

    private fun openDataListener(): ServerSocket? {
        closeDataListener()
        return try {
            val listener = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(serverContext.passiveBindAddress, 0), 1)
                soTimeout = PASSIVE_ACCEPT_TIMEOUT_MS
            }
            dataListener = listener
            listener
        } catch (e: IOException) {
            null
        }
    }

    private fun handleListing(argument: String, format: ListingFormat) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(listingPath(argument))
        if (virtual == null) {
            closeDataListener()
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        val files: List<File> = when {
            // 隐藏上传中的临时文件，避免其他客户端看到半成品
            local.isDirectory -> local.listFiles()
                ?.filterNot { it.name.startsWith(TEMP_FILE_PREFIX) }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
            local.isFile -> listOf(local)
            else -> {
                closeDataListener()
                sendReply("550 $virtual: No such file or directory")
                return
            }
        }
        if (dataListener == null) {
            sendReply("425 Use PASV or EPSV first")
            return
        }
        transferOut("150 Opening data connection for directory listing") { stream ->
            when (format) {
                ListingFormat.LIST -> writeUnixListing(stream, files)
                ListingFormat.NLST -> writeNameListing(stream, files)
                ListingFormat.MLSD -> writeMlsdListing(stream, files)
            }
        }
    }

    private fun handleMlst(argument: String) {
        if (!requireLogin()) return
        val target = if (argument.isBlank()) cwd else argument
        val virtual = resolveVirtual(target)
        if (virtual == null) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.exists()) {
            sendReply("550 $virtual: No such file or directory")
            return
        }
        val line = " ${mlsxFacts(local)} $virtual"
        sendMultiline("250-Listing $virtual", listOf(line), "250 End")
    }

    private fun listingPath(argument: String): String {
        val trimmed = argument.trim()
        if (trimmed.isEmpty()) return cwd
        if (!trimmed.startsWith("-")) return trimmed
        // 兼容 LIST -a / LIST -la 这类带选项的请求（本服务端不区分隐藏文件）
        val tokens = trimmed.split(' ').filter { it.isNotBlank() }.dropWhile { it.startsWith("-") }
        return if (tokens.isEmpty()) cwd else tokens.joinToString(" ")
    }

    // ------------------------------------------------------------ 元数据与传输

    private fun handleSize(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.isFile) {
            sendReply("550 $virtual: No such file")
            return
        }
        sendReply("213 ${local.length()}")
    }

    private fun handleMdtm(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.isFile) {
            sendReply("550 $virtual: No such file")
            return
        }
        sendReply("213 ${formatMdtm(local.lastModified())}")
    }

    private fun handleRetr(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            closeDataListener()
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.isFile) {
            closeDataListener()
            sendReply("550 $virtual: No such file or directory")
            return
        }
        if (dataListener == null) {
            sendReply("425 Use PASV or EPSV first")
            return
        }
        val length = local.length()
        transferOut("150 Opening BINARY mode data connection for ${sanitize(local.name)} ($length bytes)") { stream ->
            local.inputStream().use { fileStream ->
                fileStream.copyTo(stream, TRANSFER_BUFFER_SIZE)
            }
        }
    }

    private fun handleStor(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            closeDataListener()
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (local.isDirectory) {
            closeDataListener()
            sendReply("550 $virtual: Is a directory")
            return
        }
        val parent = local.parentFile
        if (parent == null || !parent.isDirectory) {
            closeDataListener()
            sendReply("550 $virtual: No such directory")
            return
        }
        if (dataListener == null) {
            sendReply("425 Use PASV or EPSV first")
            return
        }
        // 先写同目录临时文件，接收完整后再原子替换目标文件，避免半截文件覆盖旧文件
        val tempFile = try {
            File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, parent)
        } catch (e: IOException) {
            closeDataListener()
            sendReply("451 Local error in processing: ${e.message ?: "cannot create temporary file"}")
            return
        }
        transferIn("150 Ok to send data") { stream ->
            tempFile.outputStream().use { fileStream ->
                stream.copyTo(fileStream, TRANSFER_BUFFER_SIZE)
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    local.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: IOException) {
                throw IOException("cannot store file: ${e.message}", e)
            }
        }
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    private fun handleDele(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.isFile) {
            sendReply("550 $virtual: No such file")
            return
        }
        if (local.delete()) {
            sendReply("250 File deleted")
        } else {
            sendReply("550 Cannot delete $virtual")
        }
    }

    private fun handleMkd(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null || virtual == ROOT) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (local.exists()) {
            sendReply("550 $virtual: File exists")
            return
        }
        if (local.mkdir()) {
            sendReply("257 \"${quote(virtual)}\" created")
        } else {
            sendReply("550 Cannot create directory $virtual")
        }
    }

    private fun handleRmd(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null || virtual == ROOT) {
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.isDirectory) {
            sendReply("550 $virtual: Not a directory")
            return
        }
        if (local.delete()) {
            sendReply("250 Directory removed")
        } else {
            sendReply("550 Directory not empty or not removable: $virtual")
        }
    }

    private fun handleRnfr(argument: String) {
        if (!requireLogin()) return
        val virtual = resolveVirtual(argument)
        if (virtual == null) {
            renameFrom = null
            sendReply("550 Invalid path")
            return
        }
        val local = toLocalFile(virtual)
        if (!local.exists()) {
            renameFrom = null
            sendReply("550 $virtual: No such file or directory")
            return
        }
        renameFrom = virtual
        sendReply("350 Ready for RNTO")
    }

    private fun handleRnto(argument: String) {
        if (!requireLogin()) return
        val from = renameFrom
        if (from == null) {
            sendReply("503 RNFR required first")
            return
        }
        renameFrom = null
        val targetVirtual = resolveVirtual(argument)
        if (targetVirtual == null) {
            sendReply("550 Invalid path")
            return
        }
        val fromLocal = toLocalFile(from)
        val targetLocal = toLocalFile(targetVirtual)
        if (!fromLocal.exists()) {
            sendReply("550 $from: No such file or directory")
            return
        }
        val parent = targetLocal.parentFile
        if (parent == null || !parent.isDirectory) {
            sendReply("550 $targetVirtual: No such directory")
            return
        }
        try {
            Files.move(fromLocal.toPath(), targetLocal.toPath(), StandardCopyOption.REPLACE_EXISTING)
            sendReply("250 Rename successful")
        } catch (e: IOException) {
            sendReply("550 Rename failed: ${e.message ?: "cannot rename"}")
        }
    }

    private fun handleAbor() {
        val hadTransfer = dataListener != null || dataSocket != null
        closeDataListener()
        closeDataSocket(null)
        sendReply(if (hadTransfer) "226 Abort successful" else "225 No transfer in progress")
    }

    // ------------------------------------------------------------ 数据连接

    private fun transferOut(preliminaryReply: String, writer: (OutputStream) -> Unit) {
        sendReply(preliminaryReply)
        val socket = try {
            acceptDataConnection()
        } catch (e: IOException) {
            quietReply("425 Can't open data connection")
            return
        }
        var success = false
        try {
            val stream = socket.getOutputStream()
            writer(stream)
            stream.flush()
            success = true
        } catch (e: IOException) {
            // 数据通道中断（客户端取消 / TLS 关闭）：按传输失败回复
        } finally {
            closeDataSocket(socket)
            quietReply(if (success) "226 Transfer complete" else "426 Connection closed; transfer aborted")
        }
    }

    private fun transferIn(preliminaryReply: String, reader: (InputStream) -> Unit) {
        sendReply(preliminaryReply)
        val socket = try {
            acceptDataConnection()
        } catch (e: IOException) {
            quietReply("425 Can't open data connection")
            return
        }
        var success = false
        try {
            reader(socket.getInputStream())
            success = true
        } catch (e: IOException) {
            // 数据通道中断或本地写入失败
        } finally {
            closeDataSocket(socket)
            quietReply(if (success) "226 Transfer complete" else "426 Connection closed; transfer aborted")
        }
    }

    private fun acceptDataConnection(): Socket {
        val listener = dataListener ?: throw IOException("No passive data listener")
        try {
            val raw = listener.accept()
            raw.soTimeout = DATA_TIMEOUT_MS
            raw.tcpNoDelay = true
            val sslContext = serverContext.sslContext
            val socket: Socket = if (protectedData && tlsActive && sslContext != null) {
                FtpServerTls.layerServerSocket(sslContext, raw)
            } else {
                raw
            }
            socket.soTimeout = DATA_TIMEOUT_MS
            dataSocket = socket
            return socket
        } finally {
            closeDataListener()
        }
    }

    private fun closeDataListener() {
        val listener = dataListener
        dataListener = null
        try {
            listener?.close()
        } catch (e: IOException) {
            // 关闭数据监听失败无副作用
        }
    }

    private fun closeDataSocket(socket: Socket?) {
        val target = socket ?: dataSocket
        dataSocket = null
        try {
            target?.close()
        } catch (e: IOException) {
            // 关闭数据连接失败无副作用
        }
    }

    // ------------------------------------------------------------ 列表格式化

    private fun writeUnixListing(stream: OutputStream, files: List<File>) {
        val now = System.currentTimeMillis()
        val owner = sanitizeToken(serverContext.username.ifBlank { "ftp" })
        for (file in files) {
            val directory = file.isDirectory
            val permissions = if (directory) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS
            val size = if (directory) DIRECTORY_ENTRY_SIZE else file.length()
            val line = buildString {
                append(permissions)
                append(" 1 ")
                append(owner)
                append(" users ")
                append(size)
                append(' ')
                append(formatListTimestamp(file.lastModified(), now))
                append(' ')
                append(sanitize(file.name))
                append("\r\n")
            }
            stream.write(line.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun writeNameListing(stream: OutputStream, files: List<File>) {
        for (file in files) {
            stream.write((sanitize(file.name) + "\r\n").toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun writeMlsdListing(stream: OutputStream, files: List<File>) {
        for (file in files) {
            val line = "${mlsxFacts(file)} ${sanitize(file.name)}\r\n"
            stream.write(line.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** RFC 3659 风格的 facts 串，以分号结尾（后面再拼一个空格和文件名）。 */
    private fun mlsxFacts(file: File): String {
        val directory = file.isDirectory
        val type = if (directory) "dir" else "file"
        val sizeFact = if (directory) "" else ";size=${file.length()}"
        return "type=$type$sizeFact;modify=${formatMdtm(file.lastModified())};"
    }

    private fun formatListTimestamp(modifiedAt: Long, now: Long): String {
        val instant = Instant.ofEpochMilli(modifiedAt)
        return if (modifiedAt >= now - SIX_MONTHS_MS) {
            LIST_RECENT_FORMAT.format(instant)
        } else {
            LIST_OLD_FORMAT.format(instant)
        }
    }

    private fun formatMdtm(epochMillis: Long): String = MDTM_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    private fun sanitize(name: String): String = buildString(name.length) {
        for (ch in name) {
            append(
                when (ch) {
                    '\r', '\n', '\u0000' -> ' '
                    else -> ch
                }
            )
        }
    }

    private fun sanitizeToken(value: String): String = buildString(value.length) {
        for (ch in value) {
            append(if (ch.isWhitespace()) '_' else ch)
        }
    }

    // ------------------------------------------------------------ 路径解析

    /**
     * 把命令参数解析为共享目录内的虚拟绝对路径；返回 null 表示路径越界（用 550 拒绝）。
     *
     * 规则与 RemotePath.normalize 对齐：修复重复斜杠、"." 与 ".."；区别在于 ".." 越过根目录时
     * 不是静默截断，而是判定为越界。
     */
    private fun resolveVirtual(argument: String): String? {
        if (argument.isEmpty()) return cwd
        val rawPath = when {
            argument.startsWith("/") -> argument
            cwd == ROOT -> "/$argument"
            else -> "$cwd/$argument"
        }
        val segments = ArrayList<String>()
        for (segment in rawPath.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> {
                    if (segment.indexOf('\u0000') >= 0) return null
                    segments.add(segment)
                }
            }
        }
        return if (segments.isEmpty()) ROOT else "/" + segments.joinToString("/")
    }

    /** 虚拟绝对路径映射到共享目录内的本地文件（虚拟路径已保证不越界）。 */
    private fun toLocalFile(virtual: String): File {
        val relative = virtual.removePrefix("/")
        val root = serverContext.shareRoot
        return if (relative.isEmpty()) root else File(root, relative)
    }

    private fun quote(value: String): String = value.replace("\"", "\"\"")

    // ------------------------------------------------------------ 控制连接 IO

    private fun readControlLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return null
            if (value == NEW_LINE) break
            if (value != CARRIAGE_RETURN) buffer.write(value)
            if (buffer.size() > MAX_CONTROL_LINE) throw IOException("Control line too long")
        }
        if (buffer.size() == 0) return ""
        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun sendReply(reply: String) {
        output.write((reply + "\r\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun quietReply(reply: String) {
        try {
            sendReply(reply)
        } catch (e: IOException) {
            // 控制连接已不可用
        }
    }

    private fun sendMultiline(firstLine: String, bodyLines: List<String>, lastLine: String) {
        val builder = StringBuilder()
        builder.append(firstLine).append("\r\n")
        for (line in bodyLines) builder.append(line).append("\r\n")
        builder.append(lastLine).append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private companion object {
        const val ROOT = "/"
        const val CONTROL_IDLE_TIMEOUT_MS = 10 * 60 * 1000
        const val DATA_TIMEOUT_MS = 60 * 1000
        const val PASSIVE_ACCEPT_TIMEOUT_MS = 15 * 1000
        const val MAX_CONTROL_LINE = 8 * 1024
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
        const val TEMP_FILE_PREFIX = ".lantransfer-upload-"
        const val TEMP_FILE_SUFFIX = ".part"
        const val SIX_MONTHS_MS = 183L * 24L * 60L * 60L * 1000L
        const val NEW_LINE = 10
        const val CARRIAGE_RETURN = 13
        const val DIRECTORY_PERMISSIONS = "drwxr-xr-x"
        const val FILE_PERMISSIONS = "-rw-r--r--"
        const val DIRECTORY_ENTRY_SIZE = 4096L

        val LIST_RECENT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(ZoneId.systemDefault())
        val LIST_OLD_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d  yyyy", Locale.US).withZone(ZoneId.systemDefault())
        val MDTM_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(ZoneOffset.UTC)
    }
}

/** 目录列表输出格式：LIST（Unix 长格式）、NLST（纯文件名）、MLSD（RFC 3659）。 */
private enum class ListingFormat { LIST, NLST, MLSD }

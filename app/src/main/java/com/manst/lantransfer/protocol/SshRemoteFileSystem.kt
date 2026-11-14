package com.manst.lantransfer.protocol

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferCancelledException
import com.manst.lantransfer.core.TransferProtocol
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/**
 * SFTP / SCP 远程文件系统实现（JSch mwiede 维护版）。
 *
 * 设计要点：
 * - SFTP 使用 [ChannelSftp]：ls/stat 列目录，get/put 流式传输并通过 [SftpProgressMonitor] 汇报进度；
 * - SCP 直接实现 SCP 线协议：[ChannelExec] 执行 `scp -f <path>`（下载）与 `scp -t <dir>`（上传），
 *   手动处理 C/T/D/E 记录与 ACK；
 * - SCP 协议没有目录列表/建目录/重命名能力，这些操作通过同一 SSH 会话执行 POSIX shell 命令
 *   （`ls -la`、`mkdir -p`、`rm`/`rmdir`、`mv`）完成，参数一律做 shell 单引号转义；
 * - 密码认证；本版不做主机密钥固定校验（见 docs/ARCHITECTURE.md 已知限制）；
 * - 全部阻塞调用放在 [Dispatchers.IO]，类本身是纯 JVM 实现（不依赖 Android API）。
 *
 * 线程模型：同一实例不支持多线程并发操作（上层「一任务一连接」保证），唯一允许跨线程调用的是
 * [close]——它会把传输中出现的 IO 异常转换成 [TransferCancelledException]。
 */
class SshRemoteFileSystem(override val profile: ServerProfile) : RemoteFileSystem {

    private val sftpMode: Boolean = profile.protocol == TransferProtocol.SFTP

    @Volatile
    private var closed = false

    private var connected = false

    private var session: Session? = null

    private var sftpChannel: ChannelSftp? = null

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        connectBlocking()
    }

    private fun connectBlocking() {
        if (closed) throw TransferCancelledException()
        if (connected && session?.isConnected == true) return

        val sshSession = try {
            JSch().getSession(profile.username, profile.host, profile.port).apply {
                setPassword(profile.password)
                // 本版不固定 SFTP/SCP 主机密钥指纹，仅接受任意主机密钥（见架构文档已知限制）
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password")
                setTimeout(READ_TIMEOUT_MS)
                connect(CONNECT_TIMEOUT_MS)
            }
        } catch (e: JSchException) {
            throw connectFailure(e)
        }
        session = sshSession

        if (sftpMode) {
            val channel = try {
                (sshSession.openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_TIMEOUT_MS) }
            } catch (e: JSchException) {
                sshSession.disconnect()
                session = null
                throw connectFailure(e)
            }
            sftpChannel = channel
        }
        connected = true
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val remote = RemotePath.normalize(path)
        if (sftpMode) listSftp(remote) else listScp(remote)
    }

    override suspend fun download(
        remotePath: String,
        sink: OutputStream,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val remote = RemotePath.normalize(remotePath)
        if (sftpMode) downloadSftp(remote, sink, onProgress) else downloadScp(remote, sink, onProgress)
    }

    override suspend fun upload(
        source: InputStream,
        remotePath: String,
        sizeHint: Long,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val remote = RemotePath.normalize(remotePath)
        if (sftpMode) uploadSftp(source, remote, onProgress) else uploadScp(source, remote, sizeHint, onProgress)
    }

    /** SFTP 支持偏移续传；SCP 协议本身不支持，按契约从 0 重传。 */
    override val supportsResume: Boolean
        get() = sftpMode

    override suspend fun downloadPartial(
        remotePath: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (startOffset < 0L) throw IllegalArgumentException("续传偏移不能为负数：" + startOffset)
        val remote = RemotePath.normalize(remotePath)
        if (sftpMode) {
            downloadSftpPartial(remote, sink, startOffset, onProgress)
        } else {
            // SCP 没有偏移读能力：从 0 全量重传（supportsResume=false，上层据此决策）
            downloadScp(remote, sink, onProgress)
        }
    }

    override suspend fun uploadPartial(
        source: InputStream,
        remotePath: String,
        sizeHint: Long,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        if (startOffset < 0L) throw IllegalArgumentException("续传偏移不能为负数：" + startOffset)
        val remote = RemotePath.normalize(remotePath)
        if (!sftpMode) {
            // SCP 没有追加写能力：从 0 全量重传（supportsResume=false，上层据此决策）
            uploadScp(source, remote, sizeHint, onProgress)
            return@withContext
        }
        if (startOffset == 0L) {
            uploadSftp(source, remote, onProgress)
            return@withContext
        }

        // 续传上传前必须确认远端已存在的字节数与调用方给出的偏移一致，否则 JSch 的 RESUME 会错位追加
        val remoteSize = sftpSize(remote)
        if (remoteSize != startOffset) {
            val actual = if (remoteSize < 0L) "未知（文件不存在或不是普通文件）" else remoteSize.toString() + " 字节"
            throw IOException(
                "无法续传上传「" + remote + "」：远端已有 " + actual +
                    "，与续传偏移 " + startOffset + " 字节不一致，请重新上传"
            )
        }
        uploadSftpPartial(source, remote, startOffset, onProgress)
    }

    override suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        if (!sftpMode) return@withContext -1L
        sftpSize(RemotePath.normalize(path))
    }

    /** SFTP 带偏移下载：JSch 的 get(..., RESUME, skip) 会先以 count(skip) 补上偏移，再累计实际读取字节。 */
    private fun downloadSftpPartial(
        remote: String,
        sink: OutputStream,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        if (startOffset == 0L) {
            downloadSftp(remote, sink, onProgress)
            return
        }
        val channel = requireSftp()
        try {
            channel.get(remote, sink, SftpProgressCallback(onProgress), ChannelSftp.RESUME, startOffset)
        } catch (e: SftpException) {
            throw sftpFailure("下载文件", remote, e)
        } catch (e: IOException) {
            throw ioFailure("下载文件", remote, e)
        }
    }

    /** SFTP 续传上传：JSch RESUME 会读取远端大小并跳过来源中对应的前缀，只写剩余字节。 */
    private fun uploadSftpPartial(
        source: InputStream,
        remote: String,
        startOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        val channel = requireSftp()
        try {
            channel.put(
                source,
                remote,
                SftpProgressCallback(onProgress, initialOffset = startOffset),
                ChannelSftp.RESUME
            )
        } catch (e: SftpException) {
            throw sftpFailure("上传文件", remote, e)
        } catch (e: IOException) {
            throw ioFailure("上传文件", remote, e)
        }
    }

    /** 远端文件大小（SFTP stat）；不存在或是目录时返回 -1（契约语义）。 */
    private fun sftpSize(remote: String): Long {
        val channel = requireSftp()
        return try {
            val attributes = channel.stat(remote)
            if (attributes.isDir) -1L else attributes.getSize()
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) -1L else throw sftpFailure("获取文件大小", remote, e)
        }
    }

    override suspend fun mkdir(path: String): Unit = withContext(Dispatchers.IO) {
        val remote = RemotePath.normalize(path)
        if (sftpMode) mkdirSftp(remote) else mkdirScp(remote)
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Unit = withContext(Dispatchers.IO) {
        val remote = RemotePath.normalize(path)
        if (sftpMode) deleteSftp(remote, isDirectory) else deleteScp(remote, isDirectory)
    }

    override suspend fun rename(fromPath: String, toPath: String): Unit = withContext(Dispatchers.IO) {
        val from = RemotePath.normalize(fromPath)
        val to = RemotePath.normalize(toPath)
        if (sftpMode) renameSftp(from, to) else renameScp(from, to)
    }

    override fun close() {
        closed = true
        connected = false
        sftpChannel?.disconnect()
        sftpChannel = null
        session?.disconnect()
        session = null
    }

    // ------------------------------------------------------------ SFTP

    private fun listSftp(remote: String): List<RemoteEntry> {
        val channel = requireSftp()
        val raw = try {
            channel.ls(remote)
        } catch (e: SftpException) {
            throw sftpFailure("列出目录", remote, e)
        }
        val entries = ArrayList<RemoteEntry>(raw.size)
        for (entry in raw) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val attrs = entry.attrs ?: continue
            val fullPath = RemotePath.join(remote, name)
            var isDirectory = attrs.isDir
            if (attrs.isLink) {
                // READDIR 返回的是链接自身属性，目录链接需要 stat 解析目标类型
                isDirectory = try {
                    channel.stat(fullPath).isDir
                } catch (e: SftpException) {
                    false
                }
            }
            entries.add(
                RemoteEntry(
                    name = name,
                    path = fullPath,
                    isDirectory = isDirectory,
                    isSymlink = attrs.isLink,
                    size = if (isDirectory) 0L else attrs.getSize(),
                    modifiedAt = attrs.getMTime().toLong() * 1000L,
                    permissions = attrs.getPermissionsString()
                )
            )
        }
        return entries
    }

    private fun downloadSftp(remote: String, sink: OutputStream, onProgress: (Long) -> Unit) {
        val channel = requireSftp()
        try {
            channel.get(remote, sink, SftpProgressCallback(onProgress))
        } catch (e: SftpException) {
            throw sftpFailure("下载文件", remote, e)
        } catch (e: IOException) {
            throw ioFailure("下载文件", remote, e)
        }
    }

    private fun uploadSftp(source: InputStream, remote: String, onProgress: (Long) -> Unit) {
        val channel = requireSftp()
        try {
            channel.put(source, remote, SftpProgressCallback(onProgress), ChannelSftp.OVERWRITE)
        } catch (e: SftpException) {
            throw sftpFailure("上传文件", remote, e)
        } catch (e: IOException) {
            throw ioFailure("上传文件", remote, e)
        }
    }

    private fun mkdirSftp(remote: String) {
        val channel = requireSftp()
        val existing = try {
            channel.stat(remote)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw sftpFailure("创建目录", remote, e)
        }
        if (existing != null) {
            if (existing.isDir) return
            throw RemotePathException("创建目录失败：「$remote」已存在且不是目录")
        }
        try {
            channel.mkdir(remote)
        } catch (e: SftpException) {
            throw sftpFailure("创建目录", remote, e)
        }
    }

    private fun deleteSftp(remote: String, isDirectory: Boolean) {
        val channel = requireSftp()
        val action = if (isDirectory) "删除目录" else "删除文件"
        try {
            if (isDirectory) channel.rmdir(remote) else channel.rm(remote)
        } catch (e: SftpException) {
            throw sftpFailure(action, remote, e)
        }
    }

    private fun renameSftp(from: String, to: String) {
        val channel = requireSftp()
        try {
            channel.rename(from, to)
        } catch (e: SftpException) {
            throw sftpFailure("重命名", "$from -> $to", e)
        }
    }

    // ------------------------------------------------------------ SCP

    private fun listScp(remote: String): List<RemoteEntry> {
        val output = runShell("LC_ALL=C ls -la ${shellQuote(remote)}", "列出目录", remote)
        return ScpListingParser.parse(output, remote)
    }

    private fun mkdirScp(remote: String) {
        // -p 同时满足「已存在视为成功」的契约语义
        runShell("mkdir -p ${shellQuote(remote)}", "创建目录", remote)
    }

    private fun deleteScp(remote: String, isDirectory: Boolean) {
        if (isDirectory) {
            runShell("rmdir ${shellQuote(remote)}", "删除目录", remote)
        } else {
            runShell("rm -f ${shellQuote(remote)}", "删除文件", remote)
        }
    }

    private fun renameScp(from: String, to: String) {
        runShell("mv ${shellQuote(from)} ${shellQuote(to)}", "重命名", "$from -> $to")
    }

    private fun downloadScp(remote: String, sink: OutputStream, onProgress: (Long) -> Unit) {
        val stderr = ByteArrayOutputStream()
        val exec = openExecChannel("scp -f ${shellQuote(remote)}", stderr)
        try {
            writeAck(exec.output)
            var total = 0L
            while (true) {
                when (val code = readByte(exec.input, remote)) {
                    0 -> {
                        writeAck(exec.output)
                        break
                    }
                    'C'.code -> {
                        val header = readProtocolLine(exec.input, remote)
                        val parts = header.split(' ', limit = 3)
                        val size = parts.getOrNull(1)?.toLongOrNull()
                        if (parts.size < 3 || size == null) {
                            throw ConnectionException("SCP 协议错误：无法解析文件头「$header」")
                        }
                        writeAck(exec.output)
                        copyExact(exec.input, sink, size) { chunk ->
                            total += chunk
                            onProgress(total)
                        }
                        writeAck(exec.output)
                    }
                    'T'.code, 'D'.code, 'E'.code -> {
                        // 时间记录/目录记录：读掉内容后确认，单文件下载场景通常不会出现
                        readProtocolLine(exec.input, remote)
                        writeAck(exec.output)
                    }
                    1, 2 -> {
                        val message = readProtocolLine(exec.input, remote)
                        throw RemotePathException("下载文件失败：「$remote」${if (message.isBlank()) "" else "（" + message + "）"}")
                    }
                    else -> throw ConnectionException("SCP 协议错误：未知记录类型 $code")
                }
            }
            finishCommand(exec, stderr, "下载文件", remote)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: JSchException) {
            throw operationFailure("下载文件", remote, e)
        } catch (e: IOException) {
            throw ioFailure("下载文件", remote, e)
        } finally {
            exec.channel.disconnect()
        }
    }

    private fun uploadScp(
        source: InputStream,
        remote: String,
        sizeHint: Long,
        onProgress: (Long) -> Unit
    ) {
        val targetDir = RemotePath.parent(remote) ?: RemotePath.ROOT
        val fileName = RemotePath.name(remote)
        if (fileName == RemotePath.ROOT) {
            throw RemotePathException("上传目标必须是文件路径：「$remote」")
        }
        if (fileName.contains('\n') || fileName.contains('\r')) {
            throw RemotePathException("SCP 协议不支持包含换行符的文件名：「$fileName」")
        }

        // SCP 的 C 记录必须携带文件大小；调用方未知大小时先落临时文件确定长度
        val spooled = if (sizeHint < 0L) spoolToTempFile(source, remote) else null
        val spooledStream = spooled?.inputStream()
        val content = spooledStream ?: source
        val stderr = ByteArrayOutputStream()
        try {
            val size = if (spooled != null) spooled.length() else sizeHint
            val exec = openExecChannel("scp -t ${shellQuote(targetDir)}", stderr)
            try {
                readAck(exec.input, remote)
                writeProtocolLine(exec.output, "C0644 $size $fileName")
                readAck(exec.input, remote)

                var total = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = content.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    exec.output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
                exec.output.flush()
                writeAck(exec.output)
                readAck(exec.input, remote)
                // SCP sink 以 stdin EOF 作为整个上传结束的信号（不是再发一个 ACK），
                // 否则远端会一直等待下一条 C/D 记录
                exec.output.close()
                finishCommand(exec, stderr, "上传文件", remote)
            } catch (e: LanTransferException) {
                throw e
            } catch (e: JSchException) {
                throw operationFailure("上传文件", remote, e)
            } catch (e: IOException) {
                throw ioFailure("上传文件", remote, e)
            } finally {
                exec.channel.disconnect()
            }
        } finally {
            spooledStream?.close()
            spooled?.delete()
        }
    }

    // ------------------------------------------------------------ 通用工具

    private fun requireSession(): Session {
        if (closed) throw TransferCancelledException()
        val current = session
        if (!connected || current == null || !current.isConnected) {
            throw ConnectionException("尚未连接到服务器（${profile.endpoint}），请先调用 connect()")
        }
        return current
    }

    private fun requireSftp(): ChannelSftp {
        requireSession()
        val channel = sftpChannel
        if (channel == null || !channel.isConnected) {
            throw ConnectionException("SFTP 通道不可用，请重新连接（${profile.endpoint}）")
        }
        return channel
    }

    /** 建立 exec 通道并取出输入输出流；命令在 channel.connect 之后由 JSch 启动。 */
    private fun openExecChannel(command: String, stderr: ByteArrayOutputStream): ExecChannel {
        val sshSession = requireSession()
        val channel = try {
            sshSession.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            throw operationFailure("打开 SSH 通道", null, e)
        }
        channel.setCommand(command)
        channel.setErrStream(stderr)
        return try {
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            ExecChannel(channel, input, output)
        } catch (e: JSchException) {
            channel.disconnect()
            throw operationFailure("打开 SSH 通道", null, e)
        } catch (e: IOException) {
            channel.disconnect()
            throw ioFailure("打开 SSH 通道", null, e)
        }
    }

    private fun runShell(command: String, action: String, path: String?): String {
        val stderr = ByteArrayOutputStream()
        val exec = openExecChannel(command, stderr)
        try {
            // 这些命令都不读 stdin，立即发送 EOF 让远端 shell 正常退出
            exec.output.close()
            val stdout = readFully(exec.input)
            val exit = waitForExit(exec.channel)
            if (exit != 0) {
                val message = stderr.toString(StandardCharsets.UTF_8.name()).trim()
                throw RemotePathException(commandFailureMessage(action, path, exit, message))
            }
            return String(stdout, StandardCharsets.UTF_8)
        } catch (e: LanTransferException) {
            throw e
        } catch (e: JSchException) {
            throw operationFailure(action, path, e)
        } catch (e: IOException) {
            throw ioFailure(action, path, e)
        } finally {
            exec.channel.disconnect()
        }
    }

    private fun finishCommand(exec: ExecChannel, stderr: ByteArrayOutputStream, action: String, path: String?) {
        val exit = waitForExit(exec.channel)
        if (exit != 0) {
            val message = stderr.toString(StandardCharsets.UTF_8.name()).trim()
            throw RemotePathException(commandFailureMessage(action, path, exit, message))
        }
    }

    private fun commandFailureMessage(action: String, path: String?, exit: Int, stderr: String): String {
        val target = if (path.isNullOrBlank()) "" else "：「$path」"
        return if (stderr.isBlank()) {
            "${action}失败$target（远端命令返回码 $exit）"
        } else {
            "${action}失败$target（$stderr）"
        }
    }

    /** 读取命令通道直到 EOF；随后等待远端返回的退出码。 */
    private fun waitForExit(channel: Channel): Int {
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = channel.exitStatus
            if (status != -1) return status
            if (channel.isClosed) return 0
            try {
                Thread.sleep(10L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ConnectionException("等待远端命令结果时被中断", e)
            }
        }
        throw ConnectionException("等待远端命令结果超时")
    }

    private fun readFully(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read > 0) buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun readByte(input: InputStream, path: String?): Int {
        val value = input.read()
        if (value < 0) {
            throw ConnectionException("SCP 连接意外关闭${path?.let { "（$it）" } ?: ""}")
        }
        return value
    }

    private fun readProtocolLine(input: InputStream, path: String?): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                throw ConnectionException("SCP 连接意外关闭${path?.let { "（$it）" } ?: ""}")
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > MAX_PROTOCOL_LINE) {
                throw ConnectionException("SCP 协议错误：记录内容过长")
            }
        }
        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun writeProtocolLine(output: OutputStream, text: String) {
        output.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun writeAck(output: OutputStream) {
        output.write(0)
        output.flush()
    }

    private fun readAck(input: InputStream, path: String?) {
        when (val code = input.read()) {
            -1 -> throw ConnectionException("SCP 连接意外关闭")
            0 -> return
            1, 2 -> {
                val message = readProtocolLine(input, path)
                throw RemotePathException(
                    "操作失败${path?.let { "：「$it」" } ?: ""}（远端返回：${message.ifBlank { "未知错误" }}）"
                )
            }
            else -> throw ConnectionException("SCP 协议错误：非法应答码 $code")
        }
    }

    /** 从输入流精确读取 size 字节写入 sink，并按增量回调进度。 */
    private fun copyExact(input: InputStream, sink: OutputStream, size: Long, onChunk: (Long) -> Unit) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0L) {
            val limit = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, limit)
            if (read < 0) {
                throw ConnectionException("SCP 传输提前结束：还差 $remaining 字节")
            }
            if (read == 0) continue
            sink.write(buffer, 0, read)
            remaining -= read.toLong()
            onChunk(read.toLong())
        }
        sink.flush()
    }

    /** 把未知大小的上传源落到临时文件，以确定 SCP 的 C 记录所需长度。 */
    private fun spoolToTempFile(source: InputStream, remote: String): File {
        val file = try {
            File.createTempFile("lantransfer-scp-", ".tmp")
        } catch (e: IOException) {
            throw RemotePathException("无法创建临时文件以确定上传大小（$remote）", e)
        }
        try {
            file.outputStream().use { output -> source.copyTo(output, BUFFER_SIZE) }
        } catch (e: IOException) {
            file.delete()
            if (closed) throw TransferCancelledException()
            throw RemotePathException("读取待上传数据失败（$remote）：${e.message}", e)
        }
        return file
    }

    /** POSIX shell 单引号转义：除单引号外的所有字符都按字面量传入。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun connectFailure(e: JSchException): LanTransferException {
        if (closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        val cause = e.cause
        return when {
            cause is UnknownHostException -> ConnectionException("无法解析主机地址「${profile.host}」", e)
            cause is ConnectException ||
                message.contains("Connection refused", ignoreCase = true) ||
                message.contains("connect failed", ignoreCase = true) ->
                ConnectionException("无法连接到 ${profile.endpoint}：${message.ifBlank { "连接被拒绝" }}", e)
            cause is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                ConnectionException("连接 ${profile.endpoint} 超时", e)
            isAuthenticationFailure(message) ->
                AuthenticationException("认证失败，请检查用户名或密码（${profile.endpoint}）", e)
            else -> ConnectionException("SSH 连接失败（${profile.endpoint}）：${message.ifBlank { e.javaClass.simpleName }}", e)
        }
    }

    private fun operationFailure(action: String, path: String?, e: JSchException): LanTransferException {
        if (closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        return when {
            isAuthenticationFailure(message) -> AuthenticationException("认证失败：服务器拒绝登录", e)
            message.contains("session is down", ignoreCase = true) ||
                message.contains("session is not connected", ignoreCase = true) ||
                message.contains("channel is not opened", ignoreCase = true) ||
                message.contains("connection is closed", ignoreCase = true) ->
                ConnectionException("${action}失败：SSH 连接已断开", e)
            else -> ConnectionException(
                "${action}失败${path?.let { "（$it）" } ?: ""}：${message.ifBlank { e.javaClass.simpleName }}", e
            )
        }
    }

    private fun isAuthenticationFailure(message: String): Boolean {
        return message.contains("Auth fail", ignoreCase = true) ||
            message.contains("Auth cancel", ignoreCase = true) ||
            message.contains("USERAUTH", ignoreCase = true) ||
            message.contains("incorrect password", ignoreCase = true) ||
            message.contains("no more authentication methods", ignoreCase = true)
    }

    private fun ioFailure(action: String, path: String?, e: IOException): LanTransferException {
        if (closed) return TransferCancelledException()
        val target = if (path.isNullOrBlank()) "" else "（$path）"
        return ConnectionException("${action}失败$target：${e.message ?: "SSH 连接中断"}", e)
    }

    private fun sftpFailure(action: String, path: String?, e: SftpException): LanTransferException {
        if (closed) return TransferCancelledException()
        val message = e.message.orEmpty()
        return when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> RemotePathException("${action}失败：「$path」不存在", e)
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> RemotePathException("${action}失败：没有权限访问「$path」", e)
            ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST ->
                ConnectionException("${action}失败：SSH 连接已断开", e)
            else -> RemotePathException(
                "${action}失败：「$path」${if (message.isBlank()) "" else "（" + message + "）"}", e
            )
        }
    }

    private data class ExecChannel(
        val channel: ChannelExec,
        val input: InputStream,
        val output: OutputStream
    )

    /**
     * JSch 传输进度回调：把增量字节累计后交给上层。
     * [initialOffset] 用于续传上传（JSch 只回调本次写入字节，需要补上已有偏移）；
     * 续传下载时 JSch 会主动以 count(skip) 上报偏移，因此 initialOffset 保持 0。
     */
    private class SftpProgressCallback(
        private val onProgress: (Long) -> Unit,
        private val initialOffset: Long = 0L
    ) : SftpProgressMonitor {
        private var total = initialOffset

        override fun init(op: Int, src: String?, dest: String?, max: Long) {
            total = initialOffset
        }

        override fun count(count: Long): Boolean {
            total += count
            onProgress(total)
            return true
        }

        override fun end() = Unit
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 32 * 1024
        const val MAX_PROTOCOL_LINE = 64 * 1024
    }
}

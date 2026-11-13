package com.manst.lantransfer.protocol

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.stream.Collectors

/**
 * 测试用的本地 SSH 服务端（Apache MINA SSHD），用于真实走线验证 SCP（以及后续 SFTP）实现：
 * - 随机端口、密码认证、临时目录作为虚拟文件系统根；
 * - SCP 由 SSHD 的 ScpCommandFactory 提供；
 * - 非 scp 命令交给最小 POSIX shell 命令实现（ls/mkdir/rm/rmdir/mv），
 *   供 SCP 适配器的列目录/建目录/删除/重命名路径使用。
 *
 * 该类仅用于 JVM 单元测试，不进入 Android 产物。
 */
internal class TestSshServer(
    private val rootDir: Path,
    private val username: String = "tester",
    private val password: String = "s3cret",
    enableSftp: Boolean = false
) : AutoCloseable {

    private val sshd: SshServer = SshServer.setUpDefaultServer()

    val port: Int

    init {
        sshd.host = HOST
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider()
        sshd.passwordAuthenticator = PasswordAuthenticator { user, pass, _ ->
            user == username && pass == password
        }
        sshd.fileSystemFactory = VirtualFileSystemFactory(rootDir)
        if (enableSftp) {
            sshd.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        }
        sshd.commandFactory = ScpCommandFactory.Builder()
            .withDelegate(TestShellCommandFactory(rootDir))
            .build()
        sshd.start()
        port = sshd.boundAddresses.first().let { (it as InetSocketAddress).port }
    }

    override fun close() {
        sshd.stop(true)
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}

/** 非 scp 命令的最小 POSIX shell 实现。 */
private class TestShellCommandFactory(private val rootDir: Path) : CommandFactory {
    override fun createCommand(channel: ChannelSession, command: String): Command {
        return TestShellCommand(command, rootDir)
    }
}

private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * 只实现本工程 SCP 适配器用到的命令：ls -la / mkdir -p / rm -f / rmdir / mv。
 * 路径解析到测试临时目录，输出格式与 GNU coreutils 的 LC_ALL=C 行为一致。
 */
private class TestShellCommand(
    private val commandLine: String,
    private val rootDir: Path
) : Command {

    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null

    override fun setInputStream(inputStream: InputStream) = Unit

    override fun setOutputStream(outputStream: OutputStream) {
        this.outputStream = outputStream
    }

    override fun setErrorStream(errorStream: OutputStream) {
        this.errorStream = errorStream
    }

    override fun setExitCallback(callback: ExitCallback) {
        this.exitCallback = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        val result = try {
            execute(commandLine)
        } catch (e: Exception) {
            ShellResult(1, "", "test shell error: ${e.message}")
        }
        try {
            if (result.stdout.isNotEmpty()) outputStream?.write(result.stdout.toByteArray(StandardCharsets.UTF_8))
            if (result.stderr.isNotEmpty()) errorStream?.write(result.stderr.toByteArray(StandardCharsets.UTF_8))
            outputStream?.flush()
            errorStream?.flush()
        } catch (e: IOException) {
            // 通道可能已被客户端关闭，测试命令无需上报
        }
        exitCallback?.onExit(result.exitCode)
    }

    override fun destroy(channel: ChannelSession) = Unit

    private fun execute(commandLine: String): ShellResult {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return ShellResult(1, "", "empty command")
        var index = 0
        if (tokens[0].contains('=')) index = 1
        if (index >= tokens.size) return ShellResult(1, "", "empty command")
        val command = tokens[index]
        val args = tokens.drop(index + 1)
        return when (command) {
            "ls" -> ls(args)
            "mkdir" -> mkdir(args)
            "rm" -> rm(args)
            "rmdir" -> rmdir(args)
            "mv" -> mv(args)
            else -> ShellResult(1, "", "$command: command not found")
        }
    }

    private fun ls(args: List<String>): ShellResult {
        val pathToken = args.lastOrNull { !it.startsWith("-") } ?: "."
        val target = resolvePath(pathToken)
        if (!Files.exists(target)) {
            return ShellResult(1, "", "ls: cannot access '$pathToken': No such file or directory")
        }
        val now = Instant.now()
        val lines = ArrayList<String>()
        if (Files.isDirectory(target)) {
            lines.add("total 0")
            lines.add(lsLine(target, now, "."))
            lines.add(lsLine(target, now, ".."))
            Files.list(target).use { list ->
                list.sorted().collect(Collectors.toList())
            }.forEach { entry -> lines.add(lsLine(entry, now, entry.fileName.toString())) }
        } else {
            lines.add(lsLine(target, now, target.fileName.toString()))
        }
        return ShellResult(0, lines.joinToString("") { it + "\n" }, "")
    }

    private fun lsLine(path: Path, now: Instant, name: String): String {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val permissions = when {
            attributes.isDirectory -> "drwxr-xr-x"
            attributes.isSymbolicLink -> "lrwxrwxrwx"
            else -> "-rw-r--r--"
        }
        val size = if (attributes.isDirectory) 4096L else attributes.size()
        return "$permissions 1 tester users $size ${formatLsTime(attributes.lastModifiedTime().toInstant(), now)} $name"
    }

    private fun mkdir(args: List<String>): ShellResult {
        val pathToken = args.lastOrNull { !it.startsWith("-") } ?: return ShellResult(1, "", "mkdir: missing operand")
        val target = resolvePath(pathToken)
        return try {
            if (Files.isDirectory(target)) {
                ShellResult(0, "", "")
            } else if (Files.exists(target)) {
                ShellResult(1, "", "mkdir: cannot create directory '$pathToken': File exists")
            } else {
                Files.createDirectories(target)
                ShellResult(0, "", "")
            }
        } catch (e: IOException) {
            ShellResult(1, "", "mkdir: cannot create directory '$pathToken': ${e.message}")
        }
    }

    private fun rm(args: List<String>): ShellResult {
        val pathToken = args.lastOrNull { !it.startsWith("-") } ?: return ShellResult(1, "", "rm: missing operand")
        val target = resolvePath(pathToken)
        if (Files.isDirectory(target)) {
            return ShellResult(1, "", "rm: cannot remove '$pathToken': Is a directory")
        }
        return try {
            Files.deleteIfExists(target)
            ShellResult(0, "", "")
        } catch (e: IOException) {
            ShellResult(1, "", "rm: cannot remove '$pathToken': ${e.message}")
        }
    }

    private fun rmdir(args: List<String>): ShellResult {
        val pathToken = args.lastOrNull { !it.startsWith("-") } ?: return ShellResult(1, "", "rmdir: missing operand")
        val target = resolvePath(pathToken)
        if (!Files.isDirectory(target)) {
            return ShellResult(1, "", "rmdir: failed to remove '$pathToken': Not a directory")
        }
        return try {
            Files.delete(target)
            ShellResult(0, "", "")
        } catch (e: IOException) {
            ShellResult(1, "", "rmdir: failed to remove '$pathToken': ${e.message}")
        }
    }

    private fun mv(args: List<String>): ShellResult {
        if (args.size < 2) return ShellResult(1, "", "mv: missing file operand")
        val from = resolvePath(args[0])
        val to = resolvePath(args[1])
        return try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            ShellResult(0, "", "")
        } catch (e: IOException) {
            ShellResult(1, "", "mv: cannot move '${args[0]}' to '${args[1]}': ${e.message}")
        }
    }

    private fun resolvePath(token: String): Path {
        val base = rootDir.toAbsolutePath().normalize()
        val resolved = if (token.startsWith("/")) {
            base.resolve(token.removePrefix("/")).normalize()
        } else {
            base.resolve(token).normalize()
        }
        return if (resolved.startsWith(base)) resolved else base
    }

    /** 极简 POSIX shell 参数切分：支持单引号包裹的参数（生产代码的 shellQuote 只输出单引号形式）。 */
    private fun tokenize(command: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        for (char in command) {
            when {
                char == '\'' -> quoted = !quoted
                char == ' ' && !quoted -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private companion object {
        val RECENT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(ZoneId.systemDefault())
        val OLD_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d  yyyy", Locale.US).withZone(ZoneId.systemDefault())

        fun formatLsTime(instant: Instant, now: Instant): String {
            val sixMonthsAgo = now.minusSeconds(183L * 24L * 60L * 60L)
            return if (instant.isAfter(sixMonthsAgo)) RECENT_FORMAT.format(instant) else OLD_FORMAT.format(instant)
        }
    }
}

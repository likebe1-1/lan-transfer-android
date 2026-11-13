package com.manst.lantransfer.server.ssh

import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.AbstractCommandSupport
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryNotEmptyException
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

/** 服务端 shell 命令执行结果（退出码 + stdout + stderr）。 */
internal data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * SCP 模式的最小 POSIX shell 命令实现，只注册本工程 SCP 客户端会执行的元数据命令：
 * - LC_ALL=C ls -la <path>（Unix 长列表，格式与 protocol/ScpListingParser 兼容）
 * - mkdir -p <path>、rmdir <path>、rm -f <path>、mv <from> <to>
 * - pwd / true（标准客户端探测用）
 *
 * 所有路径都限定在共享根目录内（lexical 归一化 + 父目录真实路径校验 + 符号链接逃逸防护）。
 * 命令参数由 [ShellCommandLine] 按 POSIX shell 规则切分（支持单/双引号与反斜杠转义，
 * 我们的客户端使用 shellQuote 生成的单引号形式，包括内嵌单引号的转义写法）。
 */
internal class SshShellCommandFactory(shareRoot: Path) : CommandFactory {

    private val commands = SshShellCommands(shareRoot)

    override fun createCommand(channel: ChannelSession, command: String): Command {
        return SshShellCommand(command, commands)
    }
}

/** 单条 shell 命令：在 SSHD 分配的独立线程里同步执行，全部输出写回通道。 */
private class SshShellCommand(
    private val commandLine: String,
    private val commands: SshShellCommands
) : AbstractCommandSupport(commandLine, null) {

    override fun run() {
        val result = try {
            commands.execute(commandLine)
        } catch (e: IOException) {
            ShellResult(1, "", e.message ?: "命令执行失败")
        } catch (e: IllegalArgumentException) {
            ShellResult(1, "", e.message ?: "命令参数非法")
        } catch (e: SecurityException) {
            ShellResult(1, "", "没有权限访问该路径")
        } catch (e: RuntimeException) {
            ShellResult(1, "", "命令执行异常：" + (e.message ?: e.javaClass.simpleName))
        }

        try {
            if (result.stdout.isNotEmpty()) {
                getOutputStream()?.write(result.stdout.toByteArray(StandardCharsets.UTF_8))
            }
            if (result.stderr.isNotEmpty()) {
                getErrorStream()?.write((result.stderr + "\n").toByteArray(StandardCharsets.UTF_8))
            }
            getOutputStream()?.flush()
            getErrorStream()?.flush()
        } catch (e: IOException) {
            // 客户端可能已关闭通道，服务端无需再上报
        }
        onExit(result.exitCode, result.stderr.take(200))
    }
}

/** 可独立测试的 shell 命令实现（不依赖 SSH 通道）。 */
internal class SshShellCommands(shareRoot: Path) {

    private val paths = SharePathResolver(shareRoot)

    fun execute(commandLine: String): ShellResult {
        val tokens = ShellCommandLine.split(commandLine)
        val args = dropEnvironmentAssignments(tokens)
        if (args.isEmpty()) return ShellResult(1, "", "空命令")
        val command = args[0]
        val rest = args.drop(1)
        return when (command) {
            "ls" -> ls(rest)
            "mkdir" -> mkdir(rest)
            "rmdir" -> rmdir(rest)
            "rm" -> rm(rest)
            "mv" -> mv(rest)
            "pwd" -> ShellResult(0, "/\n", "")
            "true" -> ShellResult(0, "", "")
            else -> ShellResult(127, "", command + ": command not found")
        }
    }

    private fun dropEnvironmentAssignments(tokens: List<String>): List<String> {
        var index = 0
        while (index < tokens.size && ShellCommandLine.isEnvironmentAssignment(tokens[index])) index++
        if (index < tokens.size && tokens[index] == "env") {
            index++
            while (index < tokens.size && ShellCommandLine.isEnvironmentAssignment(tokens[index])) index++
        }
        return tokens.drop(index)
    }

    /** 兼容本工程客户端的 "LC_ALL=C ls -la '<path>'"：忽略所有选项，取最后一个非选项参数作为路径。 */
    private fun ls(args: List<String>): ShellResult {
        val target = args.lastOrNull { !it.startsWith("-") } ?: "/"
        val path = paths.resolveExisting(target)
            ?: return ShellResult(1, "", "ls: cannot access '" + target + "': No such file or directory")
        return try {
            val now = Instant.now()
            val builder = StringBuilder()
            if (Files.isDirectory(path)) {
                builder.append("total 0\n")
                builder.append(listingLine(path, ".", now))
                builder.append(listingLine(path, "..", now))
                val children = Files.list(path).use { stream ->
                    stream.sorted().collect(Collectors.toList())
                }
                for (child in children) {
                    val name = child.fileName?.toString() ?: continue
                    // 换行符会破坏行式协议，直接跳过（这类文件在列表中不可见，但不会被错误解析）
                    if (name.contains('\n') || name.contains('\r')) continue
                    builder.append(listingLine(child, name, now))
                }
            } else {
                builder.append(listingLine(path, path.fileName?.toString() ?: target, now))
            }
            ShellResult(0, builder.toString(), "")
        } catch (e: IOException) {
            ShellResult(1, "", "ls: cannot access '" + target + "': " + (e.message ?: "读取失败"))
        }
    }

    private fun mkdir(args: List<String>): ShellResult {
        val target = args.lastOrNull { !it.startsWith("-") }
            ?: return ShellResult(1, "", "mkdir: missing operand")
        val path = paths.resolveForCreate(target)
            ?: return ShellResult(1, "", "mkdir: cannot create directory '" + target + "': 路径越界或父目录不可用")
        return try {
            when {
                Files.isDirectory(path) -> ShellResult(0, "", "")
                Files.exists(path, LinkOption.NOFOLLOW_LINKS) ->
                    ShellResult(1, "", "mkdir: cannot create directory '" + target + "': File exists")
                else -> {
                    Files.createDirectories(path)
                    ShellResult(0, "", "")
                }
            }
        } catch (e: IOException) {
            ShellResult(1, "", "mkdir: cannot create directory '" + target + "': " + (e.message ?: "创建失败"))
        }
    }

    private fun rm(args: List<String>): ShellResult {
        val force = args.any { it.startsWith("-") && it.contains('f') }
        val target = args.lastOrNull { !it.startsWith("-") }
            ?: return ShellResult(1, "", "rm: missing operand")
        val path = paths.resolveForWrite(target)
            ?: return ShellResult(1, "", "rm: cannot remove '" + target + "': 路径越界或父目录不可用")
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return if (force) {
                ShellResult(0, "", "")
            } else {
                ShellResult(1, "", "rm: cannot remove '" + target + "': No such file or directory")
            }
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return ShellResult(1, "", "rm: cannot remove '" + target + "': Is a directory")
        }
        return try {
            Files.delete(path)
            ShellResult(0, "", "")
        } catch (e: IOException) {
            ShellResult(1, "", "rm: cannot remove '" + target + "': " + (e.message ?: "删除失败"))
        }
    }

    private fun rmdir(args: List<String>): ShellResult {
        val target = args.lastOrNull { !it.startsWith("-") }
            ?: return ShellResult(1, "", "rmdir: missing operand")
        val path = paths.resolveForWrite(target)
            ?: return ShellResult(1, "", "rmdir: failed to remove '" + target + "': 路径越界或父目录不可用")
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return ShellResult(1, "", "rmdir: failed to remove '" + target + "': Not a directory")
        }
        return try {
            Files.delete(path)
            ShellResult(0, "", "")
        } catch (e: DirectoryNotEmptyException) {
            ShellResult(1, "", "rmdir: failed to remove '" + target + "': Directory not empty")
        } catch (e: IOException) {
            ShellResult(1, "", "rmdir: failed to remove '" + target + "': " + (e.message ?: "删除失败"))
        }
    }

    private fun mv(args: List<String>): ShellResult {
        val operands = args.filter { !it.startsWith("-") }
        if (operands.size < 2) return ShellResult(1, "", "mv: missing file operand")
        val fromToken = operands[operands.size - 2]
        val toToken = operands[operands.size - 1]
        val from = paths.resolveForWrite(fromToken)
            ?: return ShellResult(1, "", "mv: cannot move '" + fromToken + "': 路径越界或父目录不可用")
        val to = paths.resolveForWrite(toToken)
            ?: return ShellResult(1, "", "mv: cannot move '" + fromToken + "' to '" + toToken + "': 目标路径越界或父目录不可用")
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            return ShellResult(1, "", "mv: cannot stat '" + fromToken + "': No such file or directory")
        }
        return try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            ShellResult(0, "", "")
        } catch (e: IOException) {
            ShellResult(1, "", "mv: cannot move '" + fromToken + "' to '" + toToken + "': " + (e.message ?: "移动失败"))
        }
    }

    /** 生成一行与 protocol/ScpListingParser 兼容的 Unix 长列表。 */
    private fun listingLine(path: Path, displayName: String, now: Instant): String {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val permissions = when {
            attributes.isDirectory -> "drwxr-xr-x"
            attributes.isSymbolicLink -> "lrwxrwxrwx"
            else -> "-rw-r--r--"
        }
        val size = if (attributes.isDirectory) DIRECTORY_LIST_SIZE else attributes.size()
        val linkTarget = if (attributes.isSymbolicLink) {
            try {
                " -> " + Files.readSymbolicLink(path)
            } catch (e: IOException) {
                ""
            }
        } else {
            ""
        }
        val time = formatListingTime(attributes.lastModifiedTime().toInstant(), now)
        return permissions + " 1 lantransfer lantransfer " + size + " " + time + " " + displayName + linkTarget + "\n"
    }

    private fun formatListingTime(instant: Instant, now: Instant): String {
        val sixMonthsAgo = now.minusSeconds(HALF_YEAR_SECONDS)
        return if (instant.isAfter(sixMonthsAgo)) {
            RECENT_TIME_FORMAT.format(instant)
        } else {
            OLD_TIME_FORMAT.format(instant)
        }
    }

    private companion object {
        const val DIRECTORY_LIST_SIZE = 4096L
        const val HALF_YEAR_SECONDS = 183L * 24L * 60L * 60L
        val RECENT_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(ZoneId.systemDefault())
        val OLD_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d  yyyy", Locale.US).withZone(ZoneId.systemDefault())
    }
}

/**
 * 共享根目录下的路径解析与越界防护。
 *
 * - [resolveExisting]：读取类操作，跟随符号链接，要求最终真实路径仍在共享根内；
 * - [resolveForWrite]：删除/移动类操作，不跟随最后一段（避免删到链接目标），父目录真实路径必须在根内；
 * - [resolveForCreate]：创建类操作，允许中间目录尚不存在，但最近的已存在祖先真实路径必须在根内。
 */
internal class SharePathResolver(shareRoot: Path) {

    private val root: Path = shareRoot.toAbsolutePath().normalize()
    private val realRoot: Path = try {
        root.toRealPath()
    } catch (e: IOException) {
        root
    }

    fun resolveExisting(rawPath: String): Path? {
        val lexical = lexicalPath(rawPath) ?: return null
        if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) return null
        val real = try {
            lexical.toRealPath()
        } catch (e: IOException) {
            return null
        }
        return real.takeIf { it.startsWith(realRoot) }
    }

    fun resolveForWrite(rawPath: String): Path? {
        val lexical = lexicalPath(rawPath) ?: return null
        val parent = lexical.parent ?: return null
        if (!Files.isDirectory(parent)) return null
        val realParent = try {
            parent.toRealPath()
        } catch (e: IOException) {
            return null
        }
        if (!realParent.startsWith(realRoot)) return null
        return realParent.resolve(lexical.fileName)
    }

    fun resolveForCreate(rawPath: String): Path? {
        val lexical = lexicalPath(rawPath) ?: return null
        var ancestor: Path? = lexical
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        val existingAncestor = ancestor ?: return null
        val realAncestor = try {
            existingAncestor.toRealPath()
        } catch (e: IOException) {
            return null
        }
        if (!realAncestor.startsWith(realRoot)) return null

        val relativeSegments = ArrayList<String>()
        var cursor: Path? = lexical
        while (cursor != null && cursor != existingAncestor) {
            relativeSegments.add(cursor.fileName?.toString() ?: return null)
            cursor = cursor.parent
        }
        if (cursor == null) return null
        var result = realAncestor
        for (segment in relativeSegments.asReversed()) {
            result = result.resolve(segment)
        }
        val normalized = result.normalize()
        return normalized.takeIf { it.startsWith(realRoot) }
    }

    private fun lexicalPath(rawPath: String): Path? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.startsWith("/")) {
            root.resolve(trimmed.removePrefix("/"))
        } else {
            root.resolve(trimmed)
        }
        val normalized = candidate.normalize()
        return normalized.takeIf { it.startsWith(root) }
    }
}

/** POSIX shell 参数切分（供 shellQuote 生成的命令使用）。 */
internal object ShellCommandLine {

    private val ENV_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    fun isEnvironmentAssignment(token: String): Boolean = ENV_ASSIGNMENT.matches(token)

    fun split(command: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var index = 0
        while (index < command.length) {
            val char = command[index]
            when (quote) {
                '\'' -> {
                    if (char == '\'') quote = null else current.append(char)
                }
                '"' -> {
                    if (char == '"') {
                        quote = null
                    } else if (char == '\\' && index + 1 < command.length) {
                        index++
                        current.append(command[index])
                    } else {
                        current.append(char)
                    }
                }
                else -> when {
                    char == '\'' || char == '"' -> {
                        quote = char
                        inToken = true
                    }
                    char == '\\' && index + 1 < command.length -> {
                        index++
                        current.append(command[index])
                        inToken = true
                    }
                    char == ' ' || char == '\t' || char == '\n' || char == '\r' -> {
                        if (inToken) {
                            tokens.add(current.toString())
                            current.setLength(0)
                            inToken = false
                        }
                    }
                    else -> {
                        current.append(char)
                        inToken = true
                    }
                }
            }
            index++
        }
        if (quote != null) {
            throw IllegalArgumentException("命令参数中存在未闭合的引号")
        }
        if (inToken) tokens.add(current.toString())
        return tokens
    }
}

package com.manst.lantransfer.server.ssh

import com.manst.lantransfer.protocol.ScpListingParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator

/**
 * SCP 元数据命令（ls/mkdir/rmdir/rm/mv）与 POSIX 参数解析的单元测试。
 * 关键验证：ls 输出必须能被 [ScpListingParser] 正确解析（本工程 SCP 客户端共用该解析器），
 * 以及越界路径/符号链接逃逸必须被拒绝。
 */
class SshShellCommandsTest {

    private lateinit var workDir: Path
    private lateinit var root: Path
    private lateinit var commands: SshShellCommands

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("lantransfer-shell-")
        root = workDir.resolve("share")
        Files.createDirectories(root)
        Files.write(root.resolve("hello.txt"), "abc".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("空 格.txt"), "中文".toByteArray(StandardCharsets.UTF_8))
        Files.createDirectory(root.resolve("docs"))
        Files.write(root.resolve("docs").resolve("readme.md"), "readme".toByteArray(StandardCharsets.UTF_8))
        Files.createDirectory(root.resolve("emptydir"))
        commands = SshShellCommands(root)
    }

    @After
    fun tearDown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    @Test
    fun tokenizerHandlesPosixQuoting() {
        assertEquals(
            listOf("LC_ALL=C", "ls", "-la", "/a b"),
            ShellCommandLine.split("LC_ALL=C ls -la '/a b'")
        )
        // 客户端 shellQuote 对内嵌单引号的转义：'/it'\''s'
        assertEquals(
            listOf("mv", "/it's", "/new name"),
            ShellCommandLine.split("mv '/it'\\''s' '/new name'")
        )
        assertEquals(
            listOf("ls", "-la", "/double quoted"),
            ShellCommandLine.split("ls -la \"/double quoted\"")
        )
        assertEquals(emptyList<String>(), ShellCommandLine.split("   "))
        assertTrue(ShellCommandLine.isEnvironmentAssignment("LC_ALL=C"))
        assertFalse(ShellCommandLine.isEnvironmentAssignment("ls"))
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandLine.split("ls '/unterminated")
        }
    }

    @Test
    fun lsOutputCanBeParsedByScpListingParser() {
        val result = commands.execute("LC_ALL=C ls -la '/'")
        assertEquals(0, result.exitCode)
        val entries = ScpListingParser.parse(result.stdout, "/")
        val byName = entries.associateBy { it.name }
        assertTrue("应包含 hello.txt", byName.containsKey("hello.txt"))
        assertEquals(3L, byName.getValue("hello.txt").size)
        assertTrue("应包含 docs 目录", byName.getValue("docs").isDirectory)
        assertTrue("应包含 UTF-8 文件名", byName.containsKey("空 格.txt"))
        assertTrue("应解析出修改时间", entries.all { it.modifiedAt > 0L })
        assertTrue("应过滤 . 与 ..", entries.none { it.name == "." || it.name == ".." })
    }

    @Test
    fun lsOfSingleFileReturnsOneEntry() {
        val result = commands.execute("ls -la '/hello.txt'")
        assertEquals(0, result.exitCode)
        val entries = ScpListingParser.parse(result.stdout, "/")
        assertEquals(listOf("hello.txt"), entries.map { it.name })
        assertEquals(3L, entries.first().size)
    }

    @Test
    fun lsMissingPathFails() {
        val result = commands.execute("ls -la '/missing'")
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("No such file or directory"))
    }

    @Test
    fun mkdirCreatesNestedDirectoriesAndIsIdempotent() {
        assertEquals(0, commands.execute("mkdir -p '/a/b'").exitCode)
        assertTrue(Files.isDirectory(root.resolve("a").resolve("b")))
        assertEquals("已存在目录应视为成功", 0, commands.execute("mkdir -p '/a/b'").exitCode)
        assertEquals(0, commands.execute("mkdir -p '/a/b/深层 目录'").exitCode)
        assertTrue(Files.isDirectory(root.resolve("a").resolve("b").resolve("深层 目录")))
        assertNotEquals("已存在同名文件应失败", 0, commands.execute("mkdir -p '/hello.txt'").exitCode)
    }

    @Test
    fun rmDeletesFilesAndToleratesMissingWithForce() {
        val temporary = root.resolve("temp.bin")
        Files.write(temporary, byteArrayOf(1, 2, 3))
        assertEquals(0, commands.execute("rm -f '/temp.bin'").exitCode)
        assertFalse(Files.exists(temporary))
        assertEquals("rm -f 对不存在文件应成功", 0, commands.execute("rm -f '/temp.bin'").exitCode)
        assertNotEquals("rm 不能删除目录", 0, commands.execute("rm -f '/docs'").exitCode)
    }

    @Test
    fun rmdirDeletesOnlyEmptyDirectories() {
        assertEquals(0, commands.execute("rmdir '/emptydir'").exitCode)
        assertFalse(Files.exists(root.resolve("emptydir")))
        assertNotEquals("非空目录应失败", 0, commands.execute("rmdir '/docs'").exitCode)
        assertTrue(Files.isDirectory(root.resolve("docs")))
    }

    @Test
    fun mvRenamesFiles() {
        assertEquals(0, commands.execute("mv '/hello.txt' '/renamed.txt'").exitCode)
        assertFalse(Files.exists(root.resolve("hello.txt")))
        assertTrue(Files.exists(root.resolve("renamed.txt")))
        assertNotEquals("源不存在应失败", 0, commands.execute("mv '/nope.txt' '/x.txt'").exitCode)
    }

    @Test
    fun pathTraversalIsRejected() {
        assertNotEquals(0, commands.execute("ls -la '/..'").exitCode)
        assertNotEquals(0, commands.execute("ls -la '/../..'").exitCode)
        assertNotEquals(0, commands.execute("mkdir -p '/../evil'").exitCode)
        assertNotEquals(0, commands.execute("rm -f '/../evil'").exitCode)
        assertNotEquals(0, commands.execute("mv '/hello.txt' '/../evil'").exitCode)
        assertFalse(Files.exists(workDir.resolve("evil")))
        assertTrue("越界操作不应影响共享目录内文件", Files.exists(root.resolve("hello.txt")))
    }

    @Test
    fun symlinkEscapeIsRejectedForReadsButLinkItselfCanBeRemoved() {
        assumeFalse(
            "Windows 上创建符号链接需要额外权限，跳过",
            System.getProperty("os.name").orEmpty().lowercase().contains("windows")
        )
        val outside = workDir.resolve("outside")
        Files.createDirectories(outside)
        Files.write(outside.resolve("secret.txt"), "secret".toByteArray(StandardCharsets.UTF_8))
        Files.createSymbolicLink(root.resolve("link"), outside)

        assertNotEquals("读取越界符号链接应被拒绝", 0, commands.execute("ls -la '/link'").exitCode)
        assertEquals("删除链接本身应允许", 0, commands.execute("rm -f '/link'").exitCode)
        assertFalse(Files.exists(root.resolve("link"), LinkOption.NOFOLLOW_LINKS))
        assertTrue("链接目标文件不应被删除", Files.exists(outside.resolve("secret.txt")))
    }

    @Test
    fun environmentPrefixAndUnknownCommand() {
        assertEquals(0, commands.execute("LC_ALL=C mkdir -p '/via-env'").exitCode)
        assertTrue(Files.isDirectory(root.resolve("via-env")))

        val unknown = commands.execute("whoami")
        assertEquals(127, unknown.exitCode)
        assertTrue(unknown.stderr.contains("command not found"))

        val pwd = commands.execute("pwd")
        assertEquals(0, pwd.exitCode)
        assertEquals("/", pwd.stdout.trim())
    }
}

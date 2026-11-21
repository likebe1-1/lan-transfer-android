package com.manst.lantransfer.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.server.ftp.FtpProtocolServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ThreadLocalRandom

/**
 * 服务端认证失败锁定（设备测试）：
 * 连续用错误密码登录本机 FTP 服务器，达到阈值后应返回 421，
 * 且锁定期间即使输入正确密码也被拒绝——防止接收码被暴力枚举。
 */
@RunWith(AndroidJUnit4::class)
class ServerAuthLockoutDeviceTest {

    private fun login(port: Int, username: String, password: String): String {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = socket.getOutputStream()
            reader.readLine() // 220 banner
            fun command(line: String): String {
                writer.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                writer.flush()
                return reader.readLine() ?: ""
            }
            val userReply = command("USER " + username)
            // 已锁定时服务端在 USER 阶段直接返回 421
            if (userReply.startsWith("421")) return userReply
            return command("PASS " + password)
        }
    }

    @Test
    fun repeatedWrongPassword_locksOutWith421() {
        val port = ThreadLocalRandom.current().nextInt(41_000, 45_000)
        val shareRoot = File(System.getProperty("java.io.tmpdir"), "auth-lockout-" + System.nanoTime())
        shareRoot.mkdirs()
        val server = FtpProtocolServer(TransferProtocol.FTP)
        val binding = ServerBinding(
            port = port,
            shareRoot = shareRoot,
            username = "tester",
            password = "correct-password"
        )
        runBlocking { server.start(binding) }
        try {
            var locked = false
            repeat(8) { index ->
                val reply = login(port, "tester", "wrong-" + index)
                if (reply.startsWith("421")) locked = true
            }
            assertTrue("连续错误密码应触发 421 锁定，最后一次回复未锁定", locked)
            val correct = login(port, "tester", "correct-password")
            assertTrue("锁定期间正确密码也应被拒绝，实际：" + correct, correct.startsWith("421"))
            assertFalse("锁定期间不应登录成功", correct.startsWith("230"))
        } finally {
            runBlocking { server.stop() }
            shareRoot.deleteRecursively()
        }
    }
}

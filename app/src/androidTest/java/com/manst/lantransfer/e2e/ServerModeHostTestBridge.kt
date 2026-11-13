package com.manst.lantransfer.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.server.LocalServerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「另一台设备连接本机服务器」的宿主侧端到端测试桥：
 * 在设备上以固定端口/帐号启动本机服务器，等待宿主脚本通过 adb forward 连接并完成四种协议收发，
 * 宿主脚本最后用 adb run-as 在应用私有目录创建 .hosttest-done 通知测试结束。
 *
 * 测试断言：宿主通过每种协议上传的文件都出现在共享目录。
 */
@RunWith(AndroidJUnit4::class)
class ServerModeHostTestBridge {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: LanTransferApplication
        get() = context.applicationContext as LanTransferApplication

    private fun log(message: String) {
        Log.e(tag, "HOST-E2E " + message)
    }

    @Test
    fun serveForHostClients() = runBlocking {
        val server = app.container.localServerManager
        val share = app.container.shareRepository
        server.stop()

        val shareRoot = share.directory().apply { mkdirs() }
        File(shareRoot, "hello.txt").writeText("hello from android server\n", Charsets.UTF_8)
        File(shareRoot, "sample.bin").writeBytes(ByteArray(256 * 1024) { index -> (index % 251).toByte() })
        share.refresh()

        val config = LocalServerConfig(
            enableFtp = true,
            enableFtps = true,
            enableSftp = true,
            enableScp = true,
            ftpPort = 2121,
            ftpsPort = 2122,
            sshPort = 2222,
            username = "test",
            password = "test"
        )
        val started = server.start(config)
        log("READY=" + started.isSuccess + " endpoints=" + server.state.value.endpoints)
        assertTrue("服务器启动失败: " + (started.exceptionOrNull()?.message ?: ""), started.isSuccess)

        val sentinel = File(context.filesDir, ".hosttest-done")
        sentinel.delete()
        log("WAITING_FOR_HOST")
        val deadline = System.currentTimeMillis() + 150_000L
        while (System.currentTimeMillis() < deadline) {
            if (sentinel.isFile) break
            delay(500)
        }
        assertTrue("等待宿主测试超时（未收到 .hosttest-done）", sentinel.isFile)

        share.refresh()
        val names = share.files.value.map { it.name }
        log("SHARE_AFTER_HOST=" + names)
        deleteQuietly(shareRoot, "upload-ftp.bin")
        deleteQuietly(shareRoot, "upload-ftps.bin")
        deleteQuietly(shareRoot, "upload-sftp.bin")
        deleteQuietly(shareRoot, "upload-scp-keep.bin")
        assertTrue("宿主 FTP 上传的文件应出现在共享目录", names.contains("upload-ftp.bin"))
        assertTrue("宿主 FTPS 上传的文件应出现在共享目录", names.contains("upload-ftps.bin"))
        assertTrue("宿主 SFTP 上传的文件应出现在共享目录", names.contains("upload-sftp.bin"))
        assertTrue("宿主 SCP 上传的文件应出现在共享目录", names.contains("upload-scp-keep.bin"))

        server.stop()
        share.refresh()
        log("DONE")
    }

    private fun deleteQuietly(root: File, name: String) {
        runCatching { File(root, name).delete() }
    }
}
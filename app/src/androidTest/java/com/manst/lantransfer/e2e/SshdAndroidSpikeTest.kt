package com.manst.lantransfer.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.BaseBuilder
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 可行性验证：Apache MINA SSHD 能否在 Android 运行时正常启动并被 JSch 客户端连接。
 * 这是「本机服务器 SFTP/SCP」架构的前置验证，通过后由 server/ssh 实现正式复用。
 */
@RunWith(AndroidJUnit4::class)
class SshdAndroidSpikeTest {

    private val tag = "E2EDIAG"

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sshdStartsAndServesSftpOnAndroid() {
        // Android 上没有 user.home，MINA SSHD 官方为 Android 提供了该解析器注入点
        PathUtils.setUserHomeFolderResolver { context.filesDir.toPath() }
        // 关键：App 里带了 BouncyCastle（FTPS 自签证书用），SSHD 会误把 Android 内置的 "BC" provider
        // 当作自己的实现并优先使用，而 Android 内置 BC 缺少 ECDH 等算法，导致握手失败。
        // 禁用 SSHD 的 BC provider 注册，让其使用平台默认 JCE provider。
        SecurityUtils.setAPrioriDisabledProvider("BC", true)
        SecurityUtils.setAPrioriDisabledProvider("EdDSA", true)

        val port = 12222
        val rootDir = File(context.cacheDir, "sshd-spike").apply { mkdirs() }
        val hostKeyFile = File(rootDir, "hostkey.ser")
        File(rootDir, "hello.txt").writeText("hello sshd on android\n", Charsets.UTF_8)

        var sshd: SshServer? = null
        try {
            sshd = SshServer.setUpDefaultServer().also { created ->
                val dhStatus = BuiltinDHFactories.VALUES.joinToString(",") { it.name + "=" + it.isSupported() }
                Log.e(tag, "SPIKE BuiltinDHFactories: " + dhStatus)
                Log.e(
                    tag,
                    "SPIKE defaults: kex=" + (created.keyExchangeFactories?.size ?: -1) +
                        " ciphers=" + (created.cipherFactories?.size ?: -1) +
                        " sig=" + (created.signatureFactories?.size ?: -1) +
                        " macs=" + (created.macFactories?.size ?: -1) +
                        " compression=" + (created.compressionFactories?.size ?: -1)
                )
                // 若默认值缺失，显式安装默认工厂（Android 上曾观察到，作为兜底）
                // Android 上 SecurityUtils 把所有内置 kex 判定为 unsupported，默认列表为空。
                // 这里显式给出 Android JCE 支持的 kex 列表（ECDH nistp 优先，其次有限域 DH 组），
                // 并用未经过滤的默认签名工厂列表（否则 RSA 主机密钥会报 no resolved signatures）。
                val preferredKex = listOf(
                    BuiltinDHFactories.ecdhp256,
                    BuiltinDHFactories.ecdhp384,
                    BuiltinDHFactories.ecdhp521,
                    BuiltinDHFactories.dhg14_256,
                    BuiltinDHFactories.dhg16_512,
                    BuiltinDHFactories.dhg18_512,
                    BuiltinDHFactories.dhg14,
                    BuiltinDHFactories.dhg15_512,
                    BuiltinDHFactories.dhg17_512,
                    BuiltinDHFactories.dhg1
                )
                created.keyExchangeFactories = preferredKex.mapNotNull { ServerBuilder.DH2KEX.apply(it) }
                created.signatureFactories = ServerBuilder.setUpDefaultSignatureFactories(false)
            }.apply {
                host = "127.0.0.1"
                this.port = port
                keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath()).apply {
                    // Android 上 ECDSA 支持检测不可靠（hostKeys 为空），显式使用 RSA 2048 主机密钥
                    algorithm = "RSA"
                    keySize = 2048
                }
                setPasswordAuthenticator { username, password, _ ->
                    username == "test" && password == "test"
                }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(rootDir.toPath())
            }
            Log.e(
                tag,
                "SPIKE after setup: kex=" + sshd.keyExchangeFactories?.map { it.name } +
                    " sig=" + sshd.signatureFactories?.map { it.name } +
                    " hostKeys=" + runCatching { sshd.keyPairProvider?.getKeyTypes(null) }.getOrNull()
            )
            sshd.start()
            Log.e(tag, "SPIKE sshd started on " + port)

            val jsch = JSch()
            val session = jsch.getSession("test", "127.0.0.1", port)
            session.setPassword("test")
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(15_000)
            Log.e(tag, "SPIKE ssh session connected")

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            val names = channel.ls("/").mapNotNull { entry ->
                (entry as? ChannelSftp.LsEntry)?.filename
            }
            Log.e(tag, "SPIKE sftp ls=" + names)
            assertTrue("SFTP 根目录应包含 hello.txt", names.contains("hello.txt"))
            assertEquals("hello sshd on android\n", channel.get("/hello.txt").bufferedReader().readText())
            channel.disconnect()
            session.disconnect()
        } finally {
            runCatching { sshd?.stop(true) }
        }
    }
}
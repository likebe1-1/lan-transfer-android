package com.manst.lantransfer.server.ssh

import com.manst.lantransfer.server.ServerAuthGuard
import com.manst.lantransfer.server.ServerBinding
import com.manst.lantransfer.server.SshServerComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.BaseBuilder
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.IOException
import java.net.BindException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Apache MINA SSHD 实现的 SFTP / SCP 服务端（同一个 SSH 监听端口）。
 *
 * 设计要点：
 * - 监听 0.0.0.0:binding.port，start 返回时端口已处于监听状态；
 * - 密码认证：用户名/密码来自 [ServerBinding]，使用常量时间比较避免时序侧信道；
 * - 共享目录：VirtualFileSystemFactory 把会话文件系统限制在 [ServerBinding.shareRoot]，客户端无法越出共享根；
 * - SFTP：SftpSubsystemFactory；SCP：ScpCommandFactory（内建 scp -f / scp -t 线协议），
 *   其 delegate 实现本工程 SCP 客户端使用的 ls/mkdir/rmdir/rm/mv 等元数据命令（见 [SshShellCommandFactory]）；
 * - 主机密钥：SimpleGeneratorHostKeyProvider 生成 RSA 2048 并持久化到 [hostKeyFile]（默认
 *   java.io.tmpdir/lantransfer_hostkey；Android 上即应用 cache 目录），重启复用同一把主机密钥；
 * - Android 兼容：显式指定 Nio2ServiceFactoryFactory，避免 DefaultIoServiceFactoryFactory 通过
 *   ServiceLoader 探测 IO 实现（Android 打包/裁剪后可能失败）；SSHD 2.14 jar 为 Java 8 字节码，
 *   仅使用 API 26+ 已有的 java.nio.file/异步通道 API；
 * - 纯 JVM 实现，不依赖 Android API。
 *
 * 生命周期：start 内部会先停止旧实例；stop 幂等，释放监听端口与内部线程。
 */
class SshProtocolServer(
    private val hostKeyFile: File = defaultHostKeyFile()
) : SshServerComponent {

    @Volatile
    private var sshd: SshServer? = null

    override suspend fun start(binding: ServerBinding, enableSftp: Boolean, enableScp: Boolean): Unit {
        // 必须在任何 SSHD 代码触发 SecurityUtils.register() 之前执行（进程级设置，幂等）
        disableSshdBundledSecurityProviders()
        withContext(Dispatchers.IO) {
            if (!enableSftp && !enableScp) {
                throw IllegalArgumentException("SFTP 与 SCP 至少需要启用一种协议")
            }
            // 幂等友好：重复 start 时先停掉旧实例，避免端口占用
            stopBlocking()

            val shareRoot = binding.shareRoot.toPath().toAbsolutePath().normalize()
            try {
                Files.createDirectories(shareRoot)
            } catch (e: IOException) {
                throw IOException("无法创建共享目录：" + shareRoot, e)
            }

            val server = createServer(binding, shareRoot, enableSftp, enableScp)
            try {
                server.start()
            } catch (e: Exception) {
                try {
                    server.stop(true)
                } catch (stopError: Exception) {
                    // 启动失败后的清理异常无需上报，原始启动异常更重要
                }
                throw startFailure(binding, e)
            }
            sshd = server
        }
    }

    /**
     * 禁用 SSHD 对 BouncyCastle provider（以及 EdDSA 提供者）的自动注册/优先使用。
     *
     * 本应用为了 FTPS 自签证书在 classpath 里引入了 bcprov；SSHD 的 SecurityUtils 检测到 BC 类后会注册并
     * 优先使用 provider "BC"，而 Android 系统自带的 "BC" provider 能力被裁剪（没有 ECDH KeyAgreement），
     * 握手阶段会报 "NoSuchAlgorithmException: no such algorithm: ECDH for provider BC"。
     * 该设置只影响 SSHD 自身的 provider 选择，不影响其他模块显式 new BouncyCastleProvider() 的用法。
     */
    private fun disableSshdBundledSecurityProviders() {
        SecurityUtils.setAPrioriDisabledProvider("BC", true)
        SecurityUtils.setAPrioriDisabledProvider("EdDSA", true)
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        stopBlocking()
    }

    private fun stopBlocking() {
        val current = sshd ?: return
        sshd = null
        try {
            // immediate=true：立即关闭会话与监听器，保证端口被释放
            current.stop(true)
        } catch (e: Exception) {
            // 停止阶段的异常不影响调用方（管理器会继续清理其他协议）
        }
    }

    private fun createServer(
        binding: ServerBinding,
        shareRoot: Path,
        enableSftp: Boolean,
        enableScp: Boolean
    ): SshServer {
        // Android 没有 user.home：必须在 SSHD 静态初始化（PathUtils$LazyDefaultUserHomeFolderHolder）之前
        // 设置 user home 解析器，否则 setUpDefaultServer() 会抛 ExceptionInInitializerError。纯 JVM 写法。
        ensureUserHomeFolderResolver()

        val server = SshServer.setUpDefaultServer()
        server.host = LISTEN_HOST
        server.port = binding.port

        // Android：避免 ServiceLoader 探测 IO 实现；显式使用 NIO2（API 26+ 可用）
        server.ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
        // Android 上 SecurityUtils 的算法支持性探测会把内建 KEX 全部判为不支持，导致默认列表为空、
        // start() 报 "KeyExchangeFactories not set"。这里显式安装一组保底 KEX（见方法注释）。
        server.keyExchangeFactories = defaultKeyExchangeFactories()

        server.keyPairProvider = createHostKeyProvider()
        // 仅允许密码认证（本机服务器面向局域网设备，不需要公钥/交互式认证）
        server.userAuthFactories = listOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
        server.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            // 故意不使用短路运算，两个比较都执行
            constantTimeEquals(username, binding.username) and constantTimeEquals(password, binding.password)
        }
        server.fileSystemFactory = VirtualFileSystemFactory(shareRoot)

        if (enableSftp) {
            server.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        }
        if (enableScp) {
            server.commandFactory = ScpCommandFactory.Builder()
                .withDelegate(SshShellCommandFactory(shareRoot))
                .build()
        }
        return server
    }

    /**
     * 生成/复用持久化主机密钥。默认文件路径：
     * java.io.tmpdir/lantransfer_hostkey —— Android 上即应用 cache 目录，重启应用后仍是同一把主机密钥。
     */
    private fun createHostKeyProvider(): SimpleGeneratorHostKeyProvider {
        hostKeyFile.parentFile?.mkdirs()
        return SimpleGeneratorHostKeyProvider(hostKeyFile.toPath()).apply {
            setAlgorithm(HOST_KEY_ALGORITHM)
            setKeySize(HOST_KEY_SIZE)
            // 文件损坏/算法不匹配时允许重新生成并覆盖，保证服务始终可启动
            setOverwriteAllowed(true)
            // 应用私有目录本身已有系统沙箱隔离；部分 Android 文件系统不支持设置 POSIX 权限，
            // 关闭严格权限校验避免主机密钥写盘失败导致每次启动换密钥
            setStrictFilePermissions(false)
        }
    }

    /** Android 上没有 user.home，提前给 SSHD 指定一个可写目录（java.io.tmpdir，即应用 cache）。 */
    private fun ensureUserHomeFolderResolver() {
        val android = OsUtils.isAndroid()
        if (!android && !System.getProperty("user.home").isNullOrBlank()) return
        val fallbackHome = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
        PathUtils.setUserHomeFolderResolver { Paths.get(fallbackHome) }
    }

    /**
     * 非空的 KEX 工厂列表（Android 上默认探测结果为空，必须显式安装）。
     *
     * 策略：
     * 1) 保底集合：ECDH nistp256/384/521 + DH group14-sha256/16/18-sha512，这些算法在 Android
     *    JCE/Conscrypt 上运行时可用，也是 OpenSSH 9 / JSch 等标准客户端都支持的公共交集；
     * 2) 额外集合：其余内置算法（curve25519/curve448/sntrup 等）仅在 JCE 探测标记为支持时才加入，
     *    避免在 Android API 29 这类缺少 X25519/X448 的设备上广播实际无法执行的算法，
     *    从而保证标准 OpenSSH 客户端握手成功（它们会把 curve25519 排在 ECDH 之前）。
     */
    private fun defaultKeyExchangeFactories(): List<KeyExchangeFactory> {
        val fallback = listOf(
            BuiltinDHFactories.ecdhp256,
            BuiltinDHFactories.ecdhp384,
            BuiltinDHFactories.ecdhp521,
            BuiltinDHFactories.dhg14_256,
            BuiltinDHFactories.dhg16_512,
            BuiltinDHFactories.dhg18_512
        )
        val extras = BaseBuilder.DEFAULT_KEX_PREFERENCE.filter { factory ->
            factory !in fallback && factory.isSupported()
        }
        return (fallback + extras).map { factory ->
            ServerBuilder.DH2KEX.apply(factory)
        }
    }

    private fun constantTimeEquals(actual: String?, expected: String): Boolean {
        return MessageDigest.isEqual(
            (actual ?: "").toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun startFailure(binding: ServerBinding, error: Exception): IOException {
        val detail = describeStartError(error) ?: (error.message ?: error.javaClass.simpleName)
        return IOException("SFTP/SCP 服务端启动失败（0.0.0.0:" + binding.port + "）：" + detail, error)
    }

    private fun describeStartError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is BindException) {
                return "端口已被占用，请更换 SSH 端口"
            }
            cause = cause.cause
        }
        return null
    }

    companion object {
        private const val LISTEN_HOST = "0.0.0.0"
        private const val HOST_KEY_ALGORITHM = "RSA"
        private const val HOST_KEY_SIZE = 2048
        private const val HOST_KEY_FILE_NAME = "lantransfer_hostkey"

        /** 默认主机密钥文件：java.io.tmpdir/lantransfer_hostkey（Android 上即应用 cache）。 */
        fun defaultHostKeyFile(): File {
            val tempDir = System.getProperty("java.io.tmpdir") ?: "."
            return File(tempDir, HOST_KEY_FILE_NAME)
        }
    }
}

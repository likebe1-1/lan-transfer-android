package com.manst.lantransfer.quick

import com.jcraft.jsch.JSch
import com.manst.lantransfer.protocol.SshHostKeyPinning

/**
 * 读取本机 SSH 服务端的主机密钥指纹（对 127.0.0.1 发起一次「只做密钥交换」的探测）。
 *
 * 为什么不在服务端内部直接算：SSH 主机密钥的线格式（wire format）由 SSHD 生成，
 * 而接收方（JSch）看到的是同一格式。用 JSch 自己连一次本机端口，取到的
 * [com.jcraft.jsch.HostKey.key] 与接收方校验时拿到的字节完全一致，避免手工序列化出错。
 *
 * 探测使用随机密码：即使认证失败，主机密钥在密钥交换阶段就已获取，因此不影响结果。
 * 该函数会阻塞（最多 [timeoutMillis]），调用方必须在 IO 线程执行。
 */
object SshHostKeyProbe {

    fun fingerprintSha256(
        port: Int,
        username: String,
        password: String,
        host: String = "127.0.0.1",
        timeoutMillis: Int = 4_000
    ): String? {
        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null
        return try {
            session = jsch.getSession(username, host, port).apply {
                // 使用会话真实临时凭据：认证成功可避免在服务端限流器里留下失败记录
                setPassword(password)
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password")
                timeout = timeoutMillis
            }
            try {
                session.connect(timeoutMillis)
            } catch (e: Exception) {
                // 认证失败是预期结果：密钥交换已完成，主机密钥已获取
            }
            val hostKey = session.hostKey ?: return null
            // JSch 的 getKey() 返回 SSH 公钥线格式 blob 的 Base64 文本，需解码后与 check() 的字节一致
            val keyBytes = java.util.Base64.getDecoder().decode(hostKey.key)
            SshHostKeyPinning.sha256Hex(keyBytes)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { session?.disconnect() }
        }
    }
}

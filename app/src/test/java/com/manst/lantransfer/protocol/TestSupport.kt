package com.manst.lantransfer.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.util.Random

/** 生成确定性的随机字节，供协议测试做字节级比对。 */
internal fun randomBytes(size: Int, seed: Long = 20261113L): ByteArray {
    val data = ByteArray(size)
    Random(seed).nextBytes(data)
    return data
}

/** 取一个当前未被监听的本地 TCP 端口（用于错误路径测试）。 */
internal fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

/**
 * 写到 [limit] 字节后抛出 IOException 的输出流，用于模拟下载中途断网/取消：
 * 保留已写入的前 [limit] 字节，供断点续传测试与新连接拼接校验。
 */
internal class InterruptingOutputStream(
    private val sink: ByteArrayOutputStream,
    private val limit: Int
) : OutputStream() {

    private var written = 0

    override fun write(b: Int) {
        if (written >= limit) throw IOException("模拟网络中断")
        sink.write(b)
        written++
    }

    override fun write(b: ByteArray, offset: Int, length: Int) {
        val allowed = minOf(length, limit - written)
        if (allowed > 0) {
            sink.write(b, offset, allowed)
            written += allowed
        }
        if (allowed < length) throw IOException("模拟网络中断")
    }

    override fun flush() {
        sink.flush()
    }
}

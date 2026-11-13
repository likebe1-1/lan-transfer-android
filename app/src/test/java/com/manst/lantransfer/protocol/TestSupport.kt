package com.manst.lantransfer.protocol

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

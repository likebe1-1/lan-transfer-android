package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.TransferCancelledException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** 把协议层/IO 异常转换为可直接展示给用户的中文提示（纯 JVM 逻辑，可单元测试）。 */
object TransferErrors {

    fun toUserMessage(error: Throwable, direction: TransferDirection): String {
        val raw = error.message?.trim()?.takeIf { it.isNotEmpty() }
        return when (error) {
            is AuthenticationException -> raw ?: "认证失败，请检查用户名或密码"
            is TransferCancelledException -> "传输已取消"
            is RemotePathException -> raw ?: "远端路径不存在或无法访问"
            is ConnectionException -> raw ?: "无法连接服务器，请检查地址、端口与网络"
            is SocketTimeoutException -> "连接超时，请检查服务器地址与局域网连接"
            is UnknownHostException -> "找不到服务器，请检查主机地址是否正确"
            is ConnectException -> "无法连接到服务器，请检查地址、端口以及服务器是否运行"
            is FileNotFoundException -> if (raw != null) "文件不存在或已被删除：$raw" else "文件不存在或已被删除"
            is IOException -> ioMessage(raw, direction)
            is LanTransferException -> raw ?: "传输失败"
            else -> "传输失败：" + (raw ?: error.javaClass.simpleName)
        }
    }

    private fun ioMessage(raw: String?, direction: TransferDirection): String {
        val lower = raw?.lowercase(Locale.ROOT).orEmpty()
        if (lower.contains("enospc") || lower.contains("no space") || lower.contains("空间不足")) {
            return "本地存储空间不足，无法写入文件"
        }
        val detail = if (raw != null) "（" + raw + "）" else ""
        return when (direction) {
            TransferDirection.DOWNLOAD -> "下载失败：网络中断或本地文件写入失败" + detail
            TransferDirection.UPLOAD -> "上传失败：网络中断或本地文件读取失败" + detail
        }
    }
}

package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.TransferCancelledException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 自动重试策略（纯 JVM 逻辑，可单元测试）。
 *
 * 规则：
 * - 只有 IO / 网络连接类瞬时错误才自动重试；认证失败、远端路径错误、用户取消、
 *   本地文件不存在等“重试也不会变好”的错误不重试；
 * - 最多自动重试 [MAX_RETRIES] 次，退避 2s / 4s / 8s；
 * - [currentAttempts] 是任务已经自动重试过的次数（初始 0）。
 */
object TransferRetryPolicy {

    /** 最多自动重试次数。 */
    const val MAX_RETRIES = 3

    private val BACKOFF_MILLIS = longArrayOf(2_000L, 4_000L, 8_000L)

    /** 该异常是否值得自动重试。 */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is AuthenticationException -> false
        is RemotePathException -> false
        is TransferCancelledException -> false
        is FileNotFoundException -> false
        is ConnectionException -> true
        is SocketTimeoutException -> true
        is UnknownHostException -> true
        is ConnectException -> true
        is IOException -> true
        else -> false
    }

    /** 是否还能再自动重试一次。 */
    fun shouldRetry(currentAttempts: Int, error: Throwable): Boolean =
        currentAttempts < MAX_RETRIES && isRetryable(error)

    /** 下一次重试后的 attempts 值（从 1 开始计数）。 */
    fun nextAttempt(currentAttempts: Int): Int = currentAttempts + 1

    /** 第 [retryIndex] 次重试前的退避毫秒数（retryIndex 从 1 开始）。 */
    fun backoffMillis(retryIndex: Int): Long {
        if (retryIndex <= 0) return BACKOFF_MILLIS.first()
        return BACKOFF_MILLIS[(retryIndex - 1).coerceIn(0, BACKOFF_MILLIS.lastIndex)]
    }
}

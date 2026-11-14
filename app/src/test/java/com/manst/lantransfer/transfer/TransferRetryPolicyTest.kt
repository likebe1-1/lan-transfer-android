package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.TransferCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** [TransferRetryPolicy] 的纯逻辑单元测试。 */
class TransferRetryPolicyTest {

    @Test
    fun `IO 与连接类瞬时错误可重试`() {
        assertTrue(TransferRetryPolicy.isRetryable(ConnectionException("连接被重置")))
        assertTrue(TransferRetryPolicy.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(TransferRetryPolicy.isRetryable(ConnectException("refused")))
        assertTrue(TransferRetryPolicy.isRetryable(UnknownHostException("nas.local")))
        assertTrue(TransferRetryPolicy.isRetryable(IOException("socket closed")))
    }

    @Test
    fun `认证路径取消与本地文件不存在不重试`() {
        assertFalse(TransferRetryPolicy.isRetryable(AuthenticationException()))
        assertFalse(TransferRetryPolicy.isRetryable(RemotePathException("目录不存在")))
        assertFalse(TransferRetryPolicy.isRetryable(TransferCancelledException()))
        assertFalse(TransferRetryPolicy.isRetryable(FileNotFoundException("缺少源文件")))
        assertFalse(TransferRetryPolicy.isRetryable(LanTransferException("未知协议错误")))
        assertFalse(TransferRetryPolicy.isRetryable(IllegalStateException("bug")))
    }

    @Test
    fun `最多重试三次`() {
        assertEquals(3, TransferRetryPolicy.MAX_RETRIES)
        val error = ConnectionException("网络抖动")
        assertTrue(TransferRetryPolicy.shouldRetry(0, error))
        assertTrue(TransferRetryPolicy.shouldRetry(1, error))
        assertTrue(TransferRetryPolicy.shouldRetry(2, error))
        assertFalse(TransferRetryPolicy.shouldRetry(3, error))
        assertFalse(TransferRetryPolicy.shouldRetry(0, AuthenticationException()))
    }

    @Test
    fun `退避时间为 2 秒 4 秒 8 秒`() {
        assertEquals(2_000L, TransferRetryPolicy.backoffMillis(1))
        assertEquals(4_000L, TransferRetryPolicy.backoffMillis(2))
        assertEquals(8_000L, TransferRetryPolicy.backoffMillis(3))
        // 边界值不会越界
        assertEquals(2_000L, TransferRetryPolicy.backoffMillis(0))
        assertEquals(8_000L, TransferRetryPolicy.backoffMillis(4))
        assertEquals(8_000L, TransferRetryPolicy.backoffMillis(99))
    }

    @Test
    fun `attempts 递增`() {
        assertEquals(1, TransferRetryPolicy.nextAttempt(0))
        assertEquals(2, TransferRetryPolicy.nextAttempt(1))
        assertEquals(3, TransferRetryPolicy.nextAttempt(2))
    }
}

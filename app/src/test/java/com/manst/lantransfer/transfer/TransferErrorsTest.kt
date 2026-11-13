package com.manst.lantransfer.transfer

import com.manst.lantransfer.core.AuthenticationException
import com.manst.lantransfer.core.ConnectionException
import com.manst.lantransfer.core.LanTransferException
import com.manst.lantransfer.core.RemotePathException
import com.manst.lantransfer.core.TransferCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransferErrorsTest {

    @Test
    fun `认证失败给出中文提示并保留自定义消息`() {
        assertEquals(
            "认证失败，请检查用户名或密码",
            TransferErrors.toUserMessage(AuthenticationException(), TransferDirection.DOWNLOAD)
        )
        assertEquals(
            "密码错误",
            TransferErrors.toUserMessage(AuthenticationException("密码错误"), TransferDirection.UPLOAD)
        )
    }

    @Test
    fun `连接相关异常给出中文提示`() {
        assertTrue(
            TransferErrors.toUserMessage(SocketTimeoutException("timeout"), TransferDirection.DOWNLOAD)
                .startsWith("连接超时")
        )
        assertTrue(
            TransferErrors.toUserMessage(UnknownHostException("nas.local"), TransferDirection.DOWNLOAD)
                .startsWith("找不到服务器")
        )
        assertTrue(
            TransferErrors.toUserMessage(ConnectException("refused"), TransferDirection.DOWNLOAD)
                .startsWith("无法连接到服务器")
        )
        assertTrue(
            TransferErrors.toUserMessage(ConnectionException(""), TransferDirection.UPLOAD)
                .startsWith("无法连接服务器")
        )
    }

    @Test
    fun `远端路径异常直接展示消息`() {
        assertEquals(
            "远端目录不存在：/data",
            TransferErrors.toUserMessage(RemotePathException("远端目录不存在：/data"), TransferDirection.DOWNLOAD)
        )
        assertEquals(
            "远端路径不存在或无法访问",
            TransferErrors.toUserMessage(RemotePathException(""), TransferDirection.DOWNLOAD)
        )
    }

    @Test
    fun `本地文件不存在提示中文`() {
        assertEquals(
            "文件不存在或已被删除：/sdcard/a.txt",
            TransferErrors.toUserMessage(FileNotFoundException("/sdcard/a.txt"), TransferDirection.UPLOAD)
        )
    }

    @Test
    fun `磁盘空间不足提示明确`() {
        assertEquals(
            "本地存储空间不足，无法写入文件",
            TransferErrors.toUserMessage(IOException("write failed: ENOSPC (No space left on device)"), TransferDirection.DOWNLOAD)
        )
    }

    @Test
    fun `读写异常按方向给出不同提示`() {
        assertTrue(
            TransferErrors.toUserMessage(IOException("socket reset"), TransferDirection.DOWNLOAD)
                .startsWith("下载失败")
        )
        assertTrue(
            TransferErrors.toUserMessage(IOException("stream closed"), TransferDirection.UPLOAD)
                .startsWith("上传失败")
        )
    }

    @Test
    fun `取消与兜底提示`() {
        assertEquals("传输已取消", TransferErrors.toUserMessage(TransferCancelledException(), TransferDirection.DOWNLOAD))
        assertEquals(
            "自定义错误",
            TransferErrors.toUserMessage(LanTransferException("自定义错误"), TransferDirection.DOWNLOAD)
        )
        assertEquals(
            "传输失败：boom",
            TransferErrors.toUserMessage(RuntimeException("boom"), TransferDirection.UPLOAD)
        )
        assertTrue(
            TransferErrors.toUserMessage(RuntimeException(), TransferDirection.UPLOAD)
                .startsWith("传输失败：")
        )
    }
}

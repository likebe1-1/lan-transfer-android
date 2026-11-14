package com.manst.lantransfer.quick

import com.manst.lantransfer.core.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class QuickQrCodecTest {

    private val session = QuickSessionInfo(
        code = "246810",
        deviceName = "测试设备",
        addresses = listOf("192.168.3.17", "10.0.0.2"),
        ftpPort = 2121,
        ftpsPort = 2122,
        sshPort = 2222,
        protocols = listOf(
            TransferProtocol.FTP,
            TransferProtocol.FTPS,
            TransferProtocol.SFTP,
            TransferProtocol.SCP
        )
    )

    @Test
    fun encode_isBase64WrappedJson() {
        val payload = QuickQrCodec.encode(session)
        // 载荷必须是 Base64（URL 安全、无填充），不能直接是明文 JSON
        assertTrue("载荷应为 Base64：$payload", !payload.startsWith("{"))
        val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        assertTrue("Base64 解码后应为 JSON：$decoded", decoded.startsWith("{"))
        assertTrue("JSON 应包含 code", decoded.contains("246810"))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val payload = QuickQrCodec.encode(session)
        val decoded = QuickQrCodec.decode(payload)
        assertEquals(session.code, decoded?.code)
        assertEquals(session.deviceName, decoded?.deviceName)
        assertEquals(session.addresses, decoded?.addresses)
        assertEquals(session.ftpPort, decoded?.ftpPort)
        assertEquals(session.ftpsPort, decoded?.ftpsPort)
        assertEquals(session.sshPort, decoded?.sshPort)
        assertEquals(session.protocols, decoded?.protocols)
    }

    @Test
    fun decode_acceptsStandardBase64WithPadding() {
        val payload = QuickQrCodec.encode(session)
        val bytes = Base64.getUrlDecoder().decode(payload)
        val standard = Base64.getEncoder().encodeToString(bytes)
        assertEquals(session.code, QuickQrCodec.decode(standard)?.code)
    }

    @Test
    fun decode_rejectsInvalidPayload() {
        assertNull(QuickQrCodec.decode(null))
        assertNull(QuickQrCodec.decode(""))
        assertNull(QuickQrCodec.decode("not-base64!!!"))
        assertNull(QuickQrCodec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray())))
    }

    @Test
    fun decode_toleratesMissingOptionalFields() {
        val json = "{\"v\":1,\"code\":\"135790\",\"addrs\":[\"127.0.0.1\"]}"
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val decoded = QuickQrCodec.decode(payload)
        assertEquals("135790", decoded?.code)
        assertEquals(listOf("127.0.0.1"), decoded?.addresses)
        assertEquals(QuickTransferDefaults.DEFAULT_FTP_PORT, decoded?.ftpPort)
        assertEquals(QuickTransferDefaults.PROTOCOLS, decoded?.protocols)
    }
}

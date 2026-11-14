package com.manst.lantransfer.quick

import com.manst.lantransfer.core.TransferProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QuickBeaconCodec] 的 JVM 单元测试：报文编解码、字段校验与非法数据容错。 */
class LanBeaconCodecTest {

    private fun sampleInfo(
        code: String = "123456",
        deviceName: String = "Pixel 4",
        addresses: List<String> = listOf("192.168.1.8", "10.0.0.8"),
        ftpPort: Int = 2121,
        ftpsPort: Int = 2122,
        sshPort: Int = 2222,
        protocols: List<TransferProtocol> = QuickTransferDefaults.PROTOCOLS
    ) = QuickSessionInfo(
        code = code,
        deviceName = deviceName,
        addresses = addresses,
        ftpPort = ftpPort,
        ftpsPort = ftpsPort,
        sshPort = sshPort,
        protocols = protocols
    )

    @Test
    fun encode_containsAllContractFields() {
        val encoded = QuickBeaconCodec.encode(sampleInfo())
        assertNotNull(encoded)
        val json = JSONObject(requireNotNull(encoded))
        assertEquals(QuickBeaconCodec.VERSION, json.getInt("v"))
        assertEquals("123456", json.getString("code"))
        assertEquals("Pixel 4", json.getString("name"))
        val addrs = json.getJSONArray("addrs")
        assertEquals(2, addrs.length())
        assertEquals("192.168.1.8", addrs.getString(0))
        assertEquals(2121, json.getInt("ftp"))
        assertEquals(2122, json.getInt("ftps"))
        assertEquals(2222, json.getInt("ssh"))
        val protocols = json.getJSONArray("protocols")
        assertEquals(4, protocols.length())
        assertTrue(protocols.getString(0) == "FTP")
        assertTrue(json.getLong("ts") > 0L)
    }

    @Test
    fun decode_roundTripsEncodedSession() {
        val original = sampleInfo(
            code = "987654",
            deviceName = "小米手机",
            addresses = listOf("192.168.31.20"),
            ftpPort = 2131,
            ftpsPort = 2132,
            sshPort = 2232,
            protocols = listOf(TransferProtocol.SFTP, TransferProtocol.SCP, TransferProtocol.FTPS)
        )
        val decoded = QuickBeaconCodec.decode(QuickBeaconCodec.encode(original))
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(original.code, decoded.code)
        assertEquals(original.deviceName, decoded.deviceName)
        assertEquals(original.addresses, decoded.addresses)
        assertEquals(original.ftpPort, decoded.ftpPort)
        assertEquals(original.ftpsPort, decoded.ftpsPort)
        assertEquals(original.sshPort, decoded.sshPort)
        // 解码时协议顺序统一归一化为 QuickTransferDefaults.PROTOCOLS 顺序（加密优先由接收端自己决定）
        assertEquals(
            listOf(TransferProtocol.FTPS, TransferProtocol.SFTP, TransferProtocol.SCP),
            decoded.protocols
        )
        assertTrue("解码应刷新本地收到时间", decoded.receivedAt > 0L)
    }

    @Test
    fun decode_acceptsManualJsonAndNormalizesOptionalFields() {
        val manual = JSONObject()
            .put("v", 1)
            .put("code", "123-456")
            .put("addrs", JSONArray(listOf(" 192.168.1.9 ", "", "192.168.1.9")))
            .put("ftp", 0)
            .put("ftps", 70000)
            .put("protocols", JSONArray(listOf("sftp", "SCP", "unknownProtocol")))
            .toString()
        val decoded = QuickBeaconCodec.decode(manual)
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals("123456", decoded.code)
        assertEquals(listOf("192.168.1.9"), decoded.addresses)
        assertEquals(QuickTransferDefaults.DEFAULT_FTP_PORT, decoded.ftpPort)
        assertEquals(QuickTransferDefaults.DEFAULT_FTPS_PORT, decoded.ftpsPort)
        assertEquals(QuickTransferDefaults.DEFAULT_SSH_PORT, decoded.sshPort)
        assertEquals(listOf(TransferProtocol.SFTP, TransferProtocol.SCP), decoded.protocols)
        assertEquals("附近设备", decoded.deviceName)
    }

    @Test
    fun decode_defaultsToAllProtocolsWhenFieldMissing() {
        val manual = JSONObject()
            .put("v", 1)
            .put("code", "123456")
            .put("addrs", JSONArray(listOf("192.168.0.2")))
            .toString()
        val decoded = QuickBeaconCodec.decode(manual)
        assertNotNull(decoded)
        assertEquals(QuickTransferDefaults.PROTOCOLS, requireNotNull(decoded).protocols)
    }

    @Test
    fun decode_rejectsMalformedPayloads() {
        assertNull(QuickBeaconCodec.decode(null))
        assertNull(QuickBeaconCodec.decode(""))
        assertNull(QuickBeaconCodec.decode("   "))
        assertNull(QuickBeaconCodec.decode("not json"))
        assertNull(QuickBeaconCodec.decode("[]"))
        assertNull(QuickBeaconCodec.decode("{}"))
        assertNull(QuickBeaconCodec.decode("{\"v\":1}"))
        assertNull(QuickBeaconCodec.decode("x".repeat(QuickBeaconCodec.MAX_PACKET_BYTES + 1)))
    }

    @Test
    fun decode_rejectsWrongVersionAndInvalidCoreFields() {
        fun payload(code: String, addrs: JSONArray?, protocols: JSONArray?, version: Int = 1): String =
            JSONObject()
                .apply {
                    put("v", version)
                    put("code", code)
                    if (addrs == null) put("addrs", JSONArray(emptyList<String>())) else put("addrs", addrs)
                    if (protocols == null) put("protocols", JSONArray(emptyList<String>())) else put("protocols", protocols)
                }
                .toString()

        val addrs = JSONArray(listOf("192.168.1.1"))
        val protocols = JSONArray(listOf("FTP"))
        assertNull("版本不一致必须忽略", QuickBeaconCodec.decode(payload("123456", addrs, protocols, version = 2)))
        assertNull("接收码非法必须忽略", QuickBeaconCodec.decode(payload("12345", addrs, protocols)))
        assertNull("接收码非数字必须忽略", QuickBeaconCodec.decode(payload("abcdef", addrs, protocols)))
        assertNull("没有可用地址必须忽略", QuickBeaconCodec.decode(payload("123456", JSONArray(listOf("  ", "")), protocols)))
        assertNull("协议全部不认识必须忽略", QuickBeaconCodec.decode(payload("123456", addrs, JSONArray(listOf("FOO")))))
    }

    @Test
    fun decode_acceptsPartialProtocolList() {
        val partial = JSONObject()
            .put("v", 1)
            .put("code", "222333")
            .put("addrs", JSONArray(listOf("10.1.2.3")))
            .put("protocols", JSONArray(listOf("FTP")))
            .toString()
        assertEquals(listOf(TransferProtocol.FTP), requireNotNull(QuickBeaconCodec.decode(partial)).protocols)
    }
}

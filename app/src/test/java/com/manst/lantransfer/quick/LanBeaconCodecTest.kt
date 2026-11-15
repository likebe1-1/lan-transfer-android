package com.manst.lantransfer.quick

import com.manst.lantransfer.core.TransferProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QuickBeaconCodec] 的 JVM 单元测试：v2 广播不携带接收码、字段编解码与非法数据容错。 */
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
    fun version_isTwoAndEncodeDoesNotCarryCode() {
        assertEquals(2, QuickBeaconCodec.VERSION)
        val info = sampleInfo(code = "123456")
        val encoded = requireNotNull(QuickBeaconCodec.encode(info))
        val json = JSONObject(encoded)
        assertEquals(2, json.getInt("v"))
        assertFalse("v2 广播报文不能包含 code 字段", json.has("code"))
        assertFalse("v2 广播报文不能包含接收码明文", encoded.contains("123456"))
    }

    @Test
    fun encode_containsAllPublicFields() {
        val encoded = QuickBeaconCodec.encode(sampleInfo())
        assertNotNull(encoded)
        val json = JSONObject(requireNotNull(encoded))
        assertEquals("Pixel 4", json.getString("name"))
        val addrs = json.getJSONArray("addrs")
        assertEquals(2, addrs.length())
        assertEquals("192.168.1.8", addrs.getString(0))
        assertEquals(2121, json.getInt("ftp"))
        assertEquals(2122, json.getInt("ftps"))
        assertEquals(2222, json.getInt("ssh"))
        val protocols = json.getJSONArray("protocols")
        assertEquals(4, protocols.length())
        assertTrue(json.getLong("ts") > 0L)
    }

    @Test
    fun decode_roundTripsEncodedSessionWithoutCode() {
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
        assertEquals("广播解码不能得到接收码", "", decoded.code)
        assertFalse(decoded.hasCode)
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
    fun decode_acceptsManualJsonWithoutCodeAndToleratesOptionalFields() {
        val manual = JSONObject()
            .put("v", 2)
            .put("addrs", JSONArray(listOf(" 192.168.1.9 ", "", "192.168.1.9")))
            .put("ftp", 0)
            .put("ftps", 70000)
            .put("protocols", JSONArray(listOf("sftp", "SCP", "unknownProtocol")))
            .toString()
        val decoded = QuickBeaconCodec.decode(manual)
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals("", decoded.code)
        assertFalse(decoded.hasCode)
        assertEquals(listOf("192.168.1.9"), decoded.addresses)
        assertEquals(QuickTransferDefaults.DEFAULT_FTP_PORT, decoded.ftpPort)
        assertEquals(QuickTransferDefaults.DEFAULT_FTPS_PORT, decoded.ftpsPort)
        assertEquals(QuickTransferDefaults.DEFAULT_SSH_PORT, decoded.sshPort)
        assertEquals(listOf(TransferProtocol.SFTP, TransferProtocol.SCP), decoded.protocols)
        assertEquals("附近设备", decoded.deviceName)
    }

    @Test
    fun decode_ignoresCodeFieldEvenIfPresent() {
        // 防御性：即使伪造的 v2 报文带了 code，也不采信（广播协议本身不传接收码）
        val forged = JSONObject()
            .put("v", 2)
            .put("code", "222333")
            .put("name", "伪造设备")
            .put("addrs", JSONArray(listOf("192.168.0.2")))
            .toString()
        val decoded = QuickBeaconCodec.decode(forged)
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals("", decoded.code)
        assertFalse(decoded.hasCode)
        assertEquals("伪造设备", decoded.deviceName)
    }

    @Test
    fun decode_defaultsToAllProtocolsWhenFieldMissing() {
        val manual = JSONObject()
            .put("v", 2)
            .put("addrs", JSONArray(listOf("192.168.0.2")))
            .toString()
        val decoded = QuickBeaconCodec.decode(manual)
        assertNotNull(decoded)
        assertEquals(QuickTransferDefaults.PROTOCOLS, requireNotNull(decoded).protocols)
    }

    @Test
    fun decode_rejectsLegacyV1PayloadEvenIfValidElsewhere() {
        // 旧版 v1 报文携带接收码：旧客户端会据此自动连接，新版必须一律忽略
        val legacy = JSONObject()
            .put("v", 1)
            .put("code", "123456")
            .put("name", "旧设备")
            .put("addrs", JSONArray(listOf("192.168.1.10")))
            .put("ftp", 2121)
            .put("ftps", 2122)
            .put("ssh", 2222)
            .toString()
        assertNull(QuickBeaconCodec.decode(legacy))
    }

    @Test
    fun decode_rejectsMalformedPayloads() {
        assertNull(QuickBeaconCodec.decode(null))
        assertNull(QuickBeaconCodec.decode(""))
        assertNull(QuickBeaconCodec.decode("   "))
        assertNull(QuickBeaconCodec.decode("not json"))
        assertNull(QuickBeaconCodec.decode("[]"))
        assertNull(QuickBeaconCodec.decode("{}"))
        assertNull(QuickBeaconCodec.decode("{\"v\":2}"))
        assertNull(QuickBeaconCodec.decode("x".repeat(QuickBeaconCodec.MAX_PACKET_BYTES + 1)))
    }

    @Test
    fun decode_rejectsWrongVersionAndInvalidCoreFields() {
        fun payload(addrs: JSONArray?, protocols: JSONArray?, version: Int = 2): String =
            JSONObject()
                .apply {
                    put("v", version)
                    put("addrs", addrs ?: JSONArray(emptyList<String>()))
                    put("protocols", protocols ?: JSONArray(emptyList<String>()))
                }
                .toString()

        val addrs = JSONArray(listOf("192.168.1.1"))
        val protocols = JSONArray(listOf("FTP"))
        assertNull("版本不一致必须忽略", QuickBeaconCodec.decode(payload(addrs, protocols, version = 1)))
        assertNull("版本不一致必须忽略", QuickBeaconCodec.decode(payload(addrs, protocols, version = 3)))
        assertNull("没有可用地址必须忽略", QuickBeaconCodec.decode(payload(JSONArray(listOf("  ", "")), protocols)))
        assertNull("协议全部不认识必须忽略", QuickBeaconCodec.decode(payload(addrs, JSONArray(listOf("FOO")))))
    }

    @Test
    fun decode_acceptsPartialProtocolList() {
        val partial = JSONObject()
            .put("v", 2)
            .put("addrs", JSONArray(listOf("10.1.2.3")))
            .put("protocols", JSONArray(listOf("FTP")))
            .toString()
        assertEquals(listOf(TransferProtocol.FTP), requireNotNull(QuickBeaconCodec.decode(partial)).protocols)
    }
}

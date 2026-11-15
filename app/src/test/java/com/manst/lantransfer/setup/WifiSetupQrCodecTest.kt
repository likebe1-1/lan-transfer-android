package com.manst.lantransfer.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSetupQrCodecTest {

    @Test
    fun encodeDecode_wpa2RoundTrip() {
        val info = WifiHotspotInfo("LanLink-Hotspot", "12345678", WifiSecurity.WPA2)
        val payload = WifiSetupQrCodec.encode(info)
        assertTrue(payload!!.startsWith(WifiSetupQrCodec.PREFIX))
        val decoded = WifiSetupQrCodec.decode(payload)
        assertEquals(info, decoded)
    }

    @Test
    fun encodeDecode_openNetwork() {
        val info = WifiHotspotInfo("OpenHotspot", "", WifiSecurity.OPEN)
        val decoded = WifiSetupQrCodec.decode(WifiSetupQrCodec.encode(info))
        assertEquals(info, decoded)
        assertTrue(decoded!!.isOpen)
    }

    @Test
    fun decode_rejectsWrongPrefixOrGarbage() {
        assertNull(WifiSetupQrCodec.decode(null))
        assertNull(WifiSetupQrCodec.decode(""))
        assertNull(WifiSetupQrCodec.decode("LANLINK-WIFI:"))
        assertNull(WifiSetupQrCodec.decode("LANLINK-WIFI:not-base64"))
        assertNull(WifiSetupQrCodec.decode("OTHER:abc"))
    }

    @Test
    fun decode_rejectsWrongVersionOrType() {
        val wrongType = java.util.Base64.getEncoder().encodeToString("{\"v\":1,\"type\":\"x\",\"ssid\":\"a\"}".toByteArray())
        assertNull(WifiSetupQrCodec.decode(WifiSetupQrCodec.PREFIX + wrongType))
        val wrongVersion = java.util.Base64.getEncoder().encodeToString("{\"v\":2,\"type\":\"lanlink-wifi\",\"ssid\":\"a\"}".toByteArray())
        assertNull(WifiSetupQrCodec.decode(WifiSetupQrCodec.PREFIX + wrongVersion))
    }

    @Test
    fun encode_rejectsInvalidSsidAndPassword() {
        assertNull(WifiSetupQrCodec.encode(WifiHotspotInfo("", "12345678")))
        assertNull(WifiSetupQrCodec.encode(WifiHotspotInfo("a".repeat(33), "12345678")))
        assertNull(WifiSetupQrCodec.encode(WifiHotspotInfo("ok", "short")))
        assertNull(WifiSetupQrCodec.build("", "12345678"))
        assertNull(WifiSetupQrCodec.build("ok", "short"))
    }

    @Test
    fun build_mapsBlankPasswordToOpenNetwork() {
        val info = WifiSetupQrCodec.build("MyHotspot", "")!!
        assertEquals(WifiSecurity.OPEN, info.security)
        assertTrue(info.isOpen)
        assertEquals(WifiSecurity.WPA2, WifiSetupQrCodec.build("MyHotspot", "12345678")!!.security)
    }

    @Test
    fun ssidAndPasswordValidation() {
        assertTrue(WifiSetupQrCodec.isValidSsid("a"))
        assertTrue(WifiSetupQrCodec.isValidSsid("a".repeat(32)))
        assertFalse(WifiSetupQrCodec.isValidSsid("a".repeat(33)))
        assertFalse(WifiSetupQrCodec.isValidSsid("   "))
        assertTrue(WifiSetupQrCodec.isValidPassword("12345678"))
        assertTrue(WifiSetupQrCodec.isValidPassword("a".repeat(63)))
        assertFalse(WifiSetupQrCodec.isValidPassword("a".repeat(64)))
        assertFalse(WifiSetupQrCodec.isValidPassword("short"))
    }

    @Test
    fun decode_doesNotAcceptSessionQrPayload() {
        // 快速传文件会话二维码是 Base64(JSON)，没有 LANLINK-WIFI: 前缀
        val sessionLike = java.util.Base64.getEncoder().encodeToString("{\"code\":\"123456\"}".toByteArray())
        assertNull(WifiSetupQrCodec.decode(sessionLike))
    }
}

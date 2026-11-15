package com.manst.lantransfer.setup

import org.json.JSONObject
import java.util.Base64

enum class WifiSecurity { WPA2, OPEN }

/** 一键组网的热点信息（A 手机创建热点后，B 扫码加入）。 */
data class WifiHotspotInfo(
    val ssid: String,
    val password: String = "",
    val security: WifiSecurity = WifiSecurity.WPA2
) {
    val isOpen: Boolean get() = security == WifiSecurity.OPEN || password.isBlank()
}

/**
 * 一键组网二维码编解码：
 * 载荷 = "LANLINK-WIFI:" + Base64(UTF-8 JSON)，与快速传文件的会话二维码前缀区分。
 * JSON: { "v":1, "type":"lanlink-wifi", "ssid":"...", "password":"...", "security":"WPA2|OPEN" }
 */
object WifiSetupQrCodec {

    const val PREFIX = "LANLINK-WIFI:"
    private const val MAX_SSID_LENGTH = 32
    private const val MIN_WPA2_PASSWORD = 8
    private const val MAX_WPA2_PASSWORD = 63

    fun encode(info: WifiHotspotInfo): String? {
        if (!isValidSsid(info.ssid)) return null
        val security = if (info.isOpen) WifiSecurity.OPEN else WifiSecurity.WPA2
        if (security == WifiSecurity.WPA2 && !isValidPassword(info.password)) return null
        val json = JSONObject()
            .put("v", 1)
            .put("type", "lanlink-wifi")
            .put("ssid", info.ssid.trim())
            .put("password", if (security == WifiSecurity.OPEN) "" else info.password)
            .put("security", security.name)
        val base64 = Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return PREFIX + base64
    }

    fun decode(payload: String?): WifiHotspotInfo? {
        val text = payload?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null
        val encoded = text.removePrefix(PREFIX)
        if (encoded.isBlank()) return null
        val json = try {
            JSONObject(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        if (json.optInt("v", 0) != 1) return null
        if (json.optString("type", "") != "lanlink-wifi") return null
        val ssid = json.optString("ssid", "").trim()
        if (!isValidSsid(ssid)) return null
        val security = runCatching { WifiSecurity.valueOf(json.optString("security", "WPA2")) }
            .getOrDefault(WifiSecurity.WPA2)
        val password = json.optString("password", "")
        if (security == WifiSecurity.WPA2 && !isValidPassword(password)) return null
        return WifiHotspotInfo(ssid = ssid, password = if (security == WifiSecurity.OPEN) "" else password, security = security)
    }

    fun isValidSsid(ssid: String): Boolean {
        val value = ssid.trim()
        return value.isNotEmpty() && value.length <= MAX_SSID_LENGTH
    }

    /** WPA2 密码长度 8~63；开放网络允许空密码。 */
    fun isValidPassword(password: String): Boolean = password.length in MIN_WPA2_PASSWORD..MAX_WPA2_PASSWORD

    /** 由用户输入构造并校验；非法返回 null。 */
    fun build(ssid: String, password: String): WifiHotspotInfo? {
        val trimmedSsid = ssid.trim()
        if (!isValidSsid(trimmedSsid)) return null
        if (password.isBlank()) return WifiHotspotInfo(trimmedSsid, "", WifiSecurity.OPEN)
        if (!isValidPassword(password)) return null
        return WifiHotspotInfo(trimmedSsid, password, WifiSecurity.WPA2)
    }
}

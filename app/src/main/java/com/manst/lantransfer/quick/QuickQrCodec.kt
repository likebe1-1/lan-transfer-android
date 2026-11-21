package com.manst.lantransfer.quick

import com.manst.lantransfer.core.TransferProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * 快速传文件二维码载荷编解码。
 *
 * 二维码内容 = Base64(UTF-8 JSON)，JSON 字段与局域网广播一致：
 * v（版本）、code（8 位接收码，兼容旧版 6 位）、name（设备名）、addrs（IP 列表）、ftp/ftps/ssh（端口）、protocols（协议）。
 * 不在二维码中携带临时帐号密码——接收端用接收码派生出相同的临时凭据。
 */
object QuickQrCodec {

    private const val VERSION = 1

    /** 会话信息 -> Base64 字符串（二维码内容）。 */
    fun encode(info: QuickSessionInfo): String {
        val json = JSONObject()
            .put("v", VERSION)
            .put("code", info.code)
            .put("name", info.deviceName)
            .put("addrs", JSONArray(info.addresses))
            .put("ftp", info.ftpPort)
            .put("ftps", info.ftpsPort)
            .put("ssh", info.sshPort)
            .put("protocols", JSONArray(info.protocols.map { it.name }))
            .put("fp", info.ftpsFingerprintSha256.orEmpty())
            .put("hk", info.sshHostKeySha256.orEmpty())
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 同时兼容 URL-safe 与标准 Base64（含填充）。 */
    private fun decodeBase64(text: String): ByteArray? {
        return try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: Exception) {
            try {
                Base64.getDecoder().decode(text)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** 二维码内容 -> 会话信息；非法 Base64/JSON/字段返回 null。 */
    fun decode(payload: String?): QuickSessionInfo? {
        val text = payload?.trim().orEmpty()
        if (text.isEmpty() || text.length > 4096) return null
        val bytes = decodeBase64(text) ?: return null
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        if (json.optInt("v", 0) != VERSION) return null
        val code = QuickSessionCode.normalize(json.optString("code", "")) ?: return null
        val addresses = ArrayList<String>()
        json.optJSONArray("addrs")?.let { array ->
            for (index in 0 until array.length()) {
                val address = array.optString(index, "").trim()
                if (address.isNotEmpty() && address !in addresses) addresses.add(address)
            }
        }
        if (addresses.isEmpty()) return null
        val protocols = ArrayList<TransferProtocol>()
        json.optJSONArray("protocols")?.let { array ->
            for (index in 0 until array.length()) {
                val name = array.optString(index, "").trim()
                TransferProtocol.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { if (it !in protocols) protocols.add(it) }
            }
        }
        val deviceName = json.optString("name", "").trim().ifBlank { "附近设备" }
        return QuickSessionInfo(
            code = code,
            deviceName = deviceName,
            addresses = addresses,
            ftpPort = json.optInt("ftp", QuickTransferDefaults.DEFAULT_FTP_PORT),
            ftpsPort = json.optInt("ftps", QuickTransferDefaults.DEFAULT_FTPS_PORT),
            sshPort = json.optInt("ssh", QuickTransferDefaults.DEFAULT_SSH_PORT),
            protocols = if (protocols.isEmpty()) QuickTransferDefaults.PROTOCOLS else protocols,
            ftpsFingerprintSha256 = json.optString("fp", "").trim()
                .takeIf { com.manst.lantransfer.core.CertificatePinning.isValidFingerprint(it) },
            sshHostKeySha256 = json.optString("hk", "").trim()
                .takeIf { com.manst.lantransfer.core.CertificatePinning.isValidFingerprint(it) }
        )
    }
}

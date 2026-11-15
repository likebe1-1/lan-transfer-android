package com.manst.lantransfer.update

import org.json.JSONObject

/**
 * update.json 解析与 ABI 选择（纯 JVM，可单元测试）。
 *
 * 清单格式见 docs/update.json：
 * { versionCode, versionName, changelog, releasePageUrl?, lanzouUrl?, lanzouPassword?,
 *   apks: [ { abi, size, sha256, urls: [...] } ] }
 */
object UpdateManifestParser {

    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    /** 解析清单并按设备 ABI 选择 APK；非法/缺字段返回 null。 */
    fun parse(jsonText: String, supportedAbis: List<String>): UpdateInfo? {
        val root = try {
            JSONObject(jsonText.trim())
        } catch (e: Exception) {
            return null
        }
        val versionCode = root.optInt("versionCode", -1)
        if (versionCode <= 0) return null
        val versionName = root.optString("versionName", "").trim().ifBlank { versionCode.toString() }
        val changelog = root.optString("changelog", "").trim()
        val array = root.optJSONArray("apks") ?: return null
        val apks = ArrayList<UpdateApk>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val abi = item.optString("abi", "").trim()
            val size = item.optLong("size", -1L)
            val sha256 = item.optString("sha256", "").trim()
            val urlsArray = item.optJSONArray("urls") ?: continue
            val urls = ArrayList<String>(urlsArray.length())
            for (urlIndex in 0 until urlsArray.length()) {
                val url = urlsArray.optString(urlIndex, "").trim()
                if (url.startsWith("https://") || url.startsWith("http://")) urls.add(url)
            }
            if (abi.isEmpty() || size <= 0L || !SHA256_PATTERN.matches(sha256) || urls.isEmpty()) continue
            apks.add(UpdateApk(abi = abi, size = size, sha256 = sha256.lowercase(), urls = urls))
        }
        if (apks.isEmpty()) return null
        val chosen = chooseApk(apks, supportedAbis) ?: return null
        return UpdateInfo(
            manifest = UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                changelog = changelog,
                apks = apks,
                releasePageUrl = root.optString("releasePageUrl", "").trim().ifBlank { null },
                lanzouUrl = root.optString("lanzouUrl", "").trim().ifBlank { null },
                lanzouPassword = root.optString("lanzouPassword", "").trim().ifBlank { null }
            ),
            apk = chosen
        )
    }

    /** 按设备 ABI 优先级选择：精确 ABI → universal → 第一个可用产物。 */
    fun chooseApk(apks: List<UpdateApk>, supportedAbis: List<String>): UpdateApk? {
        for (abi in supportedAbis) {
            apks.firstOrNull { it.abi.equals(abi, ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.abi.equals("universal", ignoreCase = true) }?.let { return it }
        return apks.firstOrNull()
    }

    /** 根据版本号判断是否需要更新（当前版本未知时不提示）。 */
    fun isNewer(manifestCode: Int, currentCode: Int): Boolean =
        manifestCode > 0 && currentCode > 0 && manifestCode > currentCode
}

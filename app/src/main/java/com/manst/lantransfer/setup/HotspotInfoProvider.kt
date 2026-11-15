package com.manst.lantransfer.setup

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/** 尝试读取本机当前热点配置（Android 11+ 公开 API；部分系统/权限下会失败，失败时由用户手动输入）。 */
class HotspotInfoProvider(context: Context) {

    private val appContext = context.applicationContext

    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun readHotspot(): WifiHotspotInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = wifiManager ?: return null
        return try {
            val config = manager.javaClass
                .getMethod("getSoftApConfiguration")
                .invoke(manager)
                ?: return null
            val ssid = config.javaClass
                .getMethod("getSsid")
                .invoke(config)
                ?.toString()
                ?.trim()
                .orEmpty()
            if (ssid.isBlank()) return null
            val password = runCatching {
                config.javaClass.getMethod("getPassphrase").invoke(config)?.toString().orEmpty()
            }.getOrDefault("")
            WifiHotspotInfo(
                ssid = ssid,
                password = password,
                security = if (password.isBlank()) WifiSecurity.OPEN else WifiSecurity.WPA2
            )
        } catch (e: Exception) {
            Log.w(TAG, "读取热点配置失败（需手动输入）", e)
            null
        }
    }

    private companion object {
        const val TAG = "HotspotInfoProvider"
    }
}
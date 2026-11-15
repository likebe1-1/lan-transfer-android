package com.manst.lantransfer.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log

/**
 * 通过 WifiNetworkSpecifier 让本应用加入 A 手机创建的热点（Android 10+）。
 *
 * 连接为「应用级」Wi-Fi：不修改系统 Wi-Fi 设置；连接成功后把本应用进程绑定到该网络，
 * 保证后续快速传文件/网络工具走热点，断开时恢复默认路由。
 */
class WifiNetworkJoiner(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    /** 当前是否已绑定到加入的热点网络。 */
    val isConnected: Boolean get() = boundNetwork != null

    fun join(info: WifiHotspotInfo, onResult: (success: Boolean, message: String?) -> Unit) {
        disconnect()
        val manager = connectivityManager ?: run {
            onResult(false, "系统不支持网络连接管理")
            return
        }
        val specifier = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(info.ssid)
                .apply {
                    if (!info.isOpen) setWpa2Passphrase(info.password)
                }
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "构造 Wi-Fi 网络配置失败", e)
            onResult(false, "热点名称或密码格式不正确")
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundNetwork = network
                runCatching { manager.bindProcessToNetwork(network) }
                onResult(true, null)
            }

            override fun onUnavailable() {
                onResult(false, "无法加入热点：请确认热点已开启、密码正确且手机支持 Wi-Fi")
            }

            override fun onLost(network: Network) {
                if (boundNetwork == network) {
                    boundNetwork = null
                    runCatching { manager.bindProcessToNetwork(null) }
                }
            }
        }
        callback = networkCallback
        try {
            manager.requestNetwork(request, networkCallback, REQUEST_TIMEOUT_MILLIS)
        } catch (e: SecurityException) {
            callback = null
            Log.w(TAG, "加入热点缺少权限", e)
            onResult(false, "缺少 Wi-Fi 权限，请在系统设置中允许「附近设备/Wi-Fi」权限")
        } catch (e: Exception) {
            callback = null
            Log.w(TAG, "加入热点失败", e)
            onResult(false, "加入热点失败：" + (e.message ?: "未知错误"))
        }
    }

    fun disconnect() {
        callback?.let { networkCallback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        }
        callback = null
        if (boundNetwork != null) {
            runCatching { connectivityManager?.bindProcessToNetwork(null) }
        }
        boundNetwork = null
    }

    private companion object {
        const val TAG = "WifiNetworkJoiner"
        const val REQUEST_TIMEOUT_MILLIS = 30_000
    }
}
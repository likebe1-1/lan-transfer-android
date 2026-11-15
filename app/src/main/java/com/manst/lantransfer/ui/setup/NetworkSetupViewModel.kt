package com.manst.lantransfer.ui.setup

import androidx.lifecycle.ViewModel
import com.manst.lantransfer.setup.HotspotInfoProvider
import com.manst.lantransfer.setup.WifiNetworkJoiner
import com.manst.lantransfer.setup.WifiSecurity
import com.manst.lantransfer.setup.WifiSetupQrCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetworkSetupUiState(
    val ssid: String = "",
    val password: String = "",
    /** 生成好的二维码载荷；null 表示还没生成 */
    val qrPayload: String? = null,
    val readingHotspot: Boolean = false,
    val joining: Boolean = false,
    val joinedSsid: String? = null,
    val connected: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null
)

/** 一键组网：A 生成热点二维码，B 扫码加入热点。 */
class NetworkSetupViewModel(
    private val hotspotInfoProvider: HotspotInfoProvider,
    private val joiner: WifiNetworkJoiner
) : ViewModel() {

    private val mutableState = MutableStateFlow(NetworkSetupUiState())
    val state: StateFlow<NetworkSetupUiState> = mutableState.asStateFlow()

    fun onSsidChange(value: String) {
        mutableState.update { it.copy(ssid = value.take(32), qrPayload = null, error = null) }
    }

    fun onPasswordChange(value: String) {
        mutableState.update { it.copy(password = value.take(63), qrPayload = null, error = null) }
    }

    /** 尝试读取当前热点 SSID/密码并填入（失败时提示手动输入）。 */
    fun readHotspot() {
        if (mutableState.value.readingHotspot) return
        mutableState.update { it.copy(readingHotspot = true, error = null) }
        val info = hotspotInfoProvider.readHotspot()
        if (info != null) {
            mutableState.update {
                it.copy(
                    readingHotspot = false,
                    ssid = info.ssid,
                    password = info.password,
                    qrPayload = null,
                    infoMessage = "已读取当前热点信息"
                )
            }
        } else {
            mutableState.update {
                it.copy(
                    readingHotspot = false,
                    error = "无法读取热点信息，请手动输入热点名称和密码"
                )
            }
        }
    }

    fun generateQr() {
        val info = WifiSetupQrCodec.build(mutableState.value.ssid, mutableState.value.password)
        if (info == null) {
            val message = if (mutableState.value.ssid.trim().length !in 1..32) {
                "热点名称需 1~32 个字符"
            } else {
                "WPA2 密码需 8~63 位（留空表示开放网络）"
            }
            mutableState.update { it.copy(error = message) }
            return
        }
        val payload = WifiSetupQrCodec.encode(info)
        mutableState.update { it.copy(qrPayload = payload, error = null) }
    }

    /** B 扫码后调用：解析二维码并加入热点。 */
    fun joinScanned(payload: String) {
        val info = WifiSetupQrCodec.decode(payload)
        if (info == null) {
            mutableState.update { it.copy(error = "不是有效的 LanLink 一键组网二维码") }
            return
        }
        if (mutableState.value.joining) return
        mutableState.update {
            it.copy(joining = true, joinedSsid = info.ssid, connected = false, error = null)
        }
        joiner.join(info) { success, message ->
            mutableState.update {
                it.copy(
                    joining = false,
                    connected = success,
                    joinedSsid = if (success) info.ssid else null,
                    error = if (success) null else message
                )
            }
        }
    }

    fun disconnect() {
        joiner.disconnect()
        mutableState.update { it.copy(connected = false, joinedSsid = null) }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun clearInfoMessage() {
        mutableState.update { it.copy(infoMessage = null) }
    }

    override fun onCleared() {
        joiner.disconnect()
        super.onCleared()
    }
}

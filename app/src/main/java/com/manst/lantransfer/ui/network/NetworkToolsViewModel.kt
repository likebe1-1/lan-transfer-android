package com.manst.lantransfer.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manst.lantransfer.net.LanCheck
import com.manst.lantransfer.net.LanHost
import com.manst.lantransfer.net.LanNetworkInfo
import com.manst.lantransfer.net.LanScanner
import com.manst.lantransfer.net.LanSubnet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** IP:端口 连通性测试结果。 */
enum class TargetTestResult { OK, FAILED, INVALID }

data class NetworkToolsUiState(
    val loading: Boolean = true,
    val info: LanNetworkInfo? = null,
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val hosts: List<LanHost> = emptyList(),
    val checks: List<LanCheck> = emptyList(),
    val gatewayReachable: Boolean? = null,
    val scanDone: Boolean = false,
    val error: String? = null,
    val targetIp: String = "",
    val targetPort: String = "",
    val testing: Boolean = false,
    val targetResult: TargetTestResult? = null
)

/** 网络工具页：读取当前局域网信息、扫描设备、测试 IP:端口。 */
class NetworkToolsViewModel(private val scanner: LanScanner) : ViewModel() {

    private val mutableState = MutableStateFlow(NetworkToolsUiState())
    val state: StateFlow<NetworkToolsUiState> = mutableState.asStateFlow()

    init {
        refreshNetwork()
    }

    fun refreshNetwork() {
        viewModelScope.launch {
            val info = runCatching { scanner.currentNetwork() }.getOrNull()
            mutableState.update { it.copy(loading = false, info = info) }
        }
    }

    fun scan() {
        if (mutableState.value.scanning) return
        val info = mutableState.value.info
        if (info == null) {
            mutableState.update {
                it.copy(loading = false, error = "未连接到局域网（请先连接 Wi-Fi）")
            }
            return
        }
        mutableState.update {
            it.copy(
                scanning = true,
                scanned = 0,
                total = 0,
                hosts = emptyList(),
                scanDone = false,
                error = null
            )
        }
        viewModelScope.launch {
            try {
                val hosts = scanner.scan(info) { scanned, total ->
                    mutableState.update { it.copy(scanned = scanned, total = total) }
                }
                val gatewayReachable = scanner.gatewayReachable(info.gateway)
                val checks = LanSubnet.buildChecks(
                    networkAvailable = true,
                    gatewayReachable = gatewayReachable,
                    hosts = hosts
                )
                mutableState.update {
                    it.copy(
                        scanning = false,
                        scanDone = true,
                        hosts = hosts,
                        checks = checks,
                        scanned = it.total,
                        gatewayReachable = gatewayReachable,
                        error = null
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(scanning = false, scanDone = true, error = "扫描失败：" + (e.message ?: "未知错误"))
                }
            }
        }
    }

    fun onTargetIpChange(value: String) {
        mutableState.update { it.copy(targetIp = value, targetResult = null) }
    }

    fun onTargetPortChange(value: String) {
        mutableState.update { it.copy(targetPort = value.filter { char -> char.isDigit() }.take(5), targetResult = null) }
    }

    fun testTarget() {
        val current = mutableState.value
        if (current.testing) return
        val port = current.targetPort.toIntOrNull()
        if (!LanSubnet.isValidIpv4(current.targetIp.trim()) || port == null || port !in 1..65535) {
            mutableState.update { it.copy(targetResult = TargetTestResult.INVALID) }
            return
        }
        mutableState.update { it.copy(testing = true, targetResult = null) }
        viewModelScope.launch {
            val ok = runCatching {
                scanner.testPort(current.targetIp.trim(), port, 2_000)
            }.getOrDefault(false)
            mutableState.update {
                it.copy(testing = false, targetResult = if (ok) TargetTestResult.OK else TargetTestResult.FAILED)
            }
        }
    }
}
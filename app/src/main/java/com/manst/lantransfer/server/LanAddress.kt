package com.manst.lantransfer.server

import java.net.Inet4Address
import java.net.NetworkInterface

/** 获取本机局域网 IPv4 地址（Wi-Fi 优先），用于告诉用户其他设备应连接的地址。 */
object LanAddress {

    fun localIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) && !runCatching { it.isLoopback }.getOrDefault(true) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() && !it.startsWith("169.254.") }
                .distinct()
                .sortedBy { address -> if (address.startsWith("192.168.")) 0 else if (address.startsWith("10.")) 1 else 2 }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun primaryAddress(): String? = localIpv4Addresses().firstOrNull()
}

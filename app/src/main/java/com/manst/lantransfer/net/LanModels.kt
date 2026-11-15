package com.manst.lantransfer.net

/** 当前设备所在的局域网信息。 */
data class LanNetworkInfo(
    val localIp: String,
    val prefixLength: Int,
    val gateway: String?,
    val interfaceName: String,
    val wifi: Boolean
) {
    /** 实际扫描时使用的 /24 网段（大网段避免扫太多地址）。 */
    val scanPrefix: Int get() = maxOf(prefixLength, 24)
}

/** 扫描到的局域网设备。 */
data class LanHost(
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val openPorts: List<Int> = emptyList(),
    val isGateway: Boolean = false,
    val isLanLink: Boolean = false,
    val lanLinkName: String? = null,
    val kind: DeviceKind = DeviceKind.UNKNOWN
) {
    val portsText: String get() = openPorts.joinToString(", ")
}

enum class DeviceKind { LANLINK, ROUTER, NAS, PRINTER, COMPUTER, PHONE, UNKNOWN }

/** 网络诊断项（文案在 UI 层按类型映射，便于单元测试与中英文）。 */
enum class LanCheckType {
    NETWORK_READY,
    GATEWAY_REACHABLE,
    OTHER_DEVICES,
    LANLINK_DEVICES,
    AP_ISOLATION_SUSPECTED
}

data class LanCheck(val type: LanCheckType, val ok: Boolean)

/** 纯 JVM 的网段计算与设备判定，便于单元测试。 */
object LanSubnet {

    /** 扫描时优先探测的常见端口（21/22 服务端、80/443 网页、139/445 共享、8080 管理口、2121/2222 LanLink 服务端）。 */
    val COMMON_PORTS: List<Int> = listOf(21, 22, 80, 139, 443, 445, 8080, 2121, 2222)

    private val IPV4_PATTERN = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isValidIpv4(ip: String): Boolean = ipv4ToLong(ip) != null

    fun ipv4ToLong(ip: String): Long? {
        val match = IPV4_PATTERN.matchEntire(ip.trim()) ?: return null
        var value = 0L
        for (index in 1..4) {
            val part = match.groupValues[index].toIntOrNull() ?: return null
            if (part !in 0..255) return null
            value = (value shl 8) or part.toLong()
        }
        return value
    }

    fun longToIpv4(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }

    /** 生成扫描候选地址：同网段但排除网络地址/广播地址/本机地址；/24 以内最多扫描 254 个地址。 */
    fun hostAddresses(localIp: String, prefixLength: Int, maxHosts: Int = 512): List<String> {
        val local = ipv4ToLong(localIp) ?: return emptyList()
        val effectivePrefix = prefixLength.coerceIn(1, 30)
        // 大网段只扫本机所在的 /24，避免一次扫描上万地址
        val scanPrefix = maxOf(effectivePrefix, 24)
        val mask = ((1L shl (32 - scanPrefix)) - 1L)
        val network = local and (0xFFFFFFFFL xor mask)
        val size = (1L shl (32 - scanPrefix)).toInt()
        val result = ArrayList<String>(minOf(size, maxHosts))
        for (offset in 1 until size - 1) {
            val address = network + offset
            if (address == local) continue
            result.add(longToIpv4(address))
            if (result.size >= maxHosts) break
        }
        return result
    }

    fun sameSubnet(first: String, second: String, prefixLength: Int): Boolean {
        val a = ipv4ToLong(first) ?: return false
        val b = ipv4ToLong(second) ?: return false
        val bits = prefixLength.coerceIn(0, 32)
        if (bits == 0) return true
        val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    /** 根据端口与角色推断设备类型。 */
    fun classify(
        openPorts: List<Int>,
        isGateway: Boolean,
        isLanLink: Boolean
    ): DeviceKind {
        if (isLanLink) return DeviceKind.LANLINK
        if (isGateway) return DeviceKind.ROUTER
        val ports = openPorts.toSet()
        if (ports.contains(9100) || ports.contains(631) || ports.contains(515)) return DeviceKind.PRINTER
        if (ports.contains(445) || ports.contains(139)) {
            return if (ports.contains(21) || ports.contains(22) || ports.contains(5000) || ports.contains(548)) {
                DeviceKind.NAS
            } else {
                DeviceKind.COMPUTER
            }
        }
        if (ports.contains(22) || ports.contains(21)) return DeviceKind.COMPUTER
        return DeviceKind.UNKNOWN
    }

    /** 根据扫描结果生成诊断项；用于 UI 和单元测试。 */
    fun buildChecks(
        networkAvailable: Boolean,
        gatewayReachable: Boolean?,
        hosts: List<LanHost>
    ): List<LanCheck> {
        if (!networkAvailable) {
            return listOf(LanCheck(LanCheckType.NETWORK_READY, false))
        }
        val otherDevices = hosts.count { !it.isGateway }
        val lanlinkDevices = hosts.count { it.isLanLink }
        return buildList {
            add(LanCheck(LanCheckType.NETWORK_READY, true))
            add(LanCheck(LanCheckType.GATEWAY_REACHABLE, gatewayReachable == true))
            add(LanCheck(LanCheckType.OTHER_DEVICES, otherDevices > 0))
            if (lanlinkDevices > 0) add(LanCheck(LanCheckType.LANLINK_DEVICES, true))
            // 网关可达但一个设备都扫不到：常见于路由器开启了 AP 隔离/客户端隔离
            add(LanCheck(LanCheckType.AP_ISOLATION_SUSPECTED, !(otherDevices == 0 && gatewayReachable == true)))
        }
    }
}

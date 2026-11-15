package com.manst.lantransfer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSubnetTest {

    @Test
    fun ipv4_roundTripAndValidation() {
        assertEquals(3232235777L, LanSubnet.ipv4ToLong("192.168.1.1")!!)
        assertEquals("192.168.1.1", LanSubnet.longToIpv4(LanSubnet.ipv4ToLong("192.168.1.1")!!))
        assertTrue(LanSubnet.isValidIpv4("10.0.0.2"))
        assertFalse(LanSubnet.isValidIpv4("192.168.1.256"))
        assertFalse(LanSubnet.isValidIpv4("192.168.1"))
        assertFalse(LanSubnet.isValidIpv4("abc"))
    }

    @Test
    fun hostAddresses_excludesNetworkBroadcastAndSelf() {
        val hosts = LanSubnet.hostAddresses("192.168.1.50", 24)
        assertEquals(253, hosts.size) // 254 个可用地址 - 本机
        assertFalse(hosts.contains("192.168.1.50"))
        assertFalse(hosts.contains("192.168.1.0"))
        assertFalse(hosts.contains("192.168.1.255"))
        assertTrue(hosts.contains("192.168.1.1"))
        assertTrue(hosts.contains("192.168.1.254"))
    }

    @Test
    fun hostAddresses_largeSubnet_capsToLocal24() {
        val hosts = LanSubnet.hostAddresses("10.20.30.40", 8)
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("10.20.30.1"))
        assertFalse(hosts.contains("10.20.31.1"))
    }

    @Test
    fun sameSubnet_checksPrefix() {
        assertTrue(LanSubnet.sameSubnet("192.168.1.10", "192.168.1.200", 24))
        assertFalse(LanSubnet.sameSubnet("192.168.1.10", "192.168.2.200", 24))
        assertTrue(LanSubnet.sameSubnet("192.168.1.10", "192.168.2.200", 16))
        assertFalse(LanSubnet.sameSubnet("bad", "192.168.1.2", 24))
    }

    @Test
    fun classify_usesPortsAndRoles() {
        assertEquals(DeviceKind.LANLINK, LanSubnet.classify(emptyList(), false, true))
        assertEquals(DeviceKind.ROUTER, LanSubnet.classify(listOf(443), true, false))
        assertEquals(DeviceKind.COMPUTER, LanSubnet.classify(listOf(445), false, false))
        assertEquals(DeviceKind.NAS, LanSubnet.classify(listOf(445, 22), false, false))
        assertEquals(DeviceKind.COMPUTER, LanSubnet.classify(listOf(22), false, false))
        assertEquals(DeviceKind.PRINTER, LanSubnet.classify(listOf(9100), false, false))
        assertEquals(DeviceKind.UNKNOWN, LanSubnet.classify(emptyList(), false, false))
    }

    @Test
    fun buildChecks_networkUnavailable() {
        val checks = LanSubnet.buildChecks(false, null, emptyList())
        assertEquals(1, checks.size)
        assertEquals(LanCheckType.NETWORK_READY, checks.first().type)
        assertFalse(checks.first().ok)
    }

    @Test
    fun buildChecks_flagsApIsolationWhenNoDevicesButGatewayReachable() {
        val checks = LanSubnet.buildChecks(
            networkAvailable = true,
            gatewayReachable = true,
            hosts = listOf(LanHost(ip = "192.168.1.1", isGateway = true))
        )
        assertTrue(checks.first { it.type == LanCheckType.GATEWAY_REACHABLE }.ok)
        assertFalse(checks.first { it.type == LanCheckType.OTHER_DEVICES }.ok)
        assertFalse(checks.first { it.type == LanCheckType.AP_ISOLATION_SUSPECTED }.ok)
    }

    @Test
    fun buildChecks_deviceFoundClearsIsolationSuspect() {
        val hosts = listOf(
            LanHost(ip = "192.168.1.1", isGateway = true),
            LanHost(ip = "192.168.1.20", isLanLink = true, lanLinkName = "测试手机")
        )
        val checks = LanSubnet.buildChecks(true, true, hosts)
        val isolation = checks.first { it.type == LanCheckType.AP_ISOLATION_SUSPECTED }
        assertTrue(isolation.ok)
        assertTrue(checks.any { it.type == LanCheckType.LANLINK_DEVICES && it.ok })
    }
}

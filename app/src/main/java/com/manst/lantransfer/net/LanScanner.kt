package com.manst.lantransfer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import com.manst.lantransfer.quick.QuickTransferDefaults
import com.manst.lantransfer.quick.UdpBeaconScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网扫描与诊断：
 * - 读取当前 Wi-Fi/局域网 的 IP、前缀与网关；
 * - 并发 TCP 探测常见端口，结合 ARP 表与 UDP 广播识别在线设备；
 * - 识别正在等待接收的 LanLink 设备（复用快速传文件的 UDP 广播）；
 * - 提供网关可达性与任意 IP:端口 连通性测试。
 */
class LanScanner(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 当前活动网络信息；未连接局域网（或只有移动网络）时返回 null。 */
    fun currentNetwork(): LanNetworkInfo? {
        val manager = connectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        val linkProperties = manager.getLinkProperties(network) ?: return null
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!wifi && !ethernet) return null
        val linkAddress = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val localIp = (linkAddress.address as Inet4Address).hostAddress ?: return null
        val gateway = linkProperties.defaultGateway()
        return LanNetworkInfo(
            localIp = localIp,
            prefixLength = linkAddress.prefixLength,
            gateway = gateway,
            interfaceName = linkProperties.interfaceName ?: "unknown",
            wifi = wifi
        )
    }

    private fun LinkProperties.defaultGateway(): String? {
        for (route in routes) {
            val gateway = route.gateway ?: continue
            if (gateway is Inet4Address) return gateway.hostAddress
        }
        return null
    }

    /**
     * 扫描当前网段。
     * @param onProgress 已完成的「主机×端口」探测数与总数（节流后可频繁更新）。
     */
    suspend fun scan(
        info: LanNetworkInfo,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<LanHost> = withContext(Dispatchers.IO) {
        val candidates = LanSubnet.hostAddresses(info.localIp, info.prefixLength)
        if (candidates.isEmpty()) return@withContext emptyList()
        val ports = LanSubnet.COMMON_PORTS
        val total = candidates.size * ports.size
        val scanned = AtomicInteger(0)
        val openPorts = ConcurrentHashMap<String, MutableSet<Int>>()
        val semaphore = Semaphore(48)

        // 广播监听：识别正在等待接收的 LanLink 设备（端口被占用时忽略）
        val beaconSessions = ConcurrentHashMap<String, String>()
        val scanner = UdpBeaconScanner(port = QuickTransferDefaults.BEACON_PORT)
        try {
            scanner.start { session ->
                runCatching {
                    session.addresses.forEach { address -> beaconSessions[address] = session.deviceName }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动 LanLink 广播监听失败", e)
        }

        try {
            coroutineScope {
                val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>(total)
                for (ip in candidates) {
                    for (port in ports) {
                        jobs += async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (probePort(ip, port, PROBE_TIMEOUT_MILLIS)) {
                                    openPorts.getOrPut(ip) { ConcurrentHashMap.newKeySet<Int>().toMutableSet() }
                                        .add(port)
                                }
                                val done = scanned.incrementAndGet()
                                if (done % PROGRESS_STEP == 0 || done == total) onProgress(done, total)
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            runCatching { scanner.stop() }
        }

        val arp = readArpTable()
        val aliveIps = LinkedHashSet<String>()
        aliveIps.addAll(openPorts.keys)
        arp.filter { (ip, _) -> candidates.contains(ip) }.keys.forEach { aliveIps.add(it) }
        info.gateway?.let { gateway ->
            if (openPorts.containsKey(gateway)) aliveIps.add(gateway)
        }

        val hosts = aliveIps.map { ip ->
            val portsForHost = openPorts[ip]?.sorted().orEmpty()
            val linkName = beaconSessions[ip]
            LanHost(
                ip = ip,
                mac = arp[ip],
                hostname = null,
                openPorts = portsForHost,
                isGateway = ip == info.gateway,
                isLanLink = linkName != null,
                lanLinkName = linkName,
                kind = LanSubnet.classify(portsForHost, ip == info.gateway, linkName != null)
            )
        }.sortedWith(
            compareByDescending<LanHost> { it.isLanLink }
                .thenByDescending { it.isGateway }
                .thenBy { LanSubnet.ipv4ToLong(it.ip) ?: 0L }
        )

        // 反向 DNS：只对在线设备做，限量避免拖慢扫描
        hosts.map { host ->
            if (host.hostname != null || host.ip.isEmpty()) return@map host
            val hostname = withTimeoutOrNull(HOSTNAME_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val address = InetAddress.getByName(host.ip)
                        address.canonicalHostName.takeIf { name -> name.isNotBlank() && name != host.ip }
                    }.getOrNull()
                }
            }
            host.copy(hostname = hostname)
        }
    }

    /** 网关可达性：TCP 常见端口 + isReachable 兜底；结果不确定时返回 null。 */
    suspend fun gatewayReachable(gateway: String?): Boolean? {
        if (gateway == null) return null
        return withContext(Dispatchers.IO) {
            val ports = listOf(80, 443, 22, 53, 8080)
            if (ports.any { probePort(gateway, it, 700) }) return@withContext true
            val reachable = runCatching {
                InetAddress.getByName(gateway).isReachable(700)
            }.getOrDefault(false)
            if (reachable) true else null
        }
    }

    /** 测试任意 IP:端口 是否可建立 TCP 连接。 */
    suspend fun testPort(ip: String, port: Int, timeoutMillis: Int = 1_500): Boolean =
        withContext(Dispatchers.IO) { probePort(ip, port, timeoutMillis) }

    private fun probePort(ip: String, port: Int, timeoutMillis: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), timeoutMillis)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun readArpTable(): Map<String, String> {
        return try {
            val file = File("/proc/net/arp")
            if (!file.canRead()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            file.readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3]
                    if (LanSubnet.isValidIpv4(ip) && mac != "00:00:00:00:00:00") {
                        result[ip] = mac.lowercase()
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "读取 ARP 表失败", e)
            emptyMap()
        }
    }

    private companion object {
        const val TAG = "LanScanner"
        const val PROBE_TIMEOUT_MILLIS = 250
        const val PROGRESS_STEP = 16
        const val HOSTNAME_TIMEOUT_MILLIS = 400L
    }
}

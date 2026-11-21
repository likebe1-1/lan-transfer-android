package com.manst.lantransfer.quick

import android.util.Log
import com.manst.lantransfer.core.TransferProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * 快捷传输广播报文的编解码（纯 JVM 逻辑，org.json 在 Android 运行时与 JVM 测试里都可用）。
 *
 * 报文是单行 JSON，字段固定为：
 * v（协议版本，当前 2）、name（设备名）、addrs（本机 IPv4 数组）、
 * ftp / ftps / ssh（三种监听端口，SFTP 与 SCP 共用 ssh）、protocols（协议名数组）、ts（发送时间戳）。
 *
 * v2 起广播**不携带接收码**：附近设备只能看到设备名/IP/端口/协议，连接时仍需发送方屏幕上的
 * 接收码（或扫描发送页二维码）。解码对非法/未知/缺字段的报文一律返回 null，绝不抛异常；
 * 端口非法时回退到默认端口；旧版 v1 报文可被发现，但其携带的接收码一律丢弃。
 */
internal object QuickBeaconCodec {

    /** 当前广播报文版本。 */
    const val VERSION = 2

    /** 旧版本广播格式（仍可被新客户端发现，但其接收码一律不采信）。 */
    const val LEGACY_VERSION = 1

    /** 单个 UDP 报文最大长度，超出视为非法报文。 */
    const val MAX_PACKET_BYTES = 4096

    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535

    /** 会话信息 -> JSON 文本；编码失败时返回 null（调用方跳过本次广播）。广播不输出接收码。 */
    fun encode(info: QuickSessionInfo): String? {
        return try {
            val json = JSONObject()
                .put("v", VERSION)
                .put("name", info.deviceName)
                .put("addrs", JSONArray(info.addresses))
                .put("ftp", info.ftpPort)
                .put("ftps", info.ftpsPort)
                .put("ssh", info.sshPort)
                .put("protocols", JSONArray(info.protocols.map { it.name }))
                .put("fp", info.ftpsFingerprintSha256.orEmpty())
                .put("hk", info.sshHostKeySha256.orEmpty())
                .put("ts", System.currentTimeMillis())
            json.toString()
        } catch (e: Exception) {
            Log.w(TAG, "编码快捷传输广播报文失败", e)
            null
        }
    }

    /**
     * JSON 文本 -> 会话信息；非法/不完整报文返回 null。
     *
     * 兼容性：同时接受 v2 与旧版 v1 广播（便于发现老版本设备）；
     * 但**两种版本都不采信广播里的接收码**（v1 的 code 字段直接忽略），
     * 接收方仍需输入屏幕上的接收码才能连接。
     */
    fun decode(payload: String?): QuickSessionInfo? {
        val text = payload?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_PACKET_BYTES) return null
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        // 接受 v2（当前）与 v1（旧版，便于发现老版本设备）；其他版本忽略。
        // 两种版本的接收码都不采信：接收方仍需输入对方屏幕上的接收码。
        val version = json.optInt("v", 0)
        if (version != VERSION && version != LEGACY_VERSION) return null

        val protocols = decodeProtocols(json) ?: return null
        val addresses = decodeAddresses(json) ?: return null
        if (addresses.isEmpty()) return null

        val deviceName = json.optString("name", "").trim().ifBlank { "附近设备" }
        return QuickSessionInfo(
            // 不读取 payload 里的 code（即使伪造的 v2 报文带了 code 也不采信）
            code = "",
            deviceName = deviceName,
            addresses = addresses,
            ftpPort = validPort(json.optInt("ftp", QuickTransferDefaults.DEFAULT_FTP_PORT), QuickTransferDefaults.DEFAULT_FTP_PORT),
            ftpsPort = validPort(json.optInt("ftps", QuickTransferDefaults.DEFAULT_FTPS_PORT), QuickTransferDefaults.DEFAULT_FTPS_PORT),
            sshPort = validPort(json.optInt("ssh", QuickTransferDefaults.DEFAULT_SSH_PORT), QuickTransferDefaults.DEFAULT_SSH_PORT),
            protocols = protocols,
            ftpsFingerprintSha256 = json.optString("fp", "").trim()
                .takeIf { com.manst.lantransfer.core.CertificatePinning.isValidFingerprint(it) },
            sshHostKeySha256 = json.optString("hk", "").trim()
                .takeIf { com.manst.lantransfer.core.CertificatePinning.isValidFingerprint(it) },
            receivedAt = System.currentTimeMillis()
        )
    }

    /** 协议名数组：缺字段时按「四种协议全开」兼容处理；显式数组里一个都认不出则认为报文非法。 */
    private fun decodeProtocols(json: JSONObject): List<TransferProtocol>? {
        val array = json.optJSONArray("protocols") ?: return QuickTransferDefaults.PROTOCOLS
        val parsed = HashSet<TransferProtocol>()
        for (index in 0 until array.length()) {
            val name = array.optString(index, "").trim()
            if (name.isEmpty()) continue
            TransferProtocol.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { parsed += it }
        }
        if (parsed.isEmpty()) return null
        return QuickTransferDefaults.PROTOCOLS.filter { it in parsed }
    }

    private fun decodeAddresses(json: JSONObject): List<String>? {
        val array = json.optJSONArray("addrs") ?: return null
        val addresses = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val address = array.optString(index, "").trim()
            if (address.isNotEmpty() && address !in addresses) addresses += address
        }
        return addresses
    }

    private fun validPort(port: Int, fallback: Int): Int = if (port in MIN_PORT..MAX_PORT) port else fallback

    private const val TAG = "QuickBeacon"
}

/**
 * UDP 局域网广播发送端：后台线程按固定间隔向目标地址（默认受限广播 255.255.255.255）发送会话信息。
 *
 * - start 幂等：重复调用会先停止上一次广播再按新会话信息重新开始；
 * - 发送失败只记录日志，不打断会话（对方仍可手动输入 IP + 接收码连接）；
 * - stop 幂等，可在任意线程调用。
 *
 * targetHost 可注入，便于单元测试往 127.0.0.1 发报文。
 */
class UdpBeaconBroadcaster(
    private val targetHost: String = DEFAULT_BROADCAST_ADDRESS,
    private val port: Int = QuickTransferDefaults.BEACON_PORT,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS
) : QuickBeaconBroadcaster {

    private val lock = Any()

    @Volatile
    private var running = false

    private var socket: DatagramSocket? = null

    private var worker: Thread? = null

    @Volatile
    private var info: QuickSessionInfo? = null

    override fun start(info: QuickSessionInfo) {
        synchronized(lock) {
            stopLocked()
            this.info = info
            val newSocket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (e: Exception) {
                Log.w(TAG, "创建广播 Socket 失败，局域网发现不可用（可手动输入 IP 连接）", e)
                this.info = null
                return
            }
            socket = newSocket
            running = true
            val thread = Thread({ sendLoop(newSocket) }, "QuickBeaconBroadcaster")
            thread.isDaemon = true
            thread.priority = Thread.NORM_PRIORITY - 1
            worker = thread
            thread.start()
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        worker?.interrupt()
        worker = null
        socket?.let { socket -> runCatching { socket.close() } }
        socket = null
        info = null
    }

    private fun sendLoop(socket: DatagramSocket) {
        val target = try {
            InetAddress.getByName(targetHost)
        } catch (e: Exception) {
            Log.w(TAG, "广播目标地址非法，停止广播：" + targetHost, e)
            stop()
            return
        }
        while (running) {
            val current = info ?: break
            val payload = QuickBeaconCodec.encode(current)
            if (payload != null) {
                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, target, port))
                } catch (e: Exception) {
                    // 停止过程中 Socket 被关闭会抛异常，属于正常退出路径
                    if (!running) break
                    Log.w(TAG, "发送快捷传输广播失败（对方可手动输入 IP 连接）", e)
                }
            }
            try {
                Thread.sleep(intervalMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    companion object {
        const val DEFAULT_BROADCAST_ADDRESS = "255.255.255.255"
        const val DEFAULT_INTERVAL_MS = 1_000L
        private const val TAG = "UdpBeaconBroadcaster"
    }
}

/**
 * UDP 局域网广播接收端：绑定 port（开启 SO_REUSEADDR）在后台线程循环接收，
 * 解析出会话信息后回调；非法报文与非本版本报文直接忽略。
 *
 * bindAddress 可注入（默认监听所有网卡 0.0.0.0），回环测试时可传 127.0.0.1。
 * 设备上接收广播通常需要 WifiManager.MulticastLock（由接收端管理器负责申请），失败不影响基本收发。
 */
class UdpBeaconScanner(
    private val port: Int = QuickTransferDefaults.BEACON_PORT,
    private val bindAddress: String = DEFAULT_BIND_ADDRESS
) : QuickBeaconScanner {

    private val lock = Any()

    @Volatile
    private var running = false

    private var socket: DatagramSocket? = null

    private var worker: Thread? = null

    private var onSession: ((QuickSessionInfo) -> Unit)? = null

    override fun start(onSession: (QuickSessionInfo) -> Unit) {
        synchronized(lock) {
            stopLocked()
            this.onSession = onSession
            // 注意：不能在 DatagramSocket.apply{} 里直接用 port —— 那是 socket 自身的端口（未绑定=-1），
            // 必须提前捕获构造参数，否则绑定到 -1 会抛 "port out of range:-1"。
            val bindPort = port
            val newSocket = try {
                DatagramSocket(null as SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(bindAddress), bindPort))
                    soTimeout = RECEIVE_POLL_MS
                }
            } catch (e: Exception) {
                Log.w(TAG, "绑定广播监听端口 " + port + " 失败，附近设备发现不可用（可手动输入 IP 连接）", e)
                this.onSession = null
                return
            }
            socket = newSocket
            running = true
            val thread = Thread({ receiveLoop(newSocket) }, "QuickBeaconScanner")
            thread.isDaemon = true
            thread.priority = Thread.NORM_PRIORITY - 1
            worker = thread
            thread.start()
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        worker?.interrupt()
        worker = null
        socket?.let { socket -> runCatching { socket.close() } }
        socket = null
        onSession = null
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(QuickBeaconCodec.MAX_PACKET_BYTES)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                // 定期醒来检查 running，保证 stop 能及时退出
                continue
            } catch (e: SocketException) {
                if (!running) break
                Log.w(TAG, "接收快捷传输广播失败，扫描结束", e)
                break
            } catch (e: Exception) {
                if (!running) break
                continue
            }
            val payload = try {
                String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            val session = QuickBeaconCodec.decode(payload) ?: continue
            try {
                onSession?.invoke(session)
            } catch (e: Exception) {
                Log.w(TAG, "处理快捷传输广播失败", e)
            }
        }
    }

    companion object {
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        private const val RECEIVE_POLL_MS = 1_000
        private const val TAG = "UdpBeaconScanner"
    }
}
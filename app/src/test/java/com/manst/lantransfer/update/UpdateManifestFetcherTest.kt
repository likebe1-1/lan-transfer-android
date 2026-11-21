package com.manst.lantransfer.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** 用本地 HTTP 服务验证多源回退与解析（不访问外网）。 */
class UpdateManifestFetcherTest {

    private lateinit var server: TestHttpServer
    private var baseUrl = ""

    private val validJson = """
        {
          "versionCode": 11,
          "versionName": "2.2.0",
          "changelog": "测试更新",
          "apks": [
            {"abi": "arm64-v8a", "size": 100, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "urls": ["https://example.com/app.apk"]}
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = TestHttpServer()
        server.route("/manifest", 200, validJson)
        server.route("/broken", 200, "{not json")
        server.route("/missing", 404, "")
        baseUrl = "http://127.0.0.1:" + server.port
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun fetchFirst_fallsBackToWorkingSource() {
        val info = UpdateManifestFetcher.fetchFirst(
            allowNonHttps = true,
            urls = listOf(baseUrl + "/missing", baseUrl + "/broken", baseUrl + "/manifest"),
            supportedAbis = listOf("arm64-v8a"),
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_000,
            allowUnsigned = true
        )
        assertNotNull(info)
        assertEquals("2.2.0", info!!.manifest.versionName)
        assertEquals("arm64-v8a", info.apk.abi)
    }

    @Test
    fun fetchFirst_allSourcesFail_returnsNull() {
        val info = UpdateManifestFetcher.fetchFirst(
            allowNonHttps = true,
            urls = listOf(baseUrl + "/missing", baseUrl + "/broken", "http://127.0.0.1:1/never"),
            supportedAbis = listOf("arm64-v8a"),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )
        assertNull(info)
    }

    @Test
    fun fetchText_returnsBodyForOkAndNullForNotFound() {
        val ok = UpdateManifestFetcher.fetchText(baseUrl + "/manifest")
        assertNotNull(ok)
        assertTrue(ok!!.contains("2.2.0"))
        assertNull(UpdateManifestFetcher.fetchText(baseUrl + "/missing"))
    }

    /** 极简 HTTP 测试服务器（避免依赖 android.jar 不包含的 com.sun.net.httpserver）。 */
    private class TestHttpServer : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, Pair<Int, String>>()
        private val thread = Thread { serve() }.apply {
            isDaemon = true
            start()
        }

        val port: Int get() = socket.localPort

        fun route(path: String, status: Int, body: String) {
            routes[path] = status to body
        }

        private fun serve() {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client -> handle(client) }
                } catch (e: Exception) {
                    if (socket.isClosed) return else continue
                }
            }
        }

        private fun handle(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val (status, body) = routes[path] ?: (404 to "")
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = if (status == 200) "OK" else "Not Found"
            val headers = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bytes.size + "\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().let { output ->
                output.write(headers.toByteArray(Charsets.US_ASCII))
                output.write(bytes)
                output.flush()
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}

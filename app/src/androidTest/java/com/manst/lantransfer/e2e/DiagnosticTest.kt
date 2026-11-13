package com.manst.lantransfer.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.protocol.RemoteFileSystems
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DiagnosticTest {

    private val tag = "E2EDIAG"

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun profile(protocol: TransferProtocol, port: Int, passive: Boolean = true) = ServerProfile(
        name = "diag",
        protocol = protocol,
        host = "10.0.2.2",
        port = port,
        username = "test",
        password = "test",
        allowInsecureTls = true,
        passiveMode = passive
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun dump(name: String, bytes: ByteArray) {
        val dir = File(context.getExternalFilesDir(null), "diag")
        dir.mkdirs()
        FileOutputStream(File(dir, name)).use { it.write(bytes) }
        Log.e(tag, "DUMP " + name + " size=" + bytes.size + " sha256=" + sha256(bytes))
    }

    private suspend fun downloadAndDump(
        protocol: TransferProtocol,
        port: Int,
        remote: String,
        dumpName: String,
        passive: Boolean = true,
        tagPrefix: String = ""
    ) {
        val fs = RemoteFileSystems.create(profile(protocol, port, passive))
        try {
            fs.connect()
            val out = ByteArrayOutputStream()
            fs.download(remote, out)
            val bytes = out.toByteArray()
            Log.e(tag, tagPrefix + protocol.label + " download " + remote + " passive=" + passive + " size=" + bytes.size + " sha256=" + sha256(bytes))
            dump(dumpName, bytes)
        } catch (t: Throwable) {
            Log.e(tag, tagPrefix + protocol.label + " download FAILED: " + t, t)
        } finally {
            runCatching { fs.close() }
        }
    }

    @Test
    fun rawTcpRead(): Unit = runBlocking {
        try {
            val socket = Socket("10.0.2.2", 7777)
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val buffer = ByteArray(64 * 1024)
            val out = ByteArrayOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            socket.close()
            val bytes = out.toByteArray()
            Log.e(tag, "RAW tcp got size=" + bytes.size + " sha256=" + sha256(bytes))
            dump("raw_sample.bin", bytes)
        } catch (t: Throwable) {
            Log.e(tag, "RAW tcp FAILED: " + t, t)
        }
        Unit
    }

    @Test
    fun diagnoseDownloads(): Unit = runBlocking {
        downloadAndDump(TransferProtocol.FTP, 2121, "/sample.bin", "ftp_sample_a.bin")
        downloadAndDump(TransferProtocol.FTP, 2121, "/sample.bin", "ftp_sample_active.bin", passive = false, tagPrefix = "ACTIVE ")
        downloadAndDump(TransferProtocol.FTPS, 2122, "/sample.bin", "ftps_sample.bin")
        downloadAndDump(TransferProtocol.SFTP, 2222, "/sample.bin", "sftp_sample.bin")
        downloadAndDump(TransferProtocol.SCP, 2222, "/sample.bin", "scp_sample.bin")
        Unit
    }
}

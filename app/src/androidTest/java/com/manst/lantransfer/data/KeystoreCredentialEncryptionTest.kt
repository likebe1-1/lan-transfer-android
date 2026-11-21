package com.manst.lantransfer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Android Keystore 凭据加密的设备测试：
 * - 密文可解密回明文；
 * - 落盘文件里不出现明文密码；
 * - 服务器仓库重启后能正确解密。
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialEncryptionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
    }

    private fun tempFile(name: String): File =
        File(context.cacheDir, name + "-" + System.nanoTime() + ".json").also { tempFiles += it }

    @Test
    fun keystoreCipher_roundTripsAndHidesPlaintext() {
        val cipher = AndroidKeystoreSecretCipher()
        val plaintext = "P@ssw0rd-中文-😀"
        val encrypted = cipher.encrypt(plaintext)
        assertTrue("应带密文前缀：" + encrypted, SecretCipher.isEncrypted(encrypted))
        assertFalse("密文不应包含明文", encrypted.contains(plaintext))
        assertEquals(plaintext, cipher.decrypt(encrypted))
        // 同一明文两次加密应因随机 IV 产生不同密文
        assertFalse(encrypted == cipher.encrypt(plaintext))
        // 历史明文兼容
        assertEquals("legacy-plain", cipher.decrypt("legacy-plain"))
    }

    @Test
    fun serverRepository_encryptsPasswordAtRest() = runBlocking {
        val file = tempFile("servers-enc")
        val cipher = AndroidKeystoreSecretCipher()
        val repository = JsonServerRepository(file, cipher)
        repository.save(
            ServerProfile(
                id = "secure",
                name = "加密服务器",
                protocol = TransferProtocol.SFTP,
                host = "192.168.1.9",
                port = 22,
                username = "alice",
                password = "device-secret",
                ftpsMode = FtpsMode.EXPLICIT,
                allowInsecureTls = false
            )
        )
        val raw = file.readText()
        assertFalse("磁盘文件不应包含明文密码", raw.contains("device-secret"))
        assertTrue("磁盘文件应包含密文前缀", raw.contains(SecretCipher.PREFIX))
        val reloaded = JsonServerRepository(file, cipher)
        assertEquals("device-secret", reloaded.profiles.value.first { it.id == "secure" }.password)
    }
}

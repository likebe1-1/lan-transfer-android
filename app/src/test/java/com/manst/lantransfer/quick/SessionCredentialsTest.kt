package com.manst.lantransfer.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/** [QuickSessionCode] 的 JVM 单元测试：接收码生成/规范化与凭据派生（两机一致、可复现）。 */
class SessionCredentialsTest {

    @Test
    fun normalize_keepsSixDigits() {
        assertEquals("123456", QuickSessionCode.normalize("123456"))
    }

    @Test
    fun normalize_removesSpacesAndHyphens() {
        assertEquals("123456", QuickSessionCode.normalize(" 123 456 "))
        assertEquals("123456", QuickSessionCode.normalize("123-456"))
        assertEquals("123456", QuickSessionCode.normalize("12 34-56"))
        assertEquals("123456", QuickSessionCode.normalize("\t123456\n"))
    }

    @Test
    fun normalize_rejectsInvalidInput() {
        assertEquals(null, QuickSessionCode.normalize(null))
        assertEquals(null, QuickSessionCode.normalize(""))
        assertEquals(null, QuickSessionCode.normalize("   "))
        assertEquals(null, QuickSessionCode.normalize("12345"))
        assertEquals(null, QuickSessionCode.normalize("1234567"))
        assertEquals(null, QuickSessionCode.normalize("abcdef"))
    }

    fun generate_alwaysProducesConfiguredLengthDigits() {
        repeat(200) {
            val code = QuickSessionCode.generate()
            assertEquals(QuickTransferDefaults.CODE_LENGTH, code.length)
            assertTrue("接收码必须是纯数字：" + code, code.all { ch -> ch.isDigit() })
        }
        assertEquals(8, QuickTransferDefaults.CODE_LENGTH)
    }

    @Test
    fun generate_isDeterministicWithSeededRandom() {
        val expected = Random(42).nextLong(10_000_000L, 100_000_000L).toString()
        assertEquals(expected, QuickSessionCode.generate(Random(42)))
    }

    @Test
    fun normalize_acceptsLegacySixDigitCodes() {
        assertEquals("123456", QuickSessionCode.normalize("123 456"))
        assertEquals("12345678", QuickSessionCode.normalize("1234 5678"))
        assertEquals(null, QuickSessionCode.normalize("123456789"))
    }

    @Test
    fun deriveCredentials_isStableAndDerivedByReceiptCode() {
        val first = QuickSessionCode.deriveCredentials("123456")
        val second = QuickSessionCode.deriveCredentials("123456")
        assertEquals(first, second)
        assertEquals("lt123456", first.username)
        assertTrue("密码必须以 lt-接收码- 开头", first.password.startsWith("lt-123456-"))
        assertTrue("密码应包含 16 位摘要", first.password.length > "lt-123456-".length)
    }

    @Test
    fun deriveCredentials_matchesFrozenAlgorithm() {
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest("LanTransferQuick|654321".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
            .substring(0, 16)
        val credentials = QuickSessionCode.deriveCredentials("654321")
        assertEquals("lt654321", credentials.username)
        assertEquals("lt-654321-" + expectedDigest, credentials.password)
    }

    @Test
    fun deriveCredentials_normalizesUserInput() {
        val normalized = QuickSessionCode.deriveCredentials("123456")
        assertEquals(normalized, QuickSessionCode.deriveCredentials("123-456"))
        assertEquals(normalized, QuickSessionCode.deriveCredentials(" 123 456 "))
    }

    @Test
    fun deriveCredentials_differsBetweenCodes() {
        val a = QuickSessionCode.deriveCredentials("111111")
        val b = QuickSessionCode.deriveCredentials("111112")
        assertNotEquals(a.username, b.username)
        assertNotEquals(a.password, b.password)
        assertFalse(a.password.contains(" "))
    }
}

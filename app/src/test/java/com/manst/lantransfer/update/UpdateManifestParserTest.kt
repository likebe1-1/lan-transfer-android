package com.manst.lantransfer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {

    private val sha = "a".repeat(64)

    private fun json(
        versionCode: Int = 11,
        apks: String = """[{"abi":"arm64-v8a","size":100,"sha256":"$sha","urls":["https://example.com/app.apk"]}]"""
    ): String = """{"versionCode":$versionCode,"versionName":"2.2.0","changelog":"修复若干问题","lanzouUrl":"https://wwbds.lanzoum.com/b0139vopuf","lanzouPassword":"1234","apks":$apks}"""

    @Test
    fun parse_validManifest_returnsInfo() {
        val info = UpdateManifestParser.parse(json(), listOf("arm64-v8a"))
        assertNotNull(info)
        assertEquals(11, info!!.manifest.versionCode)
        assertEquals("2.2.0", info.manifest.versionName)
        assertEquals("修复若干问题", info.manifest.changelog)
        assertEquals("arm64-v8a", info.apk.abi)
        assertEquals(100L, info.apk.size)
        assertEquals(sha, info.apk.sha256)
        assertEquals("https://wwbds.lanzoum.com/b0139vopuf", info.manifest.lanzouUrl)
        assertEquals("1234", info.manifest.lanzouPassword)
    }

    @Test
    fun chooseApk_prefersExactAbiThenUniversal() {
        val apks = listOf(
            UpdateApk("universal", 3, sha, listOf("https://e.com/u.apk")),
            UpdateApk("arm64-v8a", 1, sha, listOf("https://e.com/a.apk")),
            UpdateApk("armeabi-v7a", 2, sha, listOf("https://e.com/b.apk"))
        )
        assertEquals("arm64-v8a", UpdateManifestParser.chooseApk(apks, listOf("arm64-v8a"))?.abi)
        assertEquals("armeabi-v7a", UpdateManifestParser.chooseApk(apks, listOf("armeabi-v7a", "arm64-v8a"))?.abi)
        assertEquals("universal", UpdateManifestParser.chooseApk(apks, listOf("x86_64"))?.abi)
    }

    @Test
    fun parse_skipsInvalidApkEntries() {
        val apks = """[
            {"abi":"arm64-v8a","size":0,"sha256":"$sha","urls":["https://e.com/a.apk"]},
            {"abi":"arm64-v8a","size":10,"sha256":"bad","urls":["https://e.com/a.apk"]},
            {"abi":"arm64-v8a","size":10,"sha256":"$sha","urls":[]},
            {"abi":"arm64-v8a","size":10,"sha256":"$sha","urls":["https://e.com/valid.apk"]}
        ]"""
        val info = UpdateManifestParser.parse(json(apks = apks), listOf("arm64-v8a"))
        assertNotNull(info)
        assertEquals("https://e.com/valid.apk", info!!.apk.urls.first())
    }

    @Test
    fun parse_invalidManifest_returnsNull() {
        assertNull(UpdateManifestParser.parse("not json", listOf("arm64-v8a")))
        assertNull(UpdateManifestParser.parse("""{"versionName":"1.0"}""", listOf("arm64-v8a")))
        assertNull(UpdateManifestParser.parse("""{"versionCode":3,"apks":[]}""", listOf("arm64-v8a")))
        assertNull(UpdateManifestParser.parse(json(apks = "[]"), listOf("arm64-v8a")))
    }

    @Test
    fun isNewer_comparesVersionCodes() {
        assertTrue(UpdateManifestParser.isNewer(11, 9))
        assertFalse(UpdateManifestParser.isNewer(9, 9))
        assertFalse(UpdateManifestParser.isNewer(8, 9))
        assertFalse(UpdateManifestParser.isNewer(0, 9))
        assertFalse(UpdateManifestParser.isNewer(11, 0))
    }
}

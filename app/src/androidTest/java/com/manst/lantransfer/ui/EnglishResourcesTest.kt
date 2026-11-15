package com.manst.lantransfer.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manst.lantransfer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * 英文字符串资源测试：验证 values-en/strings.xml 能按英文 Locale 正确加载，
 * 且全部 294 个 key 均已翻译（中文资源里的 key 在英文资源中不会回退成中文）。
 */
@RunWith(AndroidJUnit4::class)
class EnglishResourcesTest {

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun contextFor(locale: Locale): Context {
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocale(locale)
        }
        return targetContext.createConfigurationContext(configuration)
    }

    private fun englishContext(): Context = contextFor(Locale.ENGLISH)

    private fun chineseContext(): Context = contextFor(Locale.SIMPLIFIED_CHINESE)

    private val chineseRegex = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun appName_staysLanLink() {
        assertEquals("LanLink", englishContext().getString(R.string.app_name))
    }

    @Test
    fun representativeStrings_resolveToEnglish() {
        val context = englishContext()

        // 界面各处具有代表性的资源（列表、快捷传输、传输队列、更新弹窗、通用操作）
        assertEquals("Add server", context.getString(R.string.action_add_server))
        assertEquals("No servers yet", context.getString(R.string.list_empty_title))
        assertEquals("Quick Transfer", context.getString(R.string.quick_entry_title))
        assertEquals("Transfer queue", context.getString(R.string.transfer_queue_title))
        assertEquals("Nearby devices", context.getString(R.string.quick_recv_nearby_title))
        assertEquals("Back", context.getString(R.string.cd_back))
        assertEquals("History (3)", context.getString(R.string.transfer_history_count, 3))
        assertEquals(
            "New version 2.5.0 available",
            context.getString(R.string.update_available_title, "2.5.0")
        )
        assertEquals("Progress 42%", context.getString(R.string.transfer_progress_percent, 42))
        assertEquals("Retrying (2/3)", context.getString(R.string.transfer_retrying, 2))
        assertEquals("FTP 2121 / FTPS 2122 / SSH 2222", context.getString(R.string.quick_recv_ports_line, 2121, 2122, 2222))
    }

    /**
     * 全量检查：中文资源里有中文的每一个 key，英文资源都必须给出非中文内容。
     * 若 values-en 少 key 或翻译漏项，该 key 会回退到默认中文资源，从而被此用例发现。
     */
    @Test
    fun everyTranslatedString_hasNoChineseFallback() {
        val zhContext = chineseContext()
        val enContext = englishContext()

        val checked = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (field in R.string::class.java.fields) {
            val id = field.getInt(null)
            val chinese = zhContext.getString(id)
            if (!chineseRegex.containsMatchIn(chinese)) continue
            checked += field.name
            val english = enContext.getString(id)
            if (chineseRegex.containsMatchIn(english)) {
                failures += field.name + " -> " + english
            }
        }

        assertTrue("应至少检查若干条中文字符串", checked.size >= 250)
        assertTrue(
            "以下英文资源缺失或仍是中文（共 " + failures.size + " 条）：" + failures.joinToString("; "),
            failures.isEmpty()
        )
    }

    @Test
    fun formattedUpdateHint_keepsPlaceholders() {
        val context = englishContext()
        assertEquals(
            "GitHub download unreliable? Use Lanzou Cloud instead (open in a browser; password 8x9z, copied to clipboard)",
            context.getString(R.string.update_lanzou_hint, "8x9z")
        )
        assertEquals(
            "Downloading LanLink 2.5.0 installer…",
            context.getString(R.string.update_downloading_message, "2.5.0")
        )
        assertFalse(chineseRegex.containsMatchIn(context.getString(R.string.update_lanzou_fallback_hint)))
    }
}

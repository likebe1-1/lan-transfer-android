package com.manst.lantransfer.transfer

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输并发配置（[TransferTuning] / [TransferTuningStore]）的单元测试。
 *
 * 覆盖面：默认 5 路、合法范围 1–6、非法值（0/7/负数）回落默认、SharedPreferences 读写与 StateFlow 同步。
 * 不依赖 Android 框架：SharedPreferences 用内存假实现替代，可在纯 JVM 单测中运行。
 */
class TransferTuningTest {

    @Test
    fun `默认并发路数为 5`() {
        assertEquals(5, TransferTuning.DEFAULT)
        assertEquals(5, TransferTuning.DEFAULT_MAX_CONCURRENT_TASKS)
        assertEquals(5, TransferTuning().maxConcurrentTasks)
        assertTrue(TransferTuning().isDefault)
    }

    @Test
    fun `合法范围是 1 到 6`() {
        assertEquals(1, TransferTuning.MIN_MAX_CONCURRENT_TASKS)
        assertEquals(6, TransferTuning.MAX_MAX_CONCURRENT_TASKS)
        assertEquals(1..6, TransferTuning.VALID_RANGE)
        for (value in 1..6) {
            assertTrue("" + value + " 应为合法值", TransferTuning.isValid(value))
        }
        assertFalse(TransferTuning.isValid(0))
        assertFalse(TransferTuning.isValid(7))
        assertFalse(TransferTuning.isValid(-1))
        assertFalse(TransferTuning.isValid(Int.MIN_VALUE))
    }

    @Test
    fun `非法值回落默认 5`() {
        assertEquals(5, TransferTuning.sanitize(0))
        assertEquals(5, TransferTuning.sanitize(7))
        assertEquals(5, TransferTuning.sanitize(-1))
        assertEquals(5, TransferTuning.sanitize(Int.MIN_VALUE))
        assertEquals(5, TransferTuning.sanitize(Int.MAX_VALUE))
        assertEquals(5, TransferTuning.fromRaw(0).maxConcurrentTasks)
        for (value in 1..6) {
            assertEquals(value, TransferTuning.sanitize(value))
            assertEquals(value, TransferTuning.fromRaw(value).maxConcurrentTasks)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `直接构造非法并发路数会被拒绝`() {
        TransferTuning(0)
    }

    // ------------------------------------------------------------ SharedPreferences 读写

    @Test
    fun `未保存时读取默认值 5`() {
        val store = TransferTuningStore(FakeSharedPreferences())

        assertEquals(5, store.maxConcurrentTasks)
        assertEquals(5, store.tuning.value.maxConcurrentTasks)
        assertTrue(store.current().isDefault)
    }

    @Test
    fun `保存合法值后 StateFlow 与 SharedPreferences 一致`() {
        val preferences = FakeSharedPreferences()
        val store = TransferTuningStore(preferences)

        store.setMaxConcurrentTasks(6)

        assertEquals(6, store.maxConcurrentTasks)
        assertEquals(6, store.tuning.value.maxConcurrentTasks)
        assertFalse(store.tuning.value.isDefault)
        assertEquals(
            6,
            preferences.getInt(TransferTuningStore.KEY_MAX_CONCURRENT_TASKS, -1)
        )
    }

    @Test
    fun `SharedPreferences 中的非法值读取时回落默认 5`() {
        for (invalid in listOf(0, 7, -1, Int.MIN_VALUE)) {
            val preferences = FakeSharedPreferences()
            preferences.edit()
                .putInt(TransferTuningStore.KEY_MAX_CONCURRENT_TASKS, invalid)
                .apply()
            val store = TransferTuningStore(preferences)
            assertEquals(
                "" + invalid + " 应回落默认",
                5,
                store.maxConcurrentTasks
            )
        }
    }

    @Test
    fun `设置非法值时回落默认并持久化默认值`() {
        val preferences = FakeSharedPreferences()
        val store = TransferTuningStore(preferences)
        store.setMaxConcurrentTasks(6)

        store.setMaxConcurrentTasks(7)

        assertEquals(5, store.maxConcurrentTasks)
        assertEquals(
            5,
            preferences.getInt(TransferTuningStore.KEY_MAX_CONCURRENT_TASKS, -1)
        )
    }

    @Test
    fun `reload 反映外部写入的合法值`() {
        val preferences = FakeSharedPreferences()
        val store = TransferTuningStore(preferences)
        assertEquals(5, store.maxConcurrentTasks)

        preferences.edit()
            .putInt(TransferTuningStore.KEY_MAX_CONCURRENT_TASKS, 4)
            .apply()
        store.reload()

        assertEquals(4, store.maxConcurrentTasks)
        assertNotEquals(5, store.maxConcurrentTasks)
    }

    @Test
    fun `默认值参数可覆盖且非法默认值同样回落 5`() {
        assertEquals(3, TransferTuningStore(FakeSharedPreferences(), 3).maxConcurrentTasks)
        assertEquals(5, TransferTuningStore(FakeSharedPreferences(), 9).maxConcurrentTasks)
    }

    // ------------------------------------------------------------ 假 SharedPreferences

    /** 纯内存 SharedPreferences 假实现（只实现本模块用到的读写语义）。 */
    private class FakeSharedPreferences : SharedPreferences {

        private val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {

            private val updates = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearRequested = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                updates[key] = values?.toMutableSet()
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                updates[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removals.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(updates)
            }
        }
    }
}

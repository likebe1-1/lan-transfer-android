package com.manst.lantransfer.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 调试版必须禁用应用内更新：
 * debug 包与 Release 包签名不同，下载的 Release APK 无法覆盖安装
 * （之前的体验是「下载完提示签名校验失败」），现在应直接给出可理解提示。
 */
@RunWith(AndroidJUnit4::class)
class DebugUpdateGuardTest {

    @Test
    fun debugBuild_manualCheck_showsFriendlyMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DefaultUpdateManager(context)
        manager.check(auto = false)
        // check 是同步设置的（网络部分在 debug 版被直接跳过）
        val state = manager.state.value
        assertNull("调试版不应返回可下载的更新信息", state.info)
        assertTrue(
            "应提示调试版不支持更新，实际：" + state.error,
            state.error?.contains("调试版") == true
        )
    }
}

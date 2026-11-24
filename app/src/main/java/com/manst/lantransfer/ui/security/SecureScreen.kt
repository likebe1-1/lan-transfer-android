package com.manst.lantransfer.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 给当前 Activity 窗口加 [WindowManager.LayoutParams.FLAG_SECURE]，离开该界面时清除。
 *
 * 用于展示敏感内容的页面（接收码 / 二维码 / 密码输入），防止：
 * - 系统截图与录屏把内容截走；
 * - 最近任务列表（Recents）缩略图泄露内容。
 *
 * 注意：FLAG_SECURE 会让该界面无法被截图，这是预期的安全行为；
 * 若用户需要截图，可临时切到非敏感页面。
 */
@Composable
fun SecureScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

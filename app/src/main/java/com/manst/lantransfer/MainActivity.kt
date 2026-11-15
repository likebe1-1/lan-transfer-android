package com.manst.lantransfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.manst.lantransfer.ui.LanTransferApp
import com.manst.lantransfer.ui.Routes
import com.manst.lantransfer.ui.theme.LanTransferTheme

/**
 * 单 Activity + Compose Navigation。
 *
 * 同时作为系统「分享」目标：从相册/文件管理器分享文件到本应用时，
 * 把文件 Uri 放入 [AppContainer.shareImports]，界面会自动进入「快速传文件」发送页。
 *
 * 也接收桌面快捷方式（发送文件 / 接收文件 / 启动服务器）的 action：
 * 解析后写入 [AppContainer.pendingRoute]，由 Compose 导航消费。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        clearShareAction(intent)
        // 冷启动（非重建）时处理快捷方式 action；不要修改 intent.action，
        // 否则 ActivityScenario/系统会认为 Activity 的 Intent 与启动 Intent 不一致。
        if (savedInstanceState == null) handleShortcutIntent(intent)
        val container = (application as LanTransferApplication).container
        setContent {
            LanTransferTheme {
                LanTransferApp(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        clearShareAction(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) {
            @Suppress("DEPRECATION")
            if (action == Intent.ACTION_SEND) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            } else {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        val contentUris = uris.filter { it.scheme == "content" || it.scheme == "file" }
        if (contentUris.isEmpty()) return
        val container = (application as LanTransferApplication).container
        container.shareImports.value = contentUris
    }

    /** 消费掉分享 action，避免 Activity 重建时重复触发。 */
    private fun clearShareAction(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.action = null
        }
    }

    /**
     * 桌面快捷方式：把 action 转成待处理路由，交给 LanTransferApp 导航。
     * 处理完后清空 action，避免 Activity 重建时重复跳转。
     */
    private fun handleShortcutIntent(intent: Intent?) {
        val action = intent?.action ?: return
        applyShortcutAction(action)
    }

    /** 把快捷方式 action 转为待处理路由；返回是否识别到已知快捷方式。 */
    fun applyShortcutAction(action: String): Boolean {
        val route = Routes.routeForShortcutAction(action) ?: return false
        val container = (application as LanTransferApplication).container
        container.pendingRoute.value = route
        return true
    }
}
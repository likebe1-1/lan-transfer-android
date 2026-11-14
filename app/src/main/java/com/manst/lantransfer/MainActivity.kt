package com.manst.lantransfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.manst.lantransfer.ui.LanTransferApp
import com.manst.lantransfer.ui.theme.LanTransferTheme

/**
 * 单 Activity + Compose Navigation。
 *
 * 同时作为系统「分享」目标：从相册/文件管理器分享文件到本应用时，
 * 把文件 Uri 放入 [AppContainer.shareImports]，界面会自动进入「快速传文件」发送页。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        clearShareAction(intent)
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
}

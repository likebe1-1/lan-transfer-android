package com.manst.lantransfer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次启动提示：提醒用户只在可信局域网内使用本应用。
 *
 * 说明：本应用会在当前局域网内使用 FTP/FTPS/SFTP/SCP 传输文件，
 * 其中 FTP 为明文协议，公共 Wi-Fi 存在被监听或伪造设备的风险。
 */
@Composable
fun TrustedLanNoticeDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onConfirm,
        icon = {
            Icon(
                imageVector = Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = "请在你信任的局域网下使用", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "本应用会在当前局域网内，使用 FTP / FTPS / SFTP / SCP 协议在你的设备之间传输文件。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• 建议仅在家庭、公司等可信的 Wi-Fi 下使用；\n" +
                        "• 公共 Wi-Fi 可能存在被监听、被伪造设备的风险；\n" +
                        "• FTP 为明文协议，自动协商时会优先使用加密协议。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "继续使用即表示你了解上述风险，并对传输的文件负责。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "我已知晓")
            }
        }
    )
}

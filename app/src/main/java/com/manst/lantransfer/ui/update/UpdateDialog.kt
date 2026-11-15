package com.manst.lantransfer.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manst.lantransfer.R
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.update.UpdateSources
import com.manst.lantransfer.update.UpdateUiState

/**
 * 应用内更新对话框：
 * - 有新版本：展示更新日志 +「下载并安装」；国内网络下载失败可改用蓝奏云浏览器下载；
 * - 下载中：展示进度百分比与大小；
 * - 失败：展示错误 + 重试 / 蓝奏云下载。
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onLanzou: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val info = state.info
    when {
        state.downloading -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_downloading_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.update_downloading_message,
                                info?.manifest?.versionName.orEmpty()
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        val total = info?.apk?.size ?: 0L
                        Text(
                            text = if (state.progress >= 0f) {
                                stringResource(
                                    R.string.update_progress_text,
                                    (state.progress * 100f).toInt(),
                                    Format.bytes(state.downloadedBytes),
                                    Format.bytes(total)
                                )
                            } else {
                                stringResource(R.string.update_progress_unknown)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_hide)) }
                }
            )
        }

        info != null -> {
            val password = info.manifest.lanzouPassword ?: UpdateSources.LANZOU_PASSWORD
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(stringResource(R.string.update_available_title, info.manifest.versionName))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (info.manifest.changelog.isNotBlank()) {
                            Text(info.manifest.changelog, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            text = stringResource(R.string.update_lanzou_hint, password),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.error?.let { error ->
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.update_download_install))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = onLanzou) { Text(stringResource(R.string.update_lanzou)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                    }
                }
            )
        }

        state.error != null -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_error_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.error, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.update_lanzou_fallback_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.update_retry)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = onLanzou) { Text(stringResource(R.string.update_lanzou)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_close)) }
                    }
                }
            )
        }
    }
}

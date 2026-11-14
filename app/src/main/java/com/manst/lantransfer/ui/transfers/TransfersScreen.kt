package com.manst.lantransfer.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.transfer.TransferDirection
import com.manst.lantransfer.transfer.TransferState
import com.manst.lantransfer.transfer.TransferTask
import com.manst.lantransfer.ui.common.EmptyState
import com.manst.lantransfer.ui.common.SectionHeader
import com.manst.lantransfer.ui.common.SnackbarMessageEffect

/** 传输队列页：进行中/已完成分区，展示进度、速度，支持取消/重试/移除/清空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(viewModel: TransfersViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("传输队列") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::clearFinished,
                        enabled = state.finished.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空已完成")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (state.isEmpty) {
            EmptyState(
                icon = Icons.Filled.SwapVert,
                title = "队列为空",
                description = "在服务器浏览页下载或上传文件后，任务会出现在这里",
                modifier = Modifier.padding(innerPadding),
                actionLabel = "返回",
                onAction = onBack
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.active.isNotEmpty()) {
                    item(key = "active-header") {
                        SectionHeader("进行中（${state.active.size}）")
                    }
                    items(items = state.active, key = { it.id }) { task ->
                        TransferTaskCard(
                            task = task,
                            onCancel = { viewModel.cancel(task) },
                            onRetry = { viewModel.retry(task) },
                            onRemove = { viewModel.remove(task) }
                        )
                    }
                }
                if (state.finished.isNotEmpty()) {
                    item(key = "finished-header") {
                        SectionHeader("已完成（${state.finished.size}）")
                    }
                    items(items = state.finished, key = { it.id }) { task ->
                        TransferTaskCard(
                            task = task,
                            onCancel = { viewModel.cancel(task) },
                            onRetry = { viewModel.retry(task) },
                            onRemove = { viewModel.remove(task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferTaskCard(
    task: TransferTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val isUpload = task.direction == TransferDirection.UPLOAD
    val directionColor = if (isUpload) {
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = directionColor.first,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isUpload) Icons.Filled.Upload else Icons.Filled.Download,
                            contentDescription = if (isUpload) "上传" else "下载",
                            tint = directionColor.second
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TransferStateBadge(state = task.state)
                    }
                    Text(
                        text = "${task.serverName} · ${task.protocolLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TaskActionButtons(task = task, onCancel = onCancel, onRetry = onRetry, onRemove = onRemove)
            }

            if (task.state == TransferState.RUNNING) {
                if (task.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            val errorMessage = task.error
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = progressSummary(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (task.state == TransferState.RUNNING) {
                    Text(
                        text = Format.speed(task.bytesPerSecond),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (task.state == TransferState.RUNNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "进度 " + (task.progress * 100f).toInt() + "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = Format.remainingTime(
                            task.totalBytes - task.transferredBytes,
                            task.bytesPerSecond
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (task.state == TransferState.FAILED && errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (task.state == TransferState.COMPLETED &&
                task.direction == TransferDirection.DOWNLOAD &&
                task.localDisplayName != null
            ) {
                Text(
                    text = "已保存为 ${task.localDisplayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TaskActionButtons(
    task: TransferTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Row {
        when (task.state) {
            TransferState.QUEUED, TransferState.RUNNING -> {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消任务")
                }
            }

            TransferState.FAILED, TransferState.CANCELLED -> {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Replay, contentDescription = "重试任务")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "移除任务")
                }
            }

            TransferState.COMPLETED -> {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "移除任务")
                }
            }
        }
    }
}

@Composable
private fun TransferStateBadge(state: TransferState) {
    val darkTheme = isSystemInDarkTheme()
    val (container, content) = when (state) {
        TransferState.QUEUED -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant

        TransferState.RUNNING -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer

        TransferState.COMPLETED ->
            if (darkTheme) Color(0xFF16351D) to Color(0xFF9FE0A8)
            else Color(0xFFDDF3DF) to Color(0xFF1E6B2A)

        TransferState.FAILED -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer

        TransferState.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(percent = 50)) {
        Text(
            text = stateLabel(state),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun stateLabel(state: TransferState): String = when (state) {
    TransferState.QUEUED -> "排队中"
    TransferState.RUNNING -> "传输中"
    TransferState.COMPLETED -> "已完成"
    TransferState.FAILED -> "失败"
    TransferState.CANCELLED -> "已取消"
}

private fun progressSummary(task: TransferTask): String {
    val transferred = Format.bytes(task.transferredBytes)
    val total = if (task.totalBytes > 0L) Format.bytes(task.totalBytes) else null
    return when (task.state) {
        TransferState.QUEUED -> "等待中${if (total != null) " · 共 $total" else ""}"
        TransferState.RUNNING -> if (total != null) "$transferred / $total" else "已传输 $transferred"
        TransferState.COMPLETED -> if (total != null) "共 $total" else "传输完成"
        TransferState.FAILED -> if (total != null) "$transferred / $total" else "已传输 $transferred"
        TransferState.CANCELLED -> "已取消${if (total != null) " · $transferred / $total" else ""}"
    }
}

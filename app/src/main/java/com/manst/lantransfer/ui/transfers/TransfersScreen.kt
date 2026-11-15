package com.manst.lantransfer.ui.transfers

import com.manst.lantransfer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import com.manst.lantransfer.ui.common.FileOpener
import com.manst.lantransfer.ui.common.SectionHeader
import com.manst.lantransfer.ui.common.SnackbarMessageEffect
import kotlinx.coroutines.launch

/** 传输队列页：进行中/已完成分区，展示进度、速度，支持取消/重试/移除/清空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(viewModel: TransfersViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    val openTask: (TransferTask) -> Unit = { task ->
        val opened = FileOpener.openTransferFile(
            context = context,
            localUri = task.localUri,
            displayName = task.localDisplayName ?: task.displayName
        )
        if (!opened) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.transfer_open_failed, task.displayName))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::clearHistory,
                        enabled = state.finished.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.cd_clear_history))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (state.isEmpty) {
            EmptyState(
                icon = Icons.Filled.SwapVert,
                title = stringResource(R.string.transfer_empty_title),
                description = stringResource(R.string.transfer_empty_desc),
                modifier = Modifier.padding(innerPadding),
                actionLabel = stringResource(R.string.action_back),
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
                        SectionHeader(stringResource(R.string.transfer_active_count, state.active.size))
                    }
                    items(items = state.active, key = { it.id }) { task ->
                        TransferTaskCard(
                            task = task,
                            onCancel = { viewModel.cancel(task) },
                            onRetry = { viewModel.retry(task) },
                            onRemove = { viewModel.remove(task) },
                            onOpen = openTask
                        )
                    }
                }
                if (state.finished.isNotEmpty()) {
                    item(key = "finished-header") {
                        SectionHeader(stringResource(R.string.transfer_history_count, state.finished.size))
                    }
                    items(items = state.finished, key = { it.id }) { task ->
                        TransferTaskCard(
                            task = task,
                            onCancel = { viewModel.cancel(task) },
                            onRetry = { viewModel.retry(task) },
                            onRemove = { viewModel.remove(task) },
                            onOpen = openTask
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
    onRemove: () -> Unit,
    onOpen: (TransferTask) -> Unit
) {
    val isUpload = task.direction == TransferDirection.UPLOAD
    val canOpen = task.direction == TransferDirection.DOWNLOAD && task.state == TransferState.COMPLETED
    val directionColor = if (isUpload) {
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    // 文件夹批次任务展示相对路径（如「相册/2024/文件名」）
    val displayPath = if (task.localRelativeDir.isNullOrBlank()) {
        task.displayName
    } else {
        task.localRelativeDir.trimEnd('/') + "/" + task.displayName
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen) { onOpen(task) }
    ) {
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
                            contentDescription = if (isUpload) {
                                stringResource(R.string.cd_upload)
                            } else {
                                stringResource(R.string.cd_download)
                            },
                            tint = directionColor.second
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayPath,
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
                    if (task.attempts > 0) {
                        Text(
                            text = if (task.state == TransferState.FAILED || task.state == TransferState.CANCELLED) {
                                stringResource(R.string.transfer_retried_times, task.attempts)
                            } else {
                                stringResource(R.string.transfer_retrying, task.attempts)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                TaskActionButtons(
                    task = task,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onRemove = onRemove,
                    onOpen = onOpen
                )
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

            if (task.direction == TransferDirection.DOWNLOAD && task.state != TransferState.COMPLETED) {
                Text(
                    text = when {
                        !task.resumable -> stringResource(R.string.transfer_no_resume_scp)
                        task.attempts > 0 && task.transferredBytes > 0L -> stringResource(R.string.transfer_resumed)
                        else -> stringResource(R.string.transfer_supports_resume)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.resumable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (task.state == TransferState.RUNNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.transfer_progress_percent, (task.progress * 100f).toInt()),
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
                    text = stringResource(R.string.transfer_saved_as, task.localDisplayName ?: ""),
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
    onRemove: () -> Unit,
    onOpen: (TransferTask) -> Unit
) {
    Row {
        when (task.state) {
            TransferState.QUEUED, TransferState.RUNNING -> {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel_task))
                }
            }

            TransferState.FAILED, TransferState.CANCELLED -> {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Replay, contentDescription = stringResource(R.string.cd_retry_task))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_task))
                }
            }

            TransferState.COMPLETED -> {
                if (task.direction == TransferDirection.DOWNLOAD) {
                    IconButton(onClick = { onOpen(task) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.cd_open_file)
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_task))
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

@Composable
private fun stateLabel(state: TransferState): String = when (state) {
    TransferState.QUEUED -> stringResource(R.string.transfer_state_queued)
    TransferState.RUNNING -> stringResource(R.string.transfer_state_running)
    TransferState.COMPLETED -> stringResource(R.string.transfer_state_completed)
    TransferState.FAILED -> stringResource(R.string.transfer_state_failed)
    TransferState.CANCELLED -> stringResource(R.string.transfer_state_cancelled)
}

@Composable
private fun progressSummary(task: TransferTask): String {
    val transferred = Format.bytes(task.transferredBytes)
    val total = if (task.totalBytes > 0L) Format.bytes(task.totalBytes) else null
    return when (task.state) {
        TransferState.QUEUED -> if (total != null) {
            stringResource(R.string.transfer_progress_queued_with_total, total)
        } else {
            stringResource(R.string.transfer_progress_queued)
        }

        TransferState.RUNNING -> if (total != null) {
            stringResource(R.string.transfer_progress_bytes, transferred, total)
        } else {
            stringResource(R.string.transfer_progress_sent, transferred)
        }

        TransferState.COMPLETED -> if (total != null) {
            stringResource(R.string.transfer_progress_total, total)
        } else {
            stringResource(R.string.transfer_progress_completed)
        }

        TransferState.FAILED -> if (total != null) {
            stringResource(R.string.transfer_progress_bytes, transferred, total)
        } else {
            stringResource(R.string.transfer_progress_sent, transferred)
        }

        TransferState.CANCELLED -> if (total != null) {
            stringResource(R.string.transfer_progress_cancelled_detail, transferred, total)
        } else {
            stringResource(R.string.transfer_progress_cancelled)
        }
    }
}
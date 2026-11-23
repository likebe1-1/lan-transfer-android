package com.manst.lantransfer.ui.servers

import com.manst.lantransfer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.core.ServerProfile
import com.manst.lantransfer.ui.common.ConfirmDialog
import com.manst.lantransfer.ui.common.EmptyState
import com.manst.lantransfer.ui.common.SnackbarMessageEffect
import com.manst.lantransfer.ui.components.ProtocolChip
import com.manst.lantransfer.ui.quick.QuickTransferCard
import com.manst.lantransfer.ui.update.UpdateDialog
import com.manst.lantransfer.update.UpdateManager

/**
 * 服务器列表页：最近使用排序、协议标签、本机服务器与传输队列入口、新增/编辑/删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenLocalServer: () -> Unit,
    onOpenQuickSend: () -> Unit,
    onOpenQuickReceive: () -> Unit,
    onOpenNetworkTools: () -> Unit,
    onOpenTransferTuning: () -> Unit,
    updateManager: UpdateManager
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    var manualUpdateCheck by remember { mutableStateOf(false) }
    val noUpdateText = stringResource(R.string.update_no_update)

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    // 启动时静默检查一次更新（失败不打扰；有新版本才弹窗）
    LaunchedEffect(Unit) { updateManager.check(auto = true) }

    // 手动检查结束时：没有新版本也没有错误则提示「已是最新」
    LaunchedEffect(updateState.checking, manualUpdateCheck) {
        if (manualUpdateCheck && !updateState.checking &&
            updateState.info == null && updateState.error == null && !updateState.downloading
        ) {
            manualUpdateCheck = false
            snackbarHostState.showSnackbar(noUpdateText)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenNetworkTools) {
                        Icon(
                            imageVector = Icons.Filled.Radar,
                            contentDescription = stringResource(R.string.cd_network_tools)
                        )
                    }
                    IconButton(onClick = onOpenTransferTuning) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.cd_transfer_tuning)
                        )
                    }
                    IconButton(onClick = {
                        manualUpdateCheck = true
                        updateManager.check(auto = false)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = stringResource(R.string.cd_check_update)
                        )
                    }
                    IconButton(onClick = onOpenLocalServer) {
                        Icon(
                            imageVector = Icons.Filled.WifiTethering,
                            contentDescription = stringResource(R.string.cd_local_server)
                        )
                    }
                    IconButton(onClick = onOpenTransfers) {
                        BadgedBox(
                            badge = {
                                if (state.activeTransfers > 0) {
                                    Badge { Text(state.activeTransfers.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SwapVert,
                                contentDescription = stringResource(R.string.transfer_queue_title)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddServer,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_server)) }
            )
        }
    ) { innerPadding ->
        if (state.servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                QuickTransferCard(
                    onSend = onOpenQuickSend,
                    onReceive = onOpenQuickReceive
                )
                EmptyState(
                    icon = Icons.Filled.Dns,
                    title = stringResource(R.string.list_empty_title),
                    description = stringResource(R.string.list_empty_description),
                    modifier = Modifier.weight(1f),
                    actionLabel = stringResource(R.string.action_add_server),
                    onAction = onAddServer
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "quick-transfer") {
                    QuickTransferCard(
                        onSend = onOpenQuickSend,
                        onReceive = onOpenQuickReceive
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.list_server_count, state.servers.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                items(items = state.servers, key = { it.id }) { profile ->
                    ServerCard(
                        profile = profile,
                        onClick = { onOpenServer(profile.id) },
                        onEdit = { onEditServer(profile.id) },
                        onDelete = { pendingDelete = profile }
                    )
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.list_delete_dialog_title),
            message = stringResource(R.string.list_delete_dialog_message, profile.name),
            onConfirm = {
                viewModel.delete(profile)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    val showUpdateDialog =
        updateState.downloading ||
            updateState.info != null ||
            updateState.error != null ||
            (manualUpdateCheck && updateState.checking)
    if (showUpdateDialog) {
        UpdateDialog(
            state = updateState,
            onDownload = { updateManager.downloadAndInstall() },
            onLanzou = { updateManager.openLanzou() },
            onDismiss = {
                manualUpdateCheck = false
                updateManager.dismiss()
            },
            onRetry = {
                updateManager.clearError()
                manualUpdateCheck = true
                updateManager.check(auto = false)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    profile: ServerProfile,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ProtocolChip(protocol = profile.protocol)
                }
                Text(
                    text = profile.endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = lastUsedText(profile.lastUsedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_actions_for, profile.name)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun lastUsedText(lastUsedAt: Long): String =
    if (lastUsedAt <= 0L) {
        stringResource(R.string.list_never_used)
    } else {
        stringResource(R.string.list_last_used, Format.date(lastUsedAt))
    }
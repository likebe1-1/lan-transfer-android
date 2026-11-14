package com.manst.lantransfer.ui.quick

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.QuickJoinTarget
import com.manst.lantransfer.quick.QuickSessionInfo
import com.manst.lantransfer.quick.QuickTransferDefaults
import com.manst.lantransfer.quick.QuickTransferProgress
import com.manst.lantransfer.ui.common.ConfirmDialog
import com.manst.lantransfer.ui.common.SnackbarMessageEffect
import kotlinx.coroutines.launch

/**
 * 接收端快捷传文件页：
 * 自动发现附近发送端（点击即连）或手动输入 IP + 6 位接收码；
 * 连接后列出对方文件，支持全部接收/单个接收、进度、已保存清单、失败重试，并可反向发送文件给对方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReceiveScreen(viewModel: QuickReceiveViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var disconnectThenExit by remember { mutableStateOf(false) }
    var exitAfterLeave by remember { mutableStateOf(false) }

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val limited = uris.take(QuickTransferDefaults.MAX_FILES)
            if (uris.size > QuickTransferDefaults.MAX_FILES) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "一次最多选择 ${QuickTransferDefaults.MAX_FILES} 个文件，已选择前 ${QuickTransferDefaults.MAX_FILES} 个"
                    )
                }
            }
            viewModel.sendFiles(limited)
        }
    }

    // 断开后等待状态真正复位再退出页面，避免 ViewModel 提前销毁导致断开失败
    LaunchedEffect(state.isConnected, state.isLeaving, exitAfterLeave) {
        if (exitAfterLeave && !state.isConnected && !state.isLeaving) {
            exitAfterLeave = false
            onBack()
        }
    }

    val requestBack: () -> Unit = {
        if (state.isConnected) {
            disconnectThenExit = true
            showDisconnectDialog = true
        } else {
            onBack()
        }
    }
    BackHandler(onBack = requestBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接收文件") },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.isConnected) {
                ConnectedContent(
                    state = state,
                    onDownloadAll = viewModel::downloadAll,
                    onDownload = viewModel::download,
                    onRetryFailed = viewModel::retryFailed,
                    onSendFiles = { filePicker.launch(arrayOf("*/*")) },
                    onDisconnect = {
                        disconnectThenExit = false
                        showDisconnectDialog = true
                    }
                )
            } else {
                DiscoveryContent(
                    state = state,
                    onJoinDiscovered = viewModel::joinDiscovered,
                    onRestartDiscovery = viewModel::restartDiscovery,
                    onHostChange = viewModel::onHostChange,
                    onCodeChange = viewModel::onCodeChange,
                    onProtocolChange = viewModel::onProtocolChange,
                    onJoinManual = viewModel::joinManual,
                    onRetryJoin = viewModel::retryJoin
                )
            }
        }
    }

    if (showDisconnectDialog) {
        ConfirmDialog(
            title = if (disconnectThenExit) "断开连接并返回？" else "断开连接？",
            message = if (disconnectThenExit) {
                "返回将断开与「${state.joinedDeviceName}」的连接，已完成的文件不会受影响。"
            } else {
                "断开后可以重新搜索附近设备，已完成的文件不会受影响。"
            },
            confirmLabel = if (disconnectThenExit) "断开并返回" else "断开",
            onConfirm = {
                showDisconnectDialog = false
                viewModel.leave()
                if (disconnectThenExit) exitAfterLeave = true
            },
            onDismiss = { showDisconnectDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------- 发现 / 手动连接

@Composable
private fun DiscoveryContent(
    state: QuickReceiveUiState,
    onJoinDiscovered: (QuickSessionInfo) -> Unit,
    onRestartDiscovery: () -> Unit,
    onHostChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onProtocolChange: (TransferProtocol?) -> Unit,
    onJoinManual: () -> Unit,
    onRetryJoin: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("quick_receive_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "discovery") {
            DiscoveryHeaderCard(state = state, onRestartDiscovery = onRestartDiscovery)
        }
        if (state.receive.discovered.isEmpty()) {
            item(key = "no-device") { NoDeviceCard() }
        } else {
            items(
                items = state.receive.discovered,
                key = { info -> info.code + "|" + info.primaryHost }
            ) { info ->
                DiscoveredDeviceCard(info = info, onClick = { onJoinDiscovered(info) })
            }
        }
        item(key = "manual") {
            ManualJoinCard(
                state = state,
                onHostChange = onHostChange,
                onCodeChange = onCodeChange,
                onProtocolChange = onProtocolChange,
                onJoinManual = onJoinManual
            )
        }
        if (state.isJoining) {
            item(key = "connecting") { ConnectingCard(target = state.lastTarget) }
        }
        if (state.isFailed) {
            item(key = "failed") {
                QuickErrorCard(
                    message = state.receive.error ?: "连接失败，请重试",
                    retryLabel = "重试连接",
                    onRetry = onRetryJoin
                )
            }
        }
        item(key = "bottom") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun DiscoveryHeaderCard(state: QuickReceiveUiState, onRestartDiscovery: () -> Unit) {
    QuickSectionCard(
        title = "附近设备",
        icon = Icons.Filled.WifiFind,
        trailing = {
            IconButton(onClick = onRestartDiscovery) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新搜索附近设备")
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.isDiscovering) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (state.receive.discovered.isEmpty()) {
                    "正在搜索附近设备…"
                } else {
                    "已发现 ${state.receive.discovered.size} 台设备"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = "确保两台设备连接同一 Wi-Fi；对方打开「发送文件」会话后会自动出现在这里，点击即可连接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoDeviceCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            QuickEmptyHint(
                icon = Icons.Filled.WifiFind,
                title = "未发现附近设备",
                description = "请确认对方已开启发送会话，或使用下方「手动输入」连接。"
            )
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(info: QuickSessionInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = info.deviceName.ifBlank { "附近设备" },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "接收码 ${formatSessionCode(info.code)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${info.primaryHost} · FTP ${info.ftpPort} / FTPS ${info.ftpsPort} / SSH ${info.sshPort}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SessionProtocolChips(
                    protocols = info.protocols,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ManualJoinCard(
    state: QuickReceiveUiState,
    onHostChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onProtocolChange: (TransferProtocol?) -> Unit,
    onJoinManual: () -> Unit
) {
    val hostError = state.hostError
    val codeError = state.codeError
    QuickSectionCard(title = "手动输入", icon = Icons.Filled.Link) {
        OutlinedTextField(
            value = state.host,
            onValueChange = onHostChange,
            label = { Text("对方 IP 地址") },
            placeholder = { Text("192.168.1.10") },
            singleLine = true,
            isError = hostError != null,
            supportingText = if (hostError != null) {
                { Text(hostError) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChange,
                label = { Text("6 位接收码") },
                placeholder = { Text("123456") },
                singleLine = true,
                isError = codeError != null,
                supportingText = if (codeError != null) {
                    { Text(codeError) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            QuickProtocolDropdown(
                selected = state.protocol,
                onSelected = onProtocolChange,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "「自动」会按加密优先尝试 SFTP → SCP → FTPS → FTP",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onJoinManual,
            enabled = !state.isJoining,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isJoining) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("连接中…")
            } else {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("连接")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickProtocolDropdown(
    selected: TransferProtocol?,
    onSelected: (TransferProtocol?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<TransferProtocol?> =
        listOf<TransferProtocol?>(null) + QuickTransferDefaults.PROTOCOLS

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = protocolOptionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("协议") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(protocolOptionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectingCard(target: QuickJoinTarget?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = "正在连接…", style = MaterialTheme.typography.titleSmall)
                target?.let {
                    Text(
                        text = "${it.host} · 接收码 ${formatSessionCode(it.code)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 已连接

@Composable
private fun ConnectedContent(
    state: QuickReceiveUiState,
    onDownloadAll: () -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onRetryFailed: (String) -> Unit,
    onSendFiles: () -> Unit,
    onDisconnect: () -> Unit
) {
    val receive = state.receive
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "connected") {
            ConnectedHeaderCard(
                state = state,
                onDownloadAll = onDownloadAll,
                onDisconnect = onDisconnect
            )
        }
        receive.transfer?.let { progress ->
            item(key = "progress") { TransferProgressCard(progress) }
        }
        item(key = "remote") {
            RemoteFilesCard(
                files = receive.remoteFiles,
                transferring = state.isTransferring,
                onDownload = onDownload
            )
        }
        item(key = "completed") {
            CompletedFilesCard(receive.completedFiles)
        }
        if (receive.failedFiles.isNotEmpty()) {
            item(key = "failed") {
                FailedFilesCard(receive.failedFiles, onRetryFailed)
            }
        }
        item(key = "send") {
            SendBackCard(state = state, onSendFiles = onSendFiles)
        }
        receive.error?.let { error ->
            item(key = "error") { QuickErrorCard(message = error) }
        }
        item(key = "bottom") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ConnectedHeaderCard(
    state: QuickReceiveUiState,
    onDownloadAll: () -> Unit,
    onDisconnect: () -> Unit
) {
    QuickSectionCard(title = "已连接", icon = Icons.Filled.CheckCircle) {
        Text(
            text = "已通过 ${state.activeProtocolLabel.ifBlank { "自动" }} 连接 · ${state.joinedDeviceName}",
            style = MaterialTheme.typography.bodyMedium
        )
        state.receive.joined?.primaryHost?.takeIf { it.isNotBlank() }?.let { host ->
            Text(
                text = "对方地址：$host",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("断开连接")
            }
            Button(
                onClick = onDownloadAll,
                enabled = !state.isTransferring,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("全部接收")
            }
        }
    }
}

@Composable
private fun TransferProgressCard(progress: QuickTransferProgress) {
    val isReceive = progress.direction == QuickTransferProgress.Direction.RECEIVE
    QuickSectionCard(
        title = if (isReceive) "正在接收" else "正在发送",
        icon = if (isReceive) Icons.Filled.FileDownload else Icons.Filled.FileUpload
    ) {
        Text(
            text = progress.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (progress.totalBytes > 0L) {
            LinearProgressIndicator(
                progress = {
                    (progress.bytesTransferred.toFloat() / progress.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${Format.bytes(progress.bytesTransferred)} / " +
                    if (progress.totalBytes > 0L) Format.bytes(progress.totalBytes) else "未知大小",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (progress.fileCount > 0 && progress.fileIndex in 1..progress.fileCount) {
                Text(
                    text = "第 ${progress.fileIndex}/${progress.fileCount} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemoteFilesCard(
    files: List<RemoteEntry>,
    transferring: Boolean,
    onDownload: (RemoteEntry) -> Unit
) {
    QuickSectionCard(title = "对方文件（${files.size}）", icon = Icons.Filled.Folder) {
        if (files.isEmpty()) {
            QuickEmptyHint(
                icon = Icons.Filled.Folder,
                title = "对方没有可下载的文件",
                description = "对方在发送会话中还没有选择文件"
            )
        } else {
            files.forEach { entry ->
                QuickFileRow(
                    name = entry.name,
                    supporting = Format.bytes(entry.size)
                ) {
                    TextButton(
                        onClick = { onDownload(entry) },
                        enabled = !transferring
                    ) {
                        Text("接收")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedFilesCard(names: List<String>) {
    QuickSectionCard(title = "已保存（${names.size}）", icon = Icons.Filled.CheckCircle) {
        if (names.isEmpty()) {
            Text(
                text = "还没有完成的文件，点「全部接收」开始下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            names.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = successContentColor(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "已保存到系统下载目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedFilesCard(names: List<String>, onRetry: (String) -> Unit) {
    QuickSectionCard(title = "失败（${names.size}）", icon = Icons.Filled.ErrorOutline) {
        names.forEach { name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRetry(name) }) { Text("重试") }
            }
        }
    }
}

@Composable
private fun SendBackCard(state: QuickReceiveUiState, onSendFiles: () -> Unit) {
    QuickSectionCard(title = "发送文件给对方", icon = Icons.Filled.FileUpload) {
        Text(
            text = "对方也能收到你选择的文件，实现双向互传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onSendFiles,
            enabled = !state.isTransferring,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.FileUpload,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("发送文件给对方")
        }
        if (state.receive.sentFiles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "已发送（${state.receive.sentFiles.size}）",
                style = MaterialTheme.typography.labelMedium
            )
            state.receive.sentFiles.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = successContentColor(),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun successContentColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF9FE0A8) else Color(0xFF1E6B2A)
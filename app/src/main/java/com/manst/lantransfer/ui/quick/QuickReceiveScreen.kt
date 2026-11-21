package com.manst.lantransfer.ui.quick

import com.manst.lantransfer.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.quick.QuickJoinTarget
import com.manst.lantransfer.quick.QuickQrCodec
import com.manst.lantransfer.quick.QuickSessionCode
import com.manst.lantransfer.quick.QuickSessionInfo
import com.manst.lantransfer.quick.QuickTransferDefaults
import com.manst.lantransfer.quick.QuickTransferProgress
import com.manst.lantransfer.ui.common.ConfirmDialog
import com.manst.lantransfer.ui.common.FileOpener
import com.manst.lantransfer.ui.common.SnackbarMessageEffect
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * 接收端快捷传文件页：
 * 自动发现附近发送端（点击即连）或手动输入 IP + 接收码；
 * 连接后列出对方文件，支持全部接收/单个接收、进度、已保存清单、失败重试，并可反向发送文件给对方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReceiveScreen(viewModel: QuickReceiveViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var disconnectThenExit by remember { mutableStateOf(false) }
    var exitAfterLeave by remember { mutableStateOf(false) }
    // 广播不再携带接收码：点击附近设备后弹窗输入对方屏幕上的接收码
    var pendingJoinInfo by remember { mutableStateOf<QuickSessionInfo?>(null) }

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    // 非 Composable 回调（Snackbar / 扫码提示）使用的文案提前取好
    val scanPrompt = stringResource(R.string.quick_scan_prompt)
    val scanCancelledMessage = stringResource(R.string.quick_scan_cancelled)
    val scanInvalidMessage = stringResource(R.string.quick_scan_invalid)
    val scanNoPermissionMessage = stringResource(R.string.quick_scan_no_permission)
    val scanLaunchFailedMessage = stringResource(R.string.quick_scan_launch_failed)
    val maxFilesMessage = stringResource(
        R.string.quick_max_files_message,
        QuickTransferDefaults.MAX_FILES,
        QuickTransferDefaults.MAX_FILES
    )

    // 扫码加入：zxing-android-embedded 的扫描界面，识别发送端二维码里的会话信息
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar(scanCancelledMessage) }
        } else {
            val info = QuickQrCodec.decode(contents)
            if (info == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(scanInvalidMessage)
                }
            } else {
                viewModel.joinScanned(info)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(buildScanOptions(scanPrompt))
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(scanNoPermissionMessage)
            }
        }
    }

    val requestScan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                scanLauncher.launch(buildScanOptions(scanPrompt))
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar(scanLaunchFailedMessage) }
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 已保存文件「打开」：按展示名在 MediaStore 下载记录里找同名文件
    val openCompletedFile: (String) -> Unit = { name ->
        val opened = FileOpener.openDownloadedFile(context, name)
        if (!opened) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.quick_recv_open_failed, name))
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val limited = uris.take(QuickTransferDefaults.MAX_FILES)
            if (uris.size > QuickTransferDefaults.MAX_FILES) {
                scope.launch {
                    snackbarHostState.showSnackbar(maxFilesMessage)
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
                title = { Text(stringResource(R.string.quick_recv_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                    onOpenCompleted = openCompletedFile,
                    onSendFiles = { filePicker.launch(arrayOf("*/*")) },
                    onDisconnect = {
                        disconnectThenExit = false
                        showDisconnectDialog = true
                    }
                )
            } else {
                DiscoveryContent(
                    state = state,
                    onJoinDiscovered = { info -> pendingJoinInfo = info },
                    onRestartDiscovery = viewModel::restartDiscovery,
                    onHostChange = viewModel::onHostChange,
                    onCodeChange = viewModel::onCodeChange,
                    onProtocolChange = viewModel::onProtocolChange,
                    onJoinManual = viewModel::joinManual,
                    onRetryJoin = viewModel::retryJoin,
                    onScanQr = requestScan
                )
            }
        }
    }

    if (showDisconnectDialog) {
        ConfirmDialog(
            title = if (disconnectThenExit) {
                stringResource(R.string.quick_recv_disconnect_exit_title)
            } else {
                stringResource(R.string.quick_recv_disconnect_title)
            },
            message = if (disconnectThenExit) {
                stringResource(R.string.quick_recv_disconnect_exit_message, state.joinedDeviceName)
            } else {
                stringResource(R.string.quick_recv_disconnect_message)
            },
            confirmLabel = if (disconnectThenExit) {
                stringResource(R.string.quick_recv_disconnect_exit_confirm)
            } else {
                stringResource(R.string.action_disconnect)
            },
            onConfirm = {
                showDisconnectDialog = false
                viewModel.leave()
                if (disconnectThenExit) exitAfterLeave = true
            },
            onDismiss = { showDisconnectDialog = false }
        )
    }

    pendingJoinInfo?.let { info ->
        ReceiveCodeDialog(
            deviceName = info.deviceName.ifBlank { stringResource(R.string.quick_recv_nearby_title) },
            onDismiss = { pendingJoinInfo = null },
            onConfirm = { code ->
                viewModel.joinDiscovered(info, code)
                pendingJoinInfo = null
            }
        )
    }
}

/** 点击附近设备后输入对方屏幕上的接收码（广播已不再携带接收码）。 */
@Composable
private fun ReceiveCodeDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val normalized = QuickSessionCode.normalize(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_recv_code_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.quick_recv_code_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.quick_recv_code_dialog_device, deviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.filter { it.isDigit() }.take(QuickTransferDefaults.CODE_LENGTH)
                    },
                    label = { Text(stringResource(R.string.label_six_digit_code)) },
                    placeholder = { Text("123456") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_nearby_code_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let { onConfirm(it) } },
                enabled = normalized != null
            ) {
                Text(stringResource(R.string.action_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
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
    onRetryJoin: () -> Unit,
    onScanQr: () -> Unit
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
                onJoinManual = onJoinManual,
                onScanQr = onScanQr
            )
        }
        if (state.isJoining) {
            item(key = "connecting") { ConnectingCard(target = state.lastTarget) }
        }
        if (state.isFailed) {
            item(key = "failed") {
                QuickErrorCard(
                    message = state.receive.error ?: stringResource(R.string.quick_recv_join_failed),
                    retryLabel = stringResource(R.string.action_retry_join),
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
        title = stringResource(R.string.quick_recv_nearby_title),
        icon = Icons.Filled.WifiFind,
        trailing = {
            IconButton(onClick = onRestartDiscovery) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_restart_discovery))
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
                    stringResource(R.string.quick_recv_searching)
                } else {
                    stringResource(R.string.quick_recv_found_count, state.receive.discovered.size)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = stringResource(R.string.quick_recv_nearby_hint),
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
                title = stringResource(R.string.quick_recv_no_device_title),
                description = stringResource(R.string.quick_recv_no_device_desc)
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
                    text = info.deviceName.ifBlank { stringResource(R.string.quick_recv_nearby_title) },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = info.primaryHost,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        R.string.quick_recv_ports_line,
                        info.ftpPort,
                        info.ftpsPort,
                        info.sshPort
                    ),
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
    onJoinManual: () -> Unit,
    onScanQr: () -> Unit
) {
    val hostError = state.hostError
    val codeError = state.codeError
    QuickSectionCard(title = stringResource(R.string.quick_recv_manual_title), icon = Icons.Filled.Link) {
        OutlinedTextField(
            value = state.host,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.label_peer_ip)) },
            placeholder = { Text("192.168.1.10") },
            singleLine = true,
            isError = hostError != null,
            supportingText = if (hostError != null) {
                { Text(hostError) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("quick_manual_host")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.label_six_digit_code)) },
                placeholder = { Text("123456") },
                singleLine = true,
                isError = codeError != null,
                supportingText = if (codeError != null) {
                    { Text(codeError) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_manual_code")
            )
            QuickProtocolDropdown(
                selected = state.protocol,
                onSelected = onProtocolChange,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(R.string.quick_recv_protocol_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onScanQr,
            enabled = !state.isJoining,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("quick_scan_join")
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_scan_qr))
        }
        Button(
            onClick = onJoinManual,
            enabled = !state.isJoining,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("quick_manual_connect")
        ) {
            if (state.isJoining) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.quick_connecting))
            } else {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_connect))
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
            label = { Text(stringResource(R.string.label_protocol)) },
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
                Text(text = stringResource(R.string.quick_recv_connecting_title), style = MaterialTheme.typography.titleSmall)
                target?.let {
                    Text(
                        text = stringResource(
                            R.string.quick_recv_connecting_detail,
                            it.host,
                            formatSessionCode(it.code)
                        ),
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
    onOpenCompleted: (String) -> Unit,
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
            CompletedFilesCard(receive.completedFiles, onOpenCompleted)
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
    QuickSectionCard(title = stringResource(R.string.quick_recv_connected_title), icon = Icons.Filled.CheckCircle) {
        Text(
            text = stringResource(
                R.string.quick_recv_connected_detail,
                state.activeProtocolLabel.ifBlank { stringResource(R.string.quick_protocol_auto_short) },
                state.joinedDeviceName
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        state.receive.joined?.primaryHost?.takeIf { it.isNotBlank() }?.let { host ->
            Text(
                text = stringResource(R.string.quick_recv_peer_address, host),
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
                Text(stringResource(R.string.action_disconnect_full))
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
                Text(stringResource(R.string.action_receive_all))
            }
        }
    }
}

@Composable
private fun TransferProgressCard(progress: QuickTransferProgress) {
    val isReceive = progress.direction == QuickTransferProgress.Direction.RECEIVE
    QuickSectionCard(
        title = if (isReceive) {
            stringResource(R.string.quick_recv_receiving)
        } else {
            stringResource(R.string.quick_recv_sending)
        },
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
                text = if (progress.totalBytes > 0L) {
                    stringResource(
                        R.string.quick_recv_progress_with_total,
                        Format.bytes(progress.bytesTransferred),
                        Format.bytes(progress.totalBytes)
                    )
                } else {
                    stringResource(
                        R.string.quick_recv_progress_unknown_total,
                        Format.bytes(progress.bytesTransferred)
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (progress.fileCount > 0 && progress.fileIndex in 1..progress.fileCount) {
                Text(
                    text = stringResource(
                        R.string.quick_recv_file_progress,
                        progress.fileIndex,
                        progress.fileCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val percent = if (progress.totalBytes > 0L) {
                (progress.bytesTransferred * 100L / progress.totalBytes).toInt().coerceIn(0, 100)
            } else {
                null
            }
            Text(
                text = buildString {
                    if (percent != null) {
                        append(percent).append("% · ")
                    }
                    append(Format.speed(progress.bytesPerSecond))
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = Format.remainingTime(
                    if (progress.totalBytes > 0L) progress.totalBytes - progress.bytesTransferred else -1L,
                    progress.bytesPerSecond
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RemoteFilesCard(
    files: List<RemoteEntry>,
    transferring: Boolean,
    onDownload: (RemoteEntry) -> Unit
) {
    QuickSectionCard(title = stringResource(R.string.quick_recv_remote_files, files.size), icon = Icons.Filled.Folder) {
        if (files.isEmpty()) {
            QuickEmptyHint(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.quick_recv_remote_empty),
                description = stringResource(R.string.quick_recv_remote_empty_desc)
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
                        Text(stringResource(R.string.action_receive))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedFilesCard(names: List<String>, onOpen: (String) -> Unit) {
    QuickSectionCard(title = stringResource(R.string.quick_recv_saved_count, names.size), icon = Icons.Filled.CheckCircle) {
        if (names.isEmpty()) {
            Text(
                text = stringResource(R.string.quick_recv_saved_empty),
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
                            text = stringResource(R.string.quick_recv_saved_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onOpen(name) }) { Text(stringResource(R.string.action_open)) }
                }
            }
        }
    }
}

@Composable
private fun FailedFilesCard(names: List<String>, onRetry: (String) -> Unit) {
    QuickSectionCard(title = stringResource(R.string.quick_recv_failed_count, names.size), icon = Icons.Filled.ErrorOutline) {
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
                TextButton(onClick = { onRetry(name) }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun SendBackCard(state: QuickReceiveUiState, onSendFiles: () -> Unit) {
    QuickSectionCard(title = stringResource(R.string.quick_recv_send_back), icon = Icons.Filled.FileUpload) {
        Text(
            text = stringResource(R.string.quick_recv_send_back_hint),
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
            Text(stringResource(R.string.quick_recv_send_back))
        }
        if (state.receive.sentFiles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.quick_recv_sent_count, state.receive.sentFiles.size),
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

/** 扫码界面配置：只识别二维码、中文提示、关闭提示音。 */
private fun buildScanOptions(prompt: String): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(prompt)
    setBeepEnabled(false)
}

@Composable
private fun successContentColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF9FE0A8) else Color(0xFF1E6B2A)
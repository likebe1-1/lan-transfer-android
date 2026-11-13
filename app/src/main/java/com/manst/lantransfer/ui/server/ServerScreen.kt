package com.manst.lantransfer.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.data.ShareFile
import com.manst.lantransfer.ui.common.ConfirmDialog
import com.manst.lantransfer.ui.common.SnackbarMessageEffect
import com.manst.lantransfer.ui.components.ProtocolChip
import kotlinx.coroutines.launch

/**
 * 本机服务器页：一键在手机上启动 FTP / FTPS / SFTP / SCP 服务端，
 * 展示局域网访问地址，并管理共享文件（导入、导出、删除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(viewModel: ServerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var passwordVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ShareFile?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    val copyToClipboard: (String, String) -> Unit = { text, tip ->
        if (text.isNotBlank()) {
            clipboard.setText(AnnotatedString(text))
            scope.launch { snackbarHostState.showSnackbar(tip) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本机服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("server_screen_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "status") {
                StatusCard(
                    state = state,
                    onStart = viewModel::startServer,
                    onStop = viewModel::stopServer
                )
            }

            if (state.isRunning) {
                item(key = "access") {
                    AccessInfoCard(
                        state = state,
                        onCopy = { text -> copyToClipboard(text, "已复制连接信息") }
                    )
                }
            }

            item(key = "config") {
                ConfigCard(
                    state = state,
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    onProtocolEnabledChange = viewModel::onProtocolEnabledChange,
                    onFtpPortChange = viewModel::onFtpPortChange,
                    onFtpsPortChange = viewModel::onFtpsPortChange,
                    onSshPortChange = viewModel::onSshPortChange,
                    onUsernameChange = viewModel::onUsernameChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onFtpsModeChange = viewModel::onFtpsModeChange
                )
            }

            item(key = "share-header") {
                ShareHeaderCard(
                    state = state,
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onRefresh = viewModel::refreshSharedFiles,
                    onCopyPath = { path -> copyToClipboard(path, "已复制共享目录路径") }
                )
            }

            if (state.sharedFiles.isEmpty()) {
                item(key = "share-empty") {
                    ShareEmptyCard(onImport = { importLauncher.launch(arrayOf("*/*")) })
                }
            } else {
                items(items = state.sharedFiles, key = { it.name }) { file ->
                    ShareFileRow(
                        file = file,
                        onExport = { viewModel.exportSharedFile(file) },
                        onDelete = { pendingDelete = file }
                    )
                }
            }
        }
    }

    pendingDelete?.let { file ->
        ConfirmDialog(
            title = "删除共享文件",
            message = "确定删除「${file.name}」吗？删除后其他设备将无法再下载该文件。",
            onConfirm = {
                viewModel.deleteSharedFile(file)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ---------------------------------------------------------------------------- 状态卡

@Composable
private fun StatusCard(
    state: ServerUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val successContent = successContentColor()
    val successContainer = successContainerColor()
    val statusContent = when {
        state.isRunning -> successContent
        state.isStarting -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusContainer = when {
        state.isRunning -> successContainer
        state.isStarting -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = statusContainer, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                state.isRunning -> Icons.Filled.WifiTethering
                                state.isStarting -> Icons.Filled.PowerSettingsNew
                                else -> Icons.Filled.PowerSettingsNew
                            },
                            contentDescription = null,
                            tint = statusContent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.isRunning -> "服务器运行中"
                            state.isStarting -> "正在启动服务器…"
                            else -> "服务器已停止"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = when {
                            state.isRunning -> "其他设备可通过局域网连接并传输文件"
                            state.isStarting -> "正在监听端口，请稍候"
                            else -> "启动后其他设备即可连接本机收发文件"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                protocolStatusRows(state).forEach { row ->
                    ProtocolEndpointRow(row)
                }
            }

            state.lastError?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "部分协议启动失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            when {
                state.isStarting -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在启动…")
                    }
                }

                state.isRunning -> {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !state.isStopping,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在停止…")
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("停止服务器")
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onStart,
                        enabled = !state.isSwitching,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始服务器")
                    }
                }
            }

            if (state.isRunning && state.startedAt > 0L) {
                Text(
                    text = "启动时间：${Format.date(state.startedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProtocolEndpointRow(row: ProtocolStatusRow) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProtocolChip(protocol = row.protocol)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "端口 ${row.port}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val color = when {
                row.running -> successContentColor()
                row.isFailed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = color, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        row.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ---------------------------------------------------------------------------- 访问信息

@Composable
private fun AccessInfoCard(state: ServerUiState, onCopy: (String) -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("访问信息", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = "确保另一台设备与本机连接同一个 Wi-Fi / 局域网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.addresses.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "未检测到局域网地址，请连接 Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                state.addresses.forEachIndexed { index, address ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    AddressBlock(
                        state = state,
                        address = address,
                        onCopy = { onCopy(buildAccessText(state, address)) }
                    )
                }
            }

            Text(
                text = "其他设备上传的文件会保存到共享目录：${state.shareDirectoryPath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddressBlock(state: ServerUiState, address: String, onCopy: () -> Unit) {
    val form = state.form
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = address,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCopy) { Text("复制") }
        }
        AccessLine(label = "FTP", value = "ftp://$address:${portFor(state, TransferProtocol.FTP)}", enabled = form.enableFtp)
        AccessLine(
            label = "FTPS",
            value = "ftps://$address:${portFor(state, TransferProtocol.FTPS)}（${ftpsModeShortLabel(form.ftpsMode)}）",
            enabled = form.enableFtps
        )
        AccessLine(
            label = "SFTP",
            value = "sftp://$address:${portFor(state, TransferProtocol.SFTP)}",
            enabled = form.enableSftp
        )
        AccessLine(
            label = "SCP",
            value = "scp -P ${portFor(state, TransferProtocol.SCP)} ${form.username}@$address:${state.shareDirectoryPath}",
            enabled = form.enableScp
        )
        Text(
            text = "用户名：${form.username.ifBlank { "—" }} · 密码：${form.password.ifBlank { "—" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccessLine(label: String, value: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Text(
            text = if (enabled) value else "$value（未启用）",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------- 配置

@Composable
private fun ConfigCard(
    state: ServerUiState,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    onProtocolEnabledChange: (TransferProtocol, Boolean) -> Unit,
    onFtpPortChange: (String) -> Unit,
    onFtpsPortChange: (String) -> Unit,
    onSshPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFtpsModeChange: (FtpsMode) -> Unit
) {
    val form = state.form
    val editable = state.canEditConfig
    val usernameError = state.usernameError
    val passwordError = state.passwordError
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("服务器设置", style = MaterialTheme.typography.titleMedium)
            }

            if (!editable) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "服务器运行中，如需修改设置请先停止服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("协议", style = MaterialTheme.typography.labelLarge)
            SERVER_PROTOCOLS.forEach { protocol ->
                ProtocolSwitchRow(
                    protocol = protocol,
                    description = protocolDescription(protocol),
                    checked = isProtocolEnabled(form, protocol),
                    enabled = editable,
                    onCheckedChange = { checked -> onProtocolEnabledChange(protocol, checked) }
                )
            }
            state.protocolError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("端口", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "范围 1024-65535；SFTP 与 SCP 共用 SSH 端口",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PortField(
                    value = form.ftpPort,
                    label = "FTP",
                    error = state.ftpPortError,
                    enabled = editable && form.enableFtp,
                    onValueChange = onFtpPortChange,
                    modifier = Modifier.weight(1f)
                )
                PortField(
                    value = form.ftpsPort,
                    label = "FTPS",
                    error = state.ftpsPortError,
                    enabled = editable && form.enableFtps,
                    onValueChange = onFtpsPortChange,
                    modifier = Modifier.weight(1f)
                )
                PortField(
                    value = form.sshPort,
                    label = "SSH",
                    error = state.sshPortError,
                    enabled = editable && (form.enableSftp || form.enableScp),
                    onValueChange = onSshPortChange,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("帐号", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "其他设备连接本机服务器时需要输入该用户名与密码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = onUsernameChange,
                enabled = editable,
                label = { Text("用户名") },
                singleLine = true,
                isError = usernameError != null,
                supportingText = if (usernameError != null) {
                    { Text(usernameError) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = onPasswordChange,
                enabled = editable,
                label = { Text("密码") },
                singleLine = true,
                isError = passwordError != null,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisible) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                supportingText = if (passwordError != null) {
                    { Text(passwordError) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (form.enableFtps) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("FTPS 加密模式", style = MaterialTheme.typography.labelLarge)
                FtpsMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = editable) { onFtpsModeChange(mode) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = form.ftpsMode == mode,
                            onClick = { onFtpsModeChange(mode) },
                            enabled = editable
                        )
                        Column {
                            Text(text = mode.label, style = MaterialTheme.typography.bodyMedium)
                            if (mode == FtpsMode.IMPLICIT) {
                                Text(
                                    text = "隐式模式通常使用 990 端口",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (editable) {
                Text(
                    text = "设置会在修改后自动保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProtocolSwitchRow(
    protocol: TransferProtocol,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ProtocolChip(protocol = protocol)
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun PortField(
    value: String,
    label: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error, maxLines = 2) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------- 共享文件

@Composable
private fun ShareHeaderCard(
    state: ServerUiState,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onCopyPath: (String) -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.FolderShared,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "共享文件",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "共 ${state.sharedFiles.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "共享目录：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.shareDirectoryPath.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onCopyPath(state.shareDirectoryPath) },
                    enabled = state.shareDirectoryPath.isNotBlank(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制共享目录路径",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImport, enabled = !state.isImporting) {
                    if (state.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入中…")
                    } else {
                        Icon(
                            imageVector = Icons.Filled.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导入文件")
                    }
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.isLoadingFiles) {
                    if (state.isLoadingFiles) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("刷新中…")
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刷新")
                    }
                }
            }

            Text(
                text = "其他设备连接本机服务器后可下载这些文件；对方上传的文件也会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ShareEmptyCard(onImport: () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.FolderShared,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Text(text = "还没有共享文件", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "导入文件后其他设备即可下载；对方上传的文件也会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(onClick = onImport) { Text("导入文件") }
        }
    }
}

@Composable
private fun ShareFileRow(
    file: ShareFile,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${Format.bytes(file.size)} · ${Format.date(file.modifiedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExport) {
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = "导出到下载"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除共享文件"
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 68.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

// ---------------------------------------------------------------------------- 纯函数辅助

private data class ProtocolStatusRow(
    val protocol: TransferProtocol,
    val port: Int,
    val label: String,
    val running: Boolean,
    val error: String? = null
) {
    val isFailed: Boolean get() = error != null
}

/** 状态卡里展示的协议行：运行中用真实端点状态，停止时按开关展示配置端口。 */
private fun protocolStatusRows(state: ServerUiState): List<ProtocolStatusRow> {
    val form = state.form
    val enabledProtocols = buildList {
        if (form.enableFtp) add(TransferProtocol.FTP)
        if (form.enableFtps) add(TransferProtocol.FTPS)
        if (form.enableSftp) add(TransferProtocol.SFTP)
        if (form.enableScp) add(TransferProtocol.SCP)
    }
    return enabledProtocols.map { protocol ->
        val endpoint = state.endpointOf(protocol)
        if (endpoint != null) {
            ProtocolStatusRow(
                protocol = protocol,
                port = endpoint.port,
                label = when {
                    endpoint.running -> "运行中"
                    endpoint.error != null -> "启动失败"
                    else -> "未启动"
                },
                running = endpoint.running,
                error = endpoint.error
            )
        } else {
            ProtocolStatusRow(
                protocol = protocol,
                port = portFor(state, protocol),
                label = if (state.isStarting) "启动中…" else "未启动",
                running = false
            )
        }
    }
}

/** 优先使用运行中的真实端口；未运行时回退到配置里的端口。 */
private fun portFor(state: ServerUiState, protocol: TransferProtocol): Int {
    val configured = when (protocol) {
        TransferProtocol.FTP -> state.form.ftpPort
        TransferProtocol.FTPS -> state.form.ftpsPort
        TransferProtocol.SFTP, TransferProtocol.SCP -> state.form.sshPort
    }
    return state.endpointOf(protocol)?.port ?: configured.toIntOrNull() ?: 0
}

private fun isProtocolEnabled(form: ServerConfigForm, protocol: TransferProtocol): Boolean = when (protocol) {
    TransferProtocol.FTP -> form.enableFtp
    TransferProtocol.FTPS -> form.enableFtps
    TransferProtocol.SFTP -> form.enableSftp
    TransferProtocol.SCP -> form.enableScp
}

private fun protocolDescription(protocol: TransferProtocol): String = when (protocol) {
    TransferProtocol.FTP -> "标准 FTP，兼容性最好（明文传输）"
    TransferProtocol.FTPS -> "FTP over TLS，加密传输"
    TransferProtocol.SFTP -> "SSH 文件传输，加密且通用"
    TransferProtocol.SCP -> "SSH 复制协议，与 SFTP 共用端口"
}

private fun ftpsModeShortLabel(mode: FtpsMode): String = when (mode) {
    FtpsMode.EXPLICIT -> "显式 TLS"
    FtpsMode.IMPLICIT -> "隐式 TLS"
}

/** 复制给用户的连接信息（多行文本）。 */
private fun buildAccessText(state: ServerUiState, address: String): String {
    val form = state.form
    return buildString {
        appendLine("本机服务器访问信息")
        appendLine("FTP   ftp://$address:${portFor(state, TransferProtocol.FTP)}")
        appendLine("FTPS  ftps://$address:${portFor(state, TransferProtocol.FTPS)}（${ftpsModeShortLabel(form.ftpsMode)}）")
        appendLine("SFTP  sftp://$address:${portFor(state, TransferProtocol.SFTP)}")
        appendLine("SCP   scp -P ${portFor(state, TransferProtocol.SCP)} ${form.username}@$address:${state.shareDirectoryPath}")
        appendLine("用户名：${form.username}")
        append("密码：${form.password}")
    }
}

@Composable
private fun successContentColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF9FE0A8) else Color(0xFF1E6B2A)

@Composable
private fun successContainerColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF16351D) else Color(0xFFDDF3DF)

/** 本机服务器支持的四种协议（展示顺序固定）。 */
private val SERVER_PROTOCOLS: List<TransferProtocol> = listOf(
    TransferProtocol.FTP,
    TransferProtocol.FTPS,
    TransferProtocol.SFTP,
    TransferProtocol.SCP
)
package com.manst.lantransfer.ui.quick

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.quick.QuickFile
import com.manst.lantransfer.quick.QuickQrCodec
import com.manst.lantransfer.quick.QuickSendPhase
import com.manst.lantransfer.quick.QuickSessionInfo
import com.manst.lantransfer.quick.QuickTransferDefaults
import com.manst.lantransfer.ui.common.ConfirmDialog
import com.manst.lantransfer.ui.common.QrCodeRenderer
import com.manst.lantransfer.ui.common.LoadingState
import com.manst.lantransfer.ui.common.SnackbarMessageEffect
import com.manst.lantransfer.ui.components.ProtocolChip
import kotlinx.coroutines.launch

/**
 * 发送端快捷传文件页：
 * 未开始时选择文件并自动开会话；会话中展示大号接收码、本机地址与协议，
 * 并列出待发送文件与对方发来的文件（可导出到下载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSendScreen(viewModel: QuickSendViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showStopConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var exitAfterStop by remember { mutableStateOf(false) }
    var showFullscreenCode by remember { mutableStateOf(false) }
    var showQrCode by remember { mutableStateOf(false) }

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
            viewModel.startSession(limited)
        }
    }

    // 页面可见时每 2 秒刷新一次收到的文件
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.pollWhileActive()
        }
    }

    // 「结束会话并返回」：等会话真正停止后再退出页面，避免 ViewModel 被提前销毁导致停止失败
    LaunchedEffect(state.session.phase, exitAfterStop, state.isStopping) {
        if (exitAfterStop && state.session.phase == QuickSendPhase.IDLE && !state.isStopping) {
            exitAfterStop = false
            onBack()
        }
    }

    val requestBack: () -> Unit = {
        when {
            state.isActive || state.session.phase == QuickSendPhase.PREPARING -> showExitConfirm = true
            else -> onBack()
        }
    }
    BackHandler(onBack = requestBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快速传文件") },
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
            when {
                state.isStoppingPhase -> LoadingState(message = "正在结束会话…")
                state.isActive -> SendSessionContent(
                    state = state,
                    onCopyCode = {
                        clipboard.setText(AnnotatedString(state.codeDisplay))
                        scope.launch { snackbarHostState.showSnackbar("接收码已复制") }
                    },
                    onShowFullscreenCode = { showFullscreenCode = true },
                    onShowQrCode = { showQrCode = true },
                    onExportReceived = viewModel::exportReceived,
                    onStopSession = { showStopConfirm = true }
                )

                state.isPreparing -> LoadingState(message = "正在准备文件…")
                else -> SendIdleContent(
                    state = state,
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onRetry = viewModel::retryStart
                )
            }
        }
    }

    if (showFullscreenCode) {
        FullscreenCodeDialog(
            code = state.codeDisplay,
            deviceName = state.session.deviceName,
            address = state.session.addresses.firstOrNull(),
            onDismiss = { showFullscreenCode = false }
        )
    }

    if (showQrCode) {
        QrCodeDialog(
            sessionInfo = state.session.sessionInfo,
            code = state.codeDisplay,
            onDismiss = { showQrCode = false }
        )
    }

    if (showStopConfirm) {
        ConfirmDialog(
            title = "结束会话？",
            message = "结束后对方将立即断开连接，已发送或已接收的文件不受影响。",
            confirmLabel = "结束会话",
            onConfirm = {
                showStopConfirm = false
                viewModel.stopSession()
            },
            onDismiss = { showStopConfirm = false }
        )
    }

    if (showExitConfirm) {
        ConfirmDialog(
            title = "结束会话并返回？",
            message = "返回会结束当前会话，对方将立即断开连接。",
            confirmLabel = "结束并返回",
            onConfirm = {
                showExitConfirm = false
                viewModel.stopSession()
                exitAfterStop = true
            },
            onDismiss = { showExitConfirm = false }
        )
    }
}

// ---------------------------------------------------------------------------- 未开始

@Composable
private fun SendIdleContent(
    state: QuickSendUiState,
    onPickFiles: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("quick_send_idle_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "pick") {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Text(
                        text = "把文件发给附近的设备",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "无需配置服务器：选择文件后会自动开启 FTP / FTPS / SFTP / SCP 服务，对方输入 6 位接收码即可连接。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onPickFiles,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("选择要发送的文件")
                    }
                    Text(
                        text = "一次最多选择 ${QuickTransferDefaults.MAX_FILES} 个文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        state.session.error?.let { error ->
            item(key = "error") {
                QuickErrorCard(
                    message = error,
                    retryLabel = "重试",
                    onRetry = if (state.lastSelectedCount > 0) onRetry else null
                )
            }
        }

        if (state.lastSelectedCount > 0 && state.session.error == null) {
            item(key = "retry") {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("使用上次选择的 ${state.lastSelectedCount} 个文件重试")
                }
            }
        }

        item(key = "steps") {
            QuickSectionCard(title = "使用步骤", icon = Icons.Filled.Router) {
                StepRow("1", "在本机选择要发送的文件")
                StepRow("2", "让对方打开「快速传文件 → 接收文件」，输入本页显示的 6 位接收码")
                StepRow("3", "对方接收后文件会保存到其系统下载目录；对方也可以把文件发回给你")
            }
        }
    }
}

@Composable
private fun StepRow(index: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = index,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------- 会话中

@Composable
private fun SendSessionContent(
    state: QuickSendUiState,
    onCopyCode: () -> Unit,
    onShowFullscreenCode: () -> Unit,
    onShowQrCode: () -> Unit,
    onExportReceived: (String) -> Unit,
    onStopSession: () -> Unit
) {
    val session = state.session
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "code") {
            CodeCard(
                code = state.codeDisplay,
                deviceName = session.deviceName,
                qrCodeEnabled = session.sessionInfo != null,
                onCopyCode = onCopyCode,
                onShowFullscreen = onShowFullscreenCode,
                onShowQrCode = onShowQrCode
            )
        }
        item(key = "device") {
            DeviceInfoCard(state)
        }
        item(key = "sending") {
            SendingFilesCard(session.sendingFiles)
        }
        item(key = "received") {
            ReceivedFilesCard(session.receivedFiles, onExportReceived)
        }
        session.error?.let { error ->
            item(key = "error") { QuickErrorCard(message = error) }
        }
        item(key = "stop") {
            OutlinedButton(
                onClick = onStopSession,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("结束会话")
            }
        }
        item(key = "bottom") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun CodeCard(
    code: String,
    deviceName: String,
    qrCodeEnabled: Boolean,
    onCopyCode: () -> Unit,
    onShowFullscreen: () -> Unit,
    onShowQrCode: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "接收码",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = code,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCopyCode,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("复制")
                }
                OutlinedButton(
                    onClick = onShowFullscreen,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("全屏显示")
                }
            }
            OutlinedButton(
                onClick = onShowQrCode,
                enabled = qrCodeEnabled,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("二维码")
            }
            Text(
                text = "在另一台设备的「接收文件」中输入该接收码，或从附近设备列表中选择「${deviceName.ifBlank { "本机" }}」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DeviceInfoCard(state: QuickSendUiState) {
    val session = state.session
    QuickSectionCard(title = "本机连接信息", icon = Icons.Filled.Router) {
        Text(
            text = "设备名：${session.deviceName.ifBlank { "本机" }}",
            style = MaterialTheme.typography.bodyMedium
        )
        if (session.addresses.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
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
                        text = "未检测到局域网地址，请连接 Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else {
            session.addresses.forEach { address ->
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (session.endpoints.isEmpty()) {
            Text(
                text = "端口信息准备中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            session.endpoints.forEach { endpoint ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProtocolChip(protocol = endpoint.protocol)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = session.addresses.firstOrNull()
                            ?.let { address -> "$address:${endpoint.port}" }
                            ?: "端口 ${endpoint.port}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (endpoint.running) "运行中" else (endpoint.error ?: "未启动"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (endpoint.running) {
                            successColor()
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionProtocolChips(protocols = session.protocols)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = protocolSummaryText(session.protocols.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SendingFilesCard(files: List<QuickFile>) {
    QuickSectionCard(
        title = "待发送文件（${files.size}）",
        icon = Icons.Filled.FileUpload
    ) {
        if (files.isEmpty()) {
            QuickEmptyHint(
                icon = Icons.Filled.FileUpload,
                title = "没有待发送文件",
                description = "会话目录为空，可结束会话后重新选择"
            )
        } else {
            files.forEach { file ->
                QuickFileRow(
                    name = file.name,
                    supporting = Format.bytes(file.size)
                )
            }
        }
    }
}

@Composable
private fun ReceivedFilesCard(files: List<QuickFile>, onExport: (String) -> Unit) {
    QuickSectionCard(
        title = "收到的文件（${files.size}）",
        icon = Icons.Filled.FileDownload
    ) {
        if (files.isEmpty()) {
            QuickEmptyHint(
                icon = Icons.Filled.FileDownload,
                title = "还没有收到文件",
                description = "对方也可以把文件发给你，收到后会自动出现在这里"
            )
        } else {
            files.forEach { file ->
                QuickFileRow(
                    name = file.name,
                    supporting = Format.bytes(file.size)
                ) {
                    IconButton(onClick = { onExport(file.name) }) {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = "导出「${file.name}」到下载"
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 全屏接收码

@Composable
private fun FullscreenCodeDialog(
    code: String,
    deviceName: String,
    address: String?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "接收码",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = code,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 76.sp,
                        letterSpacing = 8.sp
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "在另一台设备的「接收文件」中输入此接收码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "设备：${deviceName.ifBlank { "本机" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "IP：${address ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(40.dp))
                Button(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/** 二维码 Dialog：对方用接收页「扫码加入」扫一扫即可自动连接。 */
@Composable
private fun QrCodeDialog(
    sessionInfo: QuickSessionInfo?,
    code: String,
    onDismiss: () -> Unit
) {
    val payload = remember(sessionInfo) { sessionInfo?.let { QuickQrCodec.encode(it) } }
    val qrBitmap = remember(payload) { payload?.let { QrCodeRenderer.render(it) } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "扫码连接",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Black
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "快速传文件二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(260.dp)
                    )
                } else {
                    Text(
                        text = "二维码生成失败，请让对方手动输入接收码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Black
                    )
                }
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    ),
                    color = Color.Black
                )
                Text(
                    text = "让对方扫一扫，自动连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
                TextButton(onClick = onDismiss) {
                    Text(text = "关闭", color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun successColor(): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF9FE0A8) else Color(0xFF1E6B2A)
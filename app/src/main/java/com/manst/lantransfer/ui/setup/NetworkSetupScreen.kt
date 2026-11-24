package com.manst.lantransfer.ui.setup

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.manst.lantransfer.R
import com.manst.lantransfer.ui.security.SecureScreen
import com.manst.lantransfer.ui.common.QrCodeRenderer
import kotlinx.coroutines.launch

/**
 * 一键组网：A 手机创建热点并生成 Wi-Fi 二维码；B 手机扫码后由 App 自动加入热点，
 * 之后回到快速传文件传输（AP 隔离/无路由器场景）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSetupScreen(
    viewModel: NetworkSetupViewModel,
    onBack: () -> Unit,
    onOpenQuickReceive: () -> Unit
) {
    // 本页展示热点名/密码与热点二维码（内含 Wi-Fi 密码），禁止截图/录屏
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scanPrompt = stringResource(R.string.setup_scan_qr)
    val cameraDenied = stringResource(R.string.setup_camera_denied)
    val permissionDenied = stringResource(R.string.setup_permission_denied)
    val copiedMessage = stringResource(R.string.setup_copied)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { payload -> viewModel.joinScanned(payload) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runCatching { scanLauncher.launch(buildScanOptions(scanPrompt)) }
        } else {
            scope.launch { snackbarHostState.showSnackbar(cameraDenied) }
        }
    }
    val requestCameraOrScan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { scanLauncher.launch(buildScanOptions(scanPrompt)) }
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestCameraOrScan() else scope.launch { snackbarHostState.showSnackbar(permissionDenied) }
    }
    val startScan: () -> Unit = {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            requestCameraOrScan()
        } else {
            wifiPermissionLauncher.launch(permission)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearInfoMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.setup_step_title), style = MaterialTheme.typography.titleSmall)
                    StepRow("1", stringResource(R.string.setup_step_1))
                    StepRow("2", stringResource(R.string.setup_step_2))
                    StepRow("3", stringResource(R.string.setup_step_3))
                }
            }

            // ------------------------------ A：创建热点 + 二维码
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.setup_sender_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.setup_hotspot_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openHotspotSettings(context) }) {
                            Icon(Icons.Filled.WifiTethering, contentDescription = null)
                            Text(stringResource(R.string.setup_open_hotspot), modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            onClick = { viewModel.readHotspot() },
                            enabled = !state.readingHotspot
                        ) {
                            if (state.readingHotspot) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.setup_read_hotspot))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.ssid,
                        onValueChange = viewModel::onSsidChange,
                        label = { Text(stringResource(R.string.setup_ssid)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text(stringResource(R.string.setup_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.generateQr() }) {
                        Icon(Icons.Filled.WifiTethering, contentDescription = null)
                        Text(stringResource(R.string.setup_generate_qr), modifier = Modifier.padding(start = 8.dp))
                    }
                    val payload = state.qrPayload
                    if (payload != null) {
                        val qr = remember(payload) { QrCodeRenderer.render(payload) }
                        if (qr != null) {
                            Text(stringResource(R.string.setup_qr_title), style = MaterialTheme.typography.titleSmall)
                            Image(
                                bitmap = qr,
                                contentDescription = stringResource(R.string.setup_qr_title),
                                modifier = Modifier
                                    .size(220.dp)
                                    .align(Alignment.CenterHorizontally)
                            )
                            Text(
                                stringResource(R.string.setup_qr_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = {
                                runCatching {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                        ?.setPrimaryClip(ClipData.newPlainText("LanLink Wi-Fi", payload))
                                }
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Text(stringResource(R.string.setup_copy_qr), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.setup_sender_next),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ------------------------------ B：扫码加入
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.setup_receiver_title), style = MaterialTheme.typography.titleMedium)
                    if (state.connected) {
                        Text(
                            stringResource(R.string.setup_joined, state.joinedSsid ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.setup_connected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenQuickReceive) {
                                Text(stringResource(R.string.setup_open_quick_receive))
                            }
                            OutlinedButton(onClick = { viewModel.disconnect() }) {
                                Text(stringResource(R.string.setup_disconnect))
                            }
                        }
                    } else {
                        if (state.joining) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.setup_joining))
                            }
                        } else {
                            Button(onClick = startScan) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                                Text(stringResource(R.string.setup_scan_qr), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepRow(index: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** 打开系统热点设置页（不同厂商 action 不同，逐级回退）。 */
private fun openHotspotSettings(context: Context) {
    val candidates = listOf(
        Intent("android.settings.WIFI_TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIFI_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            // 尝试下一个入口
        }
    }
}

private fun buildScanOptions(prompt: String): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(prompt)
    setBeepEnabled(false)
}
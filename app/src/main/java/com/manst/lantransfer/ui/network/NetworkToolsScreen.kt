package com.manst.lantransfer.ui.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.R
import com.manst.lantransfer.net.DeviceKind
import com.manst.lantransfer.net.LanCheck
import com.manst.lantransfer.net.LanCheckType
import com.manst.lantransfer.net.LanHost
import com.manst.lantransfer.net.LanNetworkInfo
import kotlinx.coroutines.launch

/** 网络工具页：查看本机网络、扫描局域网设备、进行网络诊断与端口连通性测试。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsScreen(
    viewModel: NetworkToolsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_tools_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshNetwork() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.network_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { CurrentNetworkCard(state.info, state.loading) }
            item {
                ScanCard(
                    state = state,
                    onScan = { viewModel.scan() }
                )
            }
            if (state.checks.isNotEmpty()) {
                item { DiagnosticsCard(checks = state.checks, hosts = state.hosts) }
            }
            if (state.scanDone && state.hosts.isEmpty()) {
                item { NoDevicesCard() }
            }
            if (state.hosts.isNotEmpty()) {
                item { Text(stringResource(R.string.network_hosts_title, state.hosts.size), style = MaterialTheme.typography.titleSmall) }
                items(items = state.hosts, key = { it.ip }) { host ->
                    HostCard(host = host, onClick = {
                        copyIp(context, host.ip)
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.network_copied, host.ip))
                        }
                    })
                }
            }
            item {
                TargetTestCard(
                    ip = state.targetIp,
                    port = state.targetPort,
                    testing = state.testing,
                    result = state.targetResult,
                    onIpChange = viewModel::onTargetIpChange,
                    onPortChange = viewModel::onTargetPortChange,
                    onTest = { viewModel.testTarget() }
                )
            }
        }
    }
}

private fun copyIp(context: Context, ip: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("IP", ip))
    }
}

@Composable
private fun CurrentNetworkCard(info: LanNetworkInfo?, loading: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.network_current_title), style = MaterialTheme.typography.titleMedium)
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Text(stringResource(R.string.network_scanning))
                }

                info == null -> Text(
                    stringResource(R.string.network_error_no_network),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )

                else -> {
                    InfoRow(stringResource(R.string.network_local_ip), info.localIp)
                    InfoRow(stringResource(R.string.network_prefix), "/" + info.prefixLength)
                    InfoRow(stringResource(R.string.network_gateway), info.gateway ?: "-")
                    InfoRow(stringResource(R.string.network_interface), info.interfaceName)
                    InfoRow(
                        label = stringResource(R.string.network_current_title),
                        value = if (info.wifi) stringResource(R.string.network_type_wifi) else stringResource(R.string.network_type_ethernet)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScanCard(state: NetworkToolsUiState, onScan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.network_scan), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.network_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.scanning) {
                if (state.total > 0) {
                    val progress = (state.scanned.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.network_scan_progress, state.scanned, state.total),
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onScan, enabled = !state.scanning) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text(
                    text = if (state.scanning) stringResource(R.string.network_scanning) else stringResource(R.string.network_scan),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(checks: List<LanCheck>, hosts: List<LanHost>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.network_checks_title), style = MaterialTheme.typography.titleMedium)
            checks.forEach { check ->
                val row = checkContent(check, hosts)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = when (row.level) {
                            CheckLevel.OK -> Icons.Filled.CheckCircle
                            CheckLevel.WARN -> Icons.Filled.WarningAmber
                            CheckLevel.BAD -> Icons.Filled.ErrorOutline
                        },
                        contentDescription = null,
                        tint = when (row.level) {
                            CheckLevel.OK -> MaterialTheme.colorScheme.primary
                            CheckLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            CheckLevel.BAD -> MaterialTheme.colorScheme.error
                        }
                    )
                    Text(row.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private enum class CheckLevel { OK, WARN, BAD }

private data class CheckRow(val text: String, val level: CheckLevel)

@Composable
private fun checkContent(check: LanCheck, hosts: List<LanHost>): CheckRow {
    val otherCount = hosts.count { !it.isGateway }
    val lanlinkCount = hosts.count { it.isLanLink }
    return when (check.type) {
        LanCheckType.NETWORK_READY -> CheckRow(
            text = stringResource(
                if (check.ok) R.string.network_check_network_ready else R.string.network_check_network_not_ready
            ),
            level = if (check.ok) CheckLevel.OK else CheckLevel.BAD
        )

        LanCheckType.GATEWAY_REACHABLE -> CheckRow(
            text = stringResource(
                if (check.ok) R.string.network_check_gateway_ok else R.string.network_check_gateway_unknown
            ),
            level = if (check.ok) CheckLevel.OK else CheckLevel.WARN
        )

        LanCheckType.OTHER_DEVICES -> CheckRow(
            text = stringResource(
                if (check.ok) R.string.network_check_devices_found else R.string.network_check_devices_none,
                otherCount
            ),
            level = if (check.ok) CheckLevel.OK else CheckLevel.WARN
        )

        LanCheckType.LANLINK_DEVICES -> CheckRow(
            text = stringResource(R.string.network_check_lanlink_found, lanlinkCount),
            level = CheckLevel.OK
        )

        LanCheckType.AP_ISOLATION_SUSPECTED -> CheckRow(
            text = stringResource(
                if (check.ok) R.string.network_check_ap_isolation_ok else R.string.network_check_ap_isolation_warn
            ),
            level = if (check.ok) CheckLevel.OK else CheckLevel.WARN
        )
    }
}

@Composable
private fun NoDevicesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.network_no_hosts), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.network_no_hosts_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HostCard(host: LanHost, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = host.lanLinkName ?: host.hostname ?: host.ip,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (host.isGateway) BadgeText(stringResource(R.string.network_gateway_badge))
                if (host.isLanLink) BadgeText(stringResource(R.string.network_lanlink_badge), highlighted = true)
            }
            Text(
                text = buildString {
                    append(host.ip)
                    append(" · ")
                    append(kindLabel(host.kind))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (host.openPorts.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.network_ports) + "：" + host.portsText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val hostnameLabel = stringResource(R.string.network_hostname)
            val macLabel = stringResource(R.string.network_mac)
            val extra = buildString {
                host.hostname?.let { append(hostnameLabel + "：" + it + "  ") }
                host.mac?.let { append(macLabel + "：" + it) }
            }.trim()
            if (extra.isNotEmpty()) {
                Text(extra, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.network_copy_ip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun kindLabel(kind: DeviceKind): String = stringResource(
    when (kind) {
        DeviceKind.LANLINK -> R.string.network_kind_lanlink
        DeviceKind.ROUTER -> R.string.network_kind_router
        DeviceKind.NAS -> R.string.network_kind_nas
        DeviceKind.PRINTER -> R.string.network_kind_printer
        DeviceKind.COMPUTER -> R.string.network_kind_computer
        DeviceKind.PHONE -> R.string.network_kind_phone
        DeviceKind.UNKNOWN -> R.string.network_kind_unknown
    }
)

@Composable
private fun BadgeText(text: String, highlighted: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun TargetTestCard(
    ip: String,
    port: String,
    testing: Boolean,
    result: TargetTestResult?,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.network_target_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.network_target_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = onIpChange,
                    label = { Text(stringResource(R.string.network_target_ip)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text(stringResource(R.string.network_target_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Button(onClick = onTest, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                }
                Text(if (testing) stringResource(R.string.network_testing) else stringResource(R.string.network_test))
            }
            result?.let {
                Text(
                    text = when (it) {
                        TargetTestResult.OK -> stringResource(
                            R.string.network_test_ok,
                            ip,
                            port.toIntOrNull() ?: 0
                        )
                        TargetTestResult.FAILED -> stringResource(R.string.network_test_failed)
                        TargetTestResult.INVALID -> stringResource(R.string.network_test_invalid)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it == TargetTestResult.OK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
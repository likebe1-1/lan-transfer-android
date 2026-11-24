package com.manst.lantransfer.ui.editor

import com.manst.lantransfer.R
import com.manst.lantransfer.ui.security.SecureScreen
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.FtpsMode
import com.manst.lantransfer.core.TransferProtocol
import com.manst.lantransfer.ui.common.SectionHeader
import com.manst.lantransfer.ui.common.SnackbarMessageEffect

/**
 * 服务器编辑页：新增/编辑同一套表单，协议、端口联动，FTPS 与被动模式选项，
 * 「测试连接」使用协议层真实连接并列出根目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(viewModel: ServerEditorViewModel, onBack: () -> Unit) {
    // 本页包含服务器密码输入，禁止截图/录屏
    SecureScreen()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordVisible by remember { mutableStateOf(false) }

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ServerEditorEvent.Saved -> onBack()
            }
        }
    }

    val nameError = state.nameError
    val hostError = state.hostError
    val portError = state.portError

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isNew) {
                            stringResource(R.string.editor_title_new)
                        } else {
                            stringResource(R.string.editor_title_edit)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = !state.isTesting && !state.isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        if (state.isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.editor_testing))
                        } else {
                            Text(stringResource(R.string.editor_test_connection))
                        }
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = !state.isSaving && !state.isTesting,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.editor_saving))
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(stringResource(R.string.section_connection))

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.label_server_name)) },
                placeholder = { Text(stringResource(R.string.hint_server_name)) },
                singleLine = true,
                isError = nameError != null,
                supportingText = if (nameError != null) {
                    { Text(nameError) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth()
            )

            ProtocolDropdown(
                protocol = state.protocol,
                onProtocolChange = viewModel::onProtocolChange
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text(stringResource(R.string.label_host)) },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    isError = hostError != null,
                    supportingText = if (hostError != null) {
                        { Text(hostError) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text(stringResource(R.string.label_port)) },
                    singleLine = true,
                    isError = portError != null,
                    supportingText = if (portError != null) {
                        { Text(portError) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(112.dp)
                )
            }

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(R.string.label_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.label_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) {
                                stringResource(R.string.cd_hide_password)
                            } else {
                                stringResource(R.string.cd_show_password)
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.initialPath,
                onValueChange = viewModel::onInitialPathChange,
                label = { Text(stringResource(R.string.label_initial_path)) },
                placeholder = { Text("/") },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.hint_initial_path)) },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.showPassiveOption) {
                SectionHeader(stringResource(R.string.section_connection_options))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.label_passive_mode),
                            description = stringResource(R.string.hint_passive_mode),
                            checked = state.passiveMode,
                            onCheckedChange = viewModel::onPassiveModeChange
                        )
                    }
                }
            }

            if (state.showFtpsOptions) {
                SectionHeader(stringResource(R.string.section_ftps_options))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.editor_ftps_mode),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        FtpsMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onFtpsModeChange(mode) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.ftpsMode == mode,
                                    onClick = { viewModel.onFtpsModeChange(mode) }
                                )
                                Column {
                                    Text(text = mode.label, style = MaterialTheme.typography.bodyMedium)
                                    if (mode == FtpsMode.IMPLICIT) {
                                        Text(
                                            text = stringResource(R.string.hint_ftps_implicit),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        SwitchRow(
                            title = stringResource(R.string.label_trust_cert),
                            description = stringResource(R.string.hint_trust_cert),
                            checked = state.allowInsecureTls,
                            onCheckedChange = viewModel::onAllowInsecureTlsChange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolDropdown(
    protocol: TransferProtocol,
    onProtocolChange: (TransferProtocol) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = protocol.label,
            onValueChange = {},
            readOnly = true,
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
            TransferProtocol.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(R.string.editor_default_port, item.defaultPort),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onProtocolChange(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
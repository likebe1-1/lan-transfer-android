package com.manst.lantransfer.ui.browser

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manst.lantransfer.core.Format
import com.manst.lantransfer.core.RemoteEntry
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.ui.common.ConfirmDialog
import com.manst.lantransfer.ui.common.EmptyState
import com.manst.lantransfer.ui.common.ErrorState
import com.manst.lantransfer.ui.common.LoadingState
import com.manst.lantransfer.ui.common.SnackbarMessageEffect

/** 远程浏览页：面包屑/上级目录、目录优先排序、下载、SAF 上传、新建/重命名/删除、刷新。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onExit: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteEntry?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris)
    }

    val uploadFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) viewModel.uploadFolder(treeUri)
    }

    SnackbarMessageEffect(messages = viewModel.messages, snackbarHostState = snackbarHostState)

    // 系统返回键：先回上级目录，根目录时退出页面。
    BackHandler(enabled = state.profile != null) {
        if (!viewModel.navigateUp()) onExit()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.profile?.name ?: "远程浏览",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.profile?.let { profile ->
                            Text(
                                text = "${profile.protocol.label} · ${profile.endpoint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.navigateUp()) onExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::downloadCurrentFolder,
                        enabled = state.profile != null && !state.isBusy
                    ) {
                        Icon(Icons.Filled.FolderZip, contentDescription = "下载当前文件夹")
                    }
                    UploadMenu(
                        enabled = state.profile != null && !state.isBusy,
                        onPickFiles = { uploadLauncher.launch(arrayOf("*/*")) },
                        onPickFolder = { uploadFolderLauncher.launch(null) }
                    )
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = state.profile != null && !state.isBusy
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    BrowserOverflowMenu(
                        enabled = state.profile != null,
                        onCreateFolder = { showNewFolderDialog = true },
                        onOpenTransfers = onOpenTransfers
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            BrowserBreadcrumbBar(
                path = state.currentPath,
                enabled = !state.isBusy,
                onNavigate = viewModel::navigateTo
            )
            HorizontalDivider()
            if (state.isLoading || state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val errorMessage = state.errorMessage
            when {
                state.profileMissing -> EmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = "服务器配置不存在",
                    description = "这台服务器可能已被删除，请返回列表重新选择",
                    actionLabel = "返回",
                    onAction = onExit
                )

                state.profile == null -> LoadingState()

                state.isLoading && state.entries.isEmpty() -> LoadingState(message = "正在读取目录…")

                errorMessage != null -> ErrorState(
                    message = errorMessage,
                    onRetry = viewModel::refresh
                )

                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = "此目录为空",
                    description = "点击右上角上传文件或文件夹，或新建一个文件夹",
                    actionLabel = "上传文件",
                    onAction = { uploadLauncher.launch(arrayOf("*/*")) }
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(items = state.entries, key = { it.path }) { entry ->
                        BrowserEntryRow(
                            entry = entry,
                            onOpenDirectory = viewModel::navigateTo,
                            onDownload = viewModel::download,
                            onRename = { renameTarget = it },
                            onDelete = { deleteTarget = it }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                viewModel.createDirectory(name)
                showNewFolderDialog = false
            }
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(target, newName)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = if (target.isDirectory) "删除文件夹" else "删除文件",
            message = if (target.isDirectory) {
                "确定删除文件夹「${target.name}」吗？仅支持删除空文件夹。"
            } else {
                "确定删除文件「${target.name}」吗？此操作无法撤销。"
            },
            onConfirm = {
                viewModel.delete(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** 顶栏上传下拉菜单：上传文件 / 上传文件夹。 */
@Composable
private fun UploadMenu(
    enabled: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.UploadFile, contentDescription = "上传")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("上传文件") },
                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                onClick = {
                    expanded = false
                    onPickFiles()
                }
            )
            DropdownMenuItem(
                text = { Text("上传文件夹") },
                leadingIcon = { Icon(Icons.Filled.DriveFolderUpload, contentDescription = null) },
                onClick = {
                    expanded = false
                    onPickFolder()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserOverflowMenu(
    enabled: Boolean,
    onCreateFolder: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("新建文件夹") },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCreateFolder()
                }
            )
            DropdownMenuItem(
                text = { Text("传输队列") },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenTransfers()
                }
            )
        }
    }
}

@Composable
private fun BrowserBreadcrumbBar(
    path: String,
    enabled: Boolean,
    onNavigate: (String) -> Unit
) {
    val breadcrumbs = remember(path) { buildBreadcrumbs(path) }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(breadcrumbs) { index, crumb ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            val isCurrent = index == breadcrumbs.lastIndex
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled && !isCurrent) { onNavigate(crumb.path) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserEntryRow(
    entry: RemoteEntry,
    onOpenDirectory: (String) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onRename: (RemoteEntry) -> Unit,
    onDelete: (RemoteEntry) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isDirectory = entry.isDirectory

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isDirectory) onOpenDirectory(entry.path) else onDownload(entry)
                    },
                    onLongClick = { menuExpanded = true }
                )
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isDirectory) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = if (isDirectory) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isDirectory) "文件夹" else Format.bytes(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = Format.date(entry.modifiedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "「${entry.name}」操作"
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (!isDirectory) {
                        DropdownMenuItem(
                            text = { Text("下载") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDownload(entry)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename(entry)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete(entry)
                        }
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 72.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RenameDialog(
    target: RemoteEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember(target) { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                Text(
                    text = "当前名称：${target.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName) },
                enabled = newName.isNotBlank() && newName.trim() != target.name
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private data class Breadcrumb(val label: String, val path: String)

private fun buildBreadcrumbs(path: String): List<Breadcrumb> {
    val normalized = RemotePath.normalize(path)
    val result = mutableListOf(Breadcrumb(label = "根目录", path = RemotePath.ROOT))
    if (normalized == RemotePath.ROOT) return result
    var current = ""
    normalized.trim('/').split('/').forEach { segment ->
        if (segment.isEmpty()) return@forEach
        current = "$current/$segment"
        result.add(Breadcrumb(label = segment, path = current))
    }
    return result
}
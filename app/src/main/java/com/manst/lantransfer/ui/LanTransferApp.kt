package com.manst.lantransfer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.manst.lantransfer.AppContainer
import com.manst.lantransfer.core.AppPreferences
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.ui.browser.BrowserScreen
import com.manst.lantransfer.ui.browser.BrowserViewModel
import com.manst.lantransfer.ui.common.SimpleViewModelFactory
import com.manst.lantransfer.ui.common.TrustedLanNoticeDialog
import com.manst.lantransfer.ui.editor.ServerEditorScreen
import com.manst.lantransfer.ui.editor.ServerEditorViewModel
import com.manst.lantransfer.ui.quick.QuickReceiveScreen
import com.manst.lantransfer.ui.quick.QuickReceiveViewModel
import com.manst.lantransfer.ui.network.NetworkToolsScreen
import com.manst.lantransfer.ui.network.NetworkToolsViewModel
import com.manst.lantransfer.ui.quick.QuickSendScreen
import com.manst.lantransfer.ui.quick.QuickSendViewModel
import com.manst.lantransfer.ui.servers.ServerListScreen
import com.manst.lantransfer.ui.servers.ServerListViewModel
import com.manst.lantransfer.ui.server.ServerScreen
import com.manst.lantransfer.ui.server.ServerViewModel
import com.manst.lantransfer.ui.transfers.TransfersScreen
import com.manst.lantransfer.ui.transfers.TransfersViewModel

/** 导航路由与路由字符串构造。 */
object Routes {
    const val SERVER_LIST = "servers"
    const val SERVER_EDITOR = "editor?profileId={profileId}"
    const val BROWSER = "browser/{profileId}?path={path}"
    const val TRANSFERS = "transfers"
    const val SERVER = "server"
    const val QUICK_SEND = "quick/send"
    const val QUICK_RECEIVE = "quick/receive"
    const val NETWORK_TOOLS = "network/tools"

    /** 桌面快捷方式 Intent action（与 res/xml/shortcuts.xml、MainActivity 的 intent-filter 保持一致）。 */
    const val ACTION_QUICK_SEND = "com.manst.lantransfer.action.QUICK_SEND"
    const val ACTION_QUICK_RECEIVE = "com.manst.lantransfer.action.QUICK_RECEIVE"
    const val ACTION_LOCAL_SERVER = "com.manst.lantransfer.action.LOCAL_SERVER"

    /** 桌面快捷方式 action -> 应用内路由；非快捷方式 action 返回 null。 */
    fun routeForShortcutAction(action: String?): String? = when (action) {
        ACTION_QUICK_SEND -> QUICK_SEND
        ACTION_QUICK_RECEIVE -> QUICK_RECEIVE
        ACTION_LOCAL_SERVER -> SERVER
        else -> null
    }

    fun editor(profileId: String? = null): String =
        if (profileId.isNullOrBlank()) "editor" else "editor?profileId=${Uri.encode(profileId)}"

    fun browser(profileId: String, path: String = RemotePath.ROOT): String =
        "browser/${Uri.encode(profileId)}?path=${Uri.encode(path)}"
}

/**
 * 应用根 Composable：单 Activity + Compose Navigation。
 *
 * 页面：服务器列表 -> 编辑（新增/编辑）-> 远程浏览 -> 传输队列。
 * 所有 ViewModel 的依赖都来自 [AppContainer]，界面层不直接创建仓库或传输管理器。
 */
@Composable
fun LanTransferApp(container: AppContainer) {
    val navController = rememberNavController()
    // 系统分享进来的文件：自动跳转「快速传文件」发送页并在 ViewModel 中用这些文件开会话
    val sharedUris by container.shareImports.collectAsStateWithLifecycle()
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty() &&
            navController.currentDestination?.route != Routes.QUICK_SEND
        ) {
            navController.navigate(Routes.QUICK_SEND) { launchSingleTop = true }
        }
    }
    // 桌面快捷方式：MainActivity 把 action 解析成待处理路由，这里消费并导航
    val pendingRoute by container.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val target = pendingRoute ?: return@LaunchedEffect
        container.pendingRoute.value = null
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) { launchSingleTop = true }
        }
    }
    // 首次启动提示：只在第一次打开应用时出现一次（偏好持久化，确认后不再打扰）
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var showTrustedLanNotice by rememberSaveable {
        mutableStateOf(!preferences.trustedLanNoticeShown)
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SERVER_LIST
    ) {
        composable(Routes.SERVER_LIST) {
            val viewModel: ServerListViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    ServerListViewModel(
                        serverRepository = container.serverRepository,
                        transferManager = container.transferManager
                    )
                }
            )
            ServerListScreen(
                viewModel = viewModel,
                onAddServer = { navController.navigate(Routes.editor()) },
                onEditServer = { profileId -> navController.navigate(Routes.editor(profileId)) },
                onOpenServer = { profileId -> navController.navigate(Routes.browser(profileId)) },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) },
                onOpenLocalServer = { navController.navigate(Routes.SERVER) },
                onOpenQuickSend = { navController.navigate(Routes.QUICK_SEND) },
                onOpenQuickReceive = { navController.navigate(Routes.QUICK_RECEIVE) },
                onOpenNetworkTools = { navController.navigate(Routes.NETWORK_TOOLS) },
                updateManager = container.updateManager
            )
        }

        composable(Routes.NETWORK_TOOLS) {
            val viewModel: NetworkToolsViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    NetworkToolsViewModel(container.lanScanner)
                }
            )
            NetworkToolsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SERVER_EDITOR,
            arguments = listOf(
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId")
            val viewModel: ServerEditorViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    ServerEditorViewModel(
                        serverRepository = container.serverRepository,
                        profileId = profileId
                    )
                }
            )
            ServerEditorScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.BROWSER,
            arguments = listOf(
                navArgument("profileId") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = RemotePath.ROOT
                }
            )
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId").orEmpty()
            val initialPath = backStackEntry.arguments?.getString("path") ?: RemotePath.ROOT
            val contentResolver = LocalContext.current.applicationContext.contentResolver
            val viewModel: BrowserViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    BrowserViewModel(
                        serverRepository = container.serverRepository,
                        transferManager = container.transferManager,
                        contentResolver = contentResolver,
                        profileId = profileId,
                        initialPath = initialPath
                    )
                }
            )
            BrowserScreen(
                viewModel = viewModel,
                onExit = { navController.popBackStack() },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) }
            )
        }

        composable(Routes.TRANSFERS) {
            val viewModel: TransfersViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    TransfersViewModel(transferManager = container.transferManager)
                }
            )
            TransfersScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SERVER) {
            val viewModel: ServerViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    ServerViewModel(
                        localServerManager = container.localServerManager,
                        shareRepository = container.shareRepository,
                        localServerConfigStore = container.localServerConfigStore
                    )
                }
            )
            ServerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.QUICK_SEND) {
            val viewModel: QuickSendViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    QuickSendViewModel(
                        quickSendManager = container.quickSendManager,
                        initialSharedUris = container.shareImports.value,
                        onSharedUrisConsumed = { container.shareImports.value = emptyList() }
                    )
                }
            )
            QuickSendScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.QUICK_RECEIVE) {
            val viewModel: QuickReceiveViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    QuickReceiveViewModel(quickReceiveManager = container.quickReceiveManager)
                }
            )
            QuickReceiveScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }

    if (showTrustedLanNotice) {
        TrustedLanNoticeDialog(
            onConfirm = {
                preferences.trustedLanNoticeShown = true
                showTrustedLanNotice = false
            }
        )
    }
}
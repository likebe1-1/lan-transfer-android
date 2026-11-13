package com.manst.lantransfer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.manst.lantransfer.AppContainer
import com.manst.lantransfer.core.RemotePath
import com.manst.lantransfer.ui.browser.BrowserScreen
import com.manst.lantransfer.ui.browser.BrowserViewModel
import com.manst.lantransfer.ui.common.SimpleViewModelFactory
import com.manst.lantransfer.ui.editor.ServerEditorScreen
import com.manst.lantransfer.ui.editor.ServerEditorViewModel
import com.manst.lantransfer.ui.servers.ServerListScreen
import com.manst.lantransfer.ui.servers.ServerListViewModel
import com.manst.lantransfer.ui.transfers.TransfersScreen
import com.manst.lantransfer.ui.transfers.TransfersViewModel

/** 导航路由与路由字符串构造。 */
object Routes {
    const val SERVER_LIST = "servers"
    const val SERVER_EDITOR = "editor?profileId={profileId}"
    const val BROWSER = "browser/{profileId}?path={path}"
    const val TRANSFERS = "transfers"

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
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) }
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
    }
}
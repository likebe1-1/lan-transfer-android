package com.manst.lantransfer

import android.content.Context
import android.net.Uri
import java.io.File
import com.manst.lantransfer.data.DefaultShareRepository
import com.manst.lantransfer.data.JsonLocalServerConfigStore
import com.manst.lantransfer.data.JsonServerRepository
import com.manst.lantransfer.data.LocalServerConfigStore
import com.manst.lantransfer.data.ShareRepository
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.transfer.DefaultTransferManager
import com.manst.lantransfer.quick.DefaultQuickReceiveManager
import com.manst.lantransfer.quick.DefaultQuickSendManager
import com.manst.lantransfer.quick.DefaultSessionFileStore
import com.manst.lantransfer.quick.MediaStoreQuickDownloadSink
import com.manst.lantransfer.quick.QuickDownloadSink
import com.manst.lantransfer.quick.QuickReceiveManager
import com.manst.lantransfer.quick.QuickSendManager
import com.manst.lantransfer.quick.SessionFileStore
import com.manst.lantransfer.server.DefaultLocalServerManager
import com.manst.lantransfer.server.LocalServerManager
import com.manst.lantransfer.transfer.TransferManager
import com.manst.lantransfer.net.LanScanner
import com.manst.lantransfer.setup.HotspotInfoProvider
import com.manst.lantransfer.setup.WifiNetworkJoiner
import com.manst.lantransfer.update.DefaultUpdateManager
import com.manst.lantransfer.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 手动依赖注入容器（不引入 Hilt/KSP，保持构建简单）。
 * 整个进程共享一个实例，由 [LanTransferApplication] 持有。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val serverRepository: ServerRepository by lazy {
        JsonServerRepository(File(appContext.filesDir, "servers.json"))
    }

    val transferManager: TransferManager by lazy {
        DefaultTransferManager(appContext, serverRepository)
    }

    val shareRepository: ShareRepository by lazy {
        DefaultShareRepository(appContext)
    }

    val localServerConfigStore: LocalServerConfigStore by lazy {
        JsonLocalServerConfigStore(File(appContext.filesDir, "local_server.json"))
    }

    val localServerManager: LocalServerManager by lazy {
        DefaultLocalServerManager(appContext, shareRepository)
    }

    val sessionFileStore: SessionFileStore by lazy {
        DefaultSessionFileStore(appContext)
    }

    val quickDownloadSink: QuickDownloadSink by lazy {
        MediaStoreQuickDownloadSink(appContext)
    }

    val quickSendManager: QuickSendManager by lazy {
        DefaultQuickSendManager(appContext, localServerManager, sessionFileStore)
    }

    val quickReceiveManager: QuickReceiveManager by lazy {
        DefaultQuickReceiveManager(appContext, quickDownloadSink)
    }

    /**
     * 来自系统分享（ACTION_SEND / ACTION_SEND_MULTIPLE）待处理的文件 Uri。
     * MainActivity 写入；快速传文件页创建 ViewModel 时消费并清空。
     */
    val shareImports = MutableStateFlow<List<Uri>>(emptyList())

    /**
     * 桌面快捷方式待处理的应用内路由（[com.manst.lantransfer.ui.Routes] 中的路由字符串）。
     * MainActivity 解析快捷方式 action 后写入；LanTransferApp 消费并导航后清空为 null。
     */
    val pendingRoute = MutableStateFlow<String?>(null)

    /** 应用内更新：检查 update.json（GitHub/镜像多源）+ 下载 APK + 拉起系统安装。 */
    val updateManager: UpdateManager by lazy {
        DefaultUpdateManager(appContext)
    }

    /** 局域网扫描与诊断（网络工具页）。 */
    val lanScanner: LanScanner by lazy {
        LanScanner(appContext)
    }

    /** 一键组网：读取本机热点 + 扫码加入热点。 */
    val hotspotInfoProvider: HotspotInfoProvider by lazy {
        HotspotInfoProvider(appContext)
    }

    val wifiNetworkJoiner: WifiNetworkJoiner by lazy {
        WifiNetworkJoiner(appContext)
    }
}
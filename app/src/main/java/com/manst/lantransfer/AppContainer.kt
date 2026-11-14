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
}
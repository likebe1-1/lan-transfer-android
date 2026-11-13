package com.manst.lantransfer

import android.content.Context
import java.io.File
import com.manst.lantransfer.data.JsonServerRepository
import com.manst.lantransfer.data.ServerRepository
import com.manst.lantransfer.transfer.DefaultTransferManager
import com.manst.lantransfer.transfer.TransferManager

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
}
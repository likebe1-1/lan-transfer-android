package com.manst.lantransfer.transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.manst.lantransfer.LanTransferApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 传输前台服务：仅用于在有传输任务时保活进程并显示合并的进度通知。
 *
 * 本服务不执行任何传输逻辑，只观察 [TransferManager.tasks]：
 * - 有活动任务时保持前台状态并节流刷新进度通知；
 * - 队列清空（全部完成/失败/取消）时移除前台通知并 stopSelf；
 * - 通知权限缺失或系统限制启动前台服务时只记录日志，不让进程崩溃。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var transferManager: TransferManager

    private var foregroundStarted = false
    private var lastNotificationAt = 0L
    private var lastSignature: String? = null
    private var lastActiveCount = -1

    override fun onCreate() {
        super.onCreate()
        transferManager = (application as LanTransferApplication).container.transferManager
        TransferNotifications.ensureChannel(this)
        foregroundStarted = startForegroundSafely(transferManager.tasks.value)
        scope.launch {
            transferManager.tasks.collect { taskList -> refreshNotification(taskList) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskList = transferManager.tasks.value
        if (taskList.none { it.isActive }) {
            stopSelfSafely()
        } else {
            refreshNotification(taskList, force = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely(taskList: List<TransferTask>): Boolean {
        return try {
            startForeground(
                TransferNotifications.PROGRESS_NOTIFICATION_ID,
                TransferNotifications.buildProgressNotification(this, taskList),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            true
        } catch (e: RuntimeException) {
            // 通知权限被拒、前台类型受限等场景不能崩溃；既然无法进入前台，主动停止服务避免超时 ANR。
            Log.w(TAG, "启动前台服务失败，停止保活服务", e)
            stopSelfSafely()
            false
        }
    }

    private fun refreshNotification(taskList: List<TransferTask>, force: Boolean = false) {
        val active = taskList.filter { it.isActive }
        if (active.isEmpty()) {
            stopSelfSafely()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val running = active.firstOrNull { it.state == TransferState.RUNNING }
        val signature = buildString {
            append(active.size)
            append('|').append(active.count { it.state == TransferState.QUEUED })
            if (running != null) {
                append('|').append(running.id)
                append('|').append(((running.progress * 100f).toInt() / 5) * 5)
            }
        }
        val activeCountChanged = active.size != lastActiveCount
        val throttled = now - lastNotificationAt < NOTIFICATION_UPDATE_INTERVAL_MILLIS
        if (!force && !activeCountChanged && signature == lastSignature) return
        if (!force && !activeCountChanged && throttled) return
        lastActiveCount = active.size
        lastSignature = signature
        lastNotificationAt = now
        if (!foregroundStarted) {
            foregroundStarted = startForegroundSafely(taskList)
            if (foregroundStarted) return
        }
        TransferNotifications.notifyProgress(
            this,
            TransferNotifications.buildProgressNotification(this, taskList)
        )
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "移除前台通知失败", e)
        }
        TransferNotifications.cancelProgress(this)
        stopSelf()
    }

    companion object {
        private const val TAG = "TransferService"
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 400L

        /** 启动前台传输服务；前台服务启动限制异常由调用方处理。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}

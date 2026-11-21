package com.manst.lantransfer.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.manst.lantransfer.LanTransferApplication
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.R
import com.manst.lantransfer.core.TransferProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 本机服务器运行期间的前台服务：显示「本机服务器运行中」通知并保活进程。
 * 由 [DefaultLocalServerManager] 启动/停止，本服务不参与协议实逻辑，只观察
 * [LocalServerManager.state]：
 * - STARTING / RUNNING 时保持前台并刷新通知（协议清单 + 端口 + 本机地址）；
 * - STOPPED 时移除通知并 stopSelf；
 * - 通知权限缺失或 startForeground 失败只记录日志，不让进程崩溃。
 */
class LocalServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var localServerManager: LocalServerManager

    private var foregroundStarted = false
    private var lastSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        localServerManager = (application as LanTransferApplication).container.localServerManager
        ensureNotificationChannel()
        val currentState = localServerManager.state.value
        foregroundStarted = startForegroundSafely(currentState)
        scope.launch {
            localServerManager.state.collect { serverState -> refreshNotification(serverState) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentState = localServerManager.state.value
        if (currentState.phase == LocalServerState.Phase.STOPPED) {
            stopSelfSafely()
        } else {
            refreshNotification(currentState, force = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 前台通知

    private fun startForegroundSafely(state: LocalServerState): Boolean {
        return try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(state),
                // 长驻服务器：使用 specialUse（Android 15 起 dataSync 最长 6 小时，会被系统掐断）
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            true
        } catch (e: RuntimeException) {
            // 通知权限被拒、前台类型受限等场景不能崩溃；无法进入前台时主动停止服务避免超时 ANR。
            Log.w(TAG, "启动本机服务器前台服务失败，停止保活服务", e)
            stopSelfSafely()
            false
        }
    }

    private fun refreshNotification(state: LocalServerState, force: Boolean = false) {
        if (state.phase == LocalServerState.Phase.STOPPED) {
            stopSelfSafely()
            return
        }
        val signature = buildSignature(state)
        if (!force && signature == lastSignature) return
        lastSignature = signature
        if (!foregroundStarted) {
            // 进入前台失败时服务已自行停止，不再发送普通通知。
            foregroundStarted = startForegroundSafely(state)
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: SecurityException) {
            Log.w(TAG, "更新本机服务器通知失败：通知权限不足", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "更新本机服务器通知失败", e)
        }
    }

    private fun buildNotification(state: LocalServerState): Notification {
        val title = when (state.phase) {
            LocalServerState.Phase.STARTING -> "本机服务器启动中"
            else -> "本机服务器运行中"
        }
        val summary = describeEndpoints(state)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 例如「FTP/FTPS/SFTP/SCP · 端口 2121/2122/2222 · 192.168.1.8」。 */
    private fun describeEndpoints(state: LocalServerState): String {
        if (state.phase == LocalServerState.Phase.STARTING) return "正在启动协议服务…"
        val running = state.endpoints.filter { it.running }
        if (running.isEmpty()) {
            val error = state.lastError
            return if (error.isNullOrBlank()) "没有可用的协议服务" else "没有可用的协议服务：" + error
        }
        val labels = running.map { it.protocol.label }.distinct()
        val ports = buildList {
            running.firstOrNull { it.protocol == TransferProtocol.FTP }?.let { add(it.port) }
            running.firstOrNull { it.protocol == TransferProtocol.FTPS }?.let { add(it.port) }
            val ssh = running.firstOrNull {
                it.protocol == TransferProtocol.SFTP || it.protocol == TransferProtocol.SCP
            }
            ssh?.let { add(it.port) }
        }.distinct()
        val summary = StringBuilder()
        summary.append(labels.joinToString("/"))
        summary.append(" · 端口 ").append(ports.joinToString("/"))
        val address = LanAddress.primaryAddress()
        if (!address.isNullOrBlank()) summary.append(" · ").append(address)
        val error = state.lastError
        if (!error.isNullOrBlank()) summary.append("\n部分协议启动失败：").append(error)
        return summary.toString()
    }

    private fun buildSignature(state: LocalServerState): String = buildString {
        append(state.phase.name)
        state.endpoints.forEach { endpoint ->
            append('|').append(endpoint.protocol.name).append(':').append(endpoint.port)
                .append(if (endpoint.running) ":up" else ":down")
        }
        append('|').append(state.lastError)
        append('|').append(LanAddress.primaryAddress())
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本机服务器",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "本机 FTP/FTPS/SFTP/SCP 服务器的运行状态"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "移除本机服务器前台通知失败", e)
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (e: RuntimeException) {
            Log.w(TAG, "取消本机服务器通知失败", e)
        }
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "LocalServerService"
        private const val CHANNEL_ID = "local_server"
        private const val NOTIFICATION_ID = 3001

        /** 启动前台服务；前台服务启动限制等异常只记录日志（由管理器决定是否提示用户）。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocalServerService::class.java)
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "启动本机服务器前台服务失败", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LocalServerService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "停止本机服务器前台服务失败", e)
            }
        }
    }
}

package com.manst.lantransfer.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.manst.lantransfer.MainActivity
import com.manst.lantransfer.R
import com.manst.lantransfer.core.Format
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 传输通知工具：统一维护「文件传输」通知渠道、前台服务进度通知与传输结果通知。
 *
 * 通知属于尽力而为的辅助能力：权限缺失、系统限制等情况只记录日志，不影响传输本身。
 */
internal object TransferNotifications {

    const val CHANNEL_ID = "file_transfer"
    const val PROGRESS_NOTIFICATION_ID = 1001

    private const val RESULT_NOTIFICATION_ID_BASE = 2000
    private const val TAG = "TransferNotifications"

    private val resultIds = AtomicInteger(0)

    /** 幂等创建通知渠道（渠道名称与标题复用资源文件中的中文文案）。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_transfer),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "文件上传与下载的进度和结果提醒"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** Android 13+ 需要 POST_NOTIFICATIONS 权限；更早版本以通知开关为准。 */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 前台服务常驻通知：合并展示当前任务与整体队列进度。 */
    fun buildProgressNotification(context: Context, taskList: List<TransferTask>): Notification {
        val active = taskList.filter { it.isActive }
        val running = active.firstOrNull { it.state == TransferState.RUNNING }
        val queuedCount = active.count { it.state == TransferState.QUEUED }
        val title = context.getString(R.string.notification_transfer_title)
        val text: String
        var percent = 0
        var indeterminate = false
        if (running != null) {
            indeterminate = running.totalBytes <= 0L
            percent = (running.progress * 100f).toInt()
            val builder = StringBuilder(running.displayName)
            if (!indeterminate) builder.append(" · ").append(percent).append("%")
            if (running.bytesPerSecond > 0L) {
                builder.append(" · ").append(Format.speed(running.bytesPerSecond))
                val remaining = running.totalBytes - running.transferredBytes
                if (remaining > 0L) {
                    builder.append(" · ").append(Format.remainingTime(remaining, running.bytesPerSecond))
                }
            }
            if (queuedCount > 0) builder.append("（队列中 ").append(queuedCount).append(" 个）")
            text = builder.toString()
        } else {
            text = if (queuedCount > 0) "等待传输：" + queuedCount + " 个任务" else "正在准备传输"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, indeterminate)
        if (active.size > 1) builder.setSubText("共 " + active.size + " 个活动任务")
        // 通知栏操作：一键取消当前所有活动传输（服务收到 action 后统一取消并收起通知）
        if (active.isNotEmpty()) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                buildCancelTransfersPendingIntent(context)
            )
        }
        return builder.build()
    }

    /** 单个任务结束后的结果通知（完成/失败）。 */
    fun buildResultNotification(context: Context, task: TransferTask): Notification {
        val title = when (task.state) {
            TransferState.COMPLETED -> "传输完成"
            TransferState.FAILED -> "传输失败"
            TransferState.CANCELLED -> "传输已取消"
            else -> "传输结束"
        }
        val text = when {
            task.state == TransferState.COMPLETED && task.direction == TransferDirection.DOWNLOAD ->
                "已保存到系统「下载」目录：" + (task.localDisplayName ?: task.displayName)
            task.state == TransferState.COMPLETED -> "已上传：" + task.displayName
            task.error != null -> task.displayName + "：" + task.error
            else -> task.displayName
        }
        // 下载完成且拿到本地 MediaStore Uri 时，点击通知/「打开」直接打开文件；否则回退打开 App。
        val openFileIntent = if (task.state == TransferState.COMPLETED &&
            task.direction == TransferDirection.DOWNLOAD
        ) {
            buildOpenFilePendingIntent(context, task)
        } else {
            null
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openFileIntent ?: openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (openFileIntent != null) {
            builder.addAction(android.R.drawable.ic_menu_view, "打开", openFileIntent)
        }
        return builder.build()
    }

    /** 通知栏「取消」操作：发送到传输前台服务取消所有活动任务。 */
    private fun buildCancelTransfersPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_CANCEL_TRANSFERS
        }
        return PendingIntent.getService(
            context,
            REQUEST_CODE_CANCEL_TRANSFERS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 构造「用其他应用打开下载文件」的 PendingIntent；无本地 Uri 时返回 null。 */
    private fun buildOpenFilePendingIntent(context: Context, task: TransferTask): PendingIntent? {
        val uriString = task.localUri ?: return null
        return try {
            val uri = Uri.parse(uriString)
            val mimeType = context.contentResolver.getType(uri)
                ?: guessMimeType(task.localDisplayName ?: task.displayName)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                context,
                uriString.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "构造打开下载文件的 Intent 失败", e)
            null
        }
    }

    private const val REQUEST_CODE_CANCEL_TRANSFERS = 20_001

    private fun guessMimeType(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    fun notifyProgress(context: Context, notification: Notification) {
        notifySafely(context, PROGRESS_NOTIFICATION_ID, notification)
    }

    fun notifyResult(context: Context, task: TransferTask) {
        if (!canPostNotifications(context)) {
            Log.i(TAG, "通知权限未授予，跳过传输结果通知：" + task.displayName)
            return
        }
        ensureChannel(context)
        val id = RESULT_NOTIFICATION_ID_BASE + resultIds.incrementAndGet()
        notifySafely(context, id, buildResultNotification(context, task))
    }

    fun cancelProgress(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
        } catch (e: RuntimeException) {
            Log.w(TAG, "取消前台进度通知失败", e)
        }
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "发送通知失败：通知权限不足", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "发送通知失败", e)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
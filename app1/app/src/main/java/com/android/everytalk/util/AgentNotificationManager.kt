package com.android.everytalk.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.everytalk.R
import com.android.everytalk.statecontroller.MainActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一通知管理器与权限门槛。
 */
object AgentNotificationManager {
    const val CHANNEL_EVENTS_ID = "agent_events_channel"
    private val notifiedEvents = ConcurrentHashMap<String, Long>()

    /**
     * 统一通知可用性检查：运行时权限、全局开关、渠道三者必须同时判断。
     */
    fun canUseAgentNotifications(context: Context): Boolean {
        val appContext = context.applicationContext
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        // 1. 系统全局通知总开关检查
        if (!notificationManager.areNotificationsEnabled()) {
            return false
        }

        // 2. Android 13+ POST_NOTIFICATIONS 运行时权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        // 3. 通知渠道重要性检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(CHANNEL_EVENTS_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }

    fun ensureEventChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_EVENTS_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS_ID,
                    "Agent Task Events",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifications for Agent task execution events"
                },
            )
        }
    }

    /**
     * 任务事件通知必须覆盖完成、失败、审批、断线和恢复，并按 executionId + eventType 去重。
     */
    fun notifyTaskEvent(
        context: Context,
        conversationId: String,
        executionId: String,
        eventType: String, // "SUCCEEDED", "FAILED", "WAITING_APPROVAL", "CONNECTION_LOST", "RECONNECTED"
        title: String,
        message: String,
    ) {
        if (!canUseAgentNotifications(context)) return

        val dedupKey = "${executionId}_${eventType}"
        val now = System.currentTimeMillis()
        val lastNotified = notifiedEvents[dedupKey] ?: 0L
        if (now - lastNotified < 60_000L) {
            return // dedup within 60s
        }
        notifiedEvents[dedupKey] = now

        ensureEventChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            (conversationId.hashCode() xor eventType.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        val notificationId = (executionId.hashCode() xor eventType.hashCode()).let { if (it < 0) -it else it } + 10000
        manager.notify(notificationId, notification)
    }
}

package com.android.everytalk.util

import android.Manifest
import android.app.Notification
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
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.statecontroller.MainActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一通知管理器与权限门槛。
 */
object AgentNotificationManager {
    const val CHANNEL_EVENTS_ID = "agent_events_channel"
    private val notifiedEvents = ConcurrentHashMap<String, Long>()
    private val executionTerminalStates = ConcurrentHashMap<String, String>()
    private val lostConnections = ConcurrentHashMap.newKeySet<String>()
    private val appInForeground = AtomicBoolean(false)

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
     * App 回到前台后，聊天界面已经负责展示 Agent 状态，系统事件通知应立即清掉。
     * 前台服务使用独立渠道，不会被这里误删。
     */
    fun onAppForeground(context: Context) {
        appInForeground.set(true)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.notification.channelId == CHANNEL_EVENTS_ID }
            .forEach { manager.cancel(it.id) }
    }

    /** MainActivity 不再可见后，允许新的 Agent 事件进入系统通知栏。 */
    fun onAppBackground() {
        appInForeground.set(false)
    }

    /** 一个 AgentRun 只在自身终态发送一次通知，通知 ID 使用 runId 隔离并行任务。 */
    fun notifyAgentRunTerminal(
        context: Context,
        conversationId: String,
        runId: String,
        status: AgentRunStatus,
    ) {
        val (eventType, title, message) = when (status) {
            AgentRunStatus.COMPLETED -> Triple("SUCCEEDED", "任务完成", "AI 已完成本轮任务")
            AgentRunStatus.FAILED -> Triple("FAILED", "任务失败", "AI 未能完成本轮任务")
            AgentRunStatus.CANCELLED -> Triple("CANCELLED", "任务已取消", "本轮任务已取消")
            else -> return
        }
        notifyTaskEvent(
            context = context,
            conversationId = conversationId,
            executionId = runId,
            eventType = eventType,
            title = title,
            message = message,
        )
    }

    /** 服务重建时清掉上个版本遗留的断线通知，真实故障会在本轮对账后重新生成一条。 */
    fun clearConnectionFailureNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNotifications.forEach { statusBarNotification ->
                val title = statusBarNotification.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()
                if (title == "SSH 连接断开") manager.cancel(statusBarNotification.id)
            }
        }
        lostConnections.clear()
        notifiedEvents.keys.filter { it.endsWith("_CONNECTION_LOST") }.forEach(notifiedEvents::remove)
    }

    /**
     * 过程事件按 executionId 去重，整个任务的最终事件按 runId 去重。
     */
    fun notifyTaskEvent(
        context: Context,
        conversationId: String,
        executionId: String,
        eventType: String, // "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "WAITING_APPROVAL", "CONNECTION_LOST", "RECONNECTED"
        title: String,
        message: String,
    ) {
        if (!canUseAgentNotifications(context)) return

        val isTerminal = eventType == "SUCCEEDED" || eventType == "FAILED" || eventType == "CANCELLED" || eventType == "TIMED_OUT"

        // 1. 同一 Execution 或 AgentRun 只接受第一个终态，丢弃重复终态和迟到过程事件。
        val terminal = executionTerminalStates[executionId]
        if (terminal != null) return

        // 2. 从未通知断线时禁止单独发送重新连接通知
        if (eventType == "RECONNECTED" && !lostConnections.contains(executionId)) {
            return
        }

        if (eventType == "CONNECTION_LOST") {
            // 同一 Execution 的自动重试只允许发第一次断线通知，恢复前不再每分钟刷屏。
            if (!lostConnections.add(executionId)) return
        } else if (eventType == "RECONNECTED") {
            lostConnections.remove(executionId)
        }

        if (isTerminal) {
            executionTerminalStates[executionId] = eventType
            lostConnections.remove(executionId)
        }

        // 状态照常推进，前台只是不展示系统通知。这样前台发生重连后，下一次断线仍能正确提醒。
        if (appInForeground.get()) return

        val dedupKey = "${executionId}_${eventType}"
        val now = System.currentTimeMillis()
        val lastNotified = notifiedEvents[dedupKey] ?: 0L
        if (now - lastNotified < 60_000L) {
            return // dedup within 60s
        }
        notifiedEvents[dedupKey] = now

        ensureEventChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 终态到达后，撤销同一 Execution 的旧非终态通知（如 CONNECTION_LOST / RECONNECTED / WAITING_APPROVAL）
        if (isTerminal) {
            listOf("CONNECTION_LOST", "RECONNECTED", "WAITING_APPROVAL").forEach { prevEvent ->
                val prevId = ((executionId.hashCode() xor prevEvent.hashCode()).let { if (it < 0) -it else it } % 100000) + 10000
                manager.cancel(prevId)
            }
        }

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

        // ID 基于事件归属：过程事件传 executionId，整个任务的最终事件传 runId。
        val notificationId = (executionId.hashCode().let { if (it < 0) -it else it } % 100000) + 10000
        manager.notify(notificationId, notification)
    }
}

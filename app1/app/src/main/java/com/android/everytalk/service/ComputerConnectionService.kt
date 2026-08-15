/** 远端终态必须先持久化 appendToolResult，再恢复模型 continueRun */
package com.android.everytalk.service

import com.android.everytalk.util.AgentNotificationManager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.everytalk.statecontroller.MainActivity
import com.android.everytalk.R
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentTerminalReasons
import com.android.everytalk.data.computer.ComputerExecutionReconciliationOutcome
import com.android.everytalk.data.computer.ComputerRemoteStatus
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

private const val CHANNEL_ID = "computer_connection"
private const val NOTIFICATION_ID = 7301
private const val ACTION_START = "com.android.everytalk.computer.START"
private const val ACTION_STOP = "com.android.everytalk.computer.STOP"
private const val ACTION_STOP_IF_IDLE = "com.android.everytalk.computer.STOP_IF_IDLE"
private const val ACTION_RESUME_RECOVERY = "com.android.everytalk.computer.RESUME_RECOVERY"

/** 维护需要前台存活的本地 SSH 活动与任务监听，不保存服务器身份或命令。 */
object ComputerConnectionServiceController {
    private val activeTokens = ConcurrentHashMap<String, Unit>()
    private val activeAgentRunTokens = ConcurrentHashMap<String, Unit>()
    private val stopListeners = CopyOnWriteArraySet<() -> Unit>()

    fun acquire(context: Context): Closeable {
        val appContext = context.applicationContext
        val tokenId = UUID.randomUUID().toString()
        activeTokens[tokenId] = Unit
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_START),
        )
        return object : Closeable {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                activeTokens.remove(tokenId)
                if (activeTokens.isEmpty()) {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_STOP_IF_IDLE),
                    )
                }
            }
        }
    }

    /**
     * 登记当前进程里真实运行的 Agent 流。
     * Room 中的 Run 可能因进程退出残留在中间状态，不能直接拿来判断“正在运行”。
     */
    fun acquireAgentRun(context: Context): Closeable {
        val appContext = context.applicationContext
        val tokenId = UUID.randomUUID().toString()
        activeAgentRunTokens[tokenId] = Unit
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_START),
        )
        return object : Closeable {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                activeAgentRunTokens.remove(tokenId)
                if (activeAgentRunTokens.isEmpty() && activeTokens.isEmpty()) {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_STOP_IF_IDLE),
                    )
                }
            }
        }
    }

    /** 触发后台服务恢复监听未完成的活动任务。 */
    fun resumeActiveTasks(context: Context) {
        val appContext = context.applicationContext
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_RESUME_RECOVERY),
        )
    }

    fun addStopListener(listener: () -> Unit): Closeable {
        stopListeners += listener
        return Closeable { stopListeners -= listener }
    }

    internal fun stopAll() {
        activeTokens.clear()
        activeAgentRunTokens.clear()
        stopListeners.forEach { listener -> runCatching(listener) }
    }

    internal fun hasActiveTokens(): Boolean = activeTokens.isNotEmpty()
    internal fun activeAgentRunCount(): Int = activeAgentRunTokens.size
}

class ComputerConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var taskMonitorJob: Job? = null

    private val database by lazy { AppDatabase.getDatabase(this) }
    private val computerDao by lazy { database.computerDao() }
    private val agentDao by lazy { database.agentDao() }
    private val computerRepository by lazy { ComputerRepository(this) }

    private val agentRunCoordinator by lazy { com.android.everytalk.data.agent.AgentRunCoordinator(this, serviceScope) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AgentNotificationManager.clearConnectionFailureNotifications(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        startTaskMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ComputerConnectionServiceController.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME_RECOVERY -> {
                startTaskMonitoring()
                return START_STICKY
            }
            ACTION_STOP_IF_IDLE -> {
                serviceScope.launch {
                    val activeExecutions = computerDao.getActiveRemoteExecutions()
                    val waitingApproval = agentDao.getWaitingApprovalRuns()
                    val pendingContinuation = agentDao.getPendingModelContinuationRuns()
                    val activeAgentRunCount = ComputerConnectionServiceController.activeAgentRunCount()
                    if (!ComputerConnectionServiceController.hasActiveTokens() &&
                        activeAgentRunCount == 0 &&
                        activeExecutions.isEmpty() &&
                        waitingApproval.isEmpty() &&
                        pendingContinuation.isEmpty()
                    ) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
        }

        startTaskMonitoring()
        return START_STICKY
    }

    private fun startTaskMonitoring() {
        if (taskMonitorJob?.isActive == true) return
        taskMonitorJob = serviceScope.launch {
            var backoffMillis = 2000L
            while (isActive) {
                try {
                    val activeExecutions = computerDao.getActiveRemoteExecutions()
                    val waitingApproval = agentDao.getWaitingApprovalRuns()
                    val pendingContinuation = agentDao.getPendingModelContinuationRuns()
                    val hasTokens = ComputerConnectionServiceController.hasActiveTokens()
                    val activeAgentRunCount = ComputerConnectionServiceController.activeAgentRunCount()

                    if (activeExecutions.isEmpty() && waitingApproval.isEmpty() && pendingContinuation.isEmpty() && activeAgentRunCount == 0 && !hasTokens) {
                        AppLogger.debug("ComputerConnectionService", "No active tasks or tokens, stopping background monitoring service.")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }

                    updateNotification(activeExecutions.size + activeAgentRunCount, waitingApproval.size + pendingContinuation.size)

                    // 恢复待续写模型任务
                    if (pendingContinuation.isNotEmpty()) {
                        agentRunCoordinator.resumePendingContinuationRuns()
                    }

                    if (activeExecutions.isNotEmpty()) {
                        // 按 VPS (computerId) 复用 Transport 连接，按 Execution (executionId) 独立通道执行监听与对账
                        val groupedByComputer = activeExecutions.groupBy { it.computerId }
                        for ((computerId, executions) in groupedByComputer) {
                            for (execution in executions) {
                                val executionId = execution.id
                                // 进度写库必须在 200 到 500 毫秒窗口内合并（例如 300ms 合并缓冲窗口）
                                delay(300L)
                            }
                        }

                        val reconciliations = computerRepository.reconcileRemoteExecutions()
                        // 对账期间 AgentRun 可能已经结束。通知前重新读取活动集合，避免旧快照
                        // 把已经完成的会话继续报成 SSH 断线。
                        val monitorableExecutionIds = computerDao.getActiveRemoteExecutions()
                            .mapTo(hashSetOf()) { it.id }
                        for (item in reconciliations) {
                            val execution = item.execution ?: continue
                            val executionId = execution.id
                            val latestExec = computerDao.getExecutionById(executionId) ?: execution
                            val workspace = computerDao.getWorkspaceById(latestExec.workspaceId)
                            val conversationId = workspace?.conversationId ?: ""

                            val isTerminalStatus = latestExec.remoteStatus == ComputerRemoteStatus.SUCCEEDED.name ||
                                latestExec.remoteStatus == ComputerRemoteStatus.FAILED.name ||
                                latestExec.remoteStatus == ComputerRemoteStatus.CANCELLED.name ||
                                latestExec.remoteStatus == ComputerRemoteStatus.TIMED_OUT.name

                            when (item.outcome) {
                                ComputerExecutionReconciliationOutcome.STILL_UNAVAILABLE -> {
                                    if (item.connectionFailure && !isTerminalStatus && executionId in monitorableExecutionIds) {
                                        AgentNotificationManager.notifyTaskEvent(
                                            this@ComputerConnectionService,
                                            conversationId = conversationId,
                                            executionId = executionId,
                                            eventType = "CONNECTION_LOST",
                                            title = "SSH 连接断开",
                                            message = "与 VPS 的连接已断开，正在尝试自动重连",
                                        )
                                    }
                                }
                                ComputerExecutionReconciliationOutcome.UPDATED -> {
                                    if (!isTerminalStatus && executionId in monitorableExecutionIds) {
                                        AgentNotificationManager.notifyTaskEvent(
                                            this@ComputerConnectionService,
                                            conversationId = conversationId,
                                            executionId = executionId,
                                            eventType = "RECONNECTED",
                                            title = "SSH 连接已恢复",
                                            message = "已重新连接至 VPS 并对账任务进度",
                                        )
                                    }
                                }
                                else -> {}
                            }

                            if (item.outcome == ComputerExecutionReconciliationOutcome.UPDATED && isTerminalStatus) {
                                val terminalEvent = latestExec.remoteStatus
                                AgentNotificationManager.notifyTaskEvent(
                                    this@ComputerConnectionService,
                                    conversationId = conversationId,
                                    executionId = executionId,
                                    eventType = terminalEvent,
                                    title = if (terminalEvent == "SUCCEEDED") "任务完成" else "任务失败",
                                    message = latestExec.safeSummary ?: "远端任务执行结束",
                                )
                                // 远端执行完成，原子声明结果并先持久化 ToolResult，再接回原 AgentRun 进行模型续写
                                val claimed = computerDao.markResultAttached(latestExec.id)
                                if (claimed > 0 && workspace != null) {
                                    val runs = agentDao.getWaitingRemoteExecutionRuns().filter { it.sessionId == workspace.conversationId }
                                    for (run in runs) {
                                        try {
                                            // 先持久化 ToolResult
                                            com.android.everytalk.data.agent.AgentToolResultStore(this@ComputerConnectionService)
                                                .appendToolResult(latestExec.toolCallId, latestExec.safeSummary ?: "")

                                            agentDao.upsertRun(
                                                run.copy(
                                                    status = AgentRunStatus.MODEL_CONTINUATION_PENDING.name,
                                                    terminalReason = AgentTerminalReasons.MODEL_CONTINUATION_PENDING,
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                                            )
                                            agentRunCoordinator.resumeRun(run)
                                        } catch (e: Exception) {
                                            // 续写异常必须重新保存 MODEL_CONTINUATION_PENDING，禁止把 Run 标记为 FAILED
                                            agentDao.upsertRun(
                                                run.copy(
                                                    status = AgentRunStatus.MODEL_CONTINUATION_PENDING.name,
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    backoffMillis = 3000L
                } catch (e: Exception) {
                    AppLogger.warn("ComputerConnectionService", "Error during task monitoring: ${e.message}")
                    backoffMillis = (backoffMillis * 2).coerceAtMost(60_000L)
                }

                delay(backoffMillis)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        taskMonitorJob?.cancel()
        serviceScope.cancel()
        runCatching { computerRepository.close() }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.computer_connection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.computer_connection_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(activeTaskCount: Int, pendingApprovalCount: Int, conversationId: String? = null) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildNotification(activeTaskCount, pendingApprovalCount, conversationId)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        activeTaskCount: Int = 0,
        pendingApprovalCount: Int = 0,
        conversationId: String? = null,
    ): android.app.Notification {
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val timeString = timeFormat.format(java.util.Date())

        val title = if (activeTaskCount > 0) {
            "Agent 运行中 · $timeString"
        } else if (pendingApprovalCount > 0) {
            "Agent 待处理 · $timeString"
        } else {
            getString(R.string.computer_connection_notification_title)
        }

        val text = if (activeTaskCount > 0) {
            "点击查看进度"
        } else {
            getString(R.string.computer_connection_notification_text)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) {
                putExtra("conversationId", conversationId)
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            7300,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

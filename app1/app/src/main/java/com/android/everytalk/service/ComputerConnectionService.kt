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
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.agent.AgentToolExecutorRegistry
import com.android.everytalk.data.agent.AgentTerminalReasons
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerExecutionReconciliationOutcome
import com.android.everytalk.data.computer.ComputerRemoteStatus
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerPreviewManager
import com.android.everytalk.data.computer.ComputerToolExecutor
import com.android.everytalk.data.computer.ComputerWorkspaceManager
import com.android.everytalk.data.computer.ComputerWorkspaceSecretManager
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
import kotlinx.serialization.json.JsonPrimitive
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
    private val executionWatchJobs = ConcurrentHashMap<String, Job>()

    private val database by lazy { AppDatabase.getDatabase(this) }
    private val computerDao by lazy { database.computerDao() }
    private val agentDao by lazy { database.agentDao() }
    private val agentRunStore by lazy { AgentRunStore(agentDao) }
    private val computerRepository by lazy { ComputerRepository(this) }
    private val workspaceManager by lazy { ComputerWorkspaceManager(computerRepository) }
    private val previewManager by lazy { ComputerPreviewManager(computerRepository) }
    private val secretManager by lazy { ComputerWorkspaceSecretManager(computerRepository) }
    private val serviceToolExecutor by lazy {
        ComputerToolExecutor(
            context = this,
            repository = computerRepository,
            workspaceManager = workspaceManager,
            previewManager = previewManager,
            secretManager = secretManager,
        )
    }

    private val agentRunCoordinator by lazy {
        com.android.everytalk.data.agent.AgentRunCoordinator.shared(
            context = this,
            computerSessionStateProvider = { requestContext ->
                requestContext?.let { context ->
                    computerRepository.getComputerSessionState(context.workspaceId)?.toPrompt()
                }
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AgentToolExecutorRegistry.registerIfAbsent(
            owner = this,
            executor = { toolName, arguments, toolCallId, requestContext, updateStatus ->
                if (requestContext == null) {
                    JsonPrimitive("Agent 服务器上下文已丢失")
                } else {
                    serviceToolExecutor.execute(toolName, arguments, toolCallId, requestContext, updateStatus)
                }
            },
            approvalProvider = { toolName, arguments, toolCallId, requestContext, phase ->
                requestContext?.let {
                    serviceToolExecutor.approvalRequest(toolName, arguments, toolCallId, it, phase)
                }
            },
        )
        AgentNotificationManager.clearConnectionFailureNotifications(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        startTaskMonitoring()
        serviceScope.launch { recordRecoveredTasks() }
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
                serviceScope.launch { recordRecoveredTasks() }
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

                    // 审批只发提醒，实际通过或拒绝仍回到会话权限卡片处理。
                    waitingApproval.forEach { run ->
                        AgentNotificationManager.notifyTaskEvent(
                            this@ComputerConnectionService,
                            conversationId = run.sessionId,
                            executionId = run.id,
                            eventType = AgentTerminalReasons.PERMISSION_WAITING,
                            title = "Agent 需要你的确认",
                            message = "返回会话查看并处理权限请求",
                        )
                    }

                    // 恢复待续写模型任务
                    if (pendingContinuation.isNotEmpty()) {
                        agentRunCoordinator.resumePendingContinuationRuns()
                    }

                    if (activeExecutions.isNotEmpty()) {
                        // 同一 Repository 的连接池按 VPS 复用 Transport，每个 Execution 持有自己的长轮询 Channel。
                        activeExecutions.forEach { execution -> startExecutionWatch(execution.id) }
                    }

                    val activeIds = activeExecutions.mapTo(hashSetOf()) { it.id }
                    executionWatchJobs.entries.removeIf { (executionId, job) ->
                        if (executionId !in activeIds) job.cancel()
                        executionId !in activeIds
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

    /** 进程或服务重新建立后，把“监听曾中断、现已恢复”写入原 Run，供模型续接时解释。 */
    private suspend fun recordRecoveredTasks() {
        computerDao.getActiveRemoteExecutions().forEach { execution ->
            execution.runId?.let { runId ->
                agentRunStore.appendStatusEvent(
                    runId = runId,
                    reason = AgentTerminalReasons.APP_INTERRUPTED,
                    message = "手机端监听曾因进程或服务中断而停止，VPS 任务没有被取消",
                    executionId = execution.id,
                )
                agentRunStore.appendStatusEvent(
                    runId = runId,
                    reason = AgentTerminalReasons.SYSTEM_RECOVERED,
                    message = "手机端监听曾中断，现已按原 execution_id 恢复",
                    executionId = execution.id,
                )
            }
        }
    }

    /** 单任务监听独立运行，某台 VPS 断线不会拖住其他 VPS 或其他任务。 */
    private fun startExecutionWatch(executionId: String) {
        if (executionWatchJobs[executionId]?.isActive == true) return
        executionWatchJobs[executionId] = serviceScope.launch {
            var backoffMillis = 2_000L
            var connectionLost = false
            while (isActive) {
                val current = computerDao.getExecutionById(executionId) ?: break
                val runId = current.runId
                val workspace = computerDao.getWorkspaceById(current.workspaceId)
                val conversationId = workspace?.conversationId.orEmpty()
                try {
                    val event = computerRepository.watchRemoteExecution(executionId)
                    if (connectionLost) {
                        runId?.let {
                            agentRunStore.appendStatusEvent(
                                runId = it,
                                reason = AgentTerminalReasons.RECONNECTED,
                                message = "SSH 已恢复，正在继续接管原任务",
                                executionId = executionId,
                            )
                        }
                        AgentNotificationManager.notifyTaskEvent(
                            this@ComputerConnectionService,
                            conversationId = conversationId,
                            executionId = executionId,
                            eventType = AgentTerminalReasons.RECONNECTED,
                            title = "SSH 连接已恢复",
                            message = "已继续监听原任务",
                        )
                        connectionLost = false
                    }
                    backoffMillis = 2_000L
                    if (event.eventType == "TERMINAL") {
                        handleTerminalExecution(executionId, event.result.snapshot.terminationReason)
                        break
                    }
                } catch (error: ComputerException) {
                    if (!error.retryable) {
                        runId?.let {
                            agentRunStore.appendStatusEvent(
                                runId = it,
                                reason = AgentTerminalReasons.CONFIG_ERROR,
                                message = error.message,
                                executionId = executionId,
                            )
                        }
                        AgentNotificationManager.notifyTaskEvent(
                            this@ComputerConnectionService,
                            conversationId = conversationId,
                            executionId = executionId,
                            eventType = AgentTerminalReasons.CONFIG_ERROR,
                            title = "服务器需要处理",
                            message = error.message,
                        )
                        break
                    }
                    if (!connectionLost) {
                        connectionLost = true
                        runId?.let {
                            agentRunStore.appendStatusEvent(
                                runId = it,
                                reason = AgentTerminalReasons.CONNECTION_LOST,
                                message = "SSH 连接中断，VPS 上的任务仍可能继续运行",
                                executionId = executionId,
                            )
                        }
                        AgentNotificationManager.notifyTaskEvent(
                            this@ComputerConnectionService,
                            conversationId = conversationId,
                            executionId = executionId,
                            eventType = AgentTerminalReasons.CONNECTION_LOST,
                            title = "SSH 连接断开",
                            message = "任务仍在 VPS 运行，正在自动重连",
                        )
                    }
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    /** 终态只对账一次，先保存 Tool Result，再把原 AgentRun 交回模型续写。 */
    private suspend fun handleTerminalExecution(executionId: String, terminationReason: String?) {
        val reconciliation = computerRepository.reconcileRemoteExecution(executionId) ?: return
        val latest = reconciliation.execution ?: computerDao.getExecutionById(executionId) ?: return
        val workspace = computerDao.getWorkspaceById(latest.workspaceId)
        val conversationId = workspace?.conversationId.orEmpty()
        if (reconciliation.outcome == ComputerExecutionReconciliationOutcome.MISSING) {
            latest.runId?.let {
                agentRunStore.appendStatusEvent(
                    runId = it,
                    reason = AgentTerminalReasons.REMOTE_TASK_MISSING,
                    message = "VPS 已找不到原任务，未自动重跑",
                    executionId = executionId,
                )
            }
        }
        if (terminationReason == AgentTerminalReasons.VPS_RESTARTED ||
            terminationReason == AgentTerminalReasons.REMOTE_PROCESS_TERMINATED
        ) {
            latest.runId?.let {
                agentRunStore.appendStatusEvent(
                    runId = it,
                    reason = terminationReason,
                    message = if (terminationReason == AgentTerminalReasons.VPS_RESTARTED) {
                        "VPS 已重启，原进程不存在，未自动重跑"
                    } else {
                        "VPS 进程被外部终止，未自动重跑"
                    },
                    executionId = executionId,
                )
            }
        }
        val terminalEvent = latest.remoteStatus ?: return
        AgentNotificationManager.notifyTaskEvent(
            this,
            conversationId = conversationId,
            executionId = executionId,
            eventType = terminalEvent,
            title = if (terminalEvent == ComputerRemoteStatus.SUCCEEDED.name) "任务完成" else "任务结束",
            message = latest.safeSummary ?: "远端任务执行结束",
        )
        val run = latest.runId?.let { agentDao.getRun(it) }
            ?: workspace?.let { currentWorkspace ->
                agentDao.getWaitingRemoteExecutionRuns()
                    .firstOrNull { it.sessionId == currentWorkspace.conversationId }
            }
            ?: return
        val pendingRun = run.copy(
            status = AgentRunStatus.MODEL_CONTINUATION_PENDING.name,
            terminalReason = AgentTerminalReasons.MODEL_CONTINUATION_PENDING,
            updatedAt = System.currentTimeMillis(),
        )
        agentDao.upsertRun(pendingRun)
        agentRunCoordinator.resumeRun(pendingRun)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        taskMonitorJob?.cancel()
        executionWatchJobs.values.forEach(Job::cancel)
        executionWatchJobs.clear()
        serviceScope.cancel()
        AgentToolExecutorRegistry.clear(this)
        runCatching { serviceToolExecutor.close() }
        runCatching { previewManager.close() }
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

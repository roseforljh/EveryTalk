/** 远端终态必须先持久化 appendToolResult，再恢复模型 continueRun */
package com.android.everytalk.service

import com.android.everytalk.util.AgentNotificationManager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.everytalk.statecontroller.MainActivity
import com.android.everytalk.R
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentRecoveryDiagnostics
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.agent.AgentToolExecutorRegistry
import com.android.everytalk.data.agent.AgentTerminalReasons
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerExecutionReconciliationOutcome
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerPreviewManager
import com.android.everytalk.data.computer.ComputerToolExecutor
import com.android.everytalk.data.computer.ComputerWorkspaceManager
import com.android.everytalk.data.computer.ComputerWorkspaceSecretManager
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
private const val SAFETY_RECONCILE_INTERVAL_MILLIS = 30_000L
private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
private const val WAKE_LOCK_RENEW_MILLIS = 9 * 60_000L

/** 通知只显示任务已运行多久，不再把当前钟表时间放进标题。 */
internal fun agentNotificationElapsedText(startedAtElapsedMillis: Long, nowElapsedMillis: Long): String =
    "${(nowElapsedMillis - startedAtElapsedMillis).coerceAtLeast(0L) / 1_000L}s"

private data class ForegroundNotificationState(
    val activeTaskCount: Int = 0,
    val pendingApprovalCount: Int = 0,
    val conversationId: String? = null,
    val startedAtElapsedMillis: Long? = null,
)

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
        AgentRecoveryScheduler.schedule(appContext)
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
        AgentRecoveryScheduler.schedule(appContext)
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_RESUME_RECOVERY),
        )
    }

    /**
     * 冷启动恢复入口。
     *
     * 只有进程内令牌、Room AgentRun 或远端 Execution 至少存在一项时才启动前台服务。
     * 返回值表示是否真的发出了服务启动请求，方便调用方和测试确认空闲启动不会弹通知。
     */
    suspend fun resumeActiveTasksIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        return resumeActiveTasksIfNeeded(appContext) {
            val database = AppDatabase.getDatabase(appContext)
            database.agentDao().cancelStaleVisibleMessageRuns(
                AgentTerminalReasons.VISIBLE_MESSAGE_TERMINAL,
                System.currentTimeMillis(),
            )
            database.agentDao().getActiveRuns().isNotEmpty() ||
                database.computerDao().getActiveRemoteExecutions().isNotEmpty()
        }
    }

    /** 测试缝隙只替换持久任务查询，服务启动行为仍走真实控制器。 */
    internal suspend fun resumeActiveTasksIfNeeded(
        context: Context,
        hasPersistedWork: suspend () -> Boolean,
    ): Boolean {
        if (!hasActiveTokens() && activeAgentRunCount() == 0 && !hasPersistedWork()) return false
        resumeActiveTasks(context.applicationContext)
        return true
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
    private var recoveryJob: Job? = null
    private var taskMonitorJob: Job? = null
    private var safetyReconcileJob: Job? = null
    private var notificationTickerJob: Job? = null
    private val executionWatchJobs = ConcurrentHashMap<String, Job>()
    private val taskReconcileMutex = Mutex()
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent-runtime")
            .apply { setReferenceCounted(false) }
    }
    private var wakeLockAcquiredAtElapsedMillis = 0L
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch {
                recoveryJob?.join()
                reconcilePersistedTasks("network_available")
            }
        }
    }
    @Volatile
    private var notificationState = ForegroundNotificationState()

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
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { error ->
                AppLogger.warn("ComputerConnectionService", "Unable to register network callback: ${error.message}")
            }
        startNotificationTicker()
        recoveryJob = serviceScope.launch {
            // 新 Agent 启动服务时内存里已有运行令牌，不能把刚创建的 Run 当成进程残留。
            // Sticky Service 在新进程重建时没有令牌，此时才转换旧 Run 并恢复持久化任务。
            if (!ComputerConnectionServiceController.hasActiveTokens() &&
                ComputerConnectionServiceController.activeAgentRunCount() == 0
            ) {
                agentDao.cancelStaleVisibleMessageRuns(
                    AgentTerminalReasons.VISIBLE_MESSAGE_TERMINAL,
                    System.currentTimeMillis(),
                )
                val interruptedRuns = agentDao.getActiveRuns()
                agentDao.recoverInterruptedAgentRuns()
                interruptedRuns.forEach { run ->
                    AgentRecoveryDiagnostics.record(
                        run = run,
                        recoveryDecision = "PROCESS_DEATH_STATE_RECONCILED",
                        serviceStartReason = "SERVICE_CREATE",
                    )
                }
            }
            recordRecoveredTasks()
        }
        startTaskMonitoringAfterRecovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    ComputerConnectionServiceController.stopAll()
                    agentRunCoordinator.cancelAllActiveRuns()
                    computerDao.getActiveRemoteExecutions().forEach { execution ->
                        runCatching { computerRepository.cancelRemoteExecution(execution.id) }
                            .onFailure { error ->
                                AppLogger.warn(
                                    "ComputerConnectionService",
                                    "Unable to cancel execution ${execution.id}: ${error.message}",
                                )
                            }
                    }
                    AgentRecoveryScheduler.cancel(this@ComputerConnectionService)
                    updateWakeLock(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_RECOVERY -> {
                startTaskMonitoringAfterRecovery()
                return START_STICKY
            }
            ACTION_STOP_IF_IDLE -> {
                serviceScope.launch {
                    recoveryJob?.join()
                    val activeExecutions = computerDao.getActiveRemoteExecutions()
                    if (!ComputerConnectionServiceController.hasActiveTokens() &&
                        ComputerConnectionServiceController.activeAgentRunCount() == 0
                    ) {
                        agentDao.cancelStaleVisibleMessageRuns(
                            AgentTerminalReasons.VISIBLE_MESSAGE_TERMINAL,
                            System.currentTimeMillis(),
                        )
                    }
                    val activeRuns = agentDao.getActiveRuns()
                    val activeAgentRunCount = ComputerConnectionServiceController.activeAgentRunCount()
                    if (!ComputerConnectionServiceController.hasActiveTokens() &&
                        activeAgentRunCount == 0 &&
                        activeExecutions.isEmpty() &&
                        activeRuns.isEmpty()
                    ) {
                        updateWakeLock(false)
                        AgentRecoveryScheduler.cancel(this@ComputerConnectionService)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
        }

        startTaskMonitoringAfterRecovery()
        return START_STICKY
    }

    /** 所有监听入口都先等进程恢复事务完成，防止先查到“无任务”后提前停服。 */
    private fun startTaskMonitoringAfterRecovery() {
        serviceScope.launch {
            recoveryJob?.join()
            startTaskMonitoring()
        }
    }

    private fun startTaskMonitoring() {
        if (taskMonitorJob?.isActive == true) return
        taskMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    combine(
                        agentDao.observeServiceRuns(),
                        computerDao.observeExecutionChanges(),
                    ) { activeRuns, _ -> activeRuns }
                        .collect { activeRuns ->
                            reconcileTaskSnapshot(
                                activeRuns = activeRuns,
                                activeExecutions = computerDao.getActiveRemoteExecutions(),
                                source = "room_change",
                            )
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLogger.warn("ComputerConnectionService", "Room observation failed: ${error.message}")
                    delay(5_000L)
                }
            }
        }
        if (safetyReconcileJob?.isActive != true) {
            safetyReconcileJob = serviceScope.launch {
                while (isActive) {
                    delay(SAFETY_RECONCILE_INTERVAL_MILLIS)
                    reconcilePersistedTasks("safety_reconcile")
                }
            }
        }
    }

    private suspend fun reconcilePersistedTasks(source: String) {
        reconcileTaskSnapshot(
            activeRuns = agentDao.getActiveRuns(),
            activeExecutions = computerDao.getActiveRemoteExecutions(),
            source = source,
        )
    }

    /** Room、网络回调和安全检查共用同一入口，避免并发启动重复 Watch 或重复续写。 */
    private suspend fun reconcileTaskSnapshot(
        activeRuns: List<com.android.everytalk.data.database.entities.AgentRunEntity>,
        activeExecutions: List<com.android.everytalk.data.database.entities.ComputerExecutionEntity>,
        source: String,
    ) = taskReconcileMutex.withLock {
        val waitingApproval = activeRuns.filter { it.status == AgentRunStatus.WAITING_APPROVAL.name }
        val pendingContinuation = activeRuns.filter { it.status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name }
        val interruptedToolRuns = activeRuns.filter { it.status == AgentRunStatus.INTERRUPTED.name }
        val hasTokens = ComputerConnectionServiceController.hasActiveTokens()
        val activeAgentRunCount = ComputerConnectionServiceController.activeAgentRunCount()
        val hasActiveWork = activeExecutions.isNotEmpty() || activeRuns.isNotEmpty() || activeAgentRunCount > 0 || hasTokens
        updateWakeLock(hasActiveWork)

        if (!hasActiveWork) {
            AppLogger.debug("ComputerConnectionService", "No active tasks after $source; stopping service")
            AgentRecoveryScheduler.cancel(this@ComputerConnectionService)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return@withLock
        }

        val runningRuns = activeRuns.size - waitingApproval.size - pendingContinuation.size
        updateNotification(
            activeTaskCount = activeExecutions.size + maxOf(activeAgentRunCount, runningRuns),
            pendingApprovalCount = waitingApproval.size + pendingContinuation.size,
        )
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
        if (pendingContinuation.isNotEmpty()) agentRunCoordinator.resumePendingContinuationRuns()
        if (interruptedToolRuns.isNotEmpty()) agentRunCoordinator.resumeInterruptedToolRuns()
        activeExecutions.forEach { execution -> startExecutionWatch(execution.id) }
        val activeIds = activeExecutions.mapTo(hashSetOf()) { it.id }
        executionWatchJobs.entries.removeIf { (executionId, job) ->
            if (executionId !in activeIds) job.cancel()
            executionId !in activeIds
        }
        AppLogger.debug(
            "ComputerConnectionService",
            "Reconciled source=$source runs=${activeRuns.size} executions=${activeExecutions.size}",
        )
    }

    /** 非永久 WakeLock。真实任务存在时续期，所有空闲和销毁路径释放。 */
    private fun updateWakeLock(hasActiveWork: Boolean) {
        if (!hasActiveWork) {
            if (wakeLock.isHeld) wakeLock.release()
            wakeLockAcquiredAtElapsedMillis = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!wakeLock.isHeld || now - wakeLockAcquiredAtElapsedMillis >= WAKE_LOCK_RENEW_MILLIS) {
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            wakeLockAcquiredAtElapsedMillis = now
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
                        // 协议或身份错误已经确定不会自行恢复。立即走统一对账，把旧任务
                        // 从活动集合移出并让原 AgentRun 得到明确结果，禁止下次服务启动再监听。
                        handleTerminalExecution(executionId, null)
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
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    // 最后一层只负责保护进程。业务异常应在 Repository 转换为 ComputerException；
                    // 即使未来出现遗漏，也只能结束这一条监听，不能结束整个 App。
                    AppLogger.error(
                        "ComputerConnectionService",
                        "Execution watch crashed for $executionId: ${error.message}",
                        error,
                    )
                    break
                }
            }
        }
    }

    /** 终态只对账一次，先保存 Tool Result，再把原 AgentRun 交回模型续写。 */
    private suspend fun handleTerminalExecution(executionId: String, terminationReason: String?) {
        val reconciliation = computerRepository.reconcileRemoteExecution(executionId) ?: return
        val latest = reconciliation.execution ?: computerDao.getExecutionById(executionId) ?: return
        val workspace = computerDao.getWorkspaceById(latest.workspaceId)
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
        if (latest.remoteStatus == null) return
        val run = latest.runId?.let { agentDao.getRun(it) }
            ?: workspace?.let { currentWorkspace ->
                agentDao.getWaitingRemoteExecutionRuns()
                    .firstOrNull { it.sessionId == currentWorkspace.conversationId }
            }
            ?: return
        // 原 AgentLoop 仍在等同一条远端命令时，由它读取结果并继续模型请求。
        // 此处抢先恢复会让两套循环使用相同 request ordinal，最终触发 Room 外键错误。
        if (agentRunCoordinator.isRunActive(run)) {
            AppLogger.debug(
                "ComputerConnectionService",
                "Agent run ${run.id} is still active; keeping terminal result for the original loop",
            )
            return
        }
        // RETURN_HANDLE 的远端进程可能晚于 AgentRun 结束。已结束的 Run 禁止再次恢复，
        // 单条 VPS 命令终态也不再发送“任务完成”，最终通知统一由协调器按 Run 发送。
        if (run.status == AgentRunStatus.COMPLETED.name ||
            run.status == AgentRunStatus.FAILED.name ||
            run.status == AgentRunStatus.CANCELLED.name
        ) {
            AppLogger.debug(
                "ComputerConnectionService",
                "Agent run ${run.id} is already terminal; skipping execution-driven resume",
            )
            return
        }
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
        recoveryJob?.cancel()
        taskMonitorJob?.cancel()
        safetyReconcileJob?.cancel()
        notificationTickerJob?.cancel()
        executionWatchJobs.values.forEach(Job::cancel)
        executionWatchJobs.clear()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        updateWakeLock(false)
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
        val previous = notificationState
        val hasTimedActivity = activeTaskCount > 0 || pendingApprovalCount > 0
        val updated = ForegroundNotificationState(
            activeTaskCount = activeTaskCount,
            pendingApprovalCount = pendingApprovalCount,
            conversationId = conversationId,
            startedAtElapsedMillis = when {
                !hasTimedActivity -> null
                previous.startedAtElapsedMillis != null -> previous.startedAtElapsedMillis
                else -> SystemClock.elapsedRealtime()
            },
        )
        notificationState = updated
        publishNotification(updated)
    }

    /** 只刷新通知文字，不增加 Room 查询和 SSH 请求。 */
    private fun startNotificationTicker() {
        if (notificationTickerJob?.isActive == true) return
        notificationTickerJob = serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                val current = notificationState
                if (current.startedAtElapsedMillis != null) publishNotification(current)
            }
        }
    }

    private fun publishNotification(state: ForegroundNotificationState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(
        state: ForegroundNotificationState = notificationState,
    ): android.app.Notification {
        val elapsedText = state.startedAtElapsedMillis?.let { startedAt ->
            agentNotificationElapsedText(startedAt, SystemClock.elapsedRealtime())
        }

        val title = if (state.activeTaskCount > 0) {
            "Agent 运行中 · ${elapsedText ?: "0s"}"
        } else if (state.pendingApprovalCount > 0) {
            "Agent 待处理 · ${elapsedText ?: "0s"}"
        } else {
            getString(R.string.computer_connection_notification_title)
        }

        val text = if (state.activeTaskCount > 0) {
            "点击查看进度"
        } else {
            getString(R.string.computer_connection_notification_text)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (state.conversationId != null) {
                putExtra("conversationId", state.conversationId)
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
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

/** 远端终态必须先持久化 appendToolResult，再恢复模型 continueRun */
package com.android.everytalk.service

import com.android.everytalk.util.locale.appLanguageContext
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
import com.android.everytalk.data.network.AppToolExecutionResult
import com.android.everytalk.data.agent.AgentTerminalReasons
import com.android.everytalk.data.computer.ComputerException
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
        try {
            AgentRecoveryScheduler.schedule(appContext)
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_START),
            )
        } catch (error: Exception) {
            // 系统拒绝启动时还没有 Closeable 可供调用者释放，必须在这里撤销令牌。
            activeAgentRunTokens.remove(tokenId)
            throw error
        }
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
     * 只有进程内令牌或 Room AgentRun 至少存在一项时才启动前台服务。
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
            database.agentDao().getLocalActiveRuns().isNotEmpty()
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
    private val agentDao by lazy { database.agentDao() }
    private val agentRunStore by lazy { AgentRunStore(agentDao) }
    private val computerRepository by lazy { ComputerRepository(this) }
    private val previewManager by lazy { ComputerPreviewManager(computerRepository) }
    private val secretManager by lazy { ComputerWorkspaceSecretManager(computerRepository) }
    private val serviceToolExecutor by lazy {
        ComputerToolExecutor(
            context = this,
            repository = computerRepository,
            previewManager = previewManager,
            secretManager = secretManager,
        )
    }

    private val agentRunCoordinator by lazy {
        com.android.everytalk.data.agent.AgentRunCoordinator.shared(
            context = this,
            computerSessionStateProvider = { requestContext ->
                requestContext?.let { context ->
                    (context.selectedTargets.map { it.computerId to it.workspaceId }
                        .ifEmpty { listOf(context.computerId to context.workspaceId) })
                        .map { (computerId, workspaceId) ->
                            "服务器 ID=$computerId\n${computerRepository.getComputerSessionState(workspaceId)?.toPrompt().orEmpty()}"
                        }.joinToString("\n\n")
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
                    AppToolExecutionResult(JsonPrimitive("Agent 服务器上下文已丢失"))
                } else {
                    AppToolExecutionResult(
                        serviceToolExecutor.execute(toolName, arguments, toolCallId, requestContext, updateStatus),
                    )
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
                val interruptedRuns = agentDao.getLocalActiveRuns()
                agentDao.recoverInterruptedAgentRuns()
                interruptedRuns.forEach { run ->
                    AgentRecoveryDiagnostics.record(
                        run = run,
                        recoveryDecision = "PROCESS_DEATH_STATE_RECONCILED",
                        serviceStartReason = "SERVICE_CREATE",
                    )
                }
            }
        }
        startTaskMonitoringAfterRecovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    ComputerConnectionServiceController.stopAll()
                    agentRunCoordinator.cancelAllActiveRuns()
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
                    // 统一重新读取 Room 和进程内令牌，避免 STOP_IF_IDLE 与后续启动请求之间
                    // 发生竞态时只检查到旧快照，导致服务留下空闲前台通知。
                    reconcilePersistedTasks("idle_stop_request")
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
            // 监听器已经存在时，新的 ACTION_START/恢复请求不会重新触发 Flow 初始值。
            // 启动后主动对账一次，确保任务已结束时能立即移除前台服务通知。
            reconcilePersistedTasks("service_start")
        }
    }

    private fun startTaskMonitoring() {
        if (taskMonitorJob?.isActive == true) return
        taskMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    agentDao.observeServiceRuns()
                        .collect { activeRuns -> reconcileTaskSnapshot(activeRuns = activeRuns.filter { it.executionOwner == "LOCAL" }, source = "room_change") }
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
        // 可见消息已经结束时，旧 Run 不能继续阻止前台服务退出。
        // 只有没有进程内令牌时才执行这项清理，避免误碰真实运行中的 Agent。
        if (!ComputerConnectionServiceController.hasActiveTokens() &&
            ComputerConnectionServiceController.activeAgentRunCount() == 0
        ) {
            agentDao.cancelStaleVisibleMessageRuns(
                AgentTerminalReasons.VISIBLE_MESSAGE_TERMINAL,
                System.currentTimeMillis(),
            )
        }
        reconcileTaskSnapshot(activeRuns = agentDao.getLocalActiveRuns(), source = source)
    }

    /** Room、网络回调和安全检查共用同一入口，避免并发启动重复 Watch 或重复续写。 */
    private suspend fun reconcileTaskSnapshot(
        activeRuns: List<com.android.everytalk.data.database.entities.AgentRunEntity>,
        source: String,
    ) = taskReconcileMutex.withLock {
        val waitingApproval = activeRuns.filter { it.status == AgentRunStatus.WAITING_APPROVAL.name }
        val pendingContinuation = activeRuns.filter { it.status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name }
        val interruptedToolRuns = activeRuns.filter { it.status == AgentRunStatus.INTERRUPTED.name }
        val hasTokens = ComputerConnectionServiceController.hasActiveTokens()
        val activeAgentRunCount = ComputerConnectionServiceController.activeAgentRunCount()
        val hasActiveWork = activeRuns.isNotEmpty() || activeAgentRunCount > 0 || hasTokens
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
            activeTaskCount = maxOf(activeAgentRunCount, runningRuns),
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
        AppLogger.debug(
            "ComputerConnectionService",
            "Reconciled source=$source runs=${activeRuns.size}",
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recoveryJob?.cancel()
        taskMonitorJob?.cancel()
        safetyReconcileJob?.cancel()
        notificationTickerJob?.cancel()
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
        val languageContext = appLanguageContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                languageContext.getString(R.string.computer_connection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = languageContext.getString(R.string.computer_connection_channel_description)
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
        val languageContext = appLanguageContext()
        val elapsedText = state.startedAtElapsedMillis?.let { startedAt ->
            agentNotificationElapsedText(startedAt, SystemClock.elapsedRealtime())
        }

        val title = if (state.activeTaskCount > 0) {
            languageContext.getString(R.string.notification_agent_running, elapsedText ?: "0s")
        } else if (state.pendingApprovalCount > 0) {
            languageContext.getString(R.string.notification_agent_pending, elapsedText ?: "0s")
        } else {
            languageContext.getString(R.string.computer_connection_notification_title)
        }

        val text = if (state.activeTaskCount > 0) {
            languageContext.getString(R.string.notification_agent_view_progress)
        } else {
            languageContext.getString(R.string.computer_connection_notification_text)
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

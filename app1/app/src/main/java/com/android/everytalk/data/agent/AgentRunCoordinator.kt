package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.util.AgentNotificationManager
import com.android.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 独立的全局应用级/服务级 AgentRun 协调器。
 * 拥有独立于 Activity/ViewModel 生命周期的协程作用域，
 * 负责驱动 AgentLoop 运行与断线/续写恢复。
 */
class AgentRunCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val injectedAgentLoop: AgentLoop? = null,
    private val computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
) {
    companion object {
        @Volatile private var sharedInstance: AgentRunCoordinator? = null

        /** UI 与前台服务必须共用同一个协调器，避免同一 Run 被两套恢复循环重复驱动。 */
        fun shared(
            context: Context,
            injectedAgentLoop: AgentLoop? = null,
            computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
        ): AgentRunCoordinator = sharedInstance ?: synchronized(this) {
            sharedInstance ?: AgentRunCoordinator(
                context = context.applicationContext,
                injectedAgentLoop = injectedAgentLoop,
                computerSessionStateProvider = computerSessionStateProvider,
            ).also { sharedInstance = it }
        }
    }
    private val appContext = context.applicationContext
    private val database by lazy { AppDatabase.getDatabase(appContext) }
    private val agentDao by lazy { database.agentDao() }
    private val computerDao by lazy { database.computerDao() }
    private val agentRunStore by lazy { AgentRunStore(agentDao) }
    private val agentToolResultStore by lazy { AgentToolResultStore(appContext) }
    private val recoveryToolRuntime by lazy {
        AgentToolRuntime(
            executorProvider = AgentToolExecutorRegistry::current,
            resultStore = agentToolResultStore,
        )
    }
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val resumeMutex = Mutex()

    private val _events = MutableSharedFlow<Pair<String, AppStreamEvent>>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    private val agentLoop by lazy {
        injectedAgentLoop ?: AgentLoop(
            runStore = agentRunStore,
            toolRuntime = AgentToolRuntime(
                executorProvider = AgentToolExecutorRegistry::current,
                resultStore = agentToolResultStore,
            ),
            computerSessionStateProvider = computerSessionStateProvider,
        )
    }

    fun isRunActive(runId: String): Boolean = activeJobs["run:$runId"]?.isActive == true

    /**
     * AgentLoop 由应用级 scope 执行。页面停止收集事件只会断开 UI，不会取消任务。
     * 用户主动停止时由 cancelVisibleRun/cancelRun 明确取消对应 Job。
     */
    fun run(request: AgentLoopRequest): Flow<AppStreamEvent> = callbackFlow {
        val jobKey = request.existingRun?.id?.let { "run:$it" }
            ?: "message:${request.visibleAssistantMessageId}"
        val uiAttached = AtomicBoolean(true)
        val job = scope.launch {
            val foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
            try {
                agentLoop.run(request).collect { event ->
                    if (!uiAttached.get() || !trySend(event).isSuccess) {
                        _events.emit(request.visibleAssistantMessageId to event)
                    }
                }
            } finally {
                foregroundActivity.close()
                activeJobs.remove(jobKey)
                close()
            }
        }
        activeJobs[jobKey]?.cancel()
        activeJobs[jobKey] = job
        // UI Collector 消失时不取消 job；前台服务和 Room 继续持有任务事实。
        awaitClose { uiAttached.set(false) }
    }

    suspend fun resume(runId: String): Boolean {
        val run = agentDao.getRun(runId) ?: return false
        return resumeRun(run)
    }

    suspend fun resumeRun(run: AgentRunEntity): Boolean = resumeMutex.withLock {
        val jobKey = "run:${run.id}"
        if (activeJobs[jobKey]?.isActive == true) return false
        if (!AgentNotificationManager.canUseAgentNotifications(appContext)) {
            AppLogger.warn("AgentRunCoordinator", "Notification permission not available, skipping resume for run ${run.id}")
            return false
        }

        val configId = run.configIdSnapshot ?: return false
        val config = database.apiConfigDao().getTextConfig(configId)?.toApiConfig() ?: return false
        val chatRequest = agentRunStore.restoreChatRequest(run, config.key) ?: return false

        // 读取 VPS 最终 stdout/stderr，沿用 ComputerToolExecutor 的 Secret 过滤和输出截断，
        // 成功写入 ToolResult 后才声明 Execution 已接回。
        val unconsumed = computerDao.getUnconsumedCompletedExecutionsForRun(run.id)
        for (exec in unconsumed) {
            if (!persistRecoveredToolResult(run, exec, chatRequest.localComputerRequestContext)) return false
        }

        val limits = com.android.everytalk.data.DataClass.ModelTokenLimits(
            maxOutputTokens = chatRequest.generationConfig?.maxOutputTokens ?: 4096,
            maxContextTokens = chatRequest.contextManagement?.maxContextTokens
                ?: com.android.everytalk.data.DataClass.DEFAULT_MAX_CONTEXT_TOKENS,
        )

        val loopRequest = AgentLoopRequest(
            request = chatRequest,
            sessionId = run.sessionId,
            userMessageId = run.userMessageId,
            visibleAssistantMessageId = run.visibleAssistantMessageId,
            tokenLimits = limits,
            existingRun = run,
        )

        val job = scope.launch {
            val foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
            try {
                agentLoop.run(loopRequest).collect { event ->
                    _events.emit(run.visibleAssistantMessageId to event)
                }
            } catch (e: Exception) {
                AppLogger.error("AgentRunCoordinator", "Error executing resumed loop for run ${run.id}", e)
                agentRunStore.updateRunStatus(
                    run,
                    AgentRunStatus.MODEL_CONTINUATION_PENDING,
                    terminalReason = AgentTerminalReasons.MODEL_CONTINUATION_PENDING,
                )
            } finally {
                foregroundActivity.close()
                activeJobs.remove(jobKey)
            }
        }
        activeJobs[jobKey] = job
        return true
    }

    suspend fun resumePendingContinuationRuns(): Int {
        if (!AgentNotificationManager.canUseAgentNotifications(appContext)) return 0
        val pendingRuns = agentDao.getPendingModelContinuationRuns()
        var resumedCount = 0
        for (run in pendingRuns) {
            if (resumeRun(run)) {
                resumedCount++
            }
        }
        return resumedCount
    }

    fun cancelRun(runId: String, reason: String = "USER_CANCELLED") {
        activeJobs.remove("run:$runId")?.cancel()
        scope.launch {
            val run = agentDao.getRun(runId) ?: return@launch
            agentRunStore.updateRunStatus(
                run,
                AgentRunStatus.CANCELLED,
                terminalReason = reason,
            )
        }
    }

    /** 用户点击输入框停止时，取消当前可见回复对应的应用级 Agent Job。 */
    fun cancelVisibleRun(messageId: String) {
        activeJobs.remove("message:$messageId")?.cancel()
        scope.launch {
            agentDao.getRunByVisibleMessage(messageId)?.let { run ->
                activeJobs.remove("run:${run.id}")?.cancel()
            }
        }
    }

    /**
     * 先把结构化 Tool Result 写入 AgentEntry，再声明 Execution 已消费。
     * 崩溃发生在两步之间时，hasFinalToolResult 会阻止重复写入。
     */
    private suspend fun persistRecoveredToolResult(
        run: AgentRunEntity,
        execution: ComputerExecutionEntity,
        computerContext: ComputerRequestContext?,
    ): Boolean {
        if (!agentRunStore.hasFinalToolResult(run.id, execution.toolCallId)) {
            val startedEntry = agentDao.getEntries(run.id).lastOrNull { entry ->
                entry.toolCallId == execution.toolCallId &&
                    entry.kind == AgentEntryKind.TOOL_EXECUTION_STARTED.name
            } ?: return false
            val call = agentRunStore.findToolCall(run.id, execution.toolCallId) ?: return false
            val recovered = recoveryToolRuntime.execute(
                call = call,
                computerContext = computerContext,
                runId = run.id,
                emit = {},
            )
            agentRunStore.appendToolResult(
                runId = run.id,
                requestId = startedEntry.requestId ?: return false,
                result = recovered,
            )
        }
        computerDao.markResultAttached(execution.id)
        return true
    }
}

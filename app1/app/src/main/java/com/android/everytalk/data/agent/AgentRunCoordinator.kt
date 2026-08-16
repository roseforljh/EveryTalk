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
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillRuntimeTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val skillRepository by lazy { SkillRepository(appContext, database.skillDao()) }
    private val skillRuntimeTools by lazy { SkillRuntimeTools(skillRepository, agentRunStore) }
    private val recoveryToolRuntime by lazy {
        AgentToolRuntime(
            executorProvider = AgentToolExecutorRegistry::current,
            resultStore = agentToolResultStore,
            skillRuntimeTools = skillRuntimeTools,
        )
    }
    private val activeJobs = ConcurrentHashMap<String, Job>()
    /** 先于 Room Run 创建到达的停止请求，防止用户第一次点击落在登记空窗内。 */
    private val visibleRunCancellationReasons = ConcurrentHashMap<String, String>()
    private val recoveringRunIds = ConcurrentHashMap.newKeySet<String>()
    private val resumeRetryStates = ConcurrentHashMap<String, ResumeRetryState>()
    private val resumeMutex = Mutex()

    private val _events = MutableSharedFlow<Pair<String, AppStreamEvent>>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    private val agentLoop by lazy {
        injectedAgentLoop ?: AgentLoop(
            runStore = agentRunStore,
            toolRuntime = AgentToolRuntime(
                executorProvider = AgentToolExecutorRegistry::current,
                resultStore = agentToolResultStore,
                skillRuntimeTools = skillRuntimeTools,
            ),
            computerSessionStateProvider = computerSessionStateProvider,
        )
    }

    /**
     * 首次启动时 Run 还没创建，只能按可见消息 ID 登记 Job；恢复启动才按 Run ID 登记。
     * 两种登记都代表同一个 Run 正在执行，恢复器必须同时检查，避免重复驱动同一轮请求。
     */
    fun isRunActive(run: AgentRunEntity): Boolean = isAgentRunActive(activeJobs, run)

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
                notifyTerminalRun(agentDao.getRunByVisibleMessage(request.visibleAssistantMessageId))
            } finally {
                foregroundActivity.close()
                activeJobs.remove(jobKey)
                visibleRunCancellationReasons[request.visibleAssistantMessageId]?.let { reason ->
                    withContext(NonCancellable) {
                        agentRunStore.cancelActiveRunByVisibleMessage(
                            request.visibleAssistantMessageId,
                            reason,
                        )
                        visibleRunCancellationReasons.remove(request.visibleAssistantMessageId, reason)
                    }
                }
                close()
            }
        }
        activeJobs[jobKey]?.cancel()
        activeJobs[jobKey] = job
        visibleRunCancellationReasons[request.visibleAssistantMessageId]?.let { reason ->
            job.cancel(CancellationException(reason))
        }
        // UI Collector 消失时不取消 job；前台服务和 Room 继续持有任务事实。
        awaitClose { uiAttached.set(false) }
    }

    suspend fun resume(runId: String): Boolean {
        val run = agentDao.getRun(runId) ?: return false
        return resumeRun(run)
    }

    suspend fun resumeRun(run: AgentRunEntity): Boolean {
        if (!canAttemptResume(run.id) || !recoveringRunIds.add(run.id)) return false
        return try {
            resumeMutex.withLock { resumeRunLocked(run) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            recordResumeFailure(run.id)
            AppLogger.warn("AgentRunCoordinator", "Resume failed for run ${run.id}: ${exception.message}")
            false
        } finally {
            recoveringRunIds.remove(run.id)
        }
    }

    private suspend fun resumeRunLocked(run: AgentRunEntity): Boolean {
        val jobKey = "run:${run.id}"
        if (isRunActive(run)) return false
        if (!AgentNotificationManager.canUseAgentNotifications(appContext)) {
            AppLogger.warn("AgentRunCoordinator", "Notification permission not available, skipping resume for run ${run.id}")
            return false
        }

        val configId = run.configIdSnapshot ?: return resumeFailed(run.id)
        val config = database.apiConfigDao().getTextConfig(configId)?.toApiConfig() ?: return resumeFailed(run.id)
        val chatRequest = agentRunStore.restoreChatRequest(run, config.key) ?: return resumeFailed(run.id)

        // 读取 VPS 最终 stdout/stderr，沿用 ComputerToolExecutor 的 Secret 过滤和输出截断，
        // 成功写入 ToolResult 后才声明 Execution 已接回。
        val unconsumed = computerDao.getUnconsumedCompletedExecutionsForRun(run.id)
        for (exec in unconsumed) {
            if (!persistRecoveredToolResult(run, exec, chatRequest.localComputerRequestContext)) {
                return resumeFailed(run.id)
            }
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
                notifyTerminalRun(agentDao.getRun(run.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordResumeFailure(run.id)
                AppLogger.error("AgentRunCoordinator", "Error executing resumed loop for run ${run.id}", e)
                agentRunStore.updateRunStatus(
                    run,
                    AgentRunStatus.MODEL_CONTINUATION_PENDING,
                    terminalReason = AgentTerminalReasons.MODEL_CONTINUATION_PENDING,
                )
            } finally {
                foregroundActivity.close()
                activeJobs.remove(jobKey)
                visibleRunCancellationReasons[run.visibleAssistantMessageId]?.let { reason ->
                    withContext(NonCancellable) {
                        agentRunStore.cancelActiveRunByVisibleMessage(run.visibleAssistantMessageId, reason)
                        visibleRunCancellationReasons.remove(run.visibleAssistantMessageId, reason)
                    }
                }
            }
        }
        activeJobs[jobKey] = job
        visibleRunCancellationReasons[run.visibleAssistantMessageId]?.let { reason ->
            job.cancel(CancellationException(reason))
        }
        resumeRetryStates.remove(run.id)
        return true
    }

    suspend fun resumePendingContinuationRuns(): Int {
        if (!AgentNotificationManager.canUseAgentNotifications(appContext)) return 0
        val pendingRuns = agentDao.getPendingModelContinuationRuns()
        var resumedCount = 0
        for (run in pendingRuns) {
            if (!canAttemptResume(run.id)) continue
            if (resumeRun(run)) {
                resumedCount++
            }
        }
        return resumedCount
    }

    fun cancelRun(runId: String, reason: String = "USER_CANCELLED") {
        resumeRetryStates.remove(runId)
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

    /** 用户第一次点击就登记停止；即使 Run 尚未写入 Room，稍后登记的 Job 也会立即取消。 */
    fun cancelVisibleRun(messageId: String, reason: String = AgentTerminalReasons.USER_STOP) {
        visibleRunCancellationReasons[messageId] = reason
        val messageJob = activeJobs.remove("message:$messageId")
        messageJob?.cancel(CancellationException(reason))
        scope.launch {
            messageJob?.join()
            agentRunStore.cancelActiveRunByVisibleMessage(messageId, reason)?.let { run ->
                resumeRetryStates.remove(run.id)
                activeJobs.remove("run:${run.id}")?.let { runJob ->
                    runJob.cancel(CancellationException(reason))
                    runJob.join()
                }
                visibleRunCancellationReasons.remove(messageId, reason)
            }
        }
    }

    private fun canAttemptResume(runId: String, now: Long = System.currentTimeMillis()): Boolean =
        resumeRetryStates[runId]?.nextAttemptAt?.let { now >= it } ?: true

    private fun resumeFailed(runId: String): Boolean {
        recordResumeFailure(runId)
        return false
    }

    /** 恢复失败保留原 Run，按上限 60 秒退避，避免前台服务每三秒重复读取同一份大快照。 */
    private fun recordResumeFailure(runId: String) {
        val now = System.currentTimeMillis()
        resumeRetryStates.compute(runId) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            ResumeRetryState(
                failures = failures,
                nextAttemptAt = now + agentResumeRetryDelayMillis(failures),
            )
        }
    }

    /**
     * 通知粒度跟随整个 AgentRun。普通聊天没有 ComputerExecution，不发送系统通知。
     * 通知失败不能反向改坏已经落库的任务终态。
     */
    private suspend fun notifyTerminalRun(run: AgentRunEntity?) {
        if (run == null) return
        try {
            val status = AgentRunStatus.valueOf(run.status)
            val executionCount = computerDao.countExecutionsForAgentRun(run.id)
            if (!shouldNotifyAgentRunTerminal(status, executionCount)) return
            AgentNotificationManager.notifyAgentRunTerminal(
                context = appContext,
                conversationId = run.sessionId,
                runId = run.id,
                status = status,
            )
        } catch (error: Exception) {
            AppLogger.warn(
                "AgentRunCoordinator",
                "Unable to publish terminal notification for run ${run.id}: ${error.message}",
            )
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

private data class ResumeRetryState(
    val failures: Int,
    val nextAttemptAt: Long,
)

/** 第一次失败等 2 秒，之后逐步放缓，最长一分钟。 */
internal fun agentResumeRetryDelayMillis(failures: Int): Long = when (failures.coerceAtLeast(1)) {
    1 -> 2_000L
    2 -> 5_000L
    3 -> 15_000L
    4 -> 30_000L
    else -> 60_000L
}

/** 同时识别首次启动的 message 键和恢复启动的 run 键。 */
internal fun isAgentRunActive(
    activeJobs: Map<String, Job>,
    run: AgentRunEntity,
): Boolean = activeJobs["run:${run.id}"]?.isActive == true ||
    activeJobs["message:${run.visibleAssistantMessageId}"]?.isActive == true

/** 只有实际使用过 VPS 且整个 Run 已结束时，才允许发送最终通知。 */
internal fun shouldNotifyAgentRunTerminal(
    status: AgentRunStatus,
    computerExecutionCount: Int,
): Boolean = computerExecutionCount > 0 && status in setOf(
    AgentRunStatus.COMPLETED,
    AgentRunStatus.FAILED,
    AgentRunStatus.CANCELLED,
)

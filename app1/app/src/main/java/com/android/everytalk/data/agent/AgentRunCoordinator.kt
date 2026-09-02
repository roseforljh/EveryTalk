package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.toApiText
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolRequestHasher
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.buildDirectMultimodalRequest
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.util.AgentNotificationManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillRuntimeTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 消费队列时才读取附件文件并生成多模态消息，Room 永远只保存稳定文件引用。 */
internal suspend fun materializeAndroidQueuedMessage(
    context: Context,
    instruction: AgentSteeringInstruction,
    request: com.android.everytalk.data.DataClass.ChatRequest,
): AbstractApiMessage {
    val baseMessage = SimpleTextApiMessage(
        id = "queued:${instruction.id}",
        role = "user",
        content = instruction.contentParts.toApiText(instruction.content),
    )
    return buildDirectMultimodalRequest(
        request = request.copy(messages = listOf(baseMessage)),
        attachments = instruction.attachments,
        context = context,
    ).messages.single()
}

/**
 * 独立的全局应用级/服务级 AgentRun 协调器。
 * 拥有独立于 Activity/ViewModel 生命周期的协程作用域，
 * 负责驱动 AgentLoop 运行与断线/续写恢复。
 */
class AgentRunCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
    private val pauseController: AgentRunPauseController = AgentRunPauseController(),
) {
    companion object {
        @Volatile private var sharedInstance: AgentRunCoordinator? = null

        /** UI 与前台服务必须共用同一个协调器，避免同一 Run 被两套恢复循环重复驱动。 */
        fun shared(
            context: Context,
            computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
        ): AgentRunCoordinator = sharedInstance ?: synchronized(this) {
            sharedInstance ?: AgentRunCoordinator(
                context = context.applicationContext,
                computerSessionStateProvider = computerSessionStateProvider,
            ).also { sharedInstance = it }
        }
    }
    private val appContext = context.applicationContext
    private val database by lazy { AppDatabase.getDatabase(appContext) }
    private val agentDao by lazy { database.agentDao() }
    private val computerDao by lazy { database.computerDao() }
    private val agentRunStore by lazy {
        AgentRunStore(
            dao = agentDao,
            queuedMessageMaterializer = { instruction, request ->
                materializeAndroidQueuedMessage(appContext, instruction, request)
            },
            skillReferenceValidator = { references ->
                skillRepository.createSnapshot(references).manualReferences
            },
        )
    }
    private val interventionStore by lazy { AgentInterventionStore(agentDao) }
    private val interventionRecovery by lazy { AgentInterventionRecovery(agentDao, interventionStore) }
    private val interventionBroker by lazy {
        AgentInterventionBroker(interventionStore) { ticket ->
            val suspension = ticket.suspension
            _pendingInterventions.value = (
                _pendingInterventions.value.filterNot { it.suspensionId == suspension.id } +
                    PendingIntervention(
                        suspensionId = suspension.id,
                        runId = suspension.runId,
                        capabilityId = suspension.capabilityId,
                        state = SuspensionState.valueOf(suspension.status),
                        resolutionNonce = ticket.resolutionNonce,
                    )
                )
        }
    }
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
    private val _pendingInterventions = MutableStateFlow<List<PendingIntervention>>(emptyList())
    val pendingInterventions = _pendingInterventions.asStateFlow()

    private val agentLoop by lazy {
        AgentLoop(
            runStore = agentRunStore,
            toolRuntime = AgentToolRuntime(
                executorProvider = AgentToolExecutorRegistry::current,
                resultStore = agentToolResultStore,
                skillRuntimeTools = skillRuntimeTools,
            ),
            computerSessionStateProvider = computerSessionStateProvider,
            pauseController = pauseController,
            interventionBroker = interventionBroker,
        )
    }

    /** UI 只读投影；真实状态由应用级 pauseController 持有。 */
    val runControlSnapshots: StateFlow<Map<String, AgentRunControlSnapshot>> = pauseController.snapshots

    fun requestPause(visibleAssistantMessageId: String): Boolean =
        pauseController.requestPause(visibleAssistantMessageId)

    fun resumePausedRun(visibleAssistantMessageId: String): Boolean =
        pauseController.resume(visibleAssistantMessageId)

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
        pauseController.register(request.visibleAssistantMessageId)
        val uiAttached = AtomicBoolean(true)
        val job = scope.launch {
            val foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
            try {
                agentLoop.run(request).collect { event ->
                    if (!uiAttached.get()) {
                        _events.emit(request.visibleAssistantMessageId to event)
                    } else {
                        try {
                            // 同一次运行只走前台通道，并通过挂起发送保持严格顺序。
                            // 禁止在通道繁忙时把单个事件改送全局通道，否则 Finish 可能越过正文 delta。
                            send(event)
                        } catch (error: CancellationException) {
                            // 下游页面取消收集也会关闭 callbackFlow。Agent 自身仍活跃时转交应用级通道；
                            // 用户真正取消 Agent 时必须继续抛出，不能把已取消任务偷偷恢复。
                            if (!currentCoroutineContext().isActive) throw error
                            _events.emit(request.visibleAssistantMessageId to event)
                        } catch (_: ClosedSendChannelException) {
                            // 页面恰好在发送期间离开时，后续事件交给应用级收集器继续处理。
                            _events.emit(request.visibleAssistantMessageId to event)
                        }
                    }
                }
                val completedRun = agentDao.getRunByVisibleMessage(request.visibleAssistantMessageId)
                updateResumeRetryAfterRun(completedRun)
                notifyTerminalRun(completedRun)
            } finally {
                foregroundActivity.close()
                activeJobs.remove(jobKey)
                pauseController.finish(request.visibleAssistantMessageId)
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

    suspend fun resumeRun(
        run: AgentRunEntity,
        approvalDecision: AgentApprovalRecord? = null,
    ): Boolean {
        if (!canAttemptResume(run.id) || !recoveringRunIds.add(run.id)) return false
        return try {
            resumeMutex.withLock { resumeRunLocked(run, approvalDecision) }
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

    /**
     * 正式 steering API。只把指令写入当前 Run 的 steering queue，不取消当前模型或工具 Job。
     * AgentLoop 在工具结果落库后的下一个模型边界消费它。
     */
    suspend fun steer(
        sessionId: String,
        steeringId: String,
        content: String,
        contentParts: List<MessageContentPart> = emptyList(),
        attachments: List<SelectedMediaItem> = emptyList(),
    ): Boolean {
        if ((content.isBlank() && contentParts.isEmpty() && attachments.isEmpty()) || steeringId.isBlank()) return false
        val run = agentDao.getLatestSteerableRun(sessionId) ?: return false
        return agentRunStore.enqueueSteering(
            runId = run.id,
            instruction = AgentSteeringInstruction(
                id = steeringId,
                content = content,
                contentParts = contentParts,
                attachments = attachments,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun resumeRunLocked(
        run: AgentRunEntity,
        approvalDecision: AgentApprovalRecord?,
    ): Boolean {
        val jobKey = "run:${run.id}"
        if (isRunActive(run)) return false

        val configId = run.configIdSnapshot ?: return resumeFailed(run.id)
        val config = database.apiConfigDao().getTextConfig(configId)?.toApiConfig() ?: return resumeFailed(run.id)
        val chatRequest = agentRunStore.restoreChatRequest(run, config.key) ?: return resumeFailed(run.id)
        AgentRecoveryDiagnostics.record(
            run = run,
            recoveryDecision = "RESUME_AGENT_LOOP",
            serviceStartReason = "COORDINATOR",
            requestId = agentRunStore.latestInterruptedAgentRequest(run.id)?.id,
            providerProtocol = config.channel,
        )

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
            approvalDecision = approvalDecision,
        )

        pauseController.register(run.visibleAssistantMessageId)
        val job = scope.launch {
            val foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
            try {
                agentLoop.run(loopRequest).collect { event ->
                    _events.emit(run.visibleAssistantMessageId to event)
                }
                val completedRun = agentDao.getRun(run.id)
                updateResumeRetryAfterRun(completedRun)
                notifyTerminalRun(completedRun)
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
                pauseController.finish(run.visibleAssistantMessageId)
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
        return true
    }

    suspend fun resumePendingContinuationRuns(): Int {
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

    /** App 启动时显式扫描全部非终态 Suspension，包括 RESOLUTION_RECEIVED 与 DELIVERED。 */
    suspend fun recoverInterventions(): List<AgentInterventionRecovery.RecoveryAction> {
        val actions = interventionRecovery.recover()
        val nonceById = _pendingInterventions.value.associate { it.suspensionId to it.resolutionNonce }.toMutableMap()
        actions.forEach { action ->
            if (action.newResolutionNonce != null) nonceById[action.suspensionId] = action.newResolutionNonce
        }
        _pendingInterventions.value = interventionStore.startupCandidates()
            .filter {
                it.status in setOf(
                    SuspensionState.WAITING_USER.name,
                    SuspensionState.WAITING_USER_REENTRY.name,
                    SuspensionState.USER_DECISION_REQUIRED.name,
                )
            }
            .map { suspension ->
                PendingIntervention(
                    suspensionId = suspension.id,
                    runId = suspension.runId,
                    capabilityId = suspension.capabilityId,
                    state = SuspensionState.valueOf(suspension.status),
                    resolutionNonce = nonceById[suspension.id],
                )
            }
        return actions
    }

    /** 只消费当前 Suspension 的一次性 resolution nonce；真正 Secret 仍由后续 Adapter 接管。 */
    suspend fun resolveIntervention(suspensionId: String, expectedVersion: Long, resolutionNonce: String): Boolean {
        val resolved = interventionBroker.resolve(suspensionId, expectedVersion, resolutionNonce)
        if (resolved) recoverInterventions()
        return resolved
    }

    /**
     * 恢复工具结果落库窗口中的 Run。
     * AgentRunStore 会先检查最终 ToolResult 和原 ComputerExecution：结果已存在就直接续写，
     * 远端仍在运行就继续等待，执行事实缺失且可能有副作用时改为等待用户确认。
     */
    suspend fun resumeInterruptedToolRuns(): Int {
        var resumedCount = 0
        agentRunStore.resumableApprovalRuns(computerDao)
            .filter { (run, _) -> run.status == AgentRunStatus.INTERRUPTED.name }
            .forEach { (run, record) ->
                if (resumeRun(run, record)) resumedCount++
            }
        return resumedCount
    }

    fun cancelRun(runId: String, reason: String = "USER_CANCELLED") {
        resumeRetryStates.remove(runId)
        scope.launch {
            val run = agentDao.getRun(runId) ?: return@launch
            agentRunStore.updateRunStatus(
                run,
                AgentRunStatus.CANCELLED,
                terminalReason = reason,
            )
            activeJobs.remove("run:$runId")?.cancel(CancellationException(reason))
        }
    }

    /** 全局停止必须等待 Job 收尾，再统一封存 Room，防止服务消失后模型流继续写入。 */
    suspend fun cancelAllActiveRuns(reason: String = AgentTerminalReasons.USER_STOP) {
        val jobs = activeJobs.values.distinct()
        activeJobs.clear()
        // 先让持久 generation 失效，再停止内存 Job；外部 callback/claim 不能穿过终止窗口。
        agentDao.getActiveRuns().forEach { run ->
            agentRunStore.cancelOpenRequests(run.id, reason)
            agentRunStore.updateRunStatus(
                run = run,
                status = AgentRunStatus.CANCELLED,
                terminalReason = reason,
            )
        }
        jobs.forEach { job -> job.cancel(CancellationException(reason)) }
        jobs.forEach { job -> job.join() }
        visibleRunCancellationReasons.clear()
        recoveringRunIds.clear()
        resumeRetryStates.clear()
    }

    /** 用户第一次点击就登记停止；即使 Run 尚未写入 Room，稍后登记的 Job 也会立即取消。 */
    fun cancelVisibleRun(messageId: String, reason: String = AgentTerminalReasons.USER_STOP) {
        visibleRunCancellationReasons[messageId] = reason
        val messageJob = activeJobs.remove("message:$messageId")
        messageJob?.cancel(CancellationException(reason))
        scope.launch {
            // 已存在的 Run 先终止持久状态，随后再等待执行协程清理。
            agentRunStore.cancelActiveRunByVisibleMessage(messageId, reason)?.let { run ->
                resumeRetryStates.remove(run.id)
                activeJobs.remove("run:${run.id}")?.let { runJob ->
                    runJob.cancel(CancellationException(reason))
                    runJob.join()
                }
                visibleRunCancellationReasons.remove(messageId, reason)
            }
            messageJob?.join()
        }
    }

    private fun canAttemptResume(runId: String, now: Long = System.currentTimeMillis()): Boolean =
        resumeRetryStates[runId]?.nextAttemptAt?.let { now >= it } ?: true

    private fun resumeFailed(runId: String): Boolean {
        recordResumeFailure(runId)
        return false
    }

    /**
     * Provider 返回临时错误时 AgentLoop 已把 Run 持久化为待续写。
     * 这里保留失败次数并退避；正常完成、审批暂停或永久失败都会清掉旧退避状态。
     */
    private fun updateResumeRetryAfterRun(run: AgentRunEntity?) {
        val runId = run?.id ?: return
        if (shouldBackoffAgentResume(run.status)) {
            recordResumeFailure(runId)
        } else {
            resumeRetryStates.remove(runId)
        }
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
        val context = computerContext ?: return false
        val startedEntry = agentDao.getEntries(run.id).lastOrNull { entry ->
            entry.kind == AgentEntryKind.TOOL_EXECUTION_STARTED.name &&
                entry.toolCallId?.let { rawToolCallId ->
                    runCatching { ComputerToolRequestHasher.toolCallKey(rawToolCallId, context) }.getOrNull()
                } == execution.toolCallId
        } ?: return false
        val requestId = startedEntry.requestId ?: return false
        val rawToolCallId = startedEntry.toolCallId ?: return false
        if (agentRunStore.finalToolResult(run.id, requestId, rawToolCallId) == null) {
            val call = agentRunStore.findToolCall(run.id, rawToolCallId, requestId) ?: return false
            val recovered = recoveryToolRuntime.execute(
                call = call,
                computerContext = context,
                runId = run.id,
                emit = {},
            )
            agentRunStore.appendToolResult(
                runId = run.id,
                requestId = requestId,
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

/** 只有可恢复的模型中断参与退避，审批等待和终态都不能继承旧失败次数。 */
internal fun shouldBackoffAgentResume(status: String?): Boolean =
    status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name

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

package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.toApiConfig
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立的全局应用级/服务级 AgentRun 协调器。
 * 拥有独立于 Activity/ViewModel 生命周期的协程作用域，
 * 负责驱动 AgentLoop 运行与断线/续写恢复。
 */
class AgentRunCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val database by lazy { AppDatabase.getDatabase(appContext) }
    private val agentDao by lazy { database.agentDao() }
    private val computerDao by lazy { database.computerDao() }
    private val agentRunStore by lazy { AgentRunStore(agentDao) }
    private val agentToolResultStore by lazy { AgentToolResultStore(appContext) }
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val resumeMutex = Mutex()

    private val _events = MutableSharedFlow<Pair<String, AppStreamEvent>>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    private val agentLoop by lazy {
        AgentLoop(
            runStore = agentRunStore,
            toolRuntime = AgentToolRuntime(
                executorProvider = AgentToolExecutorRegistry::current,
                resultStore = agentToolResultStore,
            ),
        )
    }

    fun isRunActive(runId: String): Boolean = activeJobs[runId]?.isActive == true

    fun run(request: AgentLoopRequest): Flow<AppStreamEvent> = flow {
        // 只有真实收集 Agent 流时才登记。结束、失败或取消都会在 finally 释放，
        // 避免 Room 中残留的中间状态长期显示为“Agent 运行中”。
        val foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
        try {
            emitAll(agentLoop.run(request))
        } finally {
            foregroundActivity.close()
        }
    }

    suspend fun resume(runId: String): Boolean = resumeMutex.withLock {
        if (activeJobs[runId]?.isActive == true) return false
        val run = agentDao.getRun(runId) ?: return false
        return resumeRun(run)
    }

    suspend fun resumeRun(run: AgentRunEntity): Boolean = resumeMutex.withLock {
        if (activeJobs[run.id]?.isActive == true) return false
        if (!AgentNotificationManager.canUseAgentNotifications(appContext)) {
            AppLogger.warn("AgentRunCoordinator", "Notification permission not available, skipping resume for run ${run.id}")
            return false
        }

        // 先持久化未消费的 ToolResult
        val unconsumed = computerDao.getUnconsumedCompletedExecutionsForRun(run.id)
        for (exec in unconsumed) {
            computerDao.markResultAttached(exec.id)
            agentToolResultStore.appendToolResult(exec.toolCallId, exec.safeSummary ?: "")
        }

        val configId = run.configIdSnapshot ?: return false
        val config = database.apiConfigDao().getTextConfig(configId)?.toApiConfig() ?: return false
        val chatRequest = agentRunStore.restoreChatRequest(run, config.key) ?: return false

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
            try {
                run(loopRequest).collect { event ->
                    _events.emit(run.id to event)
                }
            } catch (e: Exception) {
                AppLogger.error("AgentRunCoordinator", "Error executing resumed loop for run ${run.id}", e)
                agentRunStore.updateRunStatus(
                    run,
                    AgentRunStatus.MODEL_CONTINUATION_PENDING,
                    terminalReason = AgentTerminalReasons.MODEL_CONTINUATION_PENDING,
                )
            } finally {
                activeJobs.remove(run.id)
            }
        }
        activeJobs[run.id] = job
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
        activeJobs[runId]?.cancel()
        activeJobs.remove(runId)
        scope.launch {
            val run = agentDao.getRun(runId) ?: return@launch
            agentRunStore.updateRunStatus(
                run,
                AgentRunStatus.CANCELLED,
                terminalReason = reason,
            )
        }
    }
}

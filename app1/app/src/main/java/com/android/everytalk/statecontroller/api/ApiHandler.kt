/** 远端终态必须先持久化 appendToolResult，再恢复模型 continueRun */
package com.android.everytalk.statecontroller

import com.android.everytalk.data.agent.AgentTerminalReasons

import com.android.everytalk.util.AgentNotificationManager.canUseAgentNotifications

import android.content.Context
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.ui.components.toRecoveredMarkdown
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.agent.AgentLoop
import com.android.everytalk.data.agent.AgentLoopRequest
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.agent.AgentApprovalDecision
import com.android.everytalk.data.agent.AgentApprovalRecord
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentToolResultStore
import com.android.everytalk.data.computer.PendingComputerToolApproval
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.DataClass.resolvedModelTokenLimits
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.NetworkUtils
import com.android.everytalk.data.network.extractThinkTagContent
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.models.SelectedMediaItem.Audio
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.PromptLeakGuard
import com.android.everytalk.util.debug.PerformanceMonitor
import com.android.everytalk.util.messageprocessor.MessageProcessor
import com.android.everytalk.util.text.TextSanitizer
import com.android.everytalk.util.image.toGeneratedImageMessage
import com.android.everytalk.util.locale.localizeUiMessage
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap


internal fun shouldReturnEarlyForNetworkRetry(
    allowRetry: Boolean,
    isNetworkError: Boolean,
    currentRetryCount: Int,
    maxRetryAttempts: Int,
    hasRetryAction: Boolean,
): Boolean {
    return allowRetry &&
        isNetworkError &&
        currentRetryCount < maxRetryAttempts &&
        hasRetryAction
}

internal fun reconcileMessageAfterStatusClear(updatedMessage: Message, clearedMessage: Message): Message {
    return updatedMessage.copy(
        currentWebSearchStage = clearedMessage.currentWebSearchStage,
        executionStatus = clearedMessage.executionStatus,
    )
}

internal fun mergeStreamingCompletionMessage(syncedMessage: Message, finalizedMessage: Message): Message {
    val syncedThinkExtraction = extractThinkTagContent(syncedMessage.text)
    val mergedText = if (syncedThinkExtraction.changed) finalizedMessage.text else syncedMessage.text
    val finalizedPartsMatchMergedText = finalizedMessage.parts.isNotEmpty() &&
        finalizedMessage.parts.toRecoveredMarkdown() == mergedText
    val mergedParts = when {
        finalizedMessage.text == mergedText -> finalizedMessage.parts.ifEmpty { syncedMessage.parts }
        finalizedPartsMatchMergedText -> finalizedMessage.parts
        syncedMessage.parts.isNotEmpty() -> syncedMessage.parts
        mergedText.isNotBlank() -> listOf(MarkdownPart.Text(id = "text_0", content = mergedText))
        else -> emptyList()
    }
    return syncedMessage.copy(
        text = mergedText,
        reasoning = listOfNotNull(syncedMessage.reasoning, finalizedMessage.reasoning)
            .filter { it.isNotBlank() }
            .maxByOrNull { it.length },
        parts = mergedParts,
        webSearchResults = finalizedMessage.webSearchResults
            ?.takeIf { it.isNotEmpty() }
            ?: syncedMessage.webSearchResults,
        contentStarted = true,
    )
}

internal fun mergeWebSearchResults(
    existing: List<com.android.everytalk.data.DataClass.WebSearchResult>?,
    incoming: List<com.android.everytalk.data.DataClass.WebSearchResult>,
): List<com.android.everytalk.data.DataClass.WebSearchResult> {
    return (existing.orEmpty() + incoming)
        .filter { it.href.isNotBlank() }
        .distinctBy { it.href }
        .mapIndexed { index, result -> result.copy(index = index + 1) }
}

internal fun applyReasoningChunk(currentMessage: Message, reasoningChunk: String): Message {
    if (reasoningChunk.isBlank()) return currentMessage
    return if (currentMessage.reasoning.isNullOrBlank()) {
        currentMessage.copy(reasoning = reasoningChunk)
    } else {
        currentMessage
    }
}

internal fun applyGeneratedImageToMessage(
    message: Message,
    persistedSource: String,
): Message {
    val normalizedSource = persistedSource.trim().replace('\\', '/')
    if (normalizedSource.isBlank()) return message

    val existingUrls = message.imageUrls.orEmpty()
    if (existingUrls.any { it.trim().replace('\\', '/') == normalizedSource }) return message

    val markdown = "![Generated Image]($normalizedSource)"
    val updatedText = when {
        message.text.isBlank() -> markdown
        else -> message.text.trimEnd() + "\n\n" + markdown
    }
    return message.copy(
        text = updatedText,
        imageUrls = existingUrls + normalizedSource,
        contentStarted = true,
    )
}

internal fun addAiMessageAfterUserMessage(
    messageList: MutableList<Message>,
    newAiMessage: Message,
    afterUserMessageId: String?,
): Int {
    val insertIndex = afterUserMessageId
        ?.let { id -> messageList.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?.plus(1)

    return if (insertIndex != null && insertIndex <= messageList.size) {
        messageList.add(insertIndex, newAiMessage)
        insertIndex
    } else {
        messageList.add(newAiMessage)
        messageList.lastIndex
    }
}

internal fun updateMessageContextUsageSnapshot(
    messageList: MutableList<Message>,
    messageId: String,
    snapshot: ContextUsageSnapshot,
): Boolean {
    val index = messageList.indexOfFirst { it.id == messageId }
    if (index < 0) return false
    messageList[index] = messageList[index].copy(
        contextUsageSnapshot = snapshot.copy(messageId = messageId),
    )
    return true
}

internal fun updateMessageContextCompressionState(
    messageList: MutableList<Message>,
    messageId: String,
    state: ContextCompressionState,
): Boolean {
    val index = messageList.indexOfFirst { it.id == messageId }
    if (index < 0) return false
    messageList[index] = messageList[index].copy(contextCompressionState = state)
    return true
}

internal fun updatePreparedMessageStatus(
    messageList: MutableList<Message>,
    messageId: String,
    status: String?,
): Boolean {
    val index = messageList.indexOfFirst { it.id == messageId }
    if (index < 0) return false
    messageList[index] = messageList[index].copy(executionStatus = status)
    return true
}

internal fun markPreparedMessageFailed(
    messageList: MutableList<Message>,
    messageId: String,
    errorText: String,
): Boolean {
    val index = messageList.indexOfFirst { it.id == messageId }
    if (index < 0) return false
    messageList[index] = messageList[index].copy(
        text = errorText,
        contentStarted = false,
        isError = true,
        executionStatus = errorText,
    )
    return true
}

class ApiHandler(
    context: Context,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit,
    private val triggerScrollToBottom: () -> Unit,
    private val cancelComputerExecutions: (String, (Boolean) -> Unit) -> Job = { _, onComplete ->
        onComplete(true)
        Job()
    },
    private val computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
) {
    private val context = context.applicationContext
    private val logger = AppLogger.forComponent("ApiHandler")
    private val jsonParserForError = Json { ignoreUnknownKeys = true }
    // 为每个会话创建独立的MessageProcessor实例，确保会话隔离
    private val messageProcessorMap = ConcurrentHashMap<String, MessageProcessor>()
    private val processedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val generatedImageSourceFingerprints = ConcurrentHashMap<String, MutableSet<String>>()
    private val agentRunStore by lazy { AgentRunStore(AppDatabase.getDatabase(context).agentDao()) }
    private val _pendingAgentApprovals = MutableStateFlow<List<PendingComputerToolApproval>>(emptyList())
    val pendingAgentApprovals: StateFlow<List<PendingComputerToolApproval>> = _pendingAgentApprovals.asStateFlow()
    private val agentResumeMutex = Mutex()
    private val resumingAgentRunIds = ConcurrentHashMap.newKeySet<String>()
    private val agentRunCoordinator by lazy { com.android.everytalk.data.agent.AgentRunCoordinator(context) }
    private val agentLoop by lazy {
        AgentLoop(
            runStore = agentRunStore,
            toolRuntime = com.android.everytalk.data.agent.AgentToolRuntime(
                executorProvider = com.android.everytalk.data.agent.AgentToolExecutorRegistry::current,
                resultStore = AgentToolResultStore(context),
            ),
            computerSessionStateProvider = computerSessionStateProvider,
        )
    }
    
    // 🛡️ 防 prompt 泄露：为每个消息创建独立的流式检测器
    private val promptLeakDetectors = ConcurrentHashMap<String, PromptLeakGuard.StreamingDetector>()

    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"
    private val retryCountMap = ConcurrentHashMap<String, Int>()

    /** ViewModel 初始化或前台服务对账完成后从 Room 恢复待续写及待审批的 Run。 */
    suspend fun restorePendingAgentApproval(resumeDecided: Boolean = true) {
        if (resumeDecided) {
            agentRunStore.recoverUnknownComputerExecutions(AppDatabase.getDatabase(context).computerDao())
            // 自动恢复扫描可能新建 UNKNOWN 卡片，先完成扫描再投影，避免卡片延迟到下次刷新。
            resumeDecidedAgentRuns()
            resumePendingContinuationRuns()
        }
        refreshPendingAgentApprovals()
    }

    /** 扫描并调度 MODEL_CONTINUATION_PENDING 状态的 AgentRun 进行模型续写。 */
    suspend fun dispatchPendingContinuationRuns() {
        // 模型恢复前必须检查通知门槛，未开启通知权限时暂停模型续写并保留 MODEL_CONTINUATION_PENDING 状态
        if (!canUseAgentNotifications(context)) {
            return
        }
        val computerDao = AppDatabase.getDatabase(context).computerDao()
        val pendingRuns = agentRunStore.getPendingModelContinuationRuns()
        for (run in pendingRuns) {
            if (stateHolder.textApiJob?.isActive == true) break
            // 远端终态必须先持久化 ToolResult，再进行模型续写恢复 (continueRun / resume)
            val unconsumed = computerDao.getUnconsumedCompletedExecutionsForRun(run.id)
            for (exec in unconsumed) {
                computerDao.markResultAttached(exec.id)
                AgentToolResultStore(context).appendToolResult(exec.toolCallId, exec.safeSummary ?: "")
            }
            if (resumingAgentRunIds.add(run.id)) {
                try {
                    agentResumeMutex.withLock {
                        if (stateHolder.textApiJob?.isActive != true) {
                            // 恢复原 AgentRun 进行模型续写 (resume / continueRun)
                            startResumedAgentRun(run.id, record = null)
                        }
                    }
                } catch (e: Exception) {
                    // 续写异常必须重新保存 MODEL_CONTINUATION_PENDING，禁止把 Run 标记为 FAILED
                    agentRunStore.updateRunStatus(run, AgentRunStatus.MODEL_CONTINUATION_PENDING)
                } finally {
                    resumingAgentRunIds.remove(run.id)
                }
            }
        }
    }

    suspend fun resumePendingContinuationRuns() {
        dispatchPendingContinuationRuns()
    }

    /** 将远端恢复阶段投影到原 Assistant 消息，避免恢复时看起来像卡住。 */
    suspend fun updateRemoteRecoveryStatus(
        status: String?,
        conversationIds: Set<String> = emptySet(),
    ) {
        val candidates = (
            agentRunStore.getWaitingRemoteExecutionRuns() +
                agentRunStore.getInterruptedRuns()
            ).distinctBy { it.id }
            .filter { run -> conversationIds.isEmpty() || run.sessionId in conversationIds }
        val runs = if (status == null) {
            candidates
        } else {
            val computerDao = AppDatabase.getDatabase(context).computerDao()
            candidates.filter { run ->
                computerDao.getActiveForegroundRemoteExecutionsForConversation(run.sessionId).isNotEmpty()
            }
        }
        withContext(Dispatchers.Main.immediate) {
            runs.forEach { run ->
                updatePreparedMessageStatus(stateHolder.messages, run.visibleAssistantMessageId, status)
            }
        }
    }

    private suspend fun refreshPendingAgentApprovals() {
        _pendingAgentApprovals.value = agentRunStore.getWaitingApprovalRuns().mapNotNull { run ->
            agentRunStore.pendingApproval(run.id)?.let { record ->
                record.request?.let { request ->
                    PendingComputerToolApproval(run.id, record.approvalRequestId, request)
                }
            }
        }
    }

    /** 决定已经落库时无需再次显示卡片；恢复同一 Run 并由 Tool 幂等记录兜底。 */
    private suspend fun resumeDecidedAgentRuns() {
        val computerDao = AppDatabase.getDatabase(context).computerDao()
        for ((run, record) in agentRunStore.resumableApprovalRuns(computerDao)) {
            var started = false
            if (resumingAgentRunIds.add(run.id)) {
                try {
                    started = resumeAgentRun(run.id, record)
                } finally {
                    resumingAgentRunIds.remove(run.id)
                }
            }
            if (started || stateHolder.textApiJob?.isActive == true) break
        }
    }

    fun respondToAgentApproval(
        runId: String,
        approvalRequestId: String,
        decision: AgentApprovalDecision,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _pendingAgentApprovals.value.firstOrNull {
                it.runId == runId && it.approvalRequestId == approvalRequestId
            } ?: return@launch
            val record = agentRunStore.decideApproval(runId, approvalRequestId, decision) ?: return@launch
            _pendingAgentApprovals.value = _pendingAgentApprovals.value - current
            var started = false
            if (resumingAgentRunIds.add(runId)) {
                try {
                    started = resumeAgentRun(runId, record)
                } finally {
                    resumingAgentRunIds.remove(runId)
                }
            }
            // 当前已有文本任务时决定仍保留在 Room；任务结束后统一扫描并串行续接。
            if (started || stateHolder.textApiJob?.isActive == true) return@launch
            resumeDecidedAgentRuns()
        }
    }

    private suspend fun resumeAgentRun(runId: String, record: AgentApprovalRecord): Boolean =
        agentResumeMutex.withLock {
            if (stateHolder.textApiJob?.isActive == true) {
                return@withLock false
            }
            startResumedAgentRun(runId, record)
        }

    /** 同一时刻只允许一个文本 Run 占用统一流状态，避免恢复覆盖正在进行的会话。 */
    private suspend fun startResumedAgentRun(runId: String, record: AgentApprovalRecord?): Boolean {
        val run = agentRunStore.getRun(runId) ?: run {
            restorePendingAgentApproval(resumeDecided = false)
            return false
        }
        val configId = run.configIdSnapshot ?: run {
            if (run.status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name) {
                // 模型续写失败必须保留待续写状态 MODEL_CONTINUATION_PENDING 而不是标记 Run 失败
                return false
            }
            markApprovalDecisionFailure(run, "原模型配置不存在")
            return false
        }
        val config = AppDatabase.getDatabase(context).apiConfigDao().getTextConfig(configId)?.toApiConfig()
            ?: run {
                if (run.status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name) {
                    return false
                }
                markApprovalDecisionFailure(run, "原模型配置已删除")
                return false
            }
        val request = agentRunStore.restoreChatRequest(run, config.key)
            ?: run {
                if (run.status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name) {
                    return false
                }
                markApprovalDecisionFailure(run, "Agent 恢复快照不可用")
                return false
            }
        val limits = resolvedModelTokenLimits(
            maxOutputTokens = request.generationConfig?.maxOutputTokens,
            maxContextTokens = request.contextManagement?.maxContextTokens
                ?: com.android.everytalk.data.DataClass.DEFAULT_MAX_CONTEXT_TOKENS,
        )
        withContext(Dispatchers.Main.immediate) {
            val trace = agentRunStore.executionTrace(run.id)
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == run.visibleAssistantMessageId }
            if (messageIndex >= 0 && trace.isNotEmpty()) {
                stateHolder.messages[messageIndex] = stateHolder.messages[messageIndex].copy(executionTrace = trace)
            }
            messageProcessorMap.putIfAbsent(run.visibleAssistantMessageId, MessageProcessor())
            stateHolder.createStreamingBuffer(run.visibleAssistantMessageId, isImageGeneration = false)
            stateHolder._currentTextStreamingAiMessageId.value = run.visibleAssistantMessageId
            stateHolder._isTextApiCalling.value = true
            stateHolder._isRemoteCancellationPending.value = false
        }
        withContext(Dispatchers.Main.immediate) {
            val job = viewModelScope.launch {
                val thisJob = coroutineContext[Job]
                try {
                    agentRunCoordinator.run(
                        AgentLoopRequest(
                            request = request,
                            sessionId = run.sessionId,
                            userMessageId = run.userMessageId,
                            visibleAssistantMessageId = run.visibleAssistantMessageId,
                            tokenLimits = limits,
                            existingRun = run,
                            approvalDecision = record,
                        ),
                    ).collect { event ->
                        processStreamEvent(event, run.visibleAssistantMessageId, isImageGeneration = false)
                    }
                } finally {
                    stateHolder.syncStreamingMessageToList(run.visibleAssistantMessageId, false)
                    stateHolder.clearStreamingBuffer(run.visibleAssistantMessageId)
                    if (stateHolder.textApiJob == thisJob) {
                        stateHolder.textApiJob = null
                        stateHolder._isTextApiCalling.value = false
                        stateHolder._currentTextStreamingAiMessageId.value = null
                    }
                    restorePendingAgentApproval()
                }
            }
            stateHolder.textApiJob = job
        }
        return true
    }

    private suspend fun markApprovalDecisionFailure(
        run: com.android.everytalk.data.database.entities.AgentRunEntity,
        reason: String,
    ) {
        agentRunStore.updateRunStatus(run, com.android.everytalk.data.agent.AgentRunStatus.FAILED, terminalReason = reason)
        _pendingAgentApprovals.value = _pendingAgentApprovals.value.filterNot { it.runId == run.id }
        withContext(Dispatchers.Main.immediate) {
            updatePreparedMessageStatus(stateHolder.messages, run.visibleAssistantMessageId, reason)
        }
        restorePendingAgentApproval(resumeDecided = false)
    }


    private val errorHandler by lazy {
        ApiHandlerErrorController(
            context = context,
            stateHolder = stateHolder,
            historyManager = historyManager,
            messageProcessorMap = messageProcessorMap,
            retryCountMap = retryCountMap,
            logger = logger,
        )
    }

    private val streamProcessor by lazy {
        ApiHandlerStreamProcessor(
            context = context,
            stateHolder = stateHolder,
            viewModelScope = viewModelScope,
            historyManager = historyManager,
            messageProcessorMap = messageProcessorMap,
            processedMessageIds = processedMessageIds,
            generatedImageSourceFingerprints = generatedImageSourceFingerprints,
            promptLeakDetectors = promptLeakDetectors,
            retryCountMap = retryCountMap,
            logger = logger,
            onAiMessageFullTextChanged = onAiMessageFullTextChanged,
            errorHandler = errorHandler,
        )
    }

    private val resourceController by lazy {
        ApiHandlerResourceController(
            stateHolder = stateHolder,
            viewModelScope = viewModelScope,
            messageProcessorMap = messageProcessorMap,
            processedMessageIds = processedMessageIds,
            generatedImageSourceFingerprints = generatedImageSourceFingerprints,
            promptLeakDetectors = promptLeakDetectors,
            retryCountMap = retryCountMap,
            logger = logger,
            onAiMessageFullTextChanged = onAiMessageFullTextChanged,
        )
    }

    /**
     * 预先创建 AI 占位消息并设置流式状态，用于在自动压缩、外部搜索等请求准备阶段提供即时 UI 反馈。
     * @return 预创建的 AI 消息 ID
     */
    suspend fun prepareStreamingAiMessage(
        modelName: String,
        providerName: String,
        isImageGeneration: Boolean = false,
        onNewAiMessageAdded: () -> Unit = {},
        afterUserMessageId: String? = null,
        contextUsageSnapshot: ContextUsageSnapshot? = null,
        contextCompressionState: ContextCompressionState? = null,
        executionStatus: String? = null,
        preparationJob: Job? = null,
    ): String {
        val aiMessageId = UUID.randomUUID().toString()
        logger.debug("Preparing streaming AI message: $aiMessageId, model=$modelName, isImageGeneration=$isImageGeneration")

        PerformanceMonitor.setContext(aiMessageId, mode = if (isImageGeneration) "image" else "text")

        // 初始化处理器和状态
        val newMessageProcessor = MessageProcessor()
        messageProcessorMap[aiMessageId] = newMessageProcessor
        stateHolder.createStreamingBuffer(aiMessageId, isImageGeneration)

        // 设置流式状态
        if (isImageGeneration) {
            if (preparationJob != null) stateHolder.imageApiJob = preparationJob
            stateHolder._currentImageStreamingAiMessageId.value = aiMessageId
            stateHolder._isImageApiCalling.value = true
            stateHolder.imageReasoningCompleteMap[aiMessageId] = false
        } else {
            if (preparationJob != null) stateHolder.textApiJob = preparationJob
            stateHolder._currentTextStreamingAiMessageId.value = aiMessageId
            stateHolder._isTextApiCalling.value = true
            stateHolder._isRemoteCancellationPending.value = false
            stateHolder.textReasoningCompleteMap[aiMessageId] = false
        }

        // 创建并添加消息
        val newAiMessage = Message(
            id = aiMessageId,
            text = "",
            sender = Sender.AI,
            contentStarted = false,
            modelName = modelName,
            providerName = providerName,
            contextUsageSnapshot = contextUsageSnapshot?.copy(messageId = aiMessageId),
            contextCompressionState = contextCompressionState,
            executionStatus = executionStatus,
        )

        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            addAiMessageAfterUserMessage(messageList, newAiMessage, afterUserMessageId)
            onNewAiMessageAdded()
            logger.debug("🔧 Pre-created AI message added to list: $aiMessageId")
        }

        return aiMessageId
    }

    suspend fun updatePreparedStreamingStatus(
        messageId: String,
        status: String?,
        isImageGeneration: Boolean = false,
        contextUsageSnapshot: ContextUsageSnapshot? = null,
        contextCompressionState: ContextCompressionState? = null,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            if (!updatePreparedMessageStatus(messageList, messageId, status)) {
                logger.warn("预创建消息不存在，无法更新执行状态: $messageId")
            }
            if (
                contextUsageSnapshot != null &&
                !updateMessageContextUsageSnapshot(messageList, messageId, contextUsageSnapshot)
            ) {
                logger.warn("预创建消息不存在，无法同步上下文快照: $messageId")
            }
            if (
                contextCompressionState != null &&
                !updateMessageContextCompressionState(messageList, messageId, contextCompressionState)
            ) {
                logger.warn("预创建消息不存在，无法同步压缩检查点: $messageId")
            }
        }
    }

    suspend fun failPreparedStreamingAiMessage(
        messageId: String,
        errorText: String,
        isImageGeneration: Boolean = false,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val localizedErrorText = context.localizeUiMessage(errorText)
            if (!markPreparedMessageFailed(messageList, messageId, localizedErrorText)) {
                logger.warn("预创建消息不存在，无法写入压缩错误: $messageId")
            }
            if (isImageGeneration) {
                stateHolder.imageReasoningCompleteMap[messageId] = true
                stateHolder.imageApiJob = null
                stateHolder._isImageApiCalling.value = false
                if (stateHolder._currentImageStreamingAiMessageId.value == messageId) {
                    stateHolder._currentImageStreamingAiMessageId.value = null
                }
            } else {
                stateHolder.textReasoningCompleteMap[messageId] = true
                stateHolder.textApiJob = null
                stateHolder._isTextApiCalling.value = false
                if (stateHolder._currentTextStreamingAiMessageId.value == messageId) {
                    stateHolder._currentTextStreamingAiMessageId.value = null
                }
            }
            stateHolder.clearStreamingBuffer(messageId)
            resourceController.removeMessageResources(messageId)
            PerformanceMonitor.onAbort(messageId, reason = errorText)
        }
        withContext(Dispatchers.IO) {
            try {
                historyManager.saveCurrentChatToHistoryIfNeeded(
                    forceSave = true,
                    isImageGeneration = isImageGeneration,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("压缩失败消息持久化失败: ${error.message}")
            }
        }
    }

    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false, isImageGeneration: Boolean = false) {
        // 关键修复：增强日志，明确显示模式信息
        val modeInfo = if (isImageGeneration) "IMAGE_MODE" else "TEXT_MODE"
        logger.debug("Cancelling API job: $reason, Mode=$modeInfo, isNewMessageSend=$isNewMessageSend, isImageGeneration=$isImageGeneration")
        
        val jobToCancel = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
        val messageIdBeingCancelled = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX [$modeInfo] $reason" else "$USER_CANCEL_PREFIX [$modeInfo] $reason"

        if (messageIdBeingCancelled != null) {
            stateHolder.syncStreamingMessageToList(messageIdBeingCancelled, isImageGeneration)
        }

        if (jobToCancel?.isActive == true) {
            if (messageIdBeingCancelled != null) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index =
                        messageList.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val syncedMessage = messageList[index]
                        if (syncedMessage.text.isNotBlank()) {
                            onAiMessageFullTextChanged(messageIdBeingCancelled, syncedMessage.text)
                        }

                        logger.debug("Saving partial content on user cancellation (${syncedMessage.text.length} chars)")
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                    }
                }
            }
        }

        if (messageIdBeingCancelled != null) {
            PerformanceMonitor.onAbort(messageIdBeingCancelled, reason = specificCancelReason)
        }
        // 用户点击停止必须先写 USER_STOP，再写取消意图，再发 SSH 取消请求
        if (!isImageGeneration) {
            if (!isNewMessageSend) {
                stateHolder._isRemoteCancellationPending.value = true
                if (messageIdBeingCancelled != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        agentRunStore.getRunByVisibleMessage(messageIdBeingCancelled)?.let { run ->
                            agentRunStore.updateRunStatus(
                                run,
                                AgentRunStatus.CANCELLED,
                                terminalReason = AgentTerminalReasons.USER_STOP,
                            )
                        }
                    }
                }
            }
            if (messageIdBeingCancelled != null) {
                updateMessageExecutionStatus(messageIdBeingCancelled, "正在取消远端任务")
            }
            cancelComputerExecutions(stateHolder._currentConversationId.value) { success ->
                messageIdBeingCancelled?.let { messageId ->
                    updateMessageExecutionStatus(
                        messageId,
                        if (success) "远端任务已取消" else "远端取消失败，等待恢复确认",
                    )
                }
                if (!isNewMessageSend) {
                    stateHolder._isRemoteCancellationPending.value = false
                }
                if (!success && messageIdBeingCancelled != null) {
                    // AgentLoop 可能已经把本地 Run 标成 CANCELLED，但远端仍无法确认。
                    // 恢复为等待远端状态，轮询才能继续并为用户生成 UNKNOWN 决策卡。
                    viewModelScope.launch(Dispatchers.IO) {
                        agentRunStore.getRunByVisibleMessage(messageIdBeingCancelled)?.let { run ->
                            if (run.status in setOf(
                                    AgentRunStatus.CANCELLED.name,
                                    AgentRunStatus.WAITING_REMOTE_EXECUTION.name,
                                    AgentRunStatus.INTERRUPTED.name,
                                )) {
                                agentRunStore.updateRunStatus(
                                    run,
                                    AgentRunStatus.WAITING_REMOTE_EXECUTION,
                                    terminalReason = null,
                                )
                            }
                        }
                        restorePendingAgentApproval()
                    }
                }
            }
        }
        jobToCancel?.cancel(CancellationException(specificCancelReason))

        if (isImageGeneration) {
            stateHolder._isImageApiCalling.value = false
            if (!isNewMessageSend && stateHolder._currentImageStreamingAiMessageId.value == messageIdBeingCancelled) {
                stateHolder._currentImageStreamingAiMessageId.value = null
            }
        } else {
            stateHolder._isTextApiCalling.value = false
            if (!isNewMessageSend && stateHolder._currentTextStreamingAiMessageId.value == messageIdBeingCancelled) {
                stateHolder._currentTextStreamingAiMessageId.value = null
            }
        }
        
        // 清理对应的消息处理器和块管理器
        if (messageIdBeingCancelled != null) {
            // 🎯 清理 StreamingBuffer（Requirements: 7.5）
            stateHolder.clearStreamingBuffer(messageIdBeingCancelled)
            logger.debug("Cleared StreamingBuffer on cancellation for message: $messageIdBeingCancelled")
            
            messageProcessorMap.remove(messageIdBeingCancelled)
            // 🛡️ 清理 prompt 泄露检测器
            promptLeakDetectors.remove(messageIdBeingCancelled)
            generatedImageSourceFingerprints.remove(messageIdBeingCancelled)
        }

        if (messageIdBeingCancelled != null) {
            // 修复：取消回答时立即标记推理完成，确保思考框收起
            if (isImageGeneration) {
                stateHolder.imageReasoningCompleteMap[messageIdBeingCancelled] = true
            } else {
                stateHolder.textReasoningCompleteMap[messageIdBeingCancelled] = true
            }
            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index =
                        messageList.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = messageList[index]
                        // 如仍为占位消息则移除；否则仅触发重组以刷新思考框可见性
                        val isPlaceholder = msg.sender == Sender.AI && msg.text.isBlank() &&
                                msg.reasoning.isNullOrBlank() && msg.webSearchResults.isNullOrEmpty() &&
                                msg.currentWebSearchStage.isNullOrEmpty() && !msg.contentStarted && !msg.isError
                        val isHistoryLoaded = stateHolder._loadedHistoryIndex.value != null || stateHolder._loadedImageGenerationHistoryIndex.value != null
                        if (isPlaceholder && !isHistoryLoaded) {
                            logger.debug("Removing placeholder message: ${msg.id}")
                            messageList.removeAt(index)
                        } else {
                            // 触发一次轻微更新，确保 Compose 根据 reasoningCompleteMap 重新计算
                            messageList[index] = msg.copy(timestamp = System.currentTimeMillis())
                        }
                    }
                }
            }
        }
        // 🔧 修复：取消时必须重置所有流式状态，否则UI会继续显示"正在连接"
        if (isImageGeneration) {
            stateHolder.imageApiJob = null
            stateHolder._isImageApiCalling.value = false
            stateHolder._currentImageStreamingAiMessageId.value = null
        } else {
            stateHolder.textApiJob = null
            stateHolder._isTextApiCalling.value = false
            stateHolder._currentTextStreamingAiMessageId.value = null
        }
    }

    fun streamChatResponse(
        requestBody: ChatRequest,
        attachmentsToPassToApiClient: List<SelectedMediaItem>,
        applicationContextForApiClient: Context,
        @Suppress("UNUSED_PARAMETER") userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit,
        onRequestFailed: (Throwable) -> Unit,
        onNewAiMessageAdded: () -> Unit,
        audioBase64: String? = null,
        mimeType: String? = null,
        isImageGeneration: Boolean = false,
        preCreatedAiMessageId: String? = null,
        contextUsageSnapshot: ContextUsageSnapshot? = null,
    ) {
        logger.debug(
            "streamChatResponse request summary: inputChars=${userMessageTextForContext.length}, trimmedChars=${userMessageTextForContext.trim().length}, messages.size=${requestBody.messages.size}, conversationId=${requestBody.conversationId}, preCreatedId=$preCreatedAiMessageId"
        )
        requestBody.messages.forEachIndexed { index, message ->
            val textChars = when (message) {
                is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> message.content
                is com.android.everytalk.data.DataClass.PartsApiMessage -> message.parts
                    .filterIsInstance<ApiContentPart.Text>()
                    .joinToString(" ") { it.text }
                else -> ""
            }.length
            logger.debug("requestMessage[$index]: role=${message.role}, textChars=$textChars")
        }

        val contextForLog = when (val lastUserMsg = requestBody.messages.lastOrNull {
            it.role == "user"
        }) {
            is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> lastUserMsg.content
            is com.android.everytalk.data.DataClass.PartsApiMessage -> lastUserMsg.parts
                .filterIsInstance<ApiContentPart.Text>().joinToString(" ") { it.text }

            else -> null
        }?.let { "chars=${it.length}" } ?: "N/A"

        logger.debug("Starting new stream chat response with context: '$contextForLog'")
        
        // 如果没有预先创建的 ID，才执行常规的取消逻辑
        if (preCreatedAiMessageId == null) {
            cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true, isImageGeneration = isImageGeneration)
        }

        // 使用预创建的 ID 或创建新 ID
        val aiMessageId = preCreatedAiMessageId ?: UUID.randomUUID().toString()
        
        if (preCreatedAiMessageId == null) {
            // 只有在非预创建情况下才初始化处理器和状态（预创建时 prepareStreamingAiMessage 已做）
            val newAiMessage = Message(
                id = aiMessageId,
                text = "",
                sender = Sender.AI,
                contentStarted = false,
                modelName = requestBody.model,
                providerName = requestBody.provider,
                contextUsageSnapshot = contextUsageSnapshot?.copy(messageId = aiMessageId),
            )
            PerformanceMonitor.setContext(aiMessageId, mode = if (isImageGeneration) "image" else "text")

            val newMessageProcessor = MessageProcessor()
            messageProcessorMap[aiMessageId] = newMessageProcessor
            
            if (checkMemoryPressureAndCleanup()) {
                logger.debug("Memory pressure cleanup triggered before starting new stream")
            }
            
            stateHolder.createStreamingBuffer(aiMessageId, isImageGeneration)

            if (isImageGeneration) {
                stateHolder._currentImageStreamingAiMessageId.value = aiMessageId
                stateHolder._isImageApiCalling.value = true
                stateHolder.imageReasoningCompleteMap[aiMessageId] = false
            } else {
                stateHolder._currentTextStreamingAiMessageId.value = aiMessageId
                stateHolder._isTextApiCalling.value = true
                stateHolder._isRemoteCancellationPending.value = false
                stateHolder.textReasoningCompleteMap[aiMessageId] = false
            }
            
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            viewModelScope.launch(Dispatchers.Main.immediate) {
                addAiMessageAfterUserMessage(messageList, newAiMessage, afterUserMessageId)
                onNewAiMessageAdded()
                logger.debug("🔧 AI message added to list: $aiMessageId")
            }
        } else {
            logger.debug("🔧 Using pre-created AI message ID: $aiMessageId")
        }

        val job = viewModelScope.launch {
            val thisJob = coroutineContext[Job]
            if (preCreatedAiMessageId != null && contextUsageSnapshot != null) {
                withContext(Dispatchers.Main.immediate) {
                    val messageList = if (isImageGeneration) {
                        stateHolder.imageGenerationMessages
                    } else {
                        stateHolder.messages
                    }
                    if (!updateMessageContextUsageSnapshot(messageList, aiMessageId, contextUsageSnapshot)) {
                        logger.warn("预创建消息不存在，无法同步上下文快照: $aiMessageId")
                    }
                }
            }
            var finalSyncDone = false
            suspend fun ensureFinalStreamingSync(source: String) {
                if (finalSyncDone) return
                try {
                    stateHolder.syncStreamingMessageToList(aiMessageId, isImageGeneration)
                    finalSyncDone = true
                    logger.debug("Final streaming sync completed from $source for message: $aiMessageId")
                } catch (e: Exception) {
                    logger.warn("Final streaming sync from $source failed: ${e.message}")
                }
            }
            if (isImageGeneration) {
                stateHolder.imageApiJob = thisJob
            } else {
                stateHolder.textApiJob = thisJob
            }
            try {
               if (isImageGeneration) {
                    try {
                        val response = ApiClient.generateImage(requestBody)
                        logger.debug(
                            "[ImageGen] Response received: images=${response.images.size}, textChars=${response.text?.length ?: 0}"
                        )

                        val imageUrls = response.images.mapNotNull { it.url.takeIf(String::isNotBlank) }
                        val responseText = response.text

                        logger.debug("[ImageGen] 🖼️ Extracted ${imageUrls.size} image URLs from response")
                        imageUrls.forEachIndexed { idx, url ->
                            logger.debug("[ImageGen] 🖼️ Image[$idx] urlChars=${url.length}")
                        }

                        if (imageUrls.isNotEmpty()) {
                            // 同步完成本地持久化后再更新消息，避免 UI 与历史记录不一致。
                            logger.debug("[ImageGen] 🖼️ Starting synchronous image persistence for ${imageUrls.size} images")
                            
                            val persistenceResult = withContext(Dispatchers.IO) {
                                streamProcessor.persistGeneratedImageUrlsForMessage(aiMessageId, imageUrls)
                            }
                            val persistedUrls = persistenceResult.urls
                            
                            // 只使用校验完成的本地路径更新消息。
                            withContext(Dispatchers.Main.immediate) {
                                val messageList = stateHolder.imageGenerationMessages
                                val index = messageList.indexOfFirst { it.id == aiMessageId }
                                logger.debug("[ImageGen] 🖼️ Looking for message with ID: $aiMessageId, found at index: $index")
                                
                                if (index != -1) {
                                    val currentMessage = messageList[index]
                                    logger.debug(
                                        "[ImageGen] Current message: id=${currentMessage.id}, hasImageUrls=${currentMessage.imageUrls?.isNotEmpty()}, textChars=${currentMessage.text.length}"
                                    )
                                    
                                    val persistenceFailureText = if (persistenceResult.failures.isNotEmpty()) {
                                        val firstFailure = persistenceResult.failures.first().toGeneratedImageMessage(context)
                                        val countText = if (persistenceResult.failures.size > 1) {
                                            context.resources.getQuantityString(
                                                R.plurals.generated_images_not_saved_count,
                                                persistenceResult.failures.size,
                                                persistenceResult.failures.size,
                                            )
                                        } else {
                                            ""
                                        }
                                        "\n\n> $firstFailure$countText"
                                    } else {
                                        ""
                                    }
                                    val updatedMessage = currentMessage.copy(
                                        imageUrls = persistedUrls,
                                        text = (responseText ?: currentMessage.text) + persistenceFailureText,
                                        contentStarted = true,
                                        isError = persistedUrls.isEmpty(),
                                        currentWebSearchStage = null,
                                        executionStatus = null
                                    )
                                    
                                    // 🔥 关键修复：使用removeAt+add替代直接赋值，确保触发Compose重组
                                    messageList.removeAt(index)
                                    messageList.add(index, updatedMessage)
                                    
                                    logger.debug("[ImageGen] 🖼️ Updated message with ${persistedUrls.size} local image paths at index $index")
                                    logger.debug("[ImageGen] 🖼️ Message list size after update: ${messageList.size}")
                                    
                                    // 🔥 强制触发状态变化，确保Flow重新计算
                                    stateHolder.isImageConversationDirty.value = true
                                    
                                    logger.debug("[ImageGen] 🖼️ Marked conversation as dirty to trigger UI update")
                                } else {
                                    logger.error("[ImageGen] 🖼️ ERROR: Message with ID $aiMessageId not found in list!")
                                    logger.debug("[ImageGen] 🖼️ Current message list IDs: ${messageList.map { it.id }}")
                                }
                            }

                            // 图片本地持久化完成后立即保存历史，确保只记录稳定路径。
                            withContext(Dispatchers.IO) {
                                try {
                                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)
                                    logger.debug("[ImageGen] 🖼️ History saved with local image paths")
                                } catch (e: Exception) {
                                    logger.warn("[ImageGen] 🖼️ Failed to save history: ${e.message}")
                                }
                            }
                        } else {
                            // 后端已完成所有重试但仍无图片，将返回的文本作为错误消息处理
                            val error = IOException(responseText ?: "图像生成失败，且未返回明确错误信息。")
                            updateMessageWithError(aiMessageId, error, isImageGeneration = true)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (e: Exception) {
                        // 网络请求失败或任何其他异常
                        logger.error("[ImageGen] Exception during image generation for message $aiMessageId", e)
                        updateMessageWithError(aiMessageId, e, isImageGeneration = true)
                        // 不再调用 onRequestFailed，避免 Snackbar 弹出
                    }
               } else {
                logger.debug("Agent run started for message $aiMessageId")
                agentRunCoordinator.run(
                    AgentLoopRequest(
                        request = requestBody,
                        sessionId = requestBody.conversationId
                            ?.takeIf(String::isNotBlank)
                            ?: error("会话 ID 为空，无法启动 Agent"),
                        userMessageId = afterUserMessageId
                            ?.takeIf(String::isNotBlank)
                            ?: requestBody.messages.lastOrNull { it.role == "user" }?.id
                            ?: error("用户消息 ID 为空，无法启动 Agent"),
                        visibleAssistantMessageId = aiMessageId,
                        tokenLimits = resolvedModelTokenLimits(
                            maxOutputTokens = requestBody.generationConfig?.maxOutputTokens,
                            maxContextTokens = requestBody.contextManagement?.maxContextTokens
                                ?: com.android.everytalk.data.DataClass.DEFAULT_MAX_CONTEXT_TOKENS,
                        ),
                    )
                ).takeWhile { appEvent ->
                    val currentStreamingId = stateHolder._currentTextStreamingAiMessageId.value
                    if (stateHolder.textApiJob != thisJob || currentStreamingId != aiMessageId) {
                        thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                        return@takeWhile false
                    }
                    stateHolder.checkMemoryUsage()
                    processStreamEvent(appEvent, aiMessageId, isImageGeneration = false)
                    appEvent !is AppStreamEvent.StreamEnd && appEvent !is AppStreamEvent.Error
                }.collect { }
               }
             } catch (e: CancellationException) {
                 val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
                 val partialText = currentMessageProcessor.getCurrentText().trim()
                 currentMessageProcessor.reset()
                 logger.debug("Stream cancelled: ${e.message}")
                 ensureFinalStreamingSync("stream cancellation")
                 if (partialText.isNotBlank()) {
                     logger.debug("Saving partial content (${partialText.length} chars) to history on cancellation")
                     viewModelScope.launch(Dispatchers.IO) {
                         try {
                             historyManager.saveCurrentChatToHistoryIfNeeded(
                                 forceSave = true,
                                 isImageGeneration = isImageGeneration,
                             )
                             logger.debug("Successfully saved partial content to history")
                         } catch (saveError: Exception) {
                             logger.error("Failed to save partial content to history", saveError)
                         }
                     }
                 }
                 throw e
             } catch (e: Exception) {
                 val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
                 currentMessageProcessor.reset()
                 logger.error("Stream exception", e)
                 updateMessageWithError(aiMessageId, e, isImageGeneration)
                 onRequestFailed(e)
             } finally {
                // 🎯 最终安全网：如果在 onCompletion 中因异常未执行同步，这里再尝试一次
                // 但为了避免重复执行，syncStreamingMessageToList 内部有空值检查
                // 注意：在 finally 中不应抛出异常
                ensureFinalStreamingSync("job.finally")

                // 🎯 最后统一清理 StreamingBuffer，确保 sync 完成后再清理
                try {
                    stateHolder.clearStreamingBuffer(aiMessageId)
                    logger.debug("Cleared StreamingBuffer in finally block for message: $aiMessageId")
                } catch (e: Exception) {
                    logger.warn("Clear StreamingBuffer in finally block failed: ${e.message}")
                }

                // 流结束后这些对象不再参与后续消息处理，及时释放，避免长会话按消息累积。
                messageProcessorMap.remove(aiMessageId)
                processedMessageIds.remove(aiMessageId)
                promptLeakDetectors.remove(aiMessageId)
                generatedImageSourceFingerprints.remove(aiMessageId)
                retryCountMap.remove(aiMessageId)

                val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                var clearedCurrentTextJob = false
                if (currentJob == thisJob) {
                    if (isImageGeneration) {
                        stateHolder.imageApiJob = null
                        stateHolder._isImageApiCalling.value = false
                        stateHolder._currentImageStreamingAiMessageId.value = null
                    } else {
                        stateHolder.textApiJob = null
                        stateHolder._isTextApiCalling.value = false
                        stateHolder._currentTextStreamingAiMessageId.value = null
                        clearedCurrentTextJob = true
                    }
                }
                if (clearedCurrentTextJob) restorePendingAgentApproval()
            }
        }
    }

    private suspend fun processStreamEvent(
        appEvent: AppStreamEvent,
        aiMessageId: String,
        isImageGeneration: Boolean = false,
    ) {
        streamProcessor.processStreamEvent(appEvent, aiMessageId, isImageGeneration)
        if (appEvent is AppStreamEvent.AgentApprovalRequired) restorePendingAgentApproval()
    }

    /** 在任意线程安全地更新取消或恢复提示。 */
    private fun updateMessageExecutionStatus(messageId: String, status: String?) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            updatePreparedMessageStatus(stateHolder.messages, messageId, status)
        }
    }

    private suspend fun updateMessageWithError(
        messageId: String,
        error: Throwable,
        isImageGeneration: Boolean = false,
        allowRetry: Boolean = true,
    ) {
        errorHandler.updateMessageWithError(messageId, error, isImageGeneration, allowRetry)
    }

    fun hasImageGenerationKeywords(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase().trim()
        val imageKeywords = listOf(
            "画", "绘制", "画个", "画张", "画一张", "来一张", "给我一张", "出一张", 
            "生成图片", "生成", "生成几张", "生成多张", "出图", "图片", "图像", 
            "配图", "背景图", "封面图", "插画", "插图", "海报", "头像", "壁纸", 
            "封面", "表情包", "贴图", "示意图", "场景图", "示例图", "图标",
            "手绘", "素描", "线稿", "上色", "涂色", "水彩", "油画", "像素画", 
            "漫画", "二次元", "渲染", "p图", "p一张", "制作一张", "做一张", "合成一张",
            "image", "picture", "pictures", "photo", "photos", "art", "artwork", 
            "illustration", "render", "rendering", "draw", "sketch", "paint", 
            "painting", "watercolor", "oil painting", "pixel art", "comic", 
            "manga", "sticker", "cover", "wallpaper", "avatar", "banner", 
            "logo", "icon", "generate image", "generate a picture", 
            "create an image", "make an image", "image generation"
        )
        return imageKeywords.any { t.contains(it) }
    }

    fun clearTextChatResources() = resourceController.clearTextChatResources()

    fun clearTextChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) =
        resourceController.clearTextChatResources(sessionId)

    fun clearImageChatResources() = resourceController.clearImageChatResources()

    fun clearImageChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) =
        resourceController.clearImageChatResources(sessionId)

    fun flushPausedStreamingUpdate(isImageGeneration: Boolean = false) =
        resourceController.flushPausedStreamingUpdate(isImageGeneration)

    fun checkMemoryPressureAndCleanup(): Boolean =
        resourceController.checkMemoryPressureAndCleanup()

    fun getResourceStats(): String = resourceController.getResourceStats()
}

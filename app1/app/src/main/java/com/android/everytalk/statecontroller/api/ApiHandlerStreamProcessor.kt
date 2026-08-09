package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.NativeContextCompactionKind
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.PromptLeakGuard
import com.android.everytalk.util.debug.PerformanceMonitor
import com.android.everytalk.util.messageprocessor.MessageProcessor
import com.android.everytalk.util.text.TextSanitizer
import com.android.everytalk.util.image.ImagePersistenceFailure
import com.android.everytalk.util.image.ImagePersistenceResult
import com.android.everytalk.util.image.toGeneratedImageMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun shouldAppendStreamTextChunk(chunk: String?): Boolean =
    !chunk.isNullOrEmpty() &&
        (chunk.isNotBlank() || chunk.any { character -> character == '\n' || character == '\r' })

private fun nativeContextWindowId(
    messageId: String,
    compactionItemId: String?,
    inputJson: String,
): String {
    val source = listOfNotNull(compactionItemId, messageId, inputJson.length.toString()).joinToString(":")
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }
    return "native-$fingerprint"
}

internal fun mergeNativeContextCompaction(
    message: Message,
    event: AppStreamEvent.NativeContextCompaction,
): Message {
    val previousState = message.contextCompressionState
    val nextState = if (event.reset) {
        when (event.kind) {
            NativeContextCompactionKind.OPENAI_RESPONSES -> previousState?.copy(
                openAiResponsesInputJson = null,
                openAiResponsesThroughMessageId = null,
                openAiResponsesEstimatedTokens = 0L,
            )
            NativeContextCompactionKind.ANTHROPIC_MESSAGES -> previousState?.copy(
                anthropicMessagesJson = null,
                anthropicThroughMessageId = null,
                anthropicEstimatedTokens = 0L,
            )
        }
    } else {
        val nextWindowId = nativeContextWindowId(
            messageId = message.id,
            compactionItemId = event.compactionItemId,
            inputJson = event.inputJson,
        )
        val baseState = if (previousState != null && previousState.configId == event.configId) {
            previousState.copy(
                provider = event.provider,
                channel = event.channel,
                model = event.model,
                windowNumber = previousState.windowNumber + 1L,
                windowId = nextWindowId,
                previousWindowId = previousState.windowId,
                estimatedTokensAfter = event.estimatedTokens,
            )
        } else {
            ContextCompressionState(
                configId = event.configId,
                provider = event.provider,
                channel = event.channel,
                model = event.model,
                windowNumber = 1L,
                windowId = nextWindowId,
                estimatedTokensAfter = event.estimatedTokens,
            )
        }
        when (event.kind) {
            NativeContextCompactionKind.OPENAI_RESPONSES -> baseState.copy(
                openAiResponsesInputJson = event.inputJson,
                openAiResponsesThroughMessageId = message.id,
                openAiResponsesEstimatedTokens = event.estimatedTokens,
                anthropicMessagesJson = null,
                anthropicThroughMessageId = null,
                anthropicEstimatedTokens = 0L,
            )
            NativeContextCompactionKind.ANTHROPIC_MESSAGES -> baseState.copy(
                openAiResponsesInputJson = null,
                openAiResponsesThroughMessageId = null,
                openAiResponsesEstimatedTokens = 0L,
                anthropicMessagesJson = event.inputJson,
                anthropicThroughMessageId = message.id,
                anthropicEstimatedTokens = event.estimatedTokens,
            )
        }
    }
    return message.copy(
        contextCompressionState = nextState,
        contextUsageSnapshot = message.contextUsageSnapshot?.withActiveContextOverride(
            if (event.reset) null else event.estimatedTokens,
        ),
    )
}

private fun compactStreamToolName(name: String, maxChars: Int = 24): String {
    val normalized = name.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return ""
    if (normalized.length <= maxChars) return normalized
    return normalized.take((maxChars - 3).coerceAtLeast(1)).trimEnd() + "..."
}

private fun buildToolCallStatus(toolName: String): String {
    val compactName = compactStreamToolName(toolName)
    val prefix = if (compactName.contains("mcp", ignoreCase = true)) "调用MCP" else "调用工具"
    return if (compactName.isBlank()) prefix else "$prefix · $compactName"
}

private fun compactExecutionLabel(value: String, maxChars: Int = 180): String {
    val normalized = value.trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars - 3).trimEnd() + "..."
}

private fun AppStreamEvent.ToolCall.argumentText(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        (argumentsObj[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.orEmpty()

internal fun executionStepForToolCall(event: AppStreamEvent.ToolCall): ExecutionStep {
    val toolName = event.name.trim()
    val query = event.argumentText("query", "q", "search_query", "searchQuery")
    val url = event.argumentText("url", "href", "link")
    val normalizedName = toolName.lowercase(Locale.ROOT)
    val type = when {
        query.isNotBlank() || normalizedName.contains("search") || normalizedName.contains("query") ->
            ExecutionStepType.Search
        url.isNotBlank() || normalizedName.contains("fetch") || normalizedName.contains("crawl") ->
            ExecutionStepType.Web
        else -> ExecutionStepType.Tool
    }
    val labels = buildList {
        when (type) {
            ExecutionStepType.Search -> add(query.ifBlank { toolName })
            ExecutionStepType.Web -> add(url.ifBlank { toolName })
            ExecutionStepType.Tool -> add(toolName)
        }
    }
        .filter { it.isNotBlank() }
        .map(::compactExecutionLabel)
        .distinct()
    return ExecutionStep(
        id = event.id.ifBlank { "${event.name}:${event.argumentsObj.hashCode()}" },
        type = type,
        title = when (type) {
            ExecutionStepType.Search -> "搜索网页"
            ExecutionStepType.Web -> "读取网页"
            ExecutionStepType.Tool -> "调用工具"
        },
        labels = labels,
    )
}

internal fun mergeExecutionStep(
    existing: List<ExecutionStep>,
    incoming: ExecutionStep,
): List<ExecutionStep> {
    val index = existing.indexOfFirst { it.id == incoming.id }
    if (index < 0) return existing + incoming
    return existing.toMutableList().apply {
        this[index] = incoming.copy(completed = this[index].completed)
    }
}

private fun List<ExecutionStep>.completeLastPendingStep(): List<ExecutionStep> {
    val index = indexOfLast { !it.completed }
    if (index < 0) return this
    return toMutableList().apply { this[index] = this[index].copy(completed = true) }
}

private fun List<ExecutionStep>.completeAllSteps(): List<ExecutionStep> =
    if (none { !it.completed }) this else map { it.copy(completed = true) }

internal sealed interface PreparedGeneratedImage {
    data class Ready(val source: String) : PreparedGeneratedImage
    data class Failed(val reason: ImagePersistenceFailure) : PreparedGeneratedImage
    data object Duplicate : PreparedGeneratedImage
}

internal data class PersistedGeneratedImageUrlsResult(
    val urls: List<String>,
    val failures: List<ImagePersistenceFailure>,
)

internal class ApiHandlerStreamProcessor(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val messageProcessorMap: ConcurrentHashMap<String, MessageProcessor>,
    private val processedMessageIds: MutableSet<String>,
    private val generatedImageSourceFingerprints: ConcurrentHashMap<String, MutableSet<String>>,
    private val promptLeakDetectors: ConcurrentHashMap<String, PromptLeakGuard.StreamingDetector>,
    private val retryCountMap: ConcurrentHashMap<String, Int>,
    private val messageTokenUsageStore: MessageTokenUsageStore,
    private val logger: AppLogger.ComponentLogger,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit,
    private val errorHandler: ApiHandlerErrorController,
) {
    private fun generatedImageSource(event: AppStreamEvent): String? = when (event) {
        is AppStreamEvent.CodeExecutionResult -> event.imageUrl
        is AppStreamEvent.ImageGeneration -> event.imageUrl
        else -> null
    }?.takeIf { it.isNotBlank() }
    
    private fun claimGeneratedImageIndex(messageId: String, source: String): Int? {
        val digest = MessageDigest.getInstance("SHA-256")
        if (source.startsWith("data:", ignoreCase = true)) {
            val commaIndex = source.indexOf(',')
            source.forEachIndexed { index, character ->
                if (!character.isWhitespace()) {
                    val normalized = if (commaIndex < 0 || index < commaIndex) {
                        character.lowercaseChar()
                    } else {
                        character
                    }
                    digest.update(normalized.code.toByte())
                }
            }
        } else {
            digest.update(source.toByteArray(Charsets.UTF_8))
        }
        val fingerprint = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        val fingerprints = generatedImageSourceFingerprints.computeIfAbsent(messageId) {
            ConcurrentHashMap.newKeySet()
        }
        return if (!fingerprints.add(fingerprint)) null else fingerprints.size - 1
    }
    
    private suspend fun prepareGeneratedImage(
        source: String,
        messageId: String,
    ): PreparedGeneratedImage {
        val trimmedSource = source.trim()
        val index = withContext(Dispatchers.Default) {
            claimGeneratedImageIndex(messageId, trimmedSource)
        }
            ?: return PreparedGeneratedImage.Duplicate
        val result = withContext(Dispatchers.IO) {
            historyManager.persistGeneratedImageSource(trimmedSource, messageId, index)
        }
        return when (result) {
            is ImagePersistenceResult.Success -> PreparedGeneratedImage.Ready(result.filePath)
            is ImagePersistenceResult.Failure -> {
                logger.warn(
                    "Generated image persistence failed: reason=${result.reason::class.simpleName}, messageId=$messageId",
                )
                PreparedGeneratedImage.Failed(result.reason)
            }
        }
    }
    internal suspend fun processStreamEvent(appEvent: AppStreamEvent, aiMessageId: String, isImageGeneration: Boolean = false) {
        // 获取当前消息ID对应的处理器和块管理器，若不存在则创建并加入映射
        val currentMessageProcessor = messageProcessorMap.computeIfAbsent(aiMessageId) { MessageProcessor() }
        val preparedGeneratedImage = generatedImageSource(appEvent)?.let { source ->
            prepareGeneratedImage(source, aiMessageId)
        }
        // 首先，让MessageProcessor处理事件并获取返回结果
        val processedResult = currentMessageProcessor.processStreamEvent(appEvent, aiMessageId)
    
        // 然后，根据处理结果和事件类型更新UI状态
        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val messageIndex = messageList.indexOfFirst { it.id == aiMessageId }
    
            if (messageIndex == -1) {
                logger.warn(
                    "Message with id $aiMessageId not found for event ${appEvent::class.simpleName}"
                )
                return@withContext
            }
    
            val currentMessage = messageList[messageIndex]
            var updatedMessage = currentMessage
            fun latestMessageForUpdate(): Message = messageList.getOrNull(messageIndex) ?: updatedMessage
            fun applyPreparedGeneratedImage(preparedImage: PreparedGeneratedImage?) {
                when (preparedImage) {
                    is PreparedGeneratedImage.Ready -> {
                        val latestMessage = latestMessageForUpdate()
                        val projectedMessage = applyGeneratedImageToMessage(latestMessage, preparedImage.source)
                        if (projectedMessage == latestMessage) return
    
                        messageList[messageIndex] = latestMessage.copy(
                            imageUrls = projectedMessage.imageUrls,
                            contentStarted = true,
                            currentWebSearchStage = null,
                            executionStatus = null,
                        )
                        stateHolder.appendContentToMessage(
                            aiMessageId,
                            "\n\n![Generated Image](${preparedImage.source})\n\n",
                            isImageGeneration,
                        )
                        stateHolder.syncStreamingSnapshotToList(aiMessageId, isImageGeneration)
                        updatedMessage = messageList[messageIndex]
                    }
                    is PreparedGeneratedImage.Failed -> {
                        stateHolder.appendContentToMessage(
                            aiMessageId,
                            "\n\n> ${preparedImage.reason.toGeneratedImageMessage()}\n\n",
                            isImageGeneration,
                        )
                        stateHolder.syncStreamingSnapshotToList(aiMessageId, isImageGeneration)
                        updatedMessage = messageList[messageIndex].copy(
                            contentStarted = true,
                            currentWebSearchStage = null,
                            executionStatus = null,
                        )
                        messageList[messageIndex] = updatedMessage
                    }
                    PreparedGeneratedImage.Duplicate, null -> return
                }
    
                if (isImageGeneration) {
                    stateHolder.isImageConversationDirty.value = true
                } else {
                    stateHolder.isTextConversationDirty.value = true
                }
                viewModelScope.launch(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = isImageGeneration)
                }
            }
    
            when (appEvent) {
                is AppStreamEvent.Content -> {
                    if (processedResult is com.android.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        val deltaChunk = processedResult.text
                        // 过滤无结构意义的纯空白内容，保留 Markdown 结构所需换行
                        if (shouldAppendStreamTextChunk(deltaChunk)) {
                            // 🛡️ 防 prompt 泄露：通过检测器过滤
                            val leakDetector = promptLeakDetectors.computeIfAbsent(aiMessageId) {
                                PromptLeakGuard.StreamingDetector()
                            }
                            val filteredChunk = leakDetector.appendAndCheck(deltaChunk)
                            if (filteredChunk.isEmpty()) {
                                logger.warn("🛡️ Blocked content chunk due to prompt leak detection for message $aiMessageId")
                                return@withContext
                            }
                            // sampling-based performance record
                            PerformanceMonitor.recordEvent(aiMessageId, "Content", filteredChunk.length)
                            stateHolder.appendContentToMessage(aiMessageId, filteredChunk, isImageGeneration)
                            // 🎯 第一个非空内容到来时，标记contentStarted = true
                            // 这样思考框会收起，正式内容开始流式展示
                            if (!currentMessage.contentStarted) {
                                updatedMessage = latestMessageForUpdate().copy(
                                    contentStarted = true,
                                    currentWebSearchStage = null,
                                    executionStatus = null
                                )
                                logger.debug("First content chunk received for message $aiMessageId, setting contentStarted=true")
                            } else {
                                updatedMessage = latestMessageForUpdate().copy(
                                    currentWebSearchStage = null,
                                    executionStatus = null
                                )
                            }
                            // 🛡️ 持久化保护：实时流式期间也触发一次"可合流"的保存（内部1.8s防抖+CONFLATED）
                            // 目的：即使用户立刻切换会话，当前内容也能落入"最后打开"或历史
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = isImageGeneration)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
                is AppStreamEvent.CodeExecutable -> {
                    // 显示"正在执行代码"状态，并将代码追加到正文
                    val code = appEvent.executableCode ?: ""
                    if (code.isNotBlank()) {
                        val formattedCode = "\n```${appEvent.codeLanguage ?: "python"}\n$code\n```\n"
                        stateHolder.appendContentToMessage(aiMessageId, formattedCode, isImageGeneration)
                        updatedMessage = latestMessageForUpdate().copy(
                            executionStatus = null,
                            currentWebSearchStage = null,
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.CodeExecutionResult -> {
                    // 清除执行状态，追加执行结果
                    updatedMessage = updatedMessage.copy(executionStatus = null)
                    val output = appEvent.codeExecutionOutput?.let(TextSanitizer::removeUnicodeReplacementCharacters)
                    if (!output.isNullOrBlank()) {
                        val formattedOutput = "\n```text\n$output\n```\n"
                        stateHolder.appendContentToMessage(aiMessageId, formattedOutput, isImageGeneration)
                        updatedMessage = latestMessageForUpdate().copy(
                            contentStarted = true,
                            currentWebSearchStage = null,
                            executionStatus = null
                        )
                    }
                    applyPreparedGeneratedImage(preparedGeneratedImage)
                }
                is AppStreamEvent.ImageGeneration -> applyPreparedGeneratedImage(preparedGeneratedImage)
                is AppStreamEvent.Text -> {
                    if (processedResult is com.android.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        val deltaChunk = processedResult.text
                        // 过滤无结构意义的纯空白内容，保留 Markdown 结构所需换行
                        if (shouldAppendStreamTextChunk(deltaChunk)) {
                            // 🛡️ 防 prompt 泄露：通过检测器过滤
                            val leakDetector = promptLeakDetectors.computeIfAbsent(aiMessageId) {
                                PromptLeakGuard.StreamingDetector()
                            }
                            val filteredChunk = leakDetector.appendAndCheck(deltaChunk)
                            if (filteredChunk.isEmpty()) {
                                logger.warn("🛡️ Blocked text chunk due to prompt leak detection for message $aiMessageId")
                                return@withContext
                            }
                            PerformanceMonitor.recordEvent(aiMessageId, "Text", filteredChunk.length)
                            stateHolder.appendContentToMessage(aiMessageId, filteredChunk, isImageGeneration)
                            // 🎯 第一个非空文本到来时，标记contentStarted = true
                            if (!currentMessage.contentStarted) {
                                updatedMessage = latestMessageForUpdate().copy(
                                    contentStarted = true,
                                    currentWebSearchStage = null,
                                    executionStatus = null
                                )
                                logger.debug("First text chunk received for message $aiMessageId, setting contentStarted=true")
                            } else {
                                updatedMessage = latestMessageForUpdate().copy(
                                    currentWebSearchStage = null,
                                    executionStatus = null
                                )
                            }
                            // 🛡️ 持久化保护：实时保存（可被防抖合并），防止切会话导致未落盘
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = isImageGeneration)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
                is AppStreamEvent.ContentFinal -> {
                    // 🎯 优化：ContentFinal 事件已被废弃（后端不再发送）
                    // 前端已通过累积 Content 增量事件构建了完整内容
                    // 保留此分支仅为向后兼容旧版本后端
                    android.util.Log.d("ApiHandler", "⚡ ContentFinal event received (deprecated, no-op)")
                    android.util.Log.d("ApiHandler", "   Message ID: $aiMessageId")
                    android.util.Log.d("ApiHandler", "   Event text length: ${appEvent.text.length}")
                    android.util.Log.d("ApiHandler", "   Note: Content already accumulated via Content events, skipping redundant processing")
                    
                    // 向后兼容：如果旧版本后端仍然发送此事件，确保内容已标记开始
                    if (appEvent.text.isNotBlank()) {
                        updatedMessage = updatedMessage.copy(
                            contentStarted = true,
                            currentWebSearchStage = null,
                            executionStatus = null
                        )
                        android.util.Log.d("ApiHandler", "   Marked contentStarted=true for backward compatibility")
                    }
                }
                is AppStreamEvent.Reasoning -> {
                    if (processedResult is com.android.everytalk.util.messageprocessor.ProcessedEventResult.ReasoningUpdated) {
                        val reasoningChunk = TextSanitizer.removeUnicodeReplacementCharacters(appEvent.text)
                        if (reasoningChunk.isNotBlank()) {
                            val seededMessage = applyReasoningChunk(currentMessage, reasoningChunk)
                            if (seededMessage !== currentMessage) {
                                messageList[messageIndex] = seededMessage
                            }
                            PerformanceMonitor.recordEvent(aiMessageId, "Reasoning", reasoningChunk.length)
                            stateHolder.appendReasoningToMessage(aiMessageId, reasoningChunk, isImageGeneration)
                        }
                        return@withContext
                    }
                }
                is AppStreamEvent.ReasoningFinish -> {
                    // 🔥 关键修复：收到推理完成事件时，立即标记推理完成并触发UI更新
                    // ✅ 但不设置contentStarted=true，等到第一个Content事件时再设置
                    // 这样思考框会继续显示，直到内容真正开始输出
                    val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                    reasoningMap[aiMessageId] = true
                    logger.debug("Reasoning finished for message $aiMessageId, marking reasoning as complete")
                    
                    // ❌ 不在这里设置contentStarted = true，避免思考框过早消失
                    updatedMessage = updatedMessage.copy(
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AppStreamEvent.Usage -> {
                    updatedMessage = messageTokenUsageStore.apply(latestMessageForUpdate(), appEvent)
                    val usage = updatedMessage.tokenUsage
                    logger.debug(
                        "Token usage: input=${usage?.inputTokens}, output=${usage?.outputTokens}, " +
                            "reasoning=${usage?.reasoningTokens}, cached=${usage?.cachedInputTokens}, " +
                            "total=${usage?.totalTokens}, final=${usage?.isFinal}"
                    )
                    if (usage?.isFinal == true) {
                        messageList[messageIndex] = updatedMessage
                        viewModelScope.launch(Dispatchers.IO) {
                            historyManager.saveCurrentChatToHistoryIfNeeded(
                                forceSave = true,
                                isImageGeneration = isImageGeneration,
                            )
                        }
                    }
                }
                is AppStreamEvent.NativeContextCompaction -> {
                    val latestMessage = latestMessageForUpdate()
                    updatedMessage = mergeNativeContextCompaction(latestMessage, appEvent)
                    if (isImageGeneration) {
                        stateHolder.isImageConversationDirty.value = true
                    } else {
                        stateHolder.isTextConversationDirty.value = true
                    }
                    messageList[messageIndex] = updatedMessage
                    viewModelScope.launch(Dispatchers.IO) {
                        historyManager.saveCurrentChatToHistoryIfNeeded(
                            forceSave = true,
                            isImageGeneration = isImageGeneration,
                        )
                    }
                }
                is AppStreamEvent.OutputType -> {
                    updatedMessage = updatedMessage.copy(outputType = appEvent.type)
                }
                is AppStreamEvent.WebSearchStatus -> {
                    updatedMessage = if (currentMessage.contentStarted || currentMessage.text.isNotBlank()) {
                        updatedMessage.copy(
                            currentWebSearchStage = null,
                            executionStatus = null
                        )
                    } else {
                        updatedMessage.copy(currentWebSearchStage = appEvent.stage)
                    }
                }
                is AppStreamEvent.StatusUpdate -> {
                    updatedMessage = if (currentMessage.contentStarted || currentMessage.text.isNotBlank()) {
                        updatedMessage.copy(
                            currentWebSearchStage = null,
                            executionStatus = null
                        )
                    } else {
                        updatedMessage.copy(currentWebSearchStage = appEvent.stage)
                    }
                }
                is AppStreamEvent.ExecutionStatusUpdate -> {
                    updatedMessage = if (currentMessage.contentStarted || currentMessage.text.isNotBlank()) {
                        updatedMessage.copy(
                            currentWebSearchStage = null,
                            executionStatus = null,
                            executionSteps = updatedMessage.executionSteps.completeAllSteps(),
                        )
                    } else if (appEvent.status.isNullOrBlank()) {
                        updatedMessage.copy(
                            currentWebSearchStage = null,
                            executionStatus = null,
                            executionSteps = updatedMessage.executionSteps.completeLastPendingStep(),
                        )
                    } else {
                        updatedMessage.copy(executionStatus = appEvent.status)
                    }
                }
                is AppStreamEvent.WebSearchResults -> {
                    updatedMessage = updatedMessage.copy(
                        webSearchResults = mergeWebSearchResults(
                            existing = updatedMessage.webSearchResults,
                            incoming = appEvent.results
                        )
                    )
                }
                is AppStreamEvent.Finish, is AppStreamEvent.StreamEnd -> {
                    if (processedMessageIds.contains(aiMessageId)) {
                        logger.debug("Ignoring duplicate terminal event for message $aiMessageId")
                        return@withContext
                    }
                    processedMessageIds.add(aiMessageId)
    
                    try {
                        val metrics = stateHolder.getStreamingPerformanceMetrics()
                        logger.debug("Stream completion performance metrics: $metrics")
                        android.util.Log.d("ApiHandler", 
                            "=== STREAMING PERFORMANCE SUMMARY ===\n" +
                            "Message ID: $aiMessageId\n" +
                            "Active Buffers: ${metrics["activeBufferCount"]}\n" +
                            "Total Flushes: ${metrics["totalFlushes"]}\n" +
                            "Total Chars: ${metrics["totalCharsProcessed"]}\n" +
                            "Avg Chars/Flush: ${metrics["avgCharsPerFlush"]}\n" +
                            "Memory Usage: ${metrics["usedMemoryMB"]}MB / ${metrics["maxMemoryMB"]}MB (${metrics["memoryUsagePercent"]}%)\n" +
                            "Text Messages: ${metrics["textMessageCount"]}\n" +
                            "Image Messages: ${metrics["imageMessageCount"]}")
                    } catch (e: Exception) {
                        logger.warn("Failed to log performance metrics: ${e.message}")
                    }
                    
                    // 🎯 重置重试计数（Requirements: 7.3）
                    resetRetryCount(aiMessageId)
                    logger.debug("Reset retry count for successfully completed message: $aiMessageId")
    
                    // 确保推理标记为完成（如果之前没有收到 ReasoningFinish 事件）
                    val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                    if (reasoningMap[aiMessageId] != true) {
                        reasoningMap[aiMessageId] = true
                    }
                    
                    // 🎯 强制最终解析：确保parts字段被正确填充
                    logger.debug("Stream finished for message $aiMessageId, forcing final message processing")
                    val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
                    val finalizedMessage = currentMessageProcessor.finalizeMessageProcessing(updatedMessage)
                    updatedMessage = finalizedMessage.copy(
                        contentStarted = true
                    )
                    
                    // 🎯 同步流式消息到 messages 列表（一次性更新）
                    stateHolder.syncStreamingMessageToList(aiMessageId, isImageGeneration)
                    updatedMessage = mergeStreamingCompletionMessage(
                        syncedMessage = messageList[messageIndex],
                        finalizedMessage = finalizedMessage,
                    )
                    updatedMessage = applyEstimatedTokenUsageFallback(updatedMessage)
                    logger.debug("Synced streaming message $aiMessageId to messages list")
                    
                    // 暂停时不触发UI刷新，等待恢复后统一刷新
                    if (!stateHolder._isStreamingPaused.value) {
                        try {
                            if (updatedMessage.text.isNotBlank()) {
                                onAiMessageFullTextChanged(aiMessageId, updatedMessage.text)
                            }
                        } catch (e: Exception) {
                            logger.warn("onAiMessageFullTextChanged in Finish handler failed: ${e.message}")
                        }
                    }
    
                    // 核心修复：在消息处理完成并最终化之后，在这里触发强制保存
                    messageList[messageIndex] = updatedMessage
                    viewModelScope.launch(Dispatchers.IO) {
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                    }
    
                    stateHolder.clearMessageStatus(aiMessageId, isImageGeneration)
                    updatedMessage = reconcileMessageAfterStatusClear(
                        updatedMessage = updatedMessage,
                        clearedMessage = messageList[messageIndex],
                    )
    
                    // 按用户期望：不要在 finish 事件处强制切 isStreaming=false
                    // 说明：
                    // - 是否呈现“最终渲染”由统一 Markdown 渲染层的 looksFinalized 判定决定
                    // - 流程收尾的 isApiCalling 状态与 streamingId 归位交由上游 finally 处理
                    // - 此处仅记录会话摘要，避免二次清空引发 UI 抖动
                    PerformanceMonitor.onFinish(aiMessageId)
                }
                is AppStreamEvent.Error -> {
                    // 🎯 错误事件会触发 updateMessageWithError，它会自动刷新和清理 buffer
                    PerformanceMonitor.recordEvent(aiMessageId, "Error", 0)
                    updateMessageWithError(aiMessageId, IOException(appEvent.message), isImageGeneration)
                }
                is AppStreamEvent.ToolCall -> {
                    logger.debug("Received ToolCall event: ${appEvent.name}")
                    val toolStatus = appEvent.status?.takeIf { it.isNotBlank() }
                        ?: buildToolCallStatus(appEvent.name)
                    val latestMessage = latestMessageForUpdate()
                    val executionSteps = mergeExecutionStep(
                        latestMessage.executionSteps,
                        executionStepForToolCall(appEvent),
                    )
                    updatedMessage = if (
                        toolStatus.isNotBlank() &&
                        !latestMessage.contentStarted &&
                        latestMessage.text.isBlank()
                    ) {
                        latestMessage.copy(
                            currentWebSearchStage = toolStatus,
                            executionSteps = executionSteps,
                        )
                    } else {
                        latestMessage.copy(executionSteps = executionSteps)
                    }
                }
            }

            if (updatedMessage.contentStarted && updatedMessage.executionSteps.any { !it.completed }) {
                updatedMessage = updatedMessage.copy(
                    executionSteps = updatedMessage.executionSteps.completeAllSteps(),
                )
            }
    
            // 若处于"暂停流式显示"状态，则不更新UI，仅由恢复时一次性刷新
            if ((
                    !stateHolder._isStreamingPaused.value ||
                        appEvent is AppStreamEvent.Usage ||
                        appEvent is AppStreamEvent.NativeContextCompaction
                    ) &&
                updatedMessage != currentMessage
            ) {
                messageList[messageIndex] = updatedMessage
            }
        }
    
        // Removed auto-scroll trigger during streaming
        // if (stateHolder.shouldAutoScroll()) {
        //     triggerScrollToBottom()
        // }
    }

    private fun resetRetryCount(messageId: String) {
        retryCountMap.remove(messageId)
    }

    private suspend fun updateMessageWithError(
        messageId: String,
        error: Throwable,
        isImageGeneration: Boolean = false,
        allowRetry: Boolean = true,
    ) {
        errorHandler.updateMessageWithError(messageId, error, isImageGeneration, allowRetry)
    }

    /** 将图像模式返回的所有图片来源统一持久化。 */
    internal suspend fun persistGeneratedImageUrlsForMessage(
        messageId: String,
        urls: List<String>
    ): PersistedGeneratedImageUrlsResult {
        if (urls.isEmpty()) return PersistedGeneratedImageUrlsResult(emptyList(), emptyList())
        val out = mutableListOf<String>()
        val failures = mutableListOf<ImagePersistenceFailure>()
        for ((idx, url) in urls.withIndex()) {
            val source = url.trim()
            when (val result = historyManager.persistGeneratedImageSource(source, messageId, idx)) {
                is ImagePersistenceResult.Success -> out += result.filePath
                is ImagePersistenceResult.Failure -> failures += result.reason
            }
        }
        return PersistedGeneratedImageUrlsResult(out, failures)
    }
}

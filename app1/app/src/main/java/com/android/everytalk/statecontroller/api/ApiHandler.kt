package com.android.everytalk.statecontroller

import android.content.Context
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.ui.components.toRecoveredMarkdown
import com.android.everytalk.data.network.AppStreamEvent
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
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit,
    private val triggerScrollToBottom: () -> Unit,
) {
    private val logger = AppLogger.forComponent("ApiHandler")
    private val jsonParserForError = Json { ignoreUnknownKeys = true }
    // 为每个会话创建独立的MessageProcessor实例，确保会话隔离
    private val messageProcessorMap = ConcurrentHashMap<String, MessageProcessor>()
    private val processedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val generatedImageSourceFingerprints = ConcurrentHashMap<String, MutableSet<String>>()
    private val messageTokenUsageStore = MessageTokenUsageStore()
    
    // 🛡️ 防 prompt 泄露：为每个消息创建独立的流式检测器
    private val promptLeakDetectors = ConcurrentHashMap<String, PromptLeakGuard.StreamingDetector>()

    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"
    private val retryCountMap = ConcurrentHashMap<String, Int>()

    private val errorHandler by lazy {
        ApiHandlerErrorController(
            stateHolder = stateHolder,
            historyManager = historyManager,
            messageProcessorMap = messageProcessorMap,
            retryCountMap = retryCountMap,
            logger = logger,
        )
    }

    private val streamProcessor by lazy {
        ApiHandlerStreamProcessor(
            stateHolder = stateHolder,
            viewModelScope = viewModelScope,
            historyManager = historyManager,
            messageProcessorMap = messageProcessorMap,
            processedMessageIds = processedMessageIds,
            generatedImageSourceFingerprints = generatedImageSourceFingerprints,
            promptLeakDetectors = promptLeakDetectors,
            retryCountMap = retryCountMap,
            messageTokenUsageStore = messageTokenUsageStore,
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
            messageTokenUsageStore = messageTokenUsageStore,
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
            if (!markPreparedMessageFailed(messageList, messageId, errorText)) {
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
                            // 🔥 关键修复：同步归档图片，确保图片保存成功后再更新消息
                            // 先在 IO 线程完成归档，再更新消息，避免异步导致的数据不一致
                            logger.debug("[ImageGen] 🖼️ Starting synchronous image archival for ${imageUrls.size} images")
                            
                            val archiveResult = withContext(Dispatchers.IO) {
                                streamProcessor.archiveImageUrlsForMessage(aiMessageId, imageUrls)
                            }
                            val archivedUrls = archiveResult.urls
                            
                            // 使用归档后的本地路径（或回退到原始URL）更新消息
                            withContext(Dispatchers.Main.immediate) {
                                val messageList = stateHolder.imageGenerationMessages
                                val index = messageList.indexOfFirst { it.id == aiMessageId }
                                logger.debug("[ImageGen] 🖼️ Looking for message with ID: $aiMessageId, found at index: $index")
                                
                                if (index != -1) {
                                    val currentMessage = messageList[index]
                                    logger.debug(
                                        "[ImageGen] Current message: id=${currentMessage.id}, hasImageUrls=${currentMessage.imageUrls?.isNotEmpty()}, textChars=${currentMessage.text.length}"
                                    )
                                    
                                    val archiveFailureText = if (archiveResult.failedCount > 0) {
                                        "\n\n> 图片生成成功，但本地保存失败。"
                                    } else {
                                        ""
                                    }
                                    val updatedMessage = currentMessage.copy(
                                        imageUrls = archivedUrls,
                                        text = (responseText ?: currentMessage.text) + archiveFailureText,
                                        contentStarted = true,
                                        isError = archivedUrls.isEmpty(),
                                        currentWebSearchStage = null,
                                        executionStatus = null
                                    )
                                    
                                    // 🔥 关键修复：使用removeAt+add替代直接赋值，确保触发Compose重组
                                    messageList.removeAt(index)
                                    messageList.add(index, updatedMessage)
                                    
                                    logger.debug("[ImageGen] 🖼️ Updated message with ${archivedUrls.size} archived image URLs at index $index")
                                    logger.debug("[ImageGen] 🖼️ Message list size after update: ${messageList.size}")
                                    
                                    // 🔥 强制触发状态变化，确保Flow重新计算
                                    stateHolder.isImageConversationDirty.value = true
                                    
                                    logger.debug("[ImageGen] 🖼️ Marked conversation as dirty to trigger UI update")
                                } else {
                                    logger.error("[ImageGen] 🖼️ ERROR: Message with ID $aiMessageId not found in list!")
                                    logger.debug("[ImageGen] 🖼️ Current message list IDs: ${messageList.map { it.id }}")
                                }
                            }

                            // 🔥 归档完成后立即强制保存历史，确保本地路径持久化
                            withContext(Dispatchers.IO) {
                                try {
                                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)
                                    logger.debug("[ImageGen] 🖼️ History saved with archived image paths")
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
                val finalAttachments = attachmentsToPassToApiClient.toMutableList()
                if (audioBase64 != null) {
                    finalAttachments.add(Audio(id = UUID.randomUUID().toString(), mimeType = mimeType ?: "audio/3gpp", data = audioBase64))
                }
                // 强制使用直连模式
                var activeRequest = requestBody
                var activeContextSnapshot = contextUsageSnapshot?.copy(messageId = aiMessageId)
                val attemptedRecoveries = mutableSetOf<RequestErrorCategory>()
                while (true) {
                    var recoveryDecision: ContextRecoveryDecision? = null
                    logger.debug("Stream started for message $aiMessageId")
                    ApiClient.streamChatResponse(
                        activeRequest,
                        finalAttachments,
                        applicationContextForApiClient
                    ).takeWhile { appEvent ->
                            val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                            val currentStreamingId = if (isImageGeneration)
                                stateHolder._currentImageStreamingAiMessageId.value
                            else
                                stateHolder._currentTextStreamingAiMessageId.value
                            if (currentJob != thisJob || currentStreamingId != aiMessageId) {
                                thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                                return@takeWhile false
                            }
                            // 🎯 Task 11: Monitor memory usage during long streaming sessions
                            // Check memory periodically to detect potential issues
                            // Requirements: 1.4, 3.4
                            stateHolder.checkMemoryUsage()
                            // Record memory snapshot for session summary
                            run {
                                val rt = Runtime.getRuntime()
                                val usedMB = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
                                val maxMB = (rt.maxMemory() / (1024 * 1024)).toInt()
                                PerformanceMonitor.recordMemory(aiMessageId, usedMB, maxMB)
                            }

                            if (appEvent is AppStreamEvent.Error) {
                                val currentProcessor = messageProcessorMap[aiMessageId]
                                val messageList = if (isImageGeneration) {
                                    stateHolder.imageGenerationMessages
                                } else {
                                    stateHolder.messages
                                }
                                val currentMessage = messageList.firstOrNull { it.id == aiMessageId }
                                val hasPartialOutput = !currentProcessor?.getCurrentText().isNullOrBlank() ||
                                    !currentProcessor?.getCurrentReasoning().isNullOrBlank() ||
                                    !currentMessage?.text.isNullOrBlank() ||
                                    !currentMessage?.reasoning.isNullOrBlank()
                                recoveryDecision = ContextRecoveryPolicy.recover(
                                    request = activeRequest,
                                    error = appEvent.toProviderErrorInfo(),
                                    hasPartialOutput = hasPartialOutput,
                                    attemptedCategories = attemptedRecoveries,
                                )
                                if (recoveryDecision != null) return@takeWhile false
                            }

                            processStreamEvent(appEvent, aiMessageId, isImageGeneration)
                            appEvent !is AppStreamEvent.StreamEnd && appEvent !is AppStreamEvent.Error
                        }.collect { }

                    val decision = recoveryDecision ?: break
                    attemptedRecoveries += decision.category
                    activeRequest = decision.request
                    val currentSnapshot = activeContextSnapshot
                    val refreshedSnapshot = currentSnapshot?.let { snapshot ->
                        RequestTokenEstimator.estimate(
                            messages = activeRequest.messages,
                            tools = activeRequest.tools,
                        ).toContextUsageSnapshot(
                            messageId = aiMessageId,
                            configId = snapshot.configId,
                            reservedOutputTokens = activeRequest.generationConfig?.maxOutputTokens
                                ?.toLong()
                                ?: snapshot.reservedOutputTokens,
                            contextWindowTokens = decision.effectiveMaxContextTokens
                                ?.toLong()
                                ?: snapshot.contextWindowTokens,
                        )
                    }
                    if (refreshedSnapshot != null) {
                        activeContextSnapshot = refreshedSnapshot
                        withContext(Dispatchers.Main.immediate) {
                            val messageList = if (isImageGeneration) {
                                stateHolder.imageGenerationMessages
                            } else {
                                stateHolder.messages
                            }
                            val index = messageList.indexOfFirst { it.id == aiMessageId }
                            if (index >= 0) {
                                messageList[index] = messageList[index].copy(
                                    contextUsageSnapshot = refreshedSnapshot
                                )
                            }
                        }
                    }
                    messageProcessorMap[aiMessageId]?.reset()
                    stateHolder.updateMessageStatus(
                        aiMessageId,
                        when (decision.category) {
                            RequestErrorCategory.INPUT_CONTEXT_TOO_LONG -> "正在缩减上下文并重试"
                            RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH -> "正在调整输出上限并重试"
                            else -> "正在重试"
                        },
                        isImageGeneration,
                    )
                    logger.debug(
                        "Context recovery retry: category=${decision.category}, " +
                            "messages=${activeRequest.messages.size}, " +
                            "maxOutput=${activeRequest.generationConfig?.maxOutputTokens}"
                    )
                }
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
                messageTokenUsageStore.remove(aiMessageId)

                val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                if (currentJob == thisJob) {
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
            }
        }
    }

    private suspend fun processStreamEvent(
        appEvent: AppStreamEvent,
        aiMessageId: String,
        isImageGeneration: Boolean = false,
    ) {
        streamProcessor.processStreamEvent(appEvent, aiMessageId, isImageGeneration)
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

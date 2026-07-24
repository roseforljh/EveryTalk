package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.openclaw.OpenClawRuntimeState
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.PromptLeakGuard
import com.android.everytalk.util.debug.PerformanceMonitor
import com.android.everytalk.util.messageprocessor.MessageProcessor
import com.android.everytalk.util.text.TextSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val MAX_REMOTE_IMAGE_SOURCE_CHARS = 16 * 1024

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

internal sealed interface PreparedGeneratedImage {
    data class Ready(val source: String) : PreparedGeneratedImage
    data object Failed : PreparedGeneratedImage
    data object Duplicate : PreparedGeneratedImage
}

internal data class ArchivedImageUrlsResult(
    val urls: List<String>,
    val failedCount: Int,
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
        if (source.startsWith("data:image", ignoreCase = true)) {
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
        val isDataImage = trimmedSource.startsWith("data:image", ignoreCase = true)
        val isRemote = trimmedSource.startsWith("http://", ignoreCase = true) ||
            trimmedSource.startsWith("https://", ignoreCase = true)
        if (isRemote && trimmedSource.length > MAX_REMOTE_IMAGE_SOURCE_CHARS) {
            logger.warn("Rejected generated image source: scheme=remote, chars=${trimmedSource.length}, messageId=$messageId")
            return PreparedGeneratedImage.Failed
        }
        if (!isDataImage && !isRemote) {
            logger.warn("Rejected generated image source: scheme=unsupported, chars=${trimmedSource.length}, messageId=$messageId")
            return PreparedGeneratedImage.Failed
        }
    
        val persisted = withContext(Dispatchers.IO) {
            historyManager.persistMessageImageSource(trimmedSource, messageId, index)
        }
        return when {
            !persisted.isNullOrBlank() -> PreparedGeneratedImage.Ready(persisted)
            isRemote -> PreparedGeneratedImage.Ready(trimmedSource)
            else -> PreparedGeneratedImage.Failed
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
                    PreparedGeneratedImage.Failed -> {
                        stateHolder.appendContentToMessage(
                            aiMessageId,
                            "\n\n> 图片生成成功，但本地保存失败。\n\n",
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
                        val deltaChunk = TextSanitizer.removeUnicodeReplacementCharacters(appEvent.text)
                        // 过滤纯空白内容，防止后端发送大量空格导致卡死
                        if (!deltaChunk.isNullOrEmpty() && deltaChunk.isNotBlank()) {
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
                        val deltaChunk = TextSanitizer.removeUnicodeReplacementCharacters(appEvent.text)
                        // 过滤纯空白内容
                        if (!deltaChunk.isNullOrEmpty() && deltaChunk.isNotBlank()) {
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
                    stateHolder.updateOpenClawGatewayStatus(appEvent.stage)
                    if (appEvent.stage.startsWith("agent_run:")) {
                        val runId = appEvent.stage.substringAfter(':', "").ifBlank { null }
                        val current = OpenClawRuntimeState.current()
                        current?.sessionKey?.let { sessionKey ->
                            OpenClawRuntimeState.update(sessionKey = sessionKey, runId = runId)
                        }
                    }
                }
                is AppStreamEvent.ExecutionStatusUpdate -> {
                    updatedMessage = if (currentMessage.contentStarted || currentMessage.text.isNotBlank()) {
                        updatedMessage.copy(
                            currentWebSearchStage = null,
                            executionStatus = null
                        )
                    } else if (appEvent.status.isNullOrBlank()) {
                        updatedMessage.copy(
                            currentWebSearchStage = null,
                            executionStatus = null
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
                    viewModelScope.launch(Dispatchers.IO) {
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                    }
    
                    stateHolder.clearMessageStatus(aiMessageId, isImageGeneration)
                    updatedMessage = reconcileMessageAfterStatusClear(
                        updatedMessage = updatedMessage,
                        clearedMessage = messageList[messageIndex],
                    )
    
                    if (!isImageGeneration) {
                        if (stateHolder._currentTextStreamingAiMessageId.value == aiMessageId) {
                            stateHolder._isTextApiCalling.value = false
                            stateHolder._currentTextStreamingAiMessageId.value = null
                        }
                    } else {
                        if (stateHolder._currentImageStreamingAiMessageId.value == aiMessageId) {
                            stateHolder._isImageApiCalling.value = false
                            stateHolder._currentImageStreamingAiMessageId.value = null
                        }
                    }
                    stateHolder.updateOpenClawSessionId(null)
                    OpenClawRuntimeState.current()?.sessionKey?.let { sessionKey ->
                        OpenClawRuntimeState.update(sessionKey = sessionKey, runId = null)
                    }
                    
                    // 按用户期望：不要在 finish 事件处强制切 isStreaming=false
                    // 说明：
                    // - 是否呈现“最终渲染”由统一 Markdown 渲染层的 looksFinalized 判定决定
                    // - 流程收尾的 isApiCalling 状态与 streamingId 归位交由上游 onCompletion 分支处理
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
                    if (!toolStatus.isNullOrBlank() && !currentMessage.contentStarted && currentMessage.text.isBlank()) {
                        stateHolder.updateMessageStatus(
                            aiMessageId,
                            toolStatus,
                            isImageGeneration
                        )
                    }
                }
                // 其他事件类型（如 ImageGeneration）暂时不直接更新消息UI，由特定逻辑处理
                else -> {
                    logger.debug("Handling other event type: ${appEvent::class.simpleName}")
                }
            }
    
            // 若处于"暂停流式显示"状态，则不更新UI，仅由恢复时一次性刷新
            if (!stateHolder._isStreamingPaused.value && updatedMessage != currentMessage) {
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

    /** 将图像模式返回的来源统一归档，危险数据 URI 失败时不会回填原值。 */
    internal suspend fun archiveImageUrlsForMessage(
        messageId: String,
        urls: List<String>
    ): ArchivedImageUrlsResult {
        if (urls.isEmpty()) return ArchivedImageUrlsResult(emptyList(), 0)
        val out = mutableListOf<String>()
        var failedCount = 0
        for ((idx, url) in urls.withIndex()) {
            val source = url.trim()
            val lower = source.lowercase(Locale.ROOT)
            val isDataImage = lower.startsWith("data:image")
            val isRemote = lower.startsWith("http://") || lower.startsWith("https://")
            if ((!isDataImage && !isRemote) || (isRemote && source.length > MAX_REMOTE_IMAGE_SOURCE_CHARS)) {
                failedCount++
                continue
            }
    
            val saved = historyManager.persistMessageImageSource(source, messageId, idx)
            if (!saved.isNullOrBlank()) {
                out += saved
            } else if (isRemote) {
                out += source
            } else {
                failedCount++
            }
        }
        return ArchivedImageUrlsResult(out, failedCount)
    }
}

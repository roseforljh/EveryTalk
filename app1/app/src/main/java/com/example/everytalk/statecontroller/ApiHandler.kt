package com.example.everytalk.statecontroller

import android.content.Context
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.data.DataClass.ApiContentPart
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.models.SelectedMediaItem.Audio
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import com.example.everytalk.util.AppLogger
import com.example.everytalk.util.MessageProcessor
import com.example.everytalk.util.ProcessedEventResult
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit,
    private val triggerScrollToBottom: () -> Unit
) {
    private val logger = AppLogger.forComponent("ApiHandler")
    private val jsonParserForError = Json { ignoreUnknownKeys = true }
    private val messageProcessor = MessageProcessor()
    private var eventChannel: Channel<AppStreamEvent>? = null

    @Serializable
    private data class BackendErrorContent(val message: String? = null, val code: Int? = null)

    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"


    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        logger.debug("Cancelling API job: $reason, isNewMessageSend=$isNewMessageSend")
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX $reason" else "$USER_CANCEL_PREFIX $reason"

        if (jobToCancel?.isActive == true && messageIdBeingCancelled != null) {
            val partialText = messageProcessor.getCurrentText().trim()
            val partialReasoning = messageProcessor.getCurrentReasoning()

            if (partialText.isNotBlank() || partialReasoning != null) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val currentMessage = stateHolder.messages[index]
                        val updatedMessage = currentMessage.copy(
                            text = partialText,
                            reasoning = partialReasoning,
                            contentStarted = currentMessage.contentStarted || partialText.isNotBlank(),
                            isError = false
                        )
                        stateHolder.messages[index] = updatedMessage

                        if (partialText.isNotBlank()) {
                            onAiMessageFullTextChanged(messageIdBeingCancelled, partialText)
                        }
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                    }
                }
            }
        }

        stateHolder._isApiCalling.value = false
        if (!isNewMessageSend && stateHolder._currentStreamingAiMessageId.value == messageIdBeingCancelled) {
            stateHolder._currentStreamingAiMessageId.value = null
        }
        messageProcessor.reset()

        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        val isPlaceholder = msg.sender == Sender.AI && msg.text.isBlank() &&
                                msg.reasoning.isNullOrBlank() && msg.webSearchResults.isNullOrEmpty() &&
                                msg.currentWebSearchStage.isNullOrEmpty() && !msg.contentStarted && !msg.isError
                        if (isPlaceholder) {
                            logger.debug("Removing placeholder message: ${msg.id}")
                            stateHolder.messages.removeAt(index)
                        }
                    }
                }
            }
        }
        jobToCancel?.takeIf { it.isActive }
            ?.cancel(CancellationException(specificCancelReason))
        stateHolder.apiJob = null
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
        mimeType: String? = null
    ) {
        val contextForLog = when (val lastUserMsg = requestBody.messages.lastOrNull {
            it.role == "user"
        }) {
            is com.example.everytalk.data.DataClass.SimpleTextApiMessage -> lastUserMsg.content
            is com.example.everytalk.data.DataClass.PartsApiMessage -> lastUserMsg.parts
                .filterIsInstance<ApiContentPart.Text>().joinToString(" ") { it.text }

            else -> null
        }?.take(30) ?: "N/A"

        logger.debug("Starting new stream chat response with context: '$contextForLog'")
        cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true)

        // 使用MessageProcessor创建新的AI消息
        val newAiMessage = messageProcessor.createNewAiMessage()
        val aiMessageId = newAiMessage.id

        // 重置消息处理器
        messageProcessor.reset()

        viewModelScope.launch(Dispatchers.Main.immediate) {
            var insertAtIndex = stateHolder.messages.size
            if (afterUserMessageId != null) {
                val userMessageIndex =
                    stateHolder.messages.indexOfFirst { it.id == afterUserMessageId }
                if (userMessageIndex != -1) insertAtIndex = userMessageIndex + 1
            }
            insertAtIndex = insertAtIndex.coerceAtMost(stateHolder.messages.size)
            stateHolder.messages.add(insertAtIndex, newAiMessage)
            onNewAiMessageAdded()
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap[aiMessageId] = false
            onMessagesProcessed()
        }

        eventChannel?.close()
        val newEventChannel = Channel<AppStreamEvent>(Channel.UNLIMITED)
        eventChannel = newEventChannel

        viewModelScope.launch(Dispatchers.Default) {
            newEventChannel.consumeAsFlow()
                .buffer(Channel.UNLIMITED)
                .collect {
                    val currentChunkIndex = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                    if (currentChunkIndex != -1) {
                        updateMessageInState(currentChunkIndex)
                        if (stateHolder.shouldAutoScroll()) {
                            triggerScrollToBottom()
                        }
                    }
                }
        }

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key]
            try {
                val finalAttachments = attachmentsToPassToApiClient.toMutableList()
                if (audioBase64 != null) {
                    finalAttachments.add(Audio(id = UUID.randomUUID().toString(), mimeType = mimeType ?: "audio/3gpp", data = audioBase64))
                }
                if (requestBody.provider == "gemini") {
                    try {
                        val geminiRequest = com.example.everytalk.data.DataClass.GeminiApiRequest(
                            contents = requestBody.messages.map {
                                com.example.everytalk.data.DataClass.Content(
                                    role = if (it.role == "assistant") "model" else it.role,
                                    parts = when (it) {
                                        is com.example.everytalk.data.DataClass.SimpleTextApiMessage -> listOf(com.example.everytalk.data.DataClass.Part.Text(it.content))
                                        is com.example.everytalk.data.DataClass.PartsApiMessage -> it.parts.mapNotNull { part ->
                                            when (part) {
                                                is ApiContentPart.Text -> com.example.everytalk.data.DataClass.Part.Text(part.text)
                                                is ApiContentPart.InlineData -> com.example.everytalk.data.DataClass.Part.InlineData(part.mimeType, part.base64Data)
                                                is ApiContentPart.FileUri -> null
                                            }
                                        }
                                        else -> emptyList()
                                    }
                                )
                            }
                        )
                        val response = ApiClient.generateContent(requestBody.apiKey!!, geminiRequest, audioBase64, mimeType)
                        val firstPart = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()
                        val text = (firstPart as? com.example.everytalk.data.DataClass.Part.Text)?.text ?: ""
                        processStreamEvent(AppStreamEvent.Text(text), aiMessageId)
                        processStreamEvent(AppStreamEvent.StreamEnd(aiMessageId), aiMessageId)
                    } catch (e: Exception) {
                        updateMessageWithError(aiMessageId, e)
                        onRequestFailed(e)
                    }
                } else {
                    ApiClient.streamChatResponse(
                        requestBody,
                        finalAttachments,
                        applicationContextForApiClient
                    )
                        .onStart { logger.debug("Stream started for message $aiMessageId") }
                        .catch { e ->
                            if (e !is CancellationException) {
                                logger.error("Stream error", e)
                                updateMessageWithError(aiMessageId, e)
                                onRequestFailed(e)
                            }
                        }
                        .onCompletion { cause ->
                            logger.debug("Stream completed for message $aiMessageId, cause: ${cause?.message}")
                            newEventChannel.close()
                            val targetMsgId = aiMessageId
                            val isThisJobStillTheCurrentOne = stateHolder.apiJob == thisJob

                            if (isThisJobStillTheCurrentOne) {
                                stateHolder._isApiCalling.value = false
                                if (stateHolder._currentStreamingAiMessageId.value == targetMsgId) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            }
                            if (stateHolder.reasoningCompleteMap[targetMsgId] != true) {
                                stateHolder.reasoningCompleteMap[targetMsgId] = true
                            }

                            val cancellationMessageFromCause =
                                (cause as? CancellationException)?.message
                            val wasCancelledByApiHandler =
                                cancellationMessageFromCause?.startsWith(USER_CANCEL_PREFIX) == true ||
                                        cancellationMessageFromCause?.startsWith(
                                            NEW_STREAM_CANCEL_PREFIX
                                        ) == true

                            if (!wasCancelledByApiHandler) {
                                val finalFullText = messageProcessor.getCurrentText().trim()
                                if (finalFullText.isNotBlank()) {
                                    onAiMessageFullTextChanged(targetMsgId, finalFullText)
                                }
                                if (cause == null || (cause !is CancellationException)) {
                                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = cause != null)
                                }
                            }

                            messageProcessor.reset()

                            withContext(Dispatchers.Main.immediate) {
                                val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                                if (finalIdx != -1) {
                                    val msg = stateHolder.messages[finalIdx]
                                    if (cause == null && !msg.isError) {
                                        val updatedMsg = msg.copy(
                                            text = msg.text,
                                            reasoning = msg.reasoning,
                                            contentStarted = msg.contentStarted || msg.text.isNotBlank()
                                        )
                                        if (updatedMsg != msg) {
                                            stateHolder.messages[finalIdx] = updatedMsg
                                        }
                                        if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                            stateHolder.messageAnimationStates[targetMsgId] = true
                                        }
                                    } else if (cause is CancellationException) {
                                        if (!wasCancelledByApiHandler) {
                                            val hasMeaningfulContent = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()
                                            if (hasMeaningfulContent) {
                                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                                            } else if (msg.sender == Sender.AI && !msg.isError) {
                                                stateHolder.messages.removeAt(finalIdx)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .collect { appEvent ->
                            if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                                thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                                return@collect
                            }
                            processStreamEvent(appEvent, aiMessageId)
                            newEventChannel.trySend(appEvent)
                        }
                }
            } catch (e: Exception) {
                messageProcessor.reset()
                when (e) {
                    is CancellationException -> {
                        logger.debug("Stream cancelled: ${e.message}")
                    }
                    else -> {
                        logger.error("Stream exception", e)
                        updateMessageWithError(aiMessageId, e)
                        onRequestFailed(e)
                    }
                }
            } finally {
                if (stateHolder.apiJob == thisJob) {
                    stateHolder.apiJob = null
                    if (stateHolder._isApiCalling.value && stateHolder._currentStreamingAiMessageId.value == aiMessageId) {
                        stateHolder._isApiCalling.value = false
                        stateHolder._currentStreamingAiMessageId.value = null
                    }
                }
            }
        }
    }

    private suspend fun processStreamEvent(appEvent: AppStreamEvent, aiMessageId: String) {
        val result = messageProcessor.processStreamEvent(appEvent, aiMessageId)

        when (appEvent) {
            is AppStreamEvent.Text -> {
                // Handled by messageProcessor
            }
            is AppStreamEvent.Content -> {
                stateHolder.appendContentToMessage(aiMessageId, appEvent.text)
            }
            is AppStreamEvent.Reasoning -> {
                stateHolder.appendReasoningToMessage(aiMessageId, appEvent.text)
            }
            is AppStreamEvent.StreamEnd -> {
                // Handled by onCompletion
            }
            is AppStreamEvent.Finish -> {
                // Handled by onCompletion
            }
            is AppStreamEvent.WebSearchStatus -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                val index = stateHolder.messages.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    val originalMessage = stateHolder.messages[index]
                    stateHolder.messages[index] = originalMessage.copy(
                        currentWebSearchStage = appEvent.stage
                    )
                }
            }
            is AppStreamEvent.WebSearchResults -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                val index = stateHolder.messages.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    val originalMessage = stateHolder.messages[index]
                    stateHolder.messages[index] = originalMessage.copy(
                        webSearchResults = appEvent.results
                    )
                }
            }
            is AppStreamEvent.ToolCall -> {
                // Handled by messageProcessor
            }
            is AppStreamEvent.Error -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                viewModelScope.launch {
                    updateMessageWithError(
                        messageId,
                        IOException(appEvent.message)
                    )
                }
            }
        }

        // 更新消息状态
        val index = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
        if (index != -1) {
            updateMessageInState(index)
        }
        if (stateHolder.shouldAutoScroll()) {
            triggerScrollToBottom()
        }
    }

    private fun updateMessageInState(index: Int) {
        val originalMessage = stateHolder.messages.getOrNull(index) ?: return
        
        // 从MessageProcessor获取当前文本和推理内容
        val accumulatedFullText = messageProcessor.getCurrentText()
        val accumulatedFullReasoning = messageProcessor.getCurrentReasoning()
        
        // 只有当内容有变化时才更新消息
        if (accumulatedFullText != originalMessage.text || accumulatedFullReasoning != originalMessage.reasoning) {
            val updatedMessage = originalMessage.copy(
                text = accumulatedFullText,
                reasoning = accumulatedFullReasoning,
                contentStarted = originalMessage.contentStarted || accumulatedFullText.isNotBlank()
            )
            
            stateHolder.messages[index] = updatedMessage
            
            // 通知文本变化
            if (accumulatedFullText.isNotEmpty()) {
                onAiMessageFullTextChanged(originalMessage.id, accumulatedFullText)
            }
        }
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        logger.error("Updating message with error", error)
        messageProcessor.reset()
        
        withContext(Dispatchers.Main.immediate) {
            val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = stateHolder.messages[idx]
                if (!msg.isError) {
                    val existingContent = (msg.text.takeIf { it.isNotBlank() }
                        ?: msg.reasoning?.takeIf { it.isNotBlank() && msg.text.isBlank() } ?: "")
                    val errorPrefix = if (existingContent.isNotBlank()) "\n\n" else ""
                    val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                        is IOException -> {
                            val message = error.message ?: "IO 错误"
                            if (message.contains("服务器错误") || message.contains("HTTP 错误")) {
                                // 对于 HTTP 状态错误，直接显示详细信息
                                message
                            } else {
                                "网络通讯故障: $message"
                            }
                        }
                        else -> "处理时发生错误: ${error.message ?: "未知应用错误"}"
                    }
                    val errorMsg = msg.copy(
                        text = existingContent + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true,
                        reasoning = if (existingContent == msg.reasoning && errorPrefix.isNotBlank()) null else msg.reasoning,
                        currentWebSearchStage = msg.currentWebSearchStage ?: "error_occurred"
                    )
                    stateHolder.messages[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) {
                        stateHolder.messageAnimationStates[messageId] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                }
            }
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
            }
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "服务响应错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "服务响应错误 ${response.status.value}: ${
                errorBody.take(150).replace(Regex("<[^>]*>"), "")
            }${if (errorBody.length > 150) "..." else ""}"
        }
    }

    private companion object {
        private const val ERROR_VISUAL_PREFIX = "⚠️ "
    }
}
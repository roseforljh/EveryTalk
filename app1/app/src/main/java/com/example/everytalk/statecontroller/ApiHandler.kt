package com.example.everytalk.statecontroller

import android.content.Context
import android.net.Uri
import java.io.File
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
import com.example.everytalk.util.FileManager
import com.example.everytalk.util.messageprocessor.MessageProcessor
import com.example.everytalk.util.messageprocessor.ProcessedEventResult
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


    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false, isImageGeneration: Boolean = false) {
        logger.debug("Cancelling API job: $reason, isNewMessageSend=$isNewMessageSend, isImageGeneration=$isImageGeneration")
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX $reason" else "$USER_CANCEL_PREFIX $reason"

        if (jobToCancel?.isActive == true && messageIdBeingCancelled != null) {
            val partialText = messageProcessor.getCurrentText().trim()
            val partialReasoning = messageProcessor.getCurrentReasoning()

            if (partialText.isNotBlank() || partialReasoning != null) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index =
                        messageList.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val currentMessage = messageList[index]
                        val updatedMessage = currentMessage.copy(
                            text = partialText,
                            reasoning = partialReasoning,
                            contentStarted = currentMessage.contentStarted || partialText.isNotBlank(),
                            isError = false
                        )
                        messageList[index] = updatedMessage

                        if (partialText.isNotBlank()) {
                            onAiMessageFullTextChanged(messageIdBeingCancelled, partialText)
                        }
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
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
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index =
                        messageList.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = messageList[index]
                        val isPlaceholder = msg.sender == Sender.AI && msg.text.isBlank() &&
                                msg.reasoning.isNullOrBlank() && msg.webSearchResults.isNullOrEmpty() &&
                                msg.currentWebSearchStage.isNullOrEmpty() && !msg.contentStarted && !msg.isError
                        if (isPlaceholder) {
                            logger.debug("Removing placeholder message: ${msg.id}")
                            messageList.removeAt(index)
                        }
                    }
                }
            }
        }
        jobToCancel?.takeIf { it.isActive }?.cancel(CancellationException(specificCancelReason))
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
        mimeType: String? = null,
        isImageGeneration: Boolean = false
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
        cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true, isImageGeneration = isImageGeneration)

        // 使用MessageProcessor创建新的AI消息
        val newAiMessage = Message(
            id = UUID.randomUUID().toString(),
            text = "",
            sender = Sender.AI,
            contentStarted = false
        )
        val aiMessageId = newAiMessage.id

        // 重置消息处理器
        messageProcessor.reset()

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            var insertAtIndex = messageList.size
            if (afterUserMessageId != null) {
                val userMessageIndex =
                    messageList.indexOfFirst { it.id == afterUserMessageId }
                if (userMessageIndex != -1) insertAtIndex = userMessageIndex + 1
            }
            insertAtIndex = insertAtIndex.coerceAtMost(messageList.size)
            messageList.add(insertAtIndex, newAiMessage)
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
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val currentChunkIndex = messageList.indexOfFirst { it.id == aiMessageId }
                    if (currentChunkIndex != -1) {
                        updateMessageInState(currentChunkIndex, isImageGeneration)
                        if (stateHolder.shouldAutoScroll()) {
                            triggerScrollToBottom()
                        }
                    }
                }
        }

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key]
            try {
               if (isImageGeneration) {
                   try {
                       val response = ApiClient.generateImage(requestBody)
                       logger.debug("[ImageGen] Received response from backend: $response")
                       
                       val imageUrlsFromResponse = response.images.mapNotNull { it.url.takeIf { url -> url.isNotBlank() } }

                       withContext(Dispatchers.Main.immediate) {
                           val messageList = stateHolder.imageGenerationMessages
                           val index = messageList.indexOfFirst { it.id == aiMessageId }
                           if (index != -1) {
                               val responseText = response.text ?: ""
                               if (responseText.startsWith("[CONTENT_FILTER]")) {
                                   val userFriendlyMessage = responseText.removePrefix("[CONTENT_FILTER]").trim()
                                   stateHolder.showSnackbar(userFriendlyMessage)
                                   messageList.removeAt(index)
                               } else {
                                   val currentMessage = messageList[index]
                                   val updatedMessage = currentMessage.copy(
                                       imageUrls = if (imageUrlsFromResponse.isNotEmpty()) imageUrlsFromResponse else currentMessage.imageUrls,
                                       text = responseText,
                                       contentStarted = true
                                   )
                                   logger.debug("[ImageGen] Updating message ${updatedMessage.id} with ${updatedMessage.imageUrls?.size ?: 0} images.")
                                   messageList[index] = updatedMessage
                               }
                           }
                       }
                       viewModelScope.launch(Dispatchers.IO) {
                           historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true)
                       }
                   } catch (e: Exception) {
                       logger.error("[ImageGen] Image processing failed for message $aiMessageId", e)
                       updateMessageWithError(aiMessageId, e, isImageGeneration = true)
                       onRequestFailed(e)
                   }
               } else {
                val finalAttachments = attachmentsToPassToApiClient.toMutableList()
                if (audioBase64 != null) {
                    finalAttachments.add(Audio(id = UUID.randomUUID().toString(), mimeType = mimeType ?: "audio/3gpp", data = audioBase64))
                }
                if (requestBody.channel == "Gemini") {
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
                        // This feature is disabled as it's not using the proxy
                        val text = "Audio processing via direct Gemini API is currently disabled."
                        processStreamEvent(AppStreamEvent.ContentFinal(text), aiMessageId)
                        processStreamEvent(AppStreamEvent.StreamEnd(aiMessageId), aiMessageId)
                    } catch (e: Exception) {
                        updateMessageWithError(aiMessageId, e, isImageGeneration)
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
                                updateMessageWithError(aiMessageId, e, isImageGeneration)
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
                                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = cause != null, isImageGeneration = isImageGeneration)
                                }
                            }

                            messageProcessor.reset()

                            withContext(Dispatchers.Main.immediate) {
                                val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                                val finalIdx = messageList.indexOfFirst { it.id == targetMsgId }
                                if (finalIdx != -1) {
                                    val msg = messageList[finalIdx]
                                    if (cause == null && !msg.isError) {
                                        val updatedMsg = msg.copy(
                                            text = msg.text,
                                            reasoning = msg.reasoning,
                                            contentStarted = msg.contentStarted || msg.text.isNotBlank()
                                        )
                                        if (updatedMsg != msg) {
                                            messageList[finalIdx] = updatedMsg
                                        }
                                        if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                            stateHolder.messageAnimationStates[targetMsgId] = true
                                        }
                                    } else if (cause is CancellationException) {
                                        if (!wasCancelledByApiHandler) {
                                            val hasMeaningfulContent = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()
                                            if (hasMeaningfulContent) {
                                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                                            } else if (msg.sender == Sender.AI && !msg.isError) {
                                                messageList.removeAt(finalIdx)
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
                            processStreamEvent(appEvent, aiMessageId, isImageGeneration)
                            newEventChannel.trySend(appEvent)
                        }
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
                        updateMessageWithError(aiMessageId, e, isImageGeneration)
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

    private suspend fun processStreamEvent(appEvent: AppStreamEvent, aiMessageId: String, isImageGeneration: Boolean = false) {
        val result = messageProcessor.processStreamEvent(appEvent, aiMessageId)

        // 根据MessageProcessor的处理结果来更新消息状态
        when (result) {
            is ProcessedEventResult.ContentUpdated, is ProcessedEventResult.ReasoningUpdated -> {
                // 这些事件由 updateMessageInState 处理，这里不需要操作
            }
            is ProcessedEventResult.ReasoningComplete -> {
                stateHolder.reasoningCompleteMap[aiMessageId] = true
            }
            is ProcessedEventResult.Error -> {
                logger.warn("MessageProcessor reported error: ${result.message}")
                updateMessageWithError(aiMessageId, IOException(result.message), isImageGeneration)
                // 不要return，继续处理其他事件，因为这可能只是格式处理的警告
                // return
            }
            else -> {
                // 对于其他类型的结果，继续处理原始事件
            }
        }

        // 继续处理一些不由MessageProcessor处理的事件类型
        when (appEvent) {
            is AppStreamEvent.Content -> {
                if (!appEvent.output_type.isNullOrBlank()) {
                    val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index = messageList.indexOfFirst { it.id == messageId }
                    if (index != -1) {
                        val originalMessage = messageList[index]
                        if (originalMessage.outputType != appEvent.output_type) {
                            messageList[index] = originalMessage.copy(outputType = appEvent.output_type)
                        }
                    }
                }
            }
            is AppStreamEvent.WebSearchStatus -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                val index = messageList.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    val originalMessage = messageList[index]
                    messageList[index] = originalMessage.copy(
                        currentWebSearchStage = appEvent.stage
                    )
                }
            }
            is AppStreamEvent.WebSearchResults -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                val index = messageList.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    val originalMessage = messageList[index]
                    messageList[index] = originalMessage.copy(
                        webSearchResults = appEvent.results
                    )
                }
            }
            is AppStreamEvent.Error -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                viewModelScope.launch {
                    updateMessageWithError(
                        messageId,
                        IOException(appEvent.message),
                        isImageGeneration
                    )
                }
            }
            is AppStreamEvent.OutputType -> {
                messageProcessor.setCurrentOutputType(appEvent.type)
            }
            is AppStreamEvent.ImageGeneration -> {
                val messageId = stateHolder._currentStreamingAiMessageId.value ?: return
                val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                val index = messageList.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    val originalMessage = messageList[index]
                    val updatedImageUrls = (originalMessage.imageUrls ?: emptyList()) + appEvent.imageUrl
                    val updatedMessage = originalMessage.copy(
                        imageUrls = updatedImageUrls,
                        contentStarted = true
                    )
                    messageList[index] = updatedMessage
                }
            }
            else -> {
                // 其他事件类型
            }
        }

        // 触发滚动（如果需要）
        if (stateHolder.shouldAutoScroll()) {
            triggerScrollToBottom()
        }
    }

    private fun updateMessageInState(index: Int, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
        val originalMessage = messageList.getOrNull(index) ?: return
         
        // 从MessageProcessor获取当前文本和推理内容
        val accumulatedFullText = messageProcessor.getCurrentText()
        val accumulatedFullReasoning = messageProcessor.getCurrentReasoning()
        val outputType = messageProcessor.getCurrentOutputType()
         
        // 只有当内容有变化时才更新消息
        if (accumulatedFullText != originalMessage.text ||
            accumulatedFullReasoning != originalMessage.reasoning ||
            outputType != originalMessage.outputType) {
            val updatedMessage = originalMessage.copy(
                text = accumulatedFullText,
                reasoning = accumulatedFullReasoning,
                outputType = outputType,
                contentStarted = originalMessage.contentStarted || accumulatedFullText.isNotBlank()
            )
             
            messageList[index] = updatedMessage
             
            // 通知文本变化
            if (accumulatedFullText.isNotEmpty()) {
                onAiMessageFullTextChanged(originalMessage.id, accumulatedFullText)
            }
        }
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable, isImageGeneration: Boolean = false) {
        logger.error("Updating message with error", error)
        messageProcessor.reset()
        
        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val idx = messageList.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = messageList[idx]
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
                    messageList[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) {
                        stateHolder.messageAnimationStates[messageId] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
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
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
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

@Serializable
private data class BackendErrorContent(val message: String? = null, val code: Int? = null)

class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit,
    private val triggerScrollToBottom: () -> Unit
) {
    private val logger = AppLogger.forComponent("ApiHandler")
    private val jsonParserForError = Json { ignoreUnknownKeys = true }
    // 为每个会话创建独立的MessageProcessor实例，确保会话隔离
    private val messageProcessorMap = mutableMapOf<String, MessageProcessor>()
    private var eventChannel: Channel<AppStreamEvent>? = null
    private val processedMessageIds = mutableSetOf<String>()

    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"
    private val ERROR_VISUAL_PREFIX = "⚠️ "


    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false, isImageGeneration: Boolean = false) {
        // 关键修复：增强日志，明确显示模式信息
        val modeInfo = if (isImageGeneration) "IMAGE_MODE" else "TEXT_MODE"
        logger.debug("Cancelling API job: $reason, Mode=$modeInfo, isNewMessageSend=$isNewMessageSend, isImageGeneration=$isImageGeneration")
        
        val jobToCancel = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
        val messageIdBeingCancelled = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX [$modeInfo] $reason" else "$USER_CANCEL_PREFIX [$modeInfo] $reason"

        if (jobToCancel?.isActive == true) {
            // 获取当前会话的消息处理器和块管理器
            val currentMessageProcessor = messageProcessorMap[messageIdBeingCancelled] ?: MessageProcessor()
            val partialText = currentMessageProcessor.getCurrentText().trim()
            val partialReasoning = currentMessageProcessor.getCurrentReasoning()

            if (partialText.isNotBlank() || partialReasoning != null) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index =
                        messageList.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val currentMessage = messageList[index]
                        val updatedMessage = currentMessage.copy(
                            contentStarted = currentMessage.contentStarted || partialText.isNotBlank(),
                            isError = false
                        )
                        messageList[index] = updatedMessage

                        if (partialText.isNotBlank() && messageIdBeingCancelled != null) {
                            onAiMessageFullTextChanged(messageIdBeingCancelled, partialText)
                        }
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                    }
                }
            }
        }

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
            messageProcessorMap.remove(messageIdBeingCancelled)
        }

        if (messageIdBeingCancelled != null) {
            if (isImageGeneration) {
                stateHolder.imageReasoningCompleteMap.remove(messageIdBeingCancelled)
            } else {
                stateHolder.textReasoningCompleteMap.remove(messageIdBeingCancelled)
            }
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
                        val isHistoryLoaded = stateHolder._loadedHistoryIndex.value != null || stateHolder._loadedImageGenerationHistoryIndex.value != null
                        if (isPlaceholder && !isHistoryLoaded) {
                            logger.debug("Removing placeholder message: ${msg.id}")
                            messageList.removeAt(index)
                        }
                    }
                }
            }
        }
        jobToCancel?.cancel(CancellationException(specificCancelReason))
        if (isImageGeneration) {
            stateHolder.imageApiJob = null
        } else {
            stateHolder.textApiJob = null
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

        // 为新消息创建独立的消息处理器和块管理器
        val newMessageProcessor = MessageProcessor()
        messageProcessorMap[aiMessageId] = newMessageProcessor

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            var insertAtIndex = messageList.size
            messageList.add(newAiMessage)
            onNewAiMessageAdded()
            if (isImageGeneration) {
                stateHolder._currentImageStreamingAiMessageId.value = aiMessageId
                stateHolder._isImageApiCalling.value = true
                stateHolder.imageReasoningCompleteMap[aiMessageId] = false
            } else {
                stateHolder._currentTextStreamingAiMessageId.value = aiMessageId
                stateHolder._isTextApiCalling.value = true
                stateHolder.textReasoningCompleteMap[aiMessageId] = false
            }
        }

        eventChannel?.close()
        val newEventChannel = Channel<AppStreamEvent>(Channel.CONFLATED)
        eventChannel = newEventChannel

        viewModelScope.launch(Dispatchers.Default) {
            newEventChannel.consumeAsFlow()
                .sample(100)
                .collect {
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    // No-op in the new model, updates are driven by block list changes
                }
        }

        val job = viewModelScope.launch {
            val thisJob = coroutineContext[Job]
            if (isImageGeneration) {
                stateHolder.imageApiJob = thisJob
            } else {
                stateHolder.textApiJob = thisJob
            }
            try {
               if (isImageGeneration) {
                    try {
                        val response = ApiClient.generateImage(requestBody)
                        logger.debug("[ImageGen] Response received: $response")

                        val imageUrls = response.images.mapNotNull { it.url.takeIf(String::isNotBlank) }
                        val responseText = response.text

                        if (imageUrls.isNotEmpty()) {
                            // 成功获取图片
                            withContext(Dispatchers.Main.immediate) {
                                val messageList = stateHolder.imageGenerationMessages
                                val index = messageList.indexOfFirst { it.id == aiMessageId }
                                if (index != -1) {
                                    val currentMessage = messageList[index]
                                    val updatedMessage = currentMessage.copy(
                                        imageUrls = imageUrls,
                                        text = responseText ?: currentMessage.text,
                                        contentStarted = true,
                                        isError = false
                                    )
                                    messageList[index] = updatedMessage
                                }
                            }
                            viewModelScope.launch(Dispatchers.IO) {
                                historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true)
                            }
                        } else {
                            // 后端已完成所有重试但仍无图片，将返回的文本作为错误消息处理
                            val error = IOException(responseText ?: "图像生成失败，且未返回明确错误信息。")
                            updateMessageWithError(aiMessageId, error, isImageGeneration = true)
                        }
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
                // 修复：所有文本请求（包括Gemini渠道）都统一使用后端代理
                // 移除了对Gemini渠道的特殊处理，确保所有请求都通过配置的后端代理进行
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
                            val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                            val isThisJobStillTheCurrentOne = currentJob == thisJob

                            if (isThisJobStillTheCurrentOne) {
                                if (isImageGeneration) {
                                    stateHolder._isImageApiCalling.value = false
                                    stateHolder._currentImageStreamingAiMessageId.value = null
                                } else {
                                    stateHolder._isTextApiCalling.value = false
                                    stateHolder._currentTextStreamingAiMessageId.value = null
                                }
                            }
                        }
                        .catch { e: Throwable ->
                            if (e !is CancellationException) {
                                logger.error("Stream catch block", e)
                            }
                        }
                        .onCompletion { cause ->
                            logger.debug("=== STREAM COMPLETION START ===")
                            logger.debug("Stream completion for messageId: $aiMessageId, cause: $cause, isImageGeneration: $isImageGeneration")
                        }
                        .collect { appEvent ->
                            val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                            val currentStreamingId = if (isImageGeneration) 
                                stateHolder._currentImageStreamingAiMessageId.value 
                            else 
                                stateHolder._currentTextStreamingAiMessageId.value
                            if (currentJob != thisJob || currentStreamingId != aiMessageId) {
                                thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                                return@collect
                            }
                            processStreamEvent(appEvent, aiMessageId, isImageGeneration)
                            newEventChannel.trySend(appEvent)
                        }
               }
            } catch (e: Exception) {
                // Handle stream cancellation/error - 获取对应的消息处理器进行重置
                val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
                currentMessageProcessor.reset()
                if (e !is CancellationException) {
                    logger.error("Stream exception", e)
                    updateMessageWithError(aiMessageId, e, isImageGeneration)
                    onRequestFailed(e)
                } else {
                    logger.debug("Stream cancelled: ${e.message}")
                }
            } finally {
                val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                if (currentJob == thisJob) {
                    if (isImageGeneration) {
                        stateHolder.imageApiJob = null
                        if (stateHolder._isImageApiCalling.value && stateHolder._currentImageStreamingAiMessageId.value == aiMessageId) {
                            stateHolder._isImageApiCalling.value = false
                            stateHolder._currentImageStreamingAiMessageId.value = null
                        }
                    } else {
                        stateHolder.textApiJob = null
                        if (stateHolder._isTextApiCalling.value && stateHolder._currentTextStreamingAiMessageId.value == aiMessageId) {
                            stateHolder._isTextApiCalling.value = false
                            stateHolder._currentTextStreamingAiMessageId.value = null
                        }
                    }
                }
            }
        }
    }
private suspend fun processStreamEvent(appEvent: AppStreamEvent, aiMessageId: String, isImageGeneration: Boolean = false) {
        // 获取当前消息ID对应的处理器和块管理器
        val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
        // 首先，让MessageProcessor处理事件并获取返回结果
        val processedResult = currentMessageProcessor.processStreamEvent(appEvent, aiMessageId)

        // 然后，根据处理结果和事件类型更新UI状态
        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val messageIndex = messageList.indexOfFirst { it.id == aiMessageId }

            if (messageIndex == -1) {
                logger.warn("Message with id $aiMessageId not found in the list for event $appEvent")
                return@withContext
            }

            val currentMessage = messageList[messageIndex]
            var updatedMessage = currentMessage

            when (appEvent) {
                is AppStreamEvent.Content -> {
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        // 🎯 关键修复：一旦收到正式内容块，立刻开始显示正式内容（contentStarted=true），不再依赖推理完成
                        val hasStreamingContent = processedResult.content.isNotBlank()

                        android.util.Log.d("ApiHandler", "=== CONTENT EVENT DEBUG ===")
                        android.util.Log.d("ApiHandler", "Message ID: $aiMessageId")
                        android.util.Log.d("ApiHandler", "Event type: Content")
                        android.util.Log.d("ApiHandler", "Content length: ${processedResult.content.length}")
                        android.util.Log.d("ApiHandler", "Setting contentStarted to: $hasStreamingContent")

                        // ✅ 强制开启主内容显示：一旦收到内容事件，立即标记 contentStarted=true
                        updatedMessage = updatedMessage.copy(
                            text = processedResult.content,
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.Text -> {
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        // 🎯 关键修复：文本块到来即开启正式内容显示（contentStarted=true）
                        val hasStreamingContent = processedResult.content.isNotBlank()

                        android.util.Log.d("ApiHandler", "=== TEXT EVENT DEBUG ===")
                        android.util.Log.d("ApiHandler", "Message ID: $aiMessageId")
                        android.util.Log.d("ApiHandler", "Event type: Text")
                        android.util.Log.d("ApiHandler", "Text length: ${processedResult.content.length}")
                        android.util.Log.d("ApiHandler", "Setting contentStarted to: $hasStreamingContent")

                        // ✅ 强制开启主内容显示：一旦收到文本事件，立即标记 contentStarted=true
                        updatedMessage = updatedMessage.copy(
                            text = processedResult.content,
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.ContentFinal -> {
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        // 🎯 最终内容到来时，确保正式内容已显示
                        val hasStreamingContent = processedResult.content.isNotBlank()

                        android.util.Log.d("ApiHandler", "=== CONTENT FINAL EVENT DEBUG ===")
                        android.util.Log.d("ApiHandler", "Message ID: $aiMessageId")
                        android.util.Log.d("ApiHandler", "Event type: ContentFinal")
                        android.util.Log.d("ApiHandler", "Content length: ${processedResult.content.length}")
                        android.util.Log.d("ApiHandler", "Setting contentStarted to: $hasStreamingContent")

                        // ✅ 最终内容到来时同样确保 contentStarted=true
                        updatedMessage = updatedMessage.copy(
                            text = processedResult.content,
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.Reasoning -> {
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ReasoningUpdated) {
                        // 推理增量更新
                        updatedMessage = updatedMessage.copy(reasoning = processedResult.reasoning)
                        // 🎯 根因修复：
                        // - 推理更新之前未标记“会话脏”，导致退出时 reasoning 未被持久化，重启后小白点消失
                        // - 这里在每次推理增量到来时标记脏并立即持久化“last open chat”，确保 reasoning 保留
                        if (isImageGeneration) {
                            stateHolder.isImageConversationDirty.value = true
                        } else {
                            stateHolder.isTextConversationDirty.value = true
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                            } catch (_: Exception) {
                                // 静默处理，避免影响流式
                            }
                        }
                    }
                }
                is AppStreamEvent.ReasoningFinish -> {
                    // 🔥 关键修复：收到推理完成事件时，立即标记推理完成并触发UI更新
                    // 这样思考框会立即收起，然后开始流式展示正式内容
                    val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                    reasoningMap[aiMessageId] = true
                    logger.debug("Reasoning finished for message $aiMessageId, marking reasoning as complete")
                    
                    updatedMessage = updatedMessage.copy(
                        contentStarted = true,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AppStreamEvent.OutputType -> {
                    updatedMessage = updatedMessage.copy(outputType = appEvent.type)
                }
                is AppStreamEvent.WebSearchStatus -> {
                    updatedMessage = updatedMessage.copy(currentWebSearchStage = appEvent.stage)
                }
                is AppStreamEvent.WebSearchResults -> {
                    updatedMessage = updatedMessage.copy(webSearchResults = appEvent.results)
                }
                is AppStreamEvent.Finish, is AppStreamEvent.StreamEnd -> {
                    if (processedMessageIds.contains(aiMessageId)) {
                        logger.debug("Ignoring duplicate terminal event for message $aiMessageId")
                        return@withContext
                    }
                    processedMessageIds.add(aiMessageId)

                    // 确保推理标记为完成（如果之前没有收到 ReasoningFinish 事件）
                    val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                    if (reasoningMap[aiMessageId] != true) {
                        reasoningMap[aiMessageId] = true
                    }
                    
                    // 🎯 强制最终解析：确保parts字段被正确填充
                    logger.debug("Stream finished for message $aiMessageId, forcing final message processing")
                    val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
                    val finalizedMessage = currentMessageProcessor.finalizeMessageProcessing(currentMessage)
                    updatedMessage = finalizedMessage.copy(
                        contentStarted = true
                    )
                    // 暂停时不触发UI刷新，等待恢复后统一刷新
                    if (!stateHolder._isStreamingPaused.value) {
                        try {
                            if (finalizedMessage.text.isNotBlank()) {
                                onAiMessageFullTextChanged(aiMessageId, finalizedMessage.text)
                            }
                        } catch (e: Exception) {
                            logger.warn("onAiMessageFullTextChanged in Finish handler failed: ${e.message}")
                        }
                    }

                    // 核心修复：在消息处理完成并最终化之后，在这里触发强制保存
                    viewModelScope.launch(Dispatchers.IO) {
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                    }

                    // 🔥 正确的修复：不要删除处理器！让它保留在内存中
                    // 处理器会在清理资源时被正确管理，不需要在这里删除
                    logger.debug("Message processor for $aiMessageId retained after stream completion")
                }
                is AppStreamEvent.Error -> {
                    updateMessageWithError(aiMessageId, IOException(appEvent.message), isImageGeneration)
                }
                // 其他事件类型（如ToolCall, ImageGeneration）暂时不直接更新消息UI，由特定逻辑处理
                else -> {
                    logger.debug("Handling other event type: ${appEvent::class.simpleName}")
                }
            }

            // 若处于“暂停流式显示”状态，则不更新UI，仅由恢复时一次性刷新
            if (!stateHolder._isStreamingPaused.value && updatedMessage != currentMessage) {
                messageList[messageIndex] = updatedMessage
            }
        }

        if (stateHolder.shouldAutoScroll()) {
            triggerScrollToBottom()
        }
    }


    private suspend fun updateMessageWithError(messageId: String, error: Throwable, isImageGeneration: Boolean = false) {
        logger.error("Updating message with error", error)
        // 获取当前消息ID对应的处理器并重置
        val currentMessageProcessor = messageProcessorMap[messageId] ?: MessageProcessor()
        currentMessageProcessor.reset()
        
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
                    val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                    if (animationMap[messageId] != true) {
                        animationMap[messageId] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                }
            }
            val currentStreamingId = if (isImageGeneration) 
                stateHolder._currentImageStreamingAiMessageId.value 
            else 
                stateHolder._currentTextStreamingAiMessageId.value
            val isApiCalling = if (isImageGeneration) 
                stateHolder._isImageApiCalling.value 
            else 
                stateHolder._isTextApiCalling.value
                
            if (currentStreamingId == messageId && isApiCalling) {
                if (isImageGeneration) {
                    stateHolder._isImageApiCalling.value = false
                    stateHolder._currentImageStreamingAiMessageId.value = null
                } else {
                    stateHolder._isTextApiCalling.value = false
                    stateHolder._currentTextStreamingAiMessageId.value = null
                }
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

    private fun isTextOnlyIntent(promptRaw: String?): Boolean {
        val p = promptRaw?.lowercase()?.trim() ?: return false
        if (p.isBlank()) return false

        // 先匹配“仅文本”硬条件，避免被“图片”等词误判
        val textOnlyHard = listOf(
            // 中文明确仅文本
            "仅返回文本", "只返回文本", "只输出文本", "仅文本", "纯文本", "只输出文字", "只输出结果",
            "只要文字", "只文字", "文字即可", "只要描述", "只要说明", "只解释", "只讲文字",
            "不要图片", "不需要图片", "不要图像", "不需要图像", "不要出图", "别画图", "不用配图", "不要配图",
            // 英文变体
            "text only", "text-only", "only text", "just text", "just answer",
            "no image", "no images", "no picture", "no pictures", "no graphics",
            "no drawing", "dont draw", "don't draw", "no pic", "no pics"
        )
        if (textOnlyHard.any { p.contains(it) }) return true

        // 若有明显出图意图，则不是仅文本
        val imageHints = listOf(
            // 中文绘图/图片意图
            "画", "绘制", "画个", "画张", "画一张", "来一张", "给我一张", "出一张", "生成图片", "生成", "生成几张", "生成多张",
            "出图", "图片", "图像", "配图", "背景图", "封面图", "插画", "插图", "海报", "头像", "壁纸", "封面",
            "表情包", "贴图", "示意图", "场景图", "示例图", "图标",
            "手绘", "素描", "线稿", "上色", "涂色", "水彩", "油画", "像素画", "漫画", "二次元", "渲染",
            "p图", "p一张", "制作一张", "做一张", "合成一张",
            // 英文意图
            "image", "picture", "pictures", "photo", "photos", "art", "artwork", "illustration", "render", "rendering",
            "draw", "sketch", "paint", "painting", "watercolor", "oil painting", "pixel art", "comic", "manga", "sticker",
            "cover", "wallpaper", "avatar", "banner", "logo", "icon",
            "generate image", "generate a picture", "create an image", "make an image", "image generation",
            // 常见模型/工具词（提示也多为出图意图）
            "stable diffusion", "sdxl", "midjourney", "mj"
        )
        if (imageHints.any { p.contains(it) }) return false

        // 简短致谢/寒暄/确认类——且长度很短时视为仅文本
        val ack = listOf(
            // 中文口语化
            "谢谢", "谢谢啦", "多谢", "多谢啦", "谢谢你", "感谢", "感谢你", "辛苦了", "辛苦啦",
            "你好", "您好", "嗨", "哈喽", "嘿", "早上好", "早安", "午安", "晚上好", "晚安",
            "好的", "好吧", "行", "行吧", "可以", "可以了", "行了", "好滴", "好嘞", "好哒", "嗯", "嗯嗯", "哦", "噢", "额", "emmm",
            "没事", "不客气", "打扰了", "抱歉", "不好意思",
            "牛", "牛逼", "牛批", "nb", "tql", "yyds", "绝了", "给力", "666", "6", "赞", "棒",
            // 英文常见
            "hi", "hello", "ok", "okay", "roger", "got it", "copy", "ack",
            "thx", "thanks", "thank you", "tks", "ty",
            "great", "awesome", "cool", "nice", "nice one"
        )
        val containsAck = ack.any { p.contains(it) }
        if (!containsAck) return false

        // 简短启发：仅当很短时判定为仅文本，避免“帮我画猫，谢谢”被误判（含“画”等词已优先排除）
        val normalized = p.replace(Regex("[\\p{Punct}\\s]+"), "")
        if (normalized.length <= 8) return true
        val tokenCount = p.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return tokenCount <= 3
    }

    private fun isBackendErrorResponseText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        val keywords = listOf(
            "区域限制", "上游错误", "网络异常", "非json",
            "failed_precondition", "user location is not supported", "provider returned error"
        )
        return keywords.any { t.contains(it) }
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

    private suspend fun handleImageGenerationFailure(messageId: String, error: Throwable) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val currentRetryCount = stateHolder._imageGenerationRetryCount.value
            val maxRetries = 3
            
            if (currentRetryCount < maxRetries) {
                stateHolder._imageGenerationRetryCount.value = currentRetryCount + 1
                logger.info("图像生成失败，准备重试 ${currentRetryCount + 1}/$maxRetries")
                
                // 延迟后重试
                kotlinx.coroutines.delay(2000)
                // 这里可以添加重试逻辑，重新发送请求
                
            } else {
                // 达到最大重试次数，显示错误提示
                val detailedError = error.message ?: "未知错误"
                val errorMessage = """
                    图像生成失败：已尝试 $maxRetries 次仍无法生成图片。
                    错误信息：$detailedError
                    请检查您的提示词是否包含图像生成关键词（如：画、生成、图片等），或稍后重试。
                """.trimIndent()
                
                stateHolder._imageGenerationError.value = errorMessage
                stateHolder._shouldShowImageGenerationError.value = true
                
                logger.error("图像生成最终失败，已达到最大重试次数", error)
            }
        }
    }

    private companion object {
        private const val ERROR_VISUAL_PREFIX = "⚠️ "
    }
    
    /**
     * 清理文本聊天相关的资源，确保会话间完全隔离
     */
    fun clearTextChatResources() {
        logger.debug("Clearing text chat resources for session isolation")
        
        // 🔥 添加调试日志，诊断消息处理器清理问题
        logger.debug("=== MESSAGE PROCESSOR CLEANUP DEBUG ===")
        logger.debug("Current message IDs in stateHolder: ${stateHolder.messages.map { it.id }}")
        logger.debug("MessageProcessorMap keys before cleanup: ${messageProcessorMap.keys}")
        logger.debug("ProcessedMessageIds before cleanup: $processedMessageIds")
        
        // 🔥 修复：只清理不在当前消息列表中的处理器，避免清除正在使用的消息内容
        val currentMessageIds = stateHolder.messages.map { it.id }.toSet()
        val textMessageIds = messageProcessorMap.keys.filter { id ->
            // 只清理不在当前消息列表中的处理器
            !currentMessageIds.contains(id)
        }
        
        logger.debug("Message IDs to be removed: $textMessageIds")
        logger.debug("Message IDs to be kept: $currentMessageIds")
        
        textMessageIds.forEach { messageId ->
            logger.debug("Removing message processor for: $messageId")
            messageProcessorMap.remove(messageId)
        }
        processedMessageIds.clear() // 清理已处理的消息ID集合
        logger.debug("Cleared ${textMessageIds.size} text chat message processors (kept ${currentMessageIds.size} active)")
        logger.debug("MessageProcessorMap keys after cleanup: ${messageProcessorMap.keys}")
        logger.debug("=== END MESSAGE PROCESSOR CLEANUP DEBUG ===")
    }

    // 为兼容调用方，提供带 sessionId 的重载，内部忽略参数
    fun clearTextChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) {
        clearTextChatResources()
    }
    
    /**
     * 清理图像聊天相关的资源，确保会话间完全隔离
     */
    fun clearImageChatResources() {
        logger.debug("Clearing image chat resources for session isolation")
        
        // 🔥 添加调试日志，诊断消息处理器清理问题
        logger.debug("=== IMAGE MESSAGE PROCESSOR CLEANUP DEBUG ===")
        logger.debug("Current image message IDs in stateHolder: ${stateHolder.imageGenerationMessages.map { it.id }}")
        logger.debug("MessageProcessorMap keys before cleanup: ${messageProcessorMap.keys}")
        
        // 🔥 修复：只清理不在当前消息列表中的处理器，避免清除正在使用的消息内容
        val currentMessageIds = stateHolder.imageGenerationMessages.map { it.id }.toSet()
        val imageMessageIds = messageProcessorMap.keys.filter { id ->
            // 只清理不在当前消息列表中的处理器
            !currentMessageIds.contains(id)
        }
        
        logger.debug("Image message IDs to be removed: $imageMessageIds")
        logger.debug("Image message IDs to be kept: $currentMessageIds")
        
        imageMessageIds.forEach { messageId ->
            logger.debug("Removing image message processor for: $messageId")
            messageProcessorMap.remove(messageId)
        }
        logger.debug("Cleared ${imageMessageIds.size} image chat message processors (kept ${currentMessageIds.size} active)")
        logger.debug("MessageProcessorMap keys after cleanup: ${messageProcessorMap.keys}")
        logger.debug("=== END IMAGE MESSAGE PROCESSOR CLEANUP DEBUG ===")
    }

    // 为兼容调用方，提供带 sessionId 的重载，内部忽略参数
    fun clearImageChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) {
        clearImageChatResources()
    }

    /**
     * 当暂停恢复时，将当前流式消息的累积文本一次性刷新到UI。
     */
    fun flushPausedStreamingUpdate(isImageGeneration: Boolean = false) {
        val messageId = if (isImageGeneration)
            stateHolder._currentImageStreamingAiMessageId.value
        else
            stateHolder._currentTextStreamingAiMessageId.value

        if (messageId.isNullOrBlank()) return

        val processor = messageProcessorMap[messageId] ?: return
        val fullText = processor.getCurrentText()

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val idx = messageList.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = messageList[idx]
                val updated = msg.copy(
                    text = fullText,
                    contentStarted = msg.contentStarted || fullText.isNotBlank()
                )
                messageList[idx] = updated
                try {
                    if (fullText.isNotBlank()) {
                        onAiMessageFullTextChanged(messageId, fullText)
                    }
                } catch (_: Exception) {
                    // 忽略刷新失败，避免影响恢复流程
                }
            }
        }
    }
}
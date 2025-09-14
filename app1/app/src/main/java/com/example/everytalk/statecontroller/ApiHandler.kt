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
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.conflate
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
        // 关键修复：增强日志，明确显示模式信息
        val modeInfo = if (isImageGeneration) "IMAGE_MODE" else "TEXT_MODE"
        logger.debug("Cancelling API job: $reason, Mode=$modeInfo, isNewMessageSend=$isNewMessageSend, isImageGeneration=$isImageGeneration")
        
        val jobToCancel = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
        val messageIdBeingCancelled = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX [$modeInfo] $reason" else "$USER_CANCEL_PREFIX [$modeInfo] $reason"

        if (jobToCancel?.isActive == true) {
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
        messageProcessor.reset()

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

        // 重置消息处理器
        messageProcessor.reset()

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
                    val currentChunkIndex = messageList.indexOfFirst { it.id == aiMessageId }
                    if (currentChunkIndex != -1) {
                        updateMessageInState(currentChunkIndex, isImageGeneration)
                        if (stateHolder.shouldAutoScroll()) {
                            viewModelScope.launch(Dispatchers.Main.immediate) { triggerScrollToBottom() }
                        }
                    }
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
                       val lastUserText = when (val lastUserMsg = requestBody.messages.lastOrNull { it.role == "user" }) {
                            is com.example.everytalk.data.DataClass.SimpleTextApiMessage -> lastUserMsg.content
                            is com.example.everytalk.data.DataClass.PartsApiMessage -> lastUserMsg.parts.filterIsInstance<ApiContentPart.Text>().joinToString(" ") { it.text }
                            else -> null
                        }
                        val textOnly = isTextOnlyIntent(lastUserText)
                        val maxAttempts = if (textOnly) 1 else 3
                        var attempt = 1
                        var finalText: String? = null
                        while (attempt <= maxAttempts) {
                           val response = ApiClient.generateImage(requestBody)
                           logger.debug("[ImageGen] Attempt $attempt/$maxAttempts, response: $response")
                           
                           val imageUrlsFromResponse = response.images.mapNotNull { it.url.takeIf { url -> url.isNotBlank() } }
                           val responseText = response.text ?: ""
                           
                           // 内容过滤：提示并终止
                           if (responseText.startsWith("[CONTENT_FILTER]")) {
                               withContext(Dispatchers.Main.immediate) {
                                   val userFriendlyMessage = responseText.removePrefix("[CONTENT_FILTER]").trim()
                                   stateHolder.showSnackbar(userFriendlyMessage)
                                   val messageList = stateHolder.imageGenerationMessages
                                   val index = messageList.indexOfFirst { it.id == aiMessageId }
                                   if (index != -1) {
                                       messageList.removeAt(index)
                                   }
                               }
                               break
                           }
                           
                           // 若先返回文本：先展示文本（模型可能后续才给图）
                           if (finalText.isNullOrBlank() && responseText.isNotBlank()) {
                               finalText = responseText
                               withContext(Dispatchers.Main.immediate) {
                                   val messageList = stateHolder.imageGenerationMessages
                                   val index = messageList.indexOfFirst { it.id == aiMessageId }
                                   if (index != -1) {
                                       val currentMessage = messageList[index]
                                       val updatedMessage = currentMessage.copy(
                                           text = responseText,
                                           contentStarted = true
                                       )
                                       messageList[index] = updatedMessage
                                   }
                               }
                           }
                           
                           // 如果后端返回明确的错误提示（如区域限制/上游错误/网络异常等），不再重试，直接以文本结束
                           if (imageUrlsFromResponse.isEmpty() && isBackendErrorResponseText(responseText)) {
                               withContext(Dispatchers.Main.immediate) {
                                   val messageList = stateHolder.imageGenerationMessages
                                   val index = messageList.indexOfFirst { it.id == aiMessageId }
                                   if (index != -1) {
                                       val currentMessage = messageList[index]
                                       val updatedMessage = currentMessage.copy(
                                           text = finalText ?: responseText,
                                           contentStarted = true
                                       )
                                       messageList[index] = updatedMessage
                                   }
                               }
                               viewModelScope.launch(Dispatchers.IO) {
                                   historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true)
                               }
                               break
                           }
                           if (imageUrlsFromResponse.isNotEmpty()) {
                               // 获得图片：合并文本与图片并结束
                               withContext(Dispatchers.Main.immediate) {
                                   val messageList = stateHolder.imageGenerationMessages
                                   val index = messageList.indexOfFirst { it.id == aiMessageId }
                                   if (index != -1) {
                                       val currentMessage = messageList[index]
                                       val updatedMessage = currentMessage.copy(
                                           imageUrls = imageUrlsFromResponse,
                                           text = finalText ?: responseText,
                                           contentStarted = true
                                       )
                                       logger.debug("[ImageGen] Updating message ${updatedMessage.id} with ${updatedMessage.imageUrls?.size ?: 0} images.")
                                       messageList[index] = updatedMessage
                                   }
                               }
                               viewModelScope.launch(Dispatchers.IO) {
                                   historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true)
                               }
                               break
                           } else {
                               // 无图片：自动重试（保持 isImageApiCalling=true）
                               if (attempt < maxAttempts) {
                                   // 若任务已切换/取消则退出
                                   val stillThisJob = stateHolder.imageApiJob == thisJob
                                   if (!stillThisJob) break
                                   withContext(Dispatchers.Main.immediate) {
                                       // 提示重试进度（不强制修改 isImageApiCalling，用户可随时取消）
                                       stateHolder.showSnackbar("图像生成失败，正在重试 (${attempt + 1}/$maxAttempts)...")
                                   }
                                   kotlinx.coroutines.delay(600)
                                   attempt++
                               } else {
                                   // 最终仍无图：保留已有文本并提示
                                   withContext(Dispatchers.Main.immediate) {
                                       val messageList = stateHolder.imageGenerationMessages
                                       val index = messageList.indexOfFirst { it.id == aiMessageId }
                                       if (index != -1) {
                                           val currentMessage = messageList[index]
                                           val updatedMessage = currentMessage.copy(
                                               text = finalText ?: currentMessage.text,
                                               contentStarted = true
                                           )
                                           messageList[index] = updatedMessage
                                       }
                                       if (!textOnly) {  }
                                   }
                                   viewModelScope.launch(Dispatchers.IO) {
                                       historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true)
                                   }
                                   break
                               }
                           }
                       }
                   } catch (e: Exception) {
                       logger.error("[ImageGen] Image processing failed for message $aiMessageId", e)
                       handleImageGenerationFailure(aiMessageId, e)
                       updateMessageWithError(aiMessageId, e, isImageGeneration = true)
                       onRequestFailed(e)
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
                            val targetMsgId = aiMessageId
                            val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                            val isThisJobStillTheCurrentOne = currentJob == thisJob

                            if (isThisJobStillTheCurrentOne) {
                                if (isImageGeneration) {
                                    stateHolder._isImageApiCalling.value = false
                                } else {
                                    stateHolder._isTextApiCalling.value = false
                                }
                                val currentStreamingId = if (isImageGeneration)
                                    stateHolder._currentImageStreamingAiMessageId.value
                                else
                                    stateHolder._currentTextStreamingAiMessageId.value
                                if (currentStreamingId == targetMsgId) {
                                    if (isImageGeneration) {
                                        stateHolder._currentImageStreamingAiMessageId.value = null
                                    } else {
                                        stateHolder._currentTextStreamingAiMessageId.value = null
                                    }
                                }
                            }
                                val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                                if (reasoningMap[targetMsgId] != true) {
                                    reasoningMap[targetMsgId] = true
                                }

                            val cancellationMessageFromCause =
                                (cause as? CancellationException)?.message
                            val wasCancelledByApiHandler =
                                cancellationMessageFromCause?.startsWith(USER_CANCEL_PREFIX) == true ||
                                        cancellationMessageFromCause?.startsWith(
                                            NEW_STREAM_CANCEL_PREFIX
                                        ) == true

                            val finalTextSnapshot = messageProcessor.getCurrentText()
                            val finalReasoningSnapshot = messageProcessor.getCurrentReasoning()

                            if (!wasCancelledByApiHandler) {
                                val finalFullText = finalTextSnapshot.trim()
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
                                        val latestText = finalTextSnapshot
                                        val latestReasoning = finalReasoningSnapshot
                                        val updatedMsg = msg.copy(
                                            text = if (latestText.isNotBlank()) latestText else msg.text,
                                            reasoning = latestReasoning ?: msg.reasoning,
                                            contentStarted = msg.contentStarted || latestText.isNotBlank() || !(latestReasoning.isNullOrBlank())
                                        )
                                        if (updatedMsg != msg) {
                                            messageList[finalIdx] = updatedMsg
                                        }
                                        val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                                        if (animationMap[targetMsgId] != true) {
                                            animationMap[targetMsgId] = true
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
        val result = messageProcessor.processStreamEvent(appEvent, aiMessageId)

        // 根据MessageProcessor的处理结果来更新消息状态
        when (result) {
            is ProcessedEventResult.ContentUpdated, is ProcessedEventResult.ReasoningUpdated -> {
                // 这些事件由 updateMessageInState 处理，这里不需要操作
            }
            is ProcessedEventResult.ReasoningComplete -> {
                val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                reasoningMap[aiMessageId] = true
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
                    val messageId = if (isImageGeneration) 
                        stateHolder._currentImageStreamingAiMessageId.value 
                    else 
                        stateHolder._currentTextStreamingAiMessageId.value
                    messageId ?: return
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
                val messageId = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
                messageId ?: return
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
                val messageId = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
                messageId ?: return
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
                val messageId = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
                messageId ?: return
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
                val messageId = if (isImageGeneration) stateHolder._currentImageStreamingAiMessageId.value else stateHolder._currentTextStreamingAiMessageId.value
                messageId ?: return
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
        // 将读取与UI状态更新都切换到主线程，避免跨线程修改Compose状态/MessageProcessor并发读写
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val originalMessage = messageList.getOrNull(index) ?: return@launch

            // 从MessageProcessor获取当前文本和推理内容（主线程，避免与写入竞争）
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
}
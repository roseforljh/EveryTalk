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
import com.example.everytalk.util.messageprocessor.MarkdownBlockManager
import com.example.everytalk.util.messageprocessor.MessageProcessor
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    // 为每个消息创建独立的MarkdownBlockManager，确保完全隔离
    private val blockManagerMap = mutableMapOf<String, MarkdownBlockManager>()
    // 为每个会话创建独立的MessageProcessor实例，确保会话隔离
    private val messageProcessorMap = mutableMapOf<String, MessageProcessor>()
    private var eventChannel: Channel<AppStreamEvent>? = null

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
            val currentBlockManager = messageIdBeingCancelled?.let { blockManagerMap.getOrPut(it) { MarkdownBlockManager() } } ?: MarkdownBlockManager()
            // 在取消前先刷写未完成的块，避免丢失末尾内容
            currentBlockManager.finalizeCurrentBlock()
            val partialText = currentMessageProcessor.getCurrentText().trim()
            val partialReasoning = currentMessageProcessor.getCurrentReasoning()
            val hasBlocks = currentBlockManager.blocks.isNotEmpty()

            if (partialText.isNotBlank() || partialReasoning != null || hasBlocks) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index =
                        messageList.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val currentMessage = messageList[index]
                        val updatedMessage = currentMessage.copy(
                            parts = currentBlockManager.blocks.toList(),
                            contentStarted = currentMessage.contentStarted || partialText.isNotBlank() || hasBlocks,
                            isError = false
                        )
                        messageList[index] = updatedMessage

                        if ((partialText.isNotBlank() || hasBlocks) && messageIdBeingCancelled != null) {
                            // Use text from blocks if available, otherwise fall back to messageProcessor
                            val textForCallback = if (hasBlocks) {
                                currentBlockManager.blocks.filterIsInstance<com.example.everytalk.ui.components.MarkdownPart.Text>()
                                    .joinToString("") { it.content }
                            } else {
                                partialText
                            }
                            if (textForCallback.isNotBlank()) {
                                onAiMessageFullTextChanged(messageIdBeingCancelled, textForCallback)
                            }
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
            blockManagerMap.remove(messageIdBeingCancelled)
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
        val newBlockManager = MarkdownBlockManager()
        messageProcessorMap[aiMessageId] = newMessageProcessor
        blockManagerMap[aiMessageId] = newBlockManager
        
        // 重置块管理器（这里应该是新创建的，重置是多余的，但保持一致性）
        newBlockManager.reset()

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
                            
                            // Finalize the block manager to ensure any incomplete block is processed
                            val currentBlockManager = blockManagerMap[aiMessageId]
                            currentBlockManager?.finalizeCurrentBlock()
                            logger.debug("BlockManager finalized, blocks count: ${currentBlockManager?.blocks?.size ?: 0}")
                            
                            // 🎯 关键修复：流结束时立即进行完整的Markdown解析和同步
                            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                            val messageIndex = messageList.indexOfFirst { it.id == aiMessageId }
                            logger.debug("Message index found: $messageIndex")
                            
                            if (messageIndex != -1) {
                                val currentMessage = messageList[messageIndex]
                                logger.debug("Current message text length: ${currentMessage.text.length}")
                                logger.debug("Current message text preview: ${currentMessage.text.take(100)}...")
                                logger.debug("Current message parts count: ${currentMessage.parts.size}")
                                logger.debug("Current message contentStarted: ${currentMessage.contentStarted}")
                                
                                // 立即进行Markdown解析，生成parts字段
                                // 使用当前消息ID对应的处理器
                                val currentMessageProcessor = messageProcessorMap[aiMessageId] ?: MessageProcessor()
                                val finalizedMessage = currentMessageProcessor.finalizeMessageProcessing(currentMessage)
                                logger.debug("After finalization - parts count: ${finalizedMessage.parts.size}")
                                finalizedMessage.parts.forEachIndexed { index, part ->
                                    logger.debug("Part $index: ${part::class.simpleName} - ${part.toString().take(50)}...")
                                }
                                
                                // 🎯 最终修复：流结束时，用 finalizeMessageProcessing 的结果覆盖 blockManager 的临时结果
                                val fullyUpdatedMessage = finalizedMessage.copy(
                                    parts = finalizedMessage.parts, // 明确使用最终解析的 parts
                                    contentStarted = true
                                )
                                logger.debug("Final message parts count: ${fullyUpdatedMessage.parts.size}")
                                messageList[messageIndex] = fullyUpdatedMessage
                                logger.debug("Message updated in list")
                            } else {
                                logger.error("Message with id $aiMessageId not found in messageList!")
                            }
                            
                            // 🎯 强制保存：确保AI输出的文字不会丢失，无论流如何结束
                            logger.debug("Triggering force save...")
                            // 🎯 关键修复：强制同步到主线程保存，确保数据完整性
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                                logger.debug("Force save completed on main thread")
                            }
                            logger.debug("=== STREAM COMPLETION END ===")
                            
                            // 清理对应的消息处理器和块管理器
                            messageProcessorMap.remove(aiMessageId)
                            blockManagerMap.remove(aiMessageId)
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
                // 🎯 关键修复：在finally块中也要清理资源，防止资源泄漏
                messageProcessorMap.remove(aiMessageId)
                blockManagerMap.remove(aiMessageId)
                
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
        val currentBlockManager = blockManagerMap[aiMessageId] ?: MarkdownBlockManager()
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
                    // 使用每条消息独立的 BlockManager，避免跨会话/跨消息互相污染
                    val currentBlockManager = blockManagerMap.getOrPut(aiMessageId) { MarkdownBlockManager() }
                    currentBlockManager.processEvent(appEvent)
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        updatedMessage = updatedMessage.copy(
                            text = processedResult.content,
                            parts = currentBlockManager.blocks.toList(),
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.Text, is AppStreamEvent.ContentFinal -> {
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        updatedMessage = updatedMessage.copy(
                            text = processedResult.content,
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.Reasoning -> {
                    if (processedResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ReasoningUpdated) {
                        updatedMessage = updatedMessage.copy(reasoning = processedResult.reasoning)
                    }
                }
                is AppStreamEvent.OutputType -> {
                    currentMessageProcessor.setCurrentOutputType(appEvent.type)
                    updatedMessage = updatedMessage.copy(outputType = appEvent.type)
                }
                is AppStreamEvent.WebSearchStatus -> {
                    updatedMessage = updatedMessage.copy(currentWebSearchStage = appEvent.stage)
                }
                is AppStreamEvent.WebSearchResults -> {
                    updatedMessage = updatedMessage.copy(webSearchResults = appEvent.results)
                }
                is AppStreamEvent.Finish, is AppStreamEvent.StreamEnd -> {
                    val reasoningMap = if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
                    reasoningMap[aiMessageId] = true
                }
                is AppStreamEvent.Error -> {
                    updateMessageWithError(aiMessageId, IOException(appEvent.message), isImageGeneration)
                }
                // 其他事件类型（如ToolCall, ImageGeneration）暂时不直接更新消息UI，由特定逻辑处理
                else -> {
                    logger.debug("Handling other event type: ${appEvent::class.simpleName}")
                }
            }

            if (updatedMessage != currentMessage) {
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
        // 同时清理对应的块管理器
        blockManagerMap.remove(messageId)
        
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
        // 清理所有文本聊天相关的消息处理器和块管理器
        val textMessageIds = messageProcessorMap.keys.filter { id ->
            // 这里可以根据实际业务逻辑判断哪些是文本聊天的消息
            // 暂时清理所有，如果需要更精确的判断可以添加标识
            true
        }
        textMessageIds.forEach { messageId ->
            messageProcessorMap.remove(messageId)
            blockManagerMap.remove(messageId)
        }
        logger.debug("Cleared ${textMessageIds.size} text chat message processors")
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
        // 清理所有图像聊天相关的消息处理器和块管理器
        val imageMessageIds = messageProcessorMap.keys.filter { id ->
            // 这里可以根据实际业务逻辑判断哪些是图像聊天的消息
            // 暂时清理所有，如果需要更精确的判断可以添加标识
            true
        }
        imageMessageIds.forEach { messageId ->
            messageProcessorMap.remove(messageId)
            blockManagerMap.remove(messageId)
        }
        logger.debug("Cleared ${imageMessageIds.size} image chat message processors")
    }

    // 为兼容调用方，提供带 sessionId 的重载，内部忽略参数
    fun clearImageChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) {
        clearImageChatResources()
    }
}
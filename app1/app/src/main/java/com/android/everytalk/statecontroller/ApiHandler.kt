package com.android.everytalk.statecontroller

import android.content.Context
import com.android.everytalk.util.storage.FileManager
import java.io.File
import java.util.Locale
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.models.SelectedMediaItem.Audio
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.PromptLeakGuard
import com.android.everytalk.util.debug.PerformanceMonitor
import com.android.everytalk.util.messageprocessor.MessageProcessor
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.sample
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
    // Note: Do not hold a FileManager with appContext here; pass Context when needed
    private val logger = AppLogger.forComponent("ApiHandler")
    private val jsonParserForError = Json { ignoreUnknownKeys = true }
    // 为每个会话创建独立的MessageProcessor实例，确保会话隔离
    private val messageProcessorMap = mutableMapOf<String, MessageProcessor>()
    private var eventChannel: Channel<AppStreamEvent>? = null
    private val processedMessageIds = mutableSetOf<String>()
    
    // 🛡️ 防 prompt 泄露：为每个消息创建独立的流式检测器
    private val promptLeakDetectors = mutableMapOf<String, PromptLeakGuard.StreamingDetector>()

    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"
    private val ERROR_VISUAL_PREFIX = "⚠️ "
    
    // 🎯 Retry mechanism configuration (Requirements: 7.3)
    private val MAX_RETRY_ATTEMPTS = 3
    private val RETRY_DELAY_MS = 2000L
    private val retryCountMap = mutableMapOf<String, Int>()

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
                        
                        // 🎯 Save partial content on cancellation (Requirements: 7.5)
                        logger.debug("Saving partial content on user cancellation (${partialText.length} chars)")
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
            // 🎯 清理 StreamingBuffer（Requirements: 7.5）
            stateHolder.clearStreamingBuffer(messageIdBeingCancelled)
            logger.debug("Cleared StreamingBuffer on cancellation for message: $messageIdBeingCancelled")
            
            messageProcessorMap.remove(messageIdBeingCancelled)
            // 🛡️ 清理 prompt 泄露检测器
            promptLeakDetectors.remove(messageIdBeingCancelled)
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
        // Emit abort summary before cancellation
        if (messageIdBeingCancelled != null) {
            PerformanceMonitor.onAbort(messageIdBeingCancelled, reason = specificCancelReason)
        }
        jobToCancel?.cancel(CancellationException(specificCancelReason))
        
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
        isImageGeneration: Boolean = false
    ) {
        val contextForLog = when (val lastUserMsg = requestBody.messages.lastOrNull {
            it.role == "user"
        }) {
            is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> lastUserMsg.content
            is com.android.everytalk.data.DataClass.PartsApiMessage -> lastUserMsg.parts
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
            // 关键修复：不要在创建时置为 true
            // 仅当首个正文增量到来时再置 true，否则思考框判定条件将被提前终止
            contentStarted = false,
            modelName = requestBody.model,
            providerName = requestBody.provider
        )
        val aiMessageId = newAiMessage.id
        // Set performance context (mode only; backend/model can be set later if available)
        PerformanceMonitor.setContext(aiMessageId, mode = if (isImageGeneration) "image" else "text")

        // 为新消息创建独立的消息处理器和块管理器
        val newMessageProcessor = MessageProcessor()
        messageProcessorMap[aiMessageId] = newMessageProcessor
        
        // 🎯 检测内存压力并触发清理（Requirements: 6.5）
        if (checkMemoryPressureAndCleanup()) {
            logger.debug("Memory pressure cleanup triggered before starting new stream")
        }
        
        // 🎯 启动流式状态管理
        stateHolder.streamingMessageStateManager.startStreaming(aiMessageId)
        logger.debug("Started streaming for message: $aiMessageId")
        
        // 🎯 创建 StreamingBuffer 用于节流更新（Requirements: 1.1, 3.1, 3.2）
        stateHolder.createStreamingBuffer(aiMessageId, isImageGeneration)
        logger.debug("Created StreamingBuffer for message: $aiMessageId")

        // 🔧 修复Loading不显示问题：确保状态设置同步完成后再启动流收集
        // 之前的问题：状态设置在协程中异步执行，流可能在状态设置完成前就开始发送事件
        // 这会导致 MessageItemsController.computeBubbleState 在检查 isApiCalling 时返回错误状态
        
        // 1. 首先同步设置流式状态（确保 Loading 指示器可以被正确显示）
        val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
        
        // 🔧 关键修复：先设置 streaming ID 和 isApiCalling 状态
        // 这样当消息被添加到列表时，MessageItemsController 就能正确计算出 Connecting 状态
        if (isImageGeneration) {
            stateHolder._currentImageStreamingAiMessageId.value = aiMessageId
            stateHolder._isImageApiCalling.value = true
            stateHolder.imageReasoningCompleteMap[aiMessageId] = false
        } else {
            stateHolder._currentTextStreamingAiMessageId.value = aiMessageId
            stateHolder._isTextApiCalling.value = true
            stateHolder.textReasoningCompleteMap[aiMessageId] = false
        }
        
        // 2. 然后添加消息到列表（此时状态已经正确设置）
        viewModelScope.launch(Dispatchers.Main.immediate) {
            messageList.add(newAiMessage)
            onNewAiMessageAdded()
            logger.debug("🔧 AI message added to list with streaming state already set: $aiMessageId")
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

                        logger.debug("[ImageGen] 🖼️ Extracted ${imageUrls.size} image URLs from response")
                        imageUrls.forEachIndexed { idx, url ->
                            logger.debug("[ImageGen] 🖼️ Image[$idx]: ${url.take(100)}...")
                        }

                        if (imageUrls.isNotEmpty()) {
                            // 🔥 关键修复：同步归档图片，确保图片保存成功后再更新消息
                            // 先在 IO 线程完成归档，再更新消息，避免异步导致的数据不一致
                            logger.debug("[ImageGen] 🖼️ Starting synchronous image archival for ${imageUrls.size} images")
                            
                            val archivedUrls = withContext(Dispatchers.IO) {
                                try {
                                    val archived = archiveImageUrlsForMessage(applicationContextForApiClient, aiMessageId, imageUrls)
                                    if (archived.isNotEmpty()) {
                                        logger.debug("[ImageGen] 🖼️ Successfully archived ${archived.size} images to local storage")
                                        archived
                                    } else {
                                        logger.warn("[ImageGen] 🖼️ Archive returned empty, falling back to original URLs")
                                        imageUrls
                                    }
                                } catch (e: Exception) {
                                    logger.warn("[ImageGen] 🖼️ Archive failed: ${e.message}, falling back to original URLs")
                                    imageUrls
                                }
                            }
                            
                            // 使用归档后的本地路径（或回退到原始URL）更新消息
                            withContext(Dispatchers.Main.immediate) {
                                val messageList = stateHolder.imageGenerationMessages
                                val index = messageList.indexOfFirst { it.id == aiMessageId }
                                logger.debug("[ImageGen] 🖼️ Looking for message with ID: $aiMessageId, found at index: $index")
                                
                                if (index != -1) {
                                    val currentMessage = messageList[index]
                                    logger.debug("[ImageGen] 🖼️ Current message - ID: ${currentMessage.id}, hasImageUrls: ${currentMessage.imageUrls?.isNotEmpty()}, text: '${currentMessage.text.take(50)}...'")
                                    
                                    val updatedMessage = currentMessage.copy(
                                        imageUrls = archivedUrls, // 使用归档后的本地路径
                                        text = responseText ?: currentMessage.text,
                                        contentStarted = true,
                                        isError = false
                                    )
                                    
                                    // 🔥 关键修复：使用removeAt+add替代直接赋值，确保触发Compose重组
                                    messageList.removeAt(index)
                                    messageList.add(index, updatedMessage)
                                    
                                    logger.debug("[ImageGen] 🖼️ Updated message with ${archivedUrls.size} archived image URLs at index $index")
                                    logger.debug("[ImageGen] 🖼️ Archived URLs: ${archivedUrls.map { it.take(50) + "..." }}")
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
                            
                            // 🎯 无论成功还是取消/错误，都必须在此处进行最终的同步
                            // 确保流式缓冲区中的残余内容被刷新并写入消息列表
                            stateHolder.flushStreamingBuffer(aiMessageId)
                            stateHolder.syncStreamingMessageToList(aiMessageId, isImageGeneration)
                            
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
                            // 🔍 [STREAM_DEBUG] 记录每个事件的接收时间
                            val timestamp = System.currentTimeMillis()
                            android.util.Log.i("STREAM_DEBUG", "[ApiHandler] 🔥 EVENT RECEIVED at $timestamp: ${appEvent::class.simpleName}, msgId=$aiMessageId")
                            
                            val currentJob = if (isImageGeneration) stateHolder.imageApiJob else stateHolder.textApiJob
                            val currentStreamingId = if (isImageGeneration)
                                stateHolder._currentImageStreamingAiMessageId.value
                            else
                                stateHolder._currentTextStreamingAiMessageId.value
                            if (currentJob != thisJob || currentStreamingId != aiMessageId) {
                                thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                                return@collect
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
                            
                            processStreamEvent(appEvent, aiMessageId, isImageGeneration)
                            newEventChannel.trySend(appEvent)
                            
                            android.util.Log.i("STREAM_DEBUG", "[ApiHandler] ✅ EVENT PROCESSED at ${System.currentTimeMillis()}: took ${System.currentTimeMillis() - timestamp}ms")

                            // 🎯 如果收到终止事件，主动结束流收集，确保触发 onCompletion 从而重置按钮状态
                            if (appEvent is AppStreamEvent.Finish || appEvent is AppStreamEvent.StreamEnd || appEvent is AppStreamEvent.Error) {
                                throw CancellationException("Stream finished with event: ${appEvent::class.simpleName}")
                            }
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
                    
                    // 🎯 Save partial content to history on cancellation (Requirements: 7.5)
                    stateHolder.flushStreamingBuffer(aiMessageId)
                    logger.debug("Flushed StreamingBuffer on cancellation for message: $aiMessageId")
                    
                    // Get partial content from message processor
                    val partialText = currentMessageProcessor.getCurrentText().trim()
                    if (partialText.isNotBlank()) {
                        logger.debug("Saving partial content (${partialText.length} chars) to history on cancellation")
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                                logger.debug("Successfully saved partial content to history")
                            } catch (saveError: Exception) {
                                logger.error("Failed to save partial content to history", saveError)
                            }
                        }
                    }
                    
                    // 🎯 清理 StreamingBuffer（Requirements: 7.5）
                    stateHolder.clearStreamingBuffer(aiMessageId)
                    logger.debug("Cleared StreamingBuffer on cancellation exception for message: $aiMessageId")
                }
            } finally {
                // 🎯 最终安全网：如果在 onCompletion 中因异常未执行同步，这里再尝试一次
                // 但为了避免重复执行，syncStreamingMessageToList 内部有空值检查
                // 注意：在 finally 中不应抛出异常
                try {
                    stateHolder.flushStreamingBuffer(aiMessageId)
                    stateHolder.syncStreamingMessageToList(aiMessageId, isImageGeneration)
                } catch (e: Exception) {
                    logger.warn("Final sync in finally block failed: ${e.message}")
                }

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
        // 获取当前消息ID对应的处理器和块管理器，若不存在则创建并加入映射
        val currentMessageProcessor = synchronized(messageProcessorMap) {
            messageProcessorMap.getOrPut(aiMessageId) { MessageProcessor() }
        }
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
                    if (processedResult is com.android.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        val deltaChunk = appEvent.text
                        // 过滤纯空白内容，防止后端发送大量空格导致卡死
                        if (!deltaChunk.isNullOrEmpty() && deltaChunk.isNotBlank()) {
                            // 🛡️ 防 prompt 泄露：通过检测器过滤
                            val leakDetector = promptLeakDetectors.getOrPut(aiMessageId) { PromptLeakGuard.StreamingDetector() }
                            val filteredChunk = leakDetector.appendAndCheck(deltaChunk)
                            if (filteredChunk.isEmpty()) {
                                logger.warn("🛡️ Blocked content chunk due to prompt leak detection for message $aiMessageId")
                                return@withContext
                            }
                            // sampling-based performance record
                            PerformanceMonitor.recordEvent(aiMessageId, "Content", filteredChunk.length)
                            // 🔍 [STREAM_DEBUG_ANDROID]
                            android.util.Log.i("STREAM_DEBUG", "[ApiHandler] ✅ Content event received: msgId=$aiMessageId, chunkLen=${filteredChunk.length}, preview='${filteredChunk.take(30)}'")
                            stateHolder.appendContentToMessage(aiMessageId, filteredChunk, isImageGeneration)
                            // 🎯 第一个非空内容到来时，标记contentStarted = true
                            // 这样思考框会收起，正式内容开始流式展示
                            if (!currentMessage.contentStarted) {
                                updatedMessage = updatedMessage.copy(contentStarted = true)
                                logger.debug("First content chunk received for message $aiMessageId, setting contentStarted=true")
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
                        updatedMessage = updatedMessage.copy(
                            executionStatus = "正在执行代码...",
                            contentStarted = true
                        )
                    }
                }
                is AppStreamEvent.CodeExecutionResult -> {
                    // 清除执行状态，追加执行结果
                    updatedMessage = updatedMessage.copy(executionStatus = null)
                    val output = appEvent.codeExecutionOutput
                    if (!output.isNullOrBlank()) {
                        val formattedOutput = "\n```text\n$output\n```\n"
                        stateHolder.appendContentToMessage(aiMessageId, formattedOutput, isImageGeneration)
                    }
                    // 如果有图片结果（虽然目前后端通过ImageGeneration事件发送，但保留兼容性）
                    if (!appEvent.imageUrl.isNullOrBlank()) {
                        // 处理图片：构建 Markdown 图片链接并追加到消息
                        // 1) 移除一切空白(空格/制表/换行)，防止被Markdown当作多段文本
                        val cleanUrl = appEvent.imageUrl.replace(Regex("\\s+"), "")
                        // 2) 构建 Markdown 图片链接 (无尖括号，兼容性最好)
                        val imageMarkdown = "\n\n![Generated Image]($cleanUrl)\n\n"
                        stateHolder.appendContentToMessage(aiMessageId, imageMarkdown, isImageGeneration)
                        logger.debug("Appended image markdown to UI state. url.len=${cleanUrl.length}")
                    }
                }
                is AppStreamEvent.Text -> {
                    if (processedResult is com.android.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated) {
                        val deltaChunk = appEvent.text
                        // 过滤纯空白内容
                        if (!deltaChunk.isNullOrEmpty() && deltaChunk.isNotBlank()) {
                            // 🛡️ 防 prompt 泄露：通过检测器过滤
                            val leakDetector = promptLeakDetectors.getOrPut(aiMessageId) { PromptLeakGuard.StreamingDetector() }
                            val filteredChunk = leakDetector.appendAndCheck(deltaChunk)
                            if (filteredChunk.isEmpty()) {
                                logger.warn("🛡️ Blocked text chunk due to prompt leak detection for message $aiMessageId")
                                return@withContext
                            }
                            PerformanceMonitor.recordEvent(aiMessageId, "Text", filteredChunk.length)
                            stateHolder.appendContentToMessage(aiMessageId, filteredChunk, isImageGeneration)
                            // 🎯 第一个非空文本到来时，标记contentStarted = true
                            if (!currentMessage.contentStarted) {
                                updatedMessage = updatedMessage.copy(contentStarted = true)
                                logger.debug("First text chunk received for message $aiMessageId, setting contentStarted=true")
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
                    if (!currentMessage.contentStarted && appEvent.text.isNotBlank()) {
                        updatedMessage = updatedMessage.copy(contentStarted = true)
                        android.util.Log.d("ApiHandler", "   Marked contentStarted=true for backward compatibility")
                    }
                }
                is AppStreamEvent.Reasoning -> {
                    if (processedResult is com.android.everytalk.util.messageprocessor.ProcessedEventResult.ReasoningUpdated) {
                        // 推理增量更新
                        updatedMessage = updatedMessage.copy(reasoning = processedResult.reasoning)
                        
                        // 🔥 核心修复：立即更新消息列表中的 reasoning，确保 UI 实时显示思考框
                        val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                        val currentMessage = messageList.find { it.id == aiMessageId }
                        if (currentMessage != null) {
                            val deltaReasoning = processedResult.reasoning.removePrefix(currentMessage.reasoning ?: "")
                            if (deltaReasoning.isNotEmpty()) {
                                PerformanceMonitor.recordEvent(aiMessageId, "Reasoning", deltaReasoning.length)
                                stateHolder.appendReasoningToMessage(aiMessageId, deltaReasoning, isImageGeneration)
                                android.util.Log.d("ApiHandler", "🎯 Appended reasoning delta (${deltaReasoning.length} chars) to message $aiMessageId")
                            }
                        }
                        
                        // 🎯 根因修复：
                        // - 推理更新之前未标记"会话脏"，导致退出时 reasoning 未被持久化，重启后小白点消失
                        // - 这里在每次推理增量到来时标记脏并立即持久化"last open chat"，确保 reasoning 保留
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

                    // 🎯 刷新 StreamingBuffer 确保所有内容已提交（Requirements: 3.3, 7.1, 7.2）
                    stateHolder.flushStreamingBuffer(aiMessageId)
                    logger.debug("Flushed StreamingBuffer for message: $aiMessageId")
                    
                    // 🎯 强制 StreamingMessageStateManager 最终 flush (忽略代码块闭合检查)
                    // 这一步至关重要，确保 UI 层的 StateFlow 接收到最后一段可能被暂缓的文本
                    stateHolder.streamingMessageStateManager.finalizeMessage(aiMessageId)
                    logger.debug("Finalized StreamingMessageStateManager for message: $aiMessageId")
                    
                    // 🎯 Task 11: Log performance metrics at stream completion
                    // This provides a summary of streaming performance for debugging
                    // Requirements: 1.4, 3.4
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
                    val finalizedMessage = currentMessageProcessor.finalizeMessageProcessing(currentMessage)
                    updatedMessage = finalizedMessage.copy(
                        contentStarted = true
                    )
                    
                    // 🎯 同步流式消息到 messages 列表（一次性更新）
                    stateHolder.syncStreamingMessageToList(aiMessageId, isImageGeneration)
                    logger.debug("Synced streaming message $aiMessageId to messages list")
                    
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
                    
                    // 按用户期望：不要在 finish 事件处强制切 isStreaming=false
                    // 说明：
                    // - 是否呈现"最终渲染"由渲染层的 looksFinalized 判定决定（MarkdownRenderer）
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
                    // 收到工具调用请求
                    logger.debug("Received ToolCall event: ${appEvent.name}")
                    // 暂时仅记录日志，后续版本可在此处分发给 ToolExecutionManager
                    // 由于当前需求未要求在客户端真实执行（而是依赖后端或模拟），
                    // 我们可以在这里更新消息状态显示 "正在调用工具: ${appEvent.name}"
                    stateHolder.updateMessageStatus(
                        aiMessageId,
                        "正在调用工具: ${appEvent.name}",
                        isImageGeneration
                    )
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


    private suspend fun updateMessageWithError(messageId: String, error: Throwable, isImageGeneration: Boolean = false, allowRetry: Boolean = true) {
        logger.error("Updating message with error", error)
        // Emit abort summary on error
        PerformanceMonitor.onAbort(messageId, reason = "error:${error.message ?: error.javaClass.simpleName}")
        
        // 🎯 刷新 StreamingBuffer 保留部分内容（Requirements: 7.1, 7.2, 7.5）
        stateHolder.flushStreamingBuffer(messageId)
        logger.debug("Flushed StreamingBuffer before error for message: $messageId")
        
        // 🎯 检查是否应该重试（Requirements: 7.3）
        if (allowRetry && isNetworkError(error)) {
            val currentRetryCount = retryCountMap.getOrDefault(messageId, 0)
            if (currentRetryCount < MAX_RETRY_ATTEMPTS) {
                logger.debug("Network error detected, attempting retry ${currentRetryCount + 1}/$MAX_RETRY_ATTEMPTS for message: $messageId")
                retryCountMap[messageId] = currentRetryCount + 1
                // 延迟后重试
                delay(RETRY_DELAY_MS)
                // 这里可以添加重试逻辑，重新发送请求
                // 注意：实际重试需要在调用方实现，这里只是标记和延迟
                return
            } else {
                logger.debug("Max retry attempts reached for message: $messageId")
                retryCountMap.remove(messageId)
            }
        }
        
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
            
            // 🎯 清理 StreamingBuffer（Requirements: 7.1, 7.2）
            stateHolder.clearStreamingBuffer(messageId)
            logger.debug("Cleared StreamingBuffer after error for message: $messageId")
        }
    }
 
    /**
     * Archive image URLs (http/https or data:image) to internal storage and return local absolute paths.
     * Keeps original URL on failure for each item to avoid breaking UI.
     */
    private suspend fun archiveImageUrlsForMessage(
        applicationContext: Context,
        messageId: String,
        urls: List<String>
    ): List<String> {
        if (urls.isEmpty()) return emptyList()
        // 使用 historyManager.persistenceManager 来进行即时保存
        // 注意：HistoryManager 的 persistenceManager 是 public 的（如果不是，需要改为 public 或添加访问器）
        // 假设 HistoryManager 暴露了 persistenceManager 或我们通过依赖注入获取
        // 由于这里无法直接访问 persistenceManager，我们使用原有的 FileManager 逻辑，但增强其稳定性
        
        val fm = com.android.everytalk.util.storage.FileManager(applicationContext)
        val out = mutableListOf<String>()
        for ((idx, url) in urls.withIndex()) {
            val lower = url.lowercase(java.util.Locale.ROOT)
            // Already a local path or file://
            if (lower.startsWith("file://") || lower.startsWith("/")) {
                out.add(url)
                continue
            }
            
            // 如果是 data:image，尝试使用我们新加的高效保存方法（如果能访问到）
            // 这里我们复用 FileManager 的通用逻辑，它已经很健壮了
            // 关键在于这个方法现在是在接收到响应后立即调用的（在 streamChatResponse 中）
            
            // Load original bytes from flexible source
            val pair = try { fm.loadBytesFromFlexibleSource(url) } catch (_: Exception) { null }
            if (pair == null) {
                out.add(url)
                continue
            }
            val bytes = pair.first
            val mime = pair.second ?: "application/octet-stream"
            val baseName = "img_${messageId}_${idx}"
            // 使用 saveBytesToInternalImages 确保保存到 filesDir/chat_attachments
            val saved = try { fm.saveBytesToInternalImages(bytes, mime, baseName, messageId, idx) } catch (_: Exception) { null }
            
            if (!saved.isNullOrBlank()) {
                logger.debug("Archived image [$idx] to local file: $saved")
                out.add(saved)
            } else {
                logger.warn("Failed to archive image [$idx], keeping original URL")
                out.add(url)
            }
        }
        return out
    }

    // 预编译的正则表达式，避免重复编译
    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val PUNCTUATION_WHITESPACE_REGEX = Regex("[\\p{Punct}\\s]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    
    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "服务响应错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "服务响应错误 ${response.status.value}: ${
                errorBody.take(150).replace(HTML_TAG_REGEX, "")
            }${if (errorBody.length > 150) "..." else ""}"
        }
    }

    private fun isTextOnlyIntent(promptRaw: String?): Boolean {
        val p = promptRaw?.lowercase()?.trim() ?: return false
        if (p.isBlank()) return false

        // 先匹配"仅文本"硬条件，避免被"图片"等词误判
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

        // 简短启发：仅当很短时判定为仅文本，避免"帮我画猫，谢谢"被误判（含"画"等词已优先排除）
        val normalized = p.replace(PUNCTUATION_WHITESPACE_REGEX, "")
        if (normalized.length <= 8) return true
        val tokenCount = p.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size
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

    /**
     * Check if an error is a network-related error that should trigger retry
     * 
     * 🎯 Network error detection (Requirements: 7.3)
     * 
     * @param error The error to check
     * @return true if this is a retryable network error
     */
    private fun isNetworkError(error: Throwable): Boolean {
        return when (error) {
            is IOException -> {
                val message = error.message?.lowercase() ?: ""
                // Check for common network error patterns
                message.contains("network") ||
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("unreachable") ||
                message.contains("failed to connect") ||
                message.contains("socket") ||
                message.contains("interrupted")
            }
            else -> false
        }
    }
    
    /**
     * Reset retry count for a message
     * Should be called when stream completes successfully
     * 
     * @param messageId Message ID to reset retry count for
     */
    private fun resetRetryCount(messageId: String) {
        retryCountMap.remove(messageId)
    }
    
    /**
     * 清理文本聊天相关的资源，确保会话间完全隔离
     *
     * 🎯 优化策略（Requirements: 6.1, 6.2, 6.3）：
     * - 只清理不在当前消息列表中的处理器（inactive processors）
     * - 保留当前活跃会话的所有处理器
     * - 清理已处理的消息ID集合
     * - 触发会话参数清理（保留最近50个）
     */
    fun clearTextChatResources() {
        logger.debug("=== TEXT CHAT RESOURCE CLEANUP START ===")
        logger.debug("Clearing text chat resources for session isolation (Requirements: 6.1, 6.2)")
        
        // 获取当前活跃的消息ID
        val currentMessageIds = stateHolder.messages.map { it.id }.toSet()
        val currentStreamingId = stateHolder._currentTextStreamingAiMessageId.value
        
        // 识别需要清理的处理器（不在当前消息列表中的）
        val inactiveProcessorIds = messageProcessorMap.keys.filter { id ->
            !currentMessageIds.contains(id) && id != currentStreamingId
        }
        
        logger.debug("Current active message count: ${currentMessageIds.size}")
        logger.debug("Current streaming message ID: $currentStreamingId")
        logger.debug("Total processors before cleanup: ${messageProcessorMap.size}")
        logger.debug("Inactive processors to remove: ${inactiveProcessorIds.size}")
        
        // 清理不活跃的处理器
        var removedCount = 0
        inactiveProcessorIds.forEach { messageId ->
            messageProcessorMap.remove(messageId)?.let {
                removedCount++
                logger.debug("✓ Removed inactive processor: $messageId")
            }
            // 🛡️ 清理 prompt 泄露检测器
            promptLeakDetectors.remove(messageId)
        }
        
        // 清理已处理的消息ID集合
        val processedIdsBeforeCleanup = processedMessageIds.size
        processedMessageIds.clear()
        
        logger.debug("Removed $removedCount inactive message processors")
        logger.debug("Cleared $processedIdsBeforeCleanup processed message IDs")
        logger.debug("Remaining active processors: ${messageProcessorMap.size}")
        logger.debug("Active processor IDs: ${messageProcessorMap.keys}")
        
        // 🎯 触发会话参数清理（Requirements: 6.4）
        stateHolder.cleanupOldConversationParameters()
        logger.debug("Triggered conversation parameter cleanup (keep last 50)")
        
        logger.debug("=== TEXT CHAT RESOURCE CLEANUP END ===")
    }

    // 为兼容调用方，提供带 sessionId 的重载，内部忽略参数
    fun clearTextChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) {
        clearTextChatResources()
    }
    
    /**
     * 清理图像聊天相关的资源，确保会话间完全隔离
     * 
     * 🎯 优化策略（Requirements: 6.1, 6.2, 6.3）：
     * - 只清理不在当前消息列表中的处理器（inactive processors）
     * - 保留当前活跃会话的所有处理器
     * - 清理已处理的消息ID集合
     * - 触发会话参数清理（保留最近50个）
     */
    fun clearImageChatResources() {
        logger.debug("=== IMAGE CHAT RESOURCE CLEANUP START ===")
        logger.debug("Clearing image chat resources for session isolation (Requirements: 6.1, 6.2)")
        
        // 获取当前活跃的消息ID
        val currentMessageIds = stateHolder.imageGenerationMessages.map { it.id }.toSet()
        val currentStreamingId = stateHolder._currentImageStreamingAiMessageId.value
        
        // 识别需要清理的处理器（不在当前消息列表中的）
        val inactiveProcessorIds = messageProcessorMap.keys.filter { id ->
            !currentMessageIds.contains(id) && id != currentStreamingId
        }
        
        logger.debug("Current active image message count: ${currentMessageIds.size}")
        logger.debug("Current streaming image message ID: $currentStreamingId")
        logger.debug("Total processors before cleanup: ${messageProcessorMap.size}")
        logger.debug("Inactive processors to remove: ${inactiveProcessorIds.size}")
        
        // 清理不活跃的处理器
        var removedCount = 0
        inactiveProcessorIds.forEach { messageId ->
            messageProcessorMap.remove(messageId)?.let {
                removedCount++
                logger.debug("✓ Removed inactive image processor: $messageId")
            }
            // 🛡️ 清理 prompt 泄露检测器
            promptLeakDetectors.remove(messageId)
        }
        
        // 清理已处理的消息ID集合
        val processedIdsBeforeCleanup = processedMessageIds.size
        processedMessageIds.clear()
        
        logger.debug("Removed $removedCount inactive image message processors")
        logger.debug("Cleared $processedIdsBeforeCleanup processed message IDs")
        logger.debug("Remaining active processors: ${messageProcessorMap.size}")
        logger.debug("Active processor IDs: ${messageProcessorMap.keys}")
        
        // 🎯 触发会话参数清理（Requirements: 6.4）
        stateHolder.cleanupOldConversationParameters()
        logger.debug("Triggered conversation parameter cleanup (keep last 50)")
        
        logger.debug("=== IMAGE CHAT RESOURCE CLEANUP END ===")
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
    
    /**
     * 检测内存压力并触发清理
     * 
     * 🎯 内存管理策略（Requirements: 6.5）：
     * - 监控消息处理器数量
     * - 当处理器数量超过阈值时触发清理
     * - 优先清理不活跃的处理器
     * - 清理旧的会话参数
     * 
     * @return true if cleanup was triggered, false otherwise
     */
    fun checkMemoryPressureAndCleanup(): Boolean {
        val processorCount = messageProcessorMap.size
        val threshold = 100 // 当处理器数量超过100时触发清理
        
        if (processorCount > threshold) {
            logger.debug("=== MEMORY PRESSURE DETECTED ===")
            logger.debug("Processor count ($processorCount) exceeds threshold ($threshold)")
            logger.debug("Triggering aggressive cleanup (Requirement: 6.5)")
            
            // 获取当前活跃的消息ID（文本和图像）
            val activeTextMessageIds = stateHolder.messages.map { it.id }.toSet()
            val activeImageMessageIds = stateHolder.imageGenerationMessages.map { it.id }.toSet()
            val currentTextStreamingId = stateHolder._currentTextStreamingAiMessageId.value
            val currentImageStreamingId = stateHolder._currentImageStreamingAiMessageId.value
            
            val allActiveIds = activeTextMessageIds + activeImageMessageIds + 
                listOfNotNull(currentTextStreamingId, currentImageStreamingId)
            
            // 清理所有不活跃的处理器
            val inactiveProcessorIds = messageProcessorMap.keys.filter { id ->
                !allActiveIds.contains(id)
            }
            
            logger.debug("Active message IDs: ${allActiveIds.size}")
            logger.debug("Inactive processors to remove: ${inactiveProcessorIds.size}")
            
            var removedCount = 0
            inactiveProcessorIds.forEach { messageId ->
                messageProcessorMap.remove(messageId)?.let {
                    removedCount++
                }
                // (removed) local archiveImageUrlsForMessage() definitions moved to class scope
            }
            
            // 清理已处理的消息ID集合
            processedMessageIds.clear()
            
            // 清理旧的会话参数
            stateHolder.cleanupOldConversationParameters()
            
            logger.debug("Memory pressure cleanup complete:")
            logger.debug("  - Removed $removedCount inactive processors")
            logger.debug("  - Remaining processors: ${messageProcessorMap.size}")
            logger.debug("  - Cleared processed message IDs")
            logger.debug("  - Cleaned up old conversation parameters")
            logger.debug("=== MEMORY PRESSURE CLEANUP END ===")
            
            return true
        }
        
        return false
    }
    
    /**
     * 获取当前资源使用统计信息
     * 用于调试和监控
     */
    fun getResourceStats(): String {
        return buildString {
            appendLine("=== Resource Statistics ===")
            appendLine("Message Processors: ${messageProcessorMap.size}")
            appendLine("Processed Message IDs: ${processedMessageIds.size}")
            appendLine("Active Text Messages: ${stateHolder.messages.size}")
            appendLine("Active Image Messages: ${stateHolder.imageGenerationMessages.size}")
            appendLine("Conversation Parameters: ${stateHolder.conversationGenerationConfigs.value.size}")
            appendLine("Streaming Buffers: ${stateHolder.getStreamingBufferCount()}")
        }
    }
}
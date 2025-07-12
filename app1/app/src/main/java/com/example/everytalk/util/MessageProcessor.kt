package com.example.everytalk.util

import com.example.everytalk.data.DataClass.AbstractApiMessage
import com.example.everytalk.data.DataClass.ApiContentPart
import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.data.DataClass.IMessage
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.PartsApiMessage
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.SimpleTextApiMessage
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.data.DataClass.toRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一的消息处理类，用于解决消息处理冲突
 * 提供线程安全的消息处理机制
 */
class MessageProcessor {
    private val logger = AppLogger.forComponent("MessageProcessor")
    private val messagesMutex = Mutex()
    private val isCancelled = AtomicBoolean(false)
    private val currentTextBuilder = AtomicReference(StringBuilder())
    private val currentReasoningBuilder = AtomicReference(StringBuilder())
    private val processedChunks = ConcurrentHashMap<String, String>()
    
    /**
     * 处理流式事件
     * @param event 流式事件
     * @param currentMessageId 当前消息ID
     */
    suspend fun processStreamEvent(
        event: AppStreamEvent,
        currentMessageId: String
    ): ProcessedEventResult {
        if (isCancelled.get()) {
            logger.debug("Event processing cancelled for message $currentMessageId")
            return ProcessedEventResult.Cancelled
        }
        
        return PerformanceMonitor.measure("MessageProcessor.processStreamEvent") {
            messagesMutex.withLock {
                try {
                   when (event) {
                       is AppStreamEvent.Text -> {
                           if (event.text.isNotEmpty()) {
                               val chunkKey = "content_${event.text.hashCode()}"
                               if (!processedChunks.containsKey(chunkKey)) {
                                   currentTextBuilder.get().append(event.text)
                                   processedChunks[chunkKey] = event.text
                               }
                           }
                           ProcessedEventResult.ContentUpdated(currentTextBuilder.get().toString())
                       }
                       is AppStreamEvent.Content -> {
                           if (event.text.isNotEmpty()) {
                               val chunkKey = "content_${event.text.hashCode()}"
                               if (!processedChunks.containsKey(chunkKey)) {
                                   currentTextBuilder.get().append(event.text)
                                   processedChunks[chunkKey] = event.text
                               }
                           }
                           ProcessedEventResult.ContentUpdated(currentTextBuilder.get().toString())
                       }
                       is AppStreamEvent.Reasoning -> {
                           if (event.text.isNotEmpty()) {
                               val chunkKey = "reasoning_${event.text.hashCode()}"
                               if (!processedChunks.containsKey(chunkKey)) {
                                   currentReasoningBuilder.get().append(event.text)
                                   processedChunks[chunkKey] = event.text
                               }
                           }
                           ProcessedEventResult.ReasoningUpdated(currentReasoningBuilder.get().toString())
                       }
                       is AppStreamEvent.StreamEnd, is AppStreamEvent.ToolCall, is AppStreamEvent.Finish -> {
                           ProcessedEventResult.ReasoningComplete
                       }
                       is AppStreamEvent.WebSearchStatus -> {
                           ProcessedEventResult.StatusUpdate(event.stage)
                       }
                       is AppStreamEvent.WebSearchResults -> {
                           ProcessedEventResult.WebSearchResults(event.results)
                       }
                       is AppStreamEvent.Error -> {
                           val errorMessage = "SSE Error: ${event.message}"
                           ProcessedEventResult.Error(errorMessage)
                       }
                   }
                } catch (e: Exception) {
                    logger.error("Error processing event", e)
                    ProcessedEventResult.Error("Error processing event: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 取消消息处理
     */
    fun cancel() {
        isCancelled.set(true)
        logger.debug("Message processing cancelled")
    }
    
    /**
     * 重置处理器状态
     */
    fun reset() {
        isCancelled.set(false)
        currentTextBuilder.set(StringBuilder())
        currentReasoningBuilder.set(StringBuilder())
        processedChunks.clear()
        logger.debug("Message processor reset")
    }
    
    /**
     * 获取当前文本内容
     */
    fun getCurrentText(): String = currentTextBuilder.get().toString()
    
    /**
     * 获取当前推理内容
     */
    fun getCurrentReasoning(): String? {
        val reasoning = currentReasoningBuilder.get().toString()
        return if (reasoning.isBlank()) null else reasoning
    }
    
    /**
     * 将UI消息转换为API消息
     * @param message UI消息
     * @return API消息
     */
    fun convertToApiMessage(message: Message): AbstractApiMessage {
        return if (message.attachments.isNotEmpty()) {
            // 如果有附件，使用PartsApiMessage
            val parts = mutableListOf<ApiContentPart>()
            if (message.text.isNotBlank()) {
                parts.add(ApiContentPart.Text(message.text))
            }
            // 这里可以添加附件转换逻辑
            PartsApiMessage(
                id = message.id,
                role = message.sender.toRole(),
                parts = parts,
                name = message.name
            )
        } else {
            // 如果没有附件，使用SimpleTextApiMessage
            SimpleTextApiMessage(
                id = message.id,
                role = message.sender.toRole(),
                content = message.text,
                name = message.name
            )
        }
    }
    
    /**
     * 创建新的AI消息
     * @return 新的AI消息
     */
    fun createNewAiMessage(): Message {
        return Message(
            id = UUID.randomUUID().toString(),
            text = "",
            sender = Sender.AI,
            contentStarted = false
        )
    }
    
    /**
     * 创建新的用户消息
     * @param text 消息文本
     * @param imageUrls 图片URL列表
     * @param attachments 附件列表
     * @return 新的用户消息
     */
    fun createNewUserMessage(
        text: String,
        imageUrls: List<String>? = null,
        attachments: List<com.example.everytalk.models.SelectedMediaItem>? = null
    ): Message {
        return Message(
            id = "user_${UUID.randomUUID()}",
            text = text,
            sender = Sender.User,
            timestamp = System.currentTimeMillis(),
            contentStarted = true,
            imageUrls = imageUrls?.ifEmpty { null },
            attachments = attachments ?: emptyList()
        )
    }
}

/**
 * 处理事件的结果
 */
sealed class ProcessedEventResult {
    /**
     * 内容已更新
     * @param content 更新后的内容
     */
    data class ContentUpdated(val content: String) : ProcessedEventResult()
    
    /**
     * 推理内容已更新
     * @param reasoning 更新后的推理内容
     */
    data class ReasoningUpdated(val reasoning: String) : ProcessedEventResult()
    
    /**
     * 推理完成
     */
    object ReasoningComplete : ProcessedEventResult()
    
    /**
     * 状态更新
     * @param stage 当前阶段
     */
    data class StatusUpdate(val stage: String) : ProcessedEventResult()
    
    /**
     * 网络搜索结果
     * @param results 搜索结果列表
     */
    data class WebSearchResults(val results: List<WebSearchResult>) : ProcessedEventResult()
    
    /**
     * 错误
     * @param message 错误消息
     */
    data class Error(val message: String) : ProcessedEventResult()
    
    /**
     * 已取消
     */
    object Cancelled : ProcessedEventResult()
    
    /**
     * 无变化
     */
    object NoChange : ProcessedEventResult()
}
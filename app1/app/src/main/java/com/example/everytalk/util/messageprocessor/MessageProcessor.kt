package com.example.everytalk.util.messageprocessor

import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MessageProcessor {
    private val logger = AppLogger.forComponent("MessageProcessor")
    private val sessionId = AtomicReference<String?>(null)
    private val messageId = AtomicReference<String?>(null)
    private val messagesMutex = Mutex()
    private val isCancelled = AtomicBoolean(false)
    private val isCompleted = AtomicBoolean(false)
    private val currentTextBuilder = AtomicReference(StringBuilder())
    private val currentReasoningBuilder = AtomicReference(StringBuilder())

    fun initialize(sessionId: String, messageId: String) {
        this.sessionId.set(sessionId)
        this.messageId.set(messageId)
    }

    fun getCurrentText(): String = currentTextBuilder.get().toString()
    fun getCurrentReasoning(): String? = currentReasoningBuilder.get().toString().ifBlank { null }
    fun isStreamCompleted(): Boolean = isCompleted.get()

    suspend fun processStreamEvent(event: AppStreamEvent, currentMessageId: String): ProcessedEventResult {
        if (messageId.get() != null && messageId.get() != currentMessageId) {
            logger.warn("Ignoring event for different message: expected=${messageId.get()}, got=$currentMessageId")
            return ProcessedEventResult.Cancelled
        }
        if (isCancelled.get() || isCompleted.get()) {
            return if (isCancelled.get()) ProcessedEventResult.Cancelled else ProcessedEventResult.NoChange
        }

        return messagesMutex.withLock {
            when (event) {
                is AppStreamEvent.Text, is AppStreamEvent.Content, is AppStreamEvent.ContentFinal -> {
                    val eventText = when (event) {
                        is AppStreamEvent.Text -> event.text
                        is AppStreamEvent.Content -> event.text
                        is AppStreamEvent.ContentFinal -> event.text
                        else -> ""
                    }
                    if (eventText.isNotEmpty()) {
                        currentTextBuilder.get().append(eventText)
                    }
                    ProcessedEventResult.ContentUpdated(currentTextBuilder.get().toString())
                }
                is AppStreamEvent.Reasoning -> {
                    if (event.text.isNotEmpty()) {
                        currentReasoningBuilder.get().append(event.text)
                    }
                    ProcessedEventResult.ReasoningUpdated(currentReasoningBuilder.get().toString())
                }
                is AppStreamEvent.ReasoningFinish -> {
                    // 推理完成事件 - 标记推理已完成，但流还在继续
                    ProcessedEventResult.ReasoningComplete
                }
                is AppStreamEvent.StreamEnd, is AppStreamEvent.Finish -> {
                    isCompleted.set(true)
                    ProcessedEventResult.StreamComplete
                }
                else -> ProcessedEventResult.NoChange
            }
        }
    }

    fun finalizeMessageProcessing(message: Message): Message {
        val currentText = getCurrentText()
        val currentReasoning = getCurrentReasoning()
        
        // 🔥 添加调试日志，诊断消息文本丢失问题
        android.util.Log.d("MessageProcessor", "=== FINALIZE MESSAGE PROCESSING ===")
        android.util.Log.d("MessageProcessor", "Message ID: ${message.id}")
        android.util.Log.d("MessageProcessor", "Original text length: ${message.text.length}")
        android.util.Log.d("MessageProcessor", "Current text length: ${currentText.length}")
        android.util.Log.d("MessageProcessor", "Current reasoning length: ${currentReasoning?.length ?: 0}")
        android.util.Log.d("MessageProcessor", "Parts count: ${message.parts.size}")
        
        // 确保文本内容不会丢失
        val finalText = if (currentText.isNotBlank()) {
            currentText
        } else if (message.text.isNotBlank()) {
            // 如果当前文本为空但原消息有文本，使用原消息文本
            android.util.Log.d("MessageProcessor", "Using original message text as fallback")
            message.text
        } else if (message.parts.isNotEmpty()) {
            // 尝试从parts重建文本
            val rebuiltFromParts = message.parts.filterIsInstance<com.example.everytalk.ui.components.MarkdownPart.Text>()
                .joinToString("") { it.content }
            
            if (rebuiltFromParts.isNotBlank()) {
                android.util.Log.d("MessageProcessor", "Rebuilt text from parts: ${rebuiltFromParts.take(50)}...")
                rebuiltFromParts
            } else {
                // 最后的占位符
                android.util.Log.w("MessageProcessor", "Using placeholder text - all recovery methods failed")
                "..."
            }
        } else {
            // 完全没有内容，使用占位符
            android.util.Log.w("MessageProcessor", "No content available, using placeholder")
            "..."
        }
        
        android.util.Log.d("MessageProcessor", "Final text length: ${finalText.length}")
        android.util.Log.d("MessageProcessor", "=== END FINALIZE MESSAGE PROCESSING ===")
        
        return message.copy(
            text = finalText,
            reasoning = currentReasoning,
            contentStarted = true
        )
    }

    fun cancel() {
        isCancelled.set(true)
    }

    fun reset() {
        isCancelled.set(false)
        isCompleted.set(false)
        currentTextBuilder.set(StringBuilder())
        currentReasoningBuilder.set(StringBuilder())
    }
}

sealed class ProcessedEventResult {
    data class ContentUpdated(val content: String) : ProcessedEventResult()
    data class ReasoningUpdated(val reasoning: String) : ProcessedEventResult()
    object ReasoningComplete : ProcessedEventResult()
    object StreamComplete : ProcessedEventResult()
    object NoChange : ProcessedEventResult()
    object Cancelled : ProcessedEventResult()
    data class Error(val message: String) : ProcessedEventResult()
}
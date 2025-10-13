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
        
        logger.debug("Finalizing message ${message.id}: currentText=${currentText.length} chars, reasoning=${currentReasoning?.length ?: 0} chars")
        
        // 简化逻辑：优先使用 currentText，只在为空时才使用 message.text 作为 fallback
        val finalText = when {
            currentText.isNotBlank() -> {
                // 正常情况：流式处理累积的文本
                currentText
            }
            message.text.isNotBlank() -> {
                // Fallback 1：使用原消息文本（可能是非流式或已完成的消息）
                logger.warn("Message ${message.id}: Using original text as fallback (currentText is empty)")
                message.text
            }
            else -> {
                // 异常情况：文本丢失，记录错误并抛出异常
                logger.error("Message ${message.id}: CRITICAL - Both currentText and message.text are empty!")
                throw IllegalStateException(
                    "Message text is empty during finalization. This indicates a processing error. " +
                    "MessageId: ${message.id}, Parts count: ${message.parts.size}"
                )
            }
        }
        
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
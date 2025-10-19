package com.example.everytalk.util.messageprocessor

import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.util.AppLogger
import com.example.everytalk.util.messageprocessor.ContentFinalValidator
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
        logger.debug("Initialized MessageProcessor for session=$sessionId, message=$messageId")
    }

    /**
     * 直接返回原始AI输出，不做任何格式清理。
     */
    private fun lightweightCleanup(text: String): String {
        return text
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
                is AppStreamEvent.Text, is AppStreamEvent.Content -> {
                    val eventText = when (event) {
                        is AppStreamEvent.Text -> event.text
                        is AppStreamEvent.Content -> event.text
                        else -> ""
                    }
                    if (eventText.isNotEmpty()) {
                        // 对每个chunk进行轻量级清理（仅转换全角符号）
                        val cleanedChunk = lightweightCleanup(eventText)
                        
                        // 记录是否发生了清理
                        if (cleanedChunk != eventText) {
                            logger.debug("Chunk cleaned: '${eventText.take(20)}...' -> '${cleanedChunk.take(20)}...' (${eventText.length} -> ${cleanedChunk.length} chars)")
                        }
                        
                        currentTextBuilder.get().append(cleanedChunk)
                    }
                    ProcessedEventResult.ContentUpdated(currentTextBuilder.get().toString())
                }
                is AppStreamEvent.ContentFinal -> {
                    if (event.text.isNotEmpty()) {
                        val currentContent = currentTextBuilder.get().toString()
                        val finalContent = event.text

                        val shouldReplace = ContentFinalValidator.shouldReplaceCurrent(currentContent, finalContent)
                        if (shouldReplace) {
                            currentTextBuilder.set(StringBuilder(finalContent))
                            logger.debug("ContentFinal: replaced current content with validated final.")
                        } else {
                            val merged = ContentFinalValidator.mergeContent(currentContent, finalContent)
                            if (merged != currentContent) {
                                currentTextBuilder.set(StringBuilder(merged))
                                logger.debug("ContentFinal: applied conservative merge.")
                            } else {
                                logger.debug("ContentFinal: kept current content; final not suitable for replace/merge.")
                            }
                        }
                    } else {
                        logger.warn("ContentFinal event received but text is empty")
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
                // 软回退：双空时不再抛出异常，返回空串并记录错误，避免用户侧“无反馈中断”
                logger.error("Message ${message.id}: CRITICAL - Both currentText and message.text are empty! Soft-fallback to empty string to avoid visible failure.")
                ""
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
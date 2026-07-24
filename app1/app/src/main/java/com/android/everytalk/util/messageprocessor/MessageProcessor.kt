package com.android.everytalk.util.messageprocessor

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.PromptCapabilityCatalog
import com.android.everytalk.data.network.extractThinkTagContent
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.text.CapabilityCardOutputSanitizer
import com.android.everytalk.util.text.TextSanitizer
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
    private val capabilityCardSanitizer = CapabilityCardOutputSanitizer.StreamingDetector()

    fun initialize(sessionId: String, messageId: String) {
        this.sessionId.set(sessionId)
        this.messageId.set(messageId)
        logger.debug("Initialized MessageProcessor for session=$sessionId, message=$messageId")
    }

    /**
     * 直接返回原始AI输出，不做任何格式清理。
     */
    private fun lightweightCleanup(text: String): String {
        // 全角空格归一化，减少排版异常
        var s = TextSanitizer.removeUnicodeReplacementCharacters(text)
                    .replace("\u3000", " ")
                    .replace("&nbsp;", " ")
                    .replace("\u00A0", " ")
        return s
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
                    }
                    if (eventText.isNotEmpty()) {
                        // 对每个chunk进行轻量级清理（仅转换全角符号）
                        val cleanedChunk = lightweightCleanup(eventText)
                        val visibleChunk = capabilityCardSanitizer.appendAndSanitize(cleanedChunk)
                        
                        // 记录是否发生了清理
                        if (visibleChunk != cleanedChunk) {
                            logger.debug("Chunk cleaned: ${eventText.length} -> ${visibleChunk.length} chars")
                        }
                        
                        currentTextBuilder.get().append(visibleChunk)
                        return@withLock ProcessedEventResult.ContentUpdated(visibleChunk)
                    }
                    ProcessedEventResult.ContentUpdated("")
                }
                is AppStreamEvent.ContentFinal -> {
                    // 不对已有内容做任何替换/合并处理，保持已累积文本原样
                    ProcessedEventResult.ContentUpdated("")
                }
                is AppStreamEvent.CodeExecutionResult -> {
                    // 图片由 ApiHandler 在落盘后统一写入消息状态，这里只处理文本执行结果。
                    val builder = currentTextBuilder.get()
                    val textSoFar = builder.toString()

                    // 检测未闭合的三反引号围栏数量（奇数表示未闭合）
                    val fenceCount = Regex("```").findAll(textSoFar).count()
                    if (fenceCount % 2 == 1) {
                        builder.append("\n```\n")
                    }

                    if (!event.codeExecutionOutput.isNullOrBlank()) {
                        val outputMarkdown = "\n\n```\n${event.codeExecutionOutput}\n```\n\n"
                        builder.append(outputMarkdown)
                        ProcessedEventResult.ContentUpdated(outputMarkdown)
                    } else {
                        ProcessedEventResult.NoChange
                    }
                }
                is AppStreamEvent.Reasoning -> {
                    if (event.text.isNotEmpty()) {
                        currentReasoningBuilder.get().append(lightweightCleanup(event.text))
                    }
                    ProcessedEventResult.ReasoningUpdated
                }
                is AppStreamEvent.ReasoningFinish -> {
                    // 推理完成事件 - 标记推理已完成，但流还在继续
                    ProcessedEventResult.ReasoningComplete
                }
                is AppStreamEvent.ToolCall -> {
                    // 暂时不在此处处理 ToolCall，由 ApiHandler 拦截并处理
                    // 返回 NoChange 或 新增 ToolCallResult
                    if (event.name.equals(PromptCapabilityCatalog.SELECT_TOOL_NAME, ignoreCase = true)) {
                        capabilityCardSanitizer.enable()
                    }
                    ProcessedEventResult.NoChange
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
        currentTextBuilder.get().append(capabilityCardSanitizer.flush())
        val currentText = getCurrentText()
        val currentReasoning = getCurrentReasoning()

        logger.debug("Finalizing message ${message.id}: currentText=${currentText.length} chars, reasoning=${currentReasoning?.length ?: 0} chars")

        // 不做“整体重组/替换”。若本地缓冲为空，保留既有 message 字段，避免覆盖已持久化/已加载的文本。
        val normalizedFinalText = TextSanitizer.removeUnicodeReplacementCharacters(
            if (currentText.isNotEmpty()) currentText else message.text
        )
        val rawFinalText = if (capabilityCardSanitizer.isEnabled()) {
            CapabilityCardOutputSanitizer.sanitize(normalizedFinalText)
        } else {
            normalizedFinalText
        }
        val thinkTagExtraction = extractThinkTagContent(rawFinalText)
        val finalText = thinkTagExtraction.content
        val extractedReasoning = thinkTagExtraction.reasoning.takeIf { it.isNotBlank() }
        val finalReasoning = mergeReasoningSegments(
            currentReasoning,
            message.reasoning,
            extractedReasoning,
        )
        val finalParts = when {
            thinkTagExtraction.changed && finalText.isBlank() -> emptyList()
            finalText.isBlank() -> message.parts
            currentText.isNotEmpty() || thinkTagExtraction.changed -> buildMarkdownParts(finalText)
            message.parts.isNotEmpty() -> message.parts
            else -> buildMarkdownParts(finalText)
        }

        return message.copy(
            text = finalText,
            reasoning = finalReasoning,
            contentStarted = message.contentStarted || finalText.isNotBlank() || !finalReasoning.isNullOrBlank(),
            parts = finalParts
        )
    }

    private fun buildMarkdownParts(text: String): List<MarkdownPart> {
        return listOf(MarkdownPart.Text(id = "text_0", content = text))
    }

    fun cancel() {
        isCancelled.set(true)
    }

    fun reset() {
        isCancelled.set(false)
        isCompleted.set(false)
        currentTextBuilder.set(StringBuilder())
        currentReasoningBuilder.set(StringBuilder())
        capabilityCardSanitizer.reset()
    }
}

sealed class ProcessedEventResult {
    data class ContentUpdated(val text: String) : ProcessedEventResult()
    data object ReasoningUpdated : ProcessedEventResult()
    object ReasoningComplete : ProcessedEventResult()
    object StreamComplete : ProcessedEventResult()
    object NoChange : ProcessedEventResult()
    object Cancelled : ProcessedEventResult()
    data class Error(val message: String) : ProcessedEventResult()
}

internal fun mergeReasoningSegments(vararg segments: String?): String? {
    val merged = mutableListOf<String>()
    segments.asSequence()
        .filterNotNull()
        .map(TextSanitizer::removeUnicodeReplacementCharacters)
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { candidate ->
            if (merged.any { existing -> existing == candidate || existing.contains(candidate) }) {
                return@forEach
            }
            merged.removeAll { existing -> candidate.contains(existing) }
            merged += candidate
        }
    return merged.joinToString("\n\n").ifBlank { null }
}

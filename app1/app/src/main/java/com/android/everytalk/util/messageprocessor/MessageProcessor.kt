package com.android.everytalk.util.messageprocessor

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.util.AppLogger
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
        // 全角空格归一化，减少排版异常
        var s = text.replace("\u3000", " ")
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
                    // 不对已有内容做任何替换/合并处理，保持已累积文本原样
                    ProcessedEventResult.ContentUpdated(currentTextBuilder.get().toString())
                }
                is AppStreamEvent.CodeExecutionResult -> {
                    // 处理代码执行结果：优先显示图片；若前面有未闭合代码块，先补一个围栏以避免图片被包进代码块
                    val builder = currentTextBuilder.get()
                    val textSoFar = builder.toString()

                    // 检测未闭合的三反引号围栏数量（奇数表示未闭合）
                    val fenceCount = Regex("```").findAll(textSoFar).count()
                    if (fenceCount % 2 == 1) {
                        builder.append("\n```\n")
                    }

                    // 若包含图片URL，优先渲染图片；并清理 data:image 中的所有空白，避免换行破坏 Markdown 链接
                    if (!event.imageUrl.isNullOrBlank()) {
                        // 1) 移除一切空白(空格/制表/换行)，防止被Markdown当作多段文本
                        val cleanUrl = event.imageUrl.replace(Regex("\\s+"), "")
                        // 2) 使用尖括号包裹URL，进一步避免URL中残留特殊字符对Markdown解析造成影响
                        val imageMarkdown = "\n\n![Generated Image](<" + cleanUrl + ">)\n\n"
                        builder.append(imageMarkdown)
                        logger.debug("Appended image markdown. url.len=${cleanUrl.length}, md.len=${imageMarkdown.length}")
                        ProcessedEventResult.ContentUpdated(builder.toString())
                    } else {
                        // 兜底：仅文本输出时以代码块形式追加，并显式闭合
                        if (!event.codeExecutionOutput.isNullOrBlank()) {
                            val outputMarkdown = "\n\n```\n${event.codeExecutionOutput}\n```\n\n"
                            builder.append(outputMarkdown)
                            ProcessedEventResult.ContentUpdated(builder.toString())
                        } else {
                            ProcessedEventResult.NoChange
                        }
                    }
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
                is AppStreamEvent.ToolCall -> {
                    // 暂时不在此处处理 ToolCall，由 ApiHandler 拦截并处理
                    // 返回 NoChange 或 新增 ToolCallResult
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
        val currentText = getCurrentText()
        val currentReasoning = getCurrentReasoning()

        logger.debug("Finalizing message ${message.id}: currentText=${currentText.length} chars, reasoning=${currentReasoning?.length ?: 0} chars")

        // 不做“整体重组/替换”。若本地缓冲为空，保留既有 message 字段，避免覆盖已持久化/已加载的文本。
        val finalText = if (currentText.isNotEmpty()) currentText else message.text
        val finalReasoning = currentReasoning ?: message.reasoning

        return message.copy(
            text = finalText,
            reasoning = finalReasoning,
            contentStarted = message.contentStarted || finalText.isNotBlank() || !finalReasoning.isNullOrBlank()
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
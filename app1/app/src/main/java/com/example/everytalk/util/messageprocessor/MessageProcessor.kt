package com.example.everytalk.util.messageprocessor

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
import com.example.everytalk.util.AppLogger
import com.example.everytalk.util.PerformanceMonitor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一的消息处理类，用于解决消息处理冲突
 * 提供线程安全的消息处理机制
 * 增强版本：包含强大的AI输出格式矫正功能和性能优化
 */
class MessageProcessor {
    private val logger = AppLogger.forComponent("MessageProcessor")
    
    // 格式矫正配置
    private var formatConfig = FormatCorrectionConfig()
    
    // 性能监控
    private val performanceMetrics = PerformanceMetrics()
    
    // 缓存系统
    private val correctionCache = ConcurrentHashMap<String, String>()
    private val preprocessingCache = ConcurrentHashMap<String, String>()
    
    // 线程安全的消息处理状态
    private val messagesMutex = Mutex()
    private val isCancelled = AtomicBoolean(false)
    private val currentTextBuilder = AtomicReference(StringBuilder())
    private val currentReasoningBuilder = AtomicReference(StringBuilder())
    private val processedChunks = ConcurrentHashMap<String, String>()
    private val currentOutputType = AtomicReference("general")
    
    // <think>标签处理相关状态
    private val thinkingBuffer = AtomicReference(StringBuilder())
    private val isInsideThinkTag = AtomicBoolean(false)
    private val hasFoundThinkTag = AtomicBoolean(false)
    
    // 格式矫正器
    private val formatCorrector = FormatCorrector(formatConfig, performanceMetrics, correctionCache, preprocessingCache)
    
    // 实时预处理器
    private val realtimePreprocessor = RealtimePreprocessor(formatConfig, performanceMetrics, preprocessingCache)
    
    // 错误矫正器
    private val errorCorrector = ErrorCorrector(formatConfig, performanceMetrics, correctionCache)
    
    // 思考内容处理器
    private val thinkingProcessor = ThinkingContentProcessor(thinkingBuffer, isInsideThinkTag, hasFoundThinkTag)
    
    /**
     * 更新格式矫正配置
     */
    fun updateFormatConfig(config: FormatCorrectionConfig) {
        this.formatConfig = config
        formatCorrector.updateConfig(config)
        realtimePreprocessor.updateConfig(config)
        errorCorrector.updateConfig(config)
        logger.debug("Format correction config updated: $config")
    }
    
    /**
     * 获取当前格式矫正配置
     */
    fun getFormatConfig(): FormatCorrectionConfig = formatConfig
    
    /**
     * 获取性能监控数据
     */
    fun getPerformanceMetrics(): PerformanceMetrics = performanceMetrics.copy()
    
    /**
     * 重置性能监控数据
     */
    fun resetPerformanceMetrics() {
        performanceMetrics.reset()
    }
    
    /**
     * 清理缓存
     */
    fun cleanupCache() {
        if (formatConfig.enableCaching) {
            // 如果缓存超过最大大小，清理最旧的条目
            if (correctionCache.size > formatConfig.maxCacheSize) {
                val toRemove = correctionCache.size - formatConfig.maxCacheSize / 2
                correctionCache.keys.take(toRemove).forEach { correctionCache.remove(it) }
            }
            if (preprocessingCache.size > formatConfig.maxCacheSize) {
                val toRemove = preprocessingCache.size - formatConfig.maxCacheSize / 2
                preprocessingCache.keys.take(toRemove).forEach { preprocessingCache.remove(it) }
            }
        }
    }
    
    /**
     * 检查文本是否实际为空（检查是否为null、完全空字符串或只包含空白字符）
     */
    private fun isEffectivelyEmpty(text: String): Boolean {
        return text.isBlank()
    }
    
    /**
     * 规范化文本用于重复检测（保持原始格式，只去除首尾空白）
     */
    private fun normalizeText(text: String): String {
        return text.trim()
    }
    
    /**
     * 检查新文本是否只是空白字符或重复内容
     */
    private fun shouldSkipTextChunk(newText: String, existingText: String): Boolean {
        // 如果新文本完全为空，跳过
        if (newText.isEmpty()) return true

        // 如果新文本只包含空白字符，但要更加保守
        if (newText.isBlank()) {
            // 只有当新文本非常长且只包含空白字符时才跳过
            return newText.length > 50
        }

        // 检查是否只包含换行符和空格，但要更加保守
        val whitespaceOnly = newText.replace(Regex("[^\n \t]"), "")
        if (whitespaceOnly == newText) {
            // 只有当空白字符非常多时才跳过，并且要确保不是有意义的格式化
            return newText.length > 20 && !newText.contains("\n\n")
        }

        // 检查是否是完全重复的内容
        if (existingText.isNotEmpty() && newText == existingText) {
            return true
        }

        return false
    }
    
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
                        is AppStreamEvent.Text, is AppStreamEvent.Content, is AppStreamEvent.ContentFinal -> {
                            val eventText = when (event) {
                                is AppStreamEvent.Text -> event.text
                                is AppStreamEvent.Content -> event.text
                                is AppStreamEvent.ContentFinal -> event.text
                                else -> "" // Should not happen
                            }
                            
                            // ContentFinal事件直接替换整个内容（已经过完整修复）
                            if (event is AppStreamEvent.ContentFinal) {
                                currentTextBuilder.set(StringBuilder(eventText))
                                processedChunks.clear()
                                logger.debug("Applied final repaired content from backend")
                                return@withLock ProcessedEventResult.ContentUpdated(eventText)
                            }

                            if (eventText.isNotEmpty() && !isEffectivelyEmpty(eventText)) {
                                if (event is AppStreamEvent.Content && eventText.startsWith("__GEMINI_FINAL_CLEANUP__\n")) {
                                    val cleanedContent = eventText.removePrefix("__GEMINI_FINAL_CLEANUP__\n")
                                    currentTextBuilder.set(StringBuilder(cleanedContent))
                                    processedChunks.clear()
                                    logger.debug("Applied Gemini final cleanup to message content")
                                } else {
                                    if (shouldSkipTextChunk(eventText, currentTextBuilder.get().toString())) {
                                        // Continue to final formatting even if chunk is skipped
                                    }
    
                                    val normalizedText = normalizeText(eventText)
                                    val textChunkKey = "text_${normalizedText.hashCode()}"
                                    val contentChunkKey = "content_${normalizedText.hashCode()}"
    
                                    if (processedChunks.containsKey(textChunkKey) || processedChunks.containsKey(contentChunkKey)) {
                                        logger.debug("Skipping duplicate content processing for chunk: $normalizedText")
                                    } else {
                                        // 检测数学内容，对数学内容使用更保守的预处理
                                        val isMathContent = eventText.contains("\\") || eventText.contains("$") ||
                                                listOf("frac", "sqrt", "计算", "第一步", "第二步", "=", "^").any { eventText.contains(it) }
                                        
                                        val preprocessedText = try {
                                            if (isMathContent || realtimePreprocessor.shouldSkipProcessing(eventText, "realtimePreprocessing")) {
                                                // 对数学内容只做最基本的处理
                                                eventText.trim()
                                            } else {
                                                realtimePreprocessor.realtimeFormatPreprocessing(eventText)
                                            }
                                        } catch (e: Exception) {
                                            logger.warn("Realtime preprocessing failed, using original text: ${e.message}")
                                            eventText
                                        }

                                        val (thinkingContent, regularContent) = try {
                                            thinkingProcessor.processThinkTags(preprocessedText)
                                        } catch (e: Exception) {
                                            logger.warn("Think tag processing failed, using original text: ${e.message}")
                                            Pair(null, preprocessedText)
                                        }

                                        thinkingContent?.let { thinking ->
                                            if (thinking.isNotEmpty() && !shouldSkipTextChunk(thinking, currentReasoningBuilder.get().toString())) {
                                                currentReasoningBuilder.get().append(thinking)
                                                processedChunks[if (event is AppStreamEvent.Text) textChunkKey else contentChunkKey] = normalizedText
                                                return@withLock ProcessedEventResult.ReasoningUpdated(currentReasoningBuilder.get().toString())
                                            }
                                        }

                                        regularContent?.let { regular ->
                                            val existing = currentTextBuilder.get().toString()
                                            if (regular.isNotEmpty() && regular != existing) {
                                                if (regular.startsWith(existing)) {
                                                    // Cumulative stream, append the new part
                                                    val delta = regular.substring(existing.length)
                                                    currentTextBuilder.get().append(delta)
                                                } else {
                                                    // Non-cumulative stream. Check for overlap to prevent duplication.
                                                    var overlap = 0
                                                    val searchRange = minOf(existing.length, regular.length)
                                                    for (i in searchRange downTo 1) {
                                                        if (existing.endsWith(regular.substring(0, i))) {
                                                            overlap = i
                                                            break
                                                        }
                                                    }
                                                    val textToAppend = regular.substring(overlap)
                                                    if (textToAppend.isNotEmpty()) {
                                                        currentTextBuilder.get().append(textToAppend)
                                                    } else {
                                                        logger.debug("Skipping append, new chunk is fully overlapped.")
                                                    }
                                                }
                                            }
                                        }
                                        processedChunks[if (event is AppStreamEvent.Text) textChunkKey else contentChunkKey] = normalizedText
                                    }
                                }
                            }
                            
                            val rawContent = currentTextBuilder.get().toString()
                            val finalContent = try {
                                if (formatCorrector.shouldSkipProcessing(rawContent, "enhancedFormatCorrection")) {
                                    formatCorrector.cleanExcessiveWhitespace(rawContent)
                                } else {
                                    val corrected = if (formatConfig.enableProgressiveCorrection) {
                                        formatCorrector.progressiveCorrection(rawContent)
                                    } else {
                                        formatCorrector.enhancedFormatCorrection(rawContent)
                                    }
                                    errorCorrector.intelligentErrorCorrection(corrected)
                                }
                            } catch (e: Exception) {
                                logger.warn("Format correction failed, using raw content: ${e.message}")
                                rawContent
                            }
                            ProcessedEventResult.ContentUpdated(finalContent)
                        }
                        is AppStreamEvent.Reasoning -> {
                            if (event.text.isNotEmpty() && !isEffectivelyEmpty(event.text)) {
                                val normalizedText = normalizeText(event.text)
                                val chunkKey = "reasoning_${normalizedText.hashCode()}"
                                if (!processedChunks.containsKey(chunkKey)) {
                                    // 智能跳过检查
                                    val preprocessedText = try {
                                        if (realtimePreprocessor.shouldSkipProcessing(event.text, "realtimePreprocessing")) {
                                            event.text
                                        } else {
                                            realtimePreprocessor.realtimeFormatPreprocessing(event.text)
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("Realtime preprocessing failed for reasoning, using original text: ${e.message}")
                                        event.text
                                    }
                                    currentReasoningBuilder.get().append(preprocessedText)
                                    processedChunks[chunkKey] = normalizedText
                                }
                            }
                            val rawReasoning = currentReasoningBuilder.get().toString()
                            val finalReasoning = try {
                                if (formatCorrector.shouldSkipProcessing(rawReasoning, "enhancedFormatCorrection")) {
                                    formatCorrector.cleanExcessiveWhitespace(rawReasoning)
                                } else {
                                    val corrected = formatCorrector.enhancedFormatCorrection(rawReasoning)
                                    errorCorrector.intelligentErrorCorrection(corrected)
                                }
                            } catch (e: Exception) {
                                logger.warn("Format correction failed for reasoning, using raw content: ${e.message}")
                                rawReasoning
                            }
                            ProcessedEventResult.ReasoningUpdated(finalReasoning)
                        }
                        is AppStreamEvent.StreamEnd, is AppStreamEvent.ToolCall, is AppStreamEvent.Finish -> {
                            // 清理缓存
                            if (formatConfig.enableCaching) {
                                cleanupCache()
                            }
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
                            logger.warn("Received error event: $errorMessage")
                            // 不要返回Error类型的结果，这会中断流处理
                            // 而是将错误信息作为普通内容处理
                            val normalizedText = normalizeText(errorMessage)
                            val chunkKey = "error_${normalizedText.hashCode()}"
                            if (!processedChunks.containsKey(chunkKey)) {
                                currentTextBuilder.get().append(errorMessage)
                                processedChunks[chunkKey] = normalizedText
                            }
                            val rawContent = currentTextBuilder.get().toString()
                            val finalContent = formatCorrector.cleanExcessiveWhitespace(rawContent)
                            ProcessedEventResult.ContentUpdated(finalContent)
                        }
                        is AppStreamEvent.OutputType -> {
                            // This event is handled in ApiHandler, but we need to acknowledge it here
                            // to make the 'when' statement exhaustive.
                            ProcessedEventResult.StatusUpdate("output_type_received")
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
        currentOutputType.set("general")
 
        // 重置<think>标签相关状态
        thinkingBuffer.set(StringBuilder())
        isInsideThinkTag.set(false)
        hasFoundThinkTag.set(false)

        logger.debug("Message processor reset")
    }
    
    /**
     * 获取当前文本内容 - 集成性能优化
     */
    fun getCurrentText(): String {
        val rawText = currentTextBuilder.get().toString()
        
        return try {
            // 智能跳过检查
            if (formatCorrector.shouldSkipProcessing(rawText, "enhancedFormatCorrection")) {
                formatCorrector.cleanExcessiveWhitespace(rawText)
            } else {
                // 使用渐进式矫正或完整矫正
                val corrected = if (formatConfig.enableProgressiveCorrection) {
                    formatCorrector.progressiveCorrection(rawText)
                } else {
                    formatCorrector.enhancedFormatCorrection(rawText)
                }
                
                errorCorrector.intelligentErrorCorrection(corrected)
            }
        } catch (e: Exception) {
            logger.warn("Format correction failed in getCurrentText, using raw content: ${e.message}")
            rawText
        }
    }
    
    /**
     * 获取当前推理内容
     */
    fun getCurrentReasoning(): String? {
        val reasoning = currentReasoningBuilder.get().toString()
        return if (reasoning.isBlank()) null else reasoning
    }

    /**
     * 设置当前输出类型
     */
    fun setCurrentOutputType(type: String) {
        currentOutputType.set(type)
    }

    /**
     * 获取当前输出类型
     */
    fun getCurrentOutputType(): String {
        return currentOutputType.get()
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
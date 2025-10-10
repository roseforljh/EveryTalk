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
import com.example.everytalk.ui.components.MarkdownPart
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
 * 🎯 会话隔离修复：每个MessageProcessor实例绑定特定的会话和消息
 */
class MessageProcessor {
    private val logger = AppLogger.forComponent("MessageProcessor")
    
    // 🎯 会话隔离：绑定会话ID和消息ID，确保处理器不会跨会话污染
    private val sessionId = AtomicReference<String?>(null)
    private val messageId = AtomicReference<String?>(null)
    private val creationTime = System.currentTimeMillis()
    
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
    private val isCompleted = AtomicBoolean(false) // 🎯 新增：标记流是否已完成
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
     * 🎯 初始化处理器，绑定会话和消息
     * @param sessionId 会话ID，用于隔离不同会话
     * @param messageId 消息ID，用于标识具体消息
     */
    fun initialize(sessionId: String, messageId: String) {
        this.sessionId.set(sessionId)
        this.messageId.set(messageId)
        logger.debug("🎯 MessageProcessor initialized for session=$sessionId, message=$messageId")
    }
    
    /**
     * 🎯 获取当前绑定的会话ID
     */
    fun getSessionId(): String? = sessionId.get()
    
    /**
     * 🎯 获取当前绑定的消息ID
     */
    fun getMessageId(): String? = messageId.get()
    
    /**
     * 🎯 检查处理器是否属于指定会话
     */
    fun belongsToSession(sessionId: String): Boolean {
        return this.sessionId.get() == sessionId
    }
    
    /**
     * 🎯 检查处理器是否处理指定消息
     */
    fun isProcessingMessage(messageId: String): Boolean {
        return this.messageId.get() == messageId
    }
    
    /**
     * 🎯 检查流是否已完成
     */
    fun isStreamCompleted(): Boolean = isCompleted.get()
    
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
     * 检查新文本是否只是空白字符或重复内容 - 改进版本
     */
    /**
     * 内容类型枚举
     */
    private enum class ContentType {
        MARKDOWN_HEADER,
        CODE_BLOCK,
        IMPORTANT_TEXT,
        REGULAR_TEXT
    }
    

    
    /**
     * 检查是否包含受保护的Markdown内容
     */
    private fun hasProtectedMarkdownContent(text: String): Boolean {
        return listOf(
                    "#", "**", "*", "`", "```", ">", "[", "]", "(", ")",
                    "公式解释", "：", ":", "解释", "说明", "步骤"
                ).any { text.contains(it) }
    }
    
    /**
     * 检查是否为Markdown边界
     */
    private fun isMarkdownBoundary(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("#") || trimmed.startsWith("```") || 
               trimmed.startsWith("*") || trimmed.startsWith(">") ||
               trimmed.endsWith("```")
    }
    
    /**
     * 分类内容类型
     */
    private fun classifyContentType(text: String): ContentType {
        val trimmed = text.trim()
        
        return when {
            trimmed.startsWith("#") -> ContentType.MARKDOWN_HEADER
            trimmed.startsWith("```") || trimmed.contains("`") -> ContentType.CODE_BLOCK
            listOf("公式解释", "解释", "说明", "步骤", "：", ":").any { trimmed.contains(it) } -> ContentType.IMPORTANT_TEXT
            else -> ContentType.REGULAR_TEXT
        }
    }
    
    /**
     * 检查是否为完全相同的标题
     */
    private fun isExactDuplicateHeader(newText: String, existingText: String): Boolean {
        val newHeader = newText.trim()
        val lines = existingText.split("\n")
        return lines.any { line ->
            val existingHeader = line.trim()
            existingHeader == newHeader && existingHeader.startsWith("#")
        }
    }
    
    /**
     * 检查是否为完全相同的代码块
     */
    private fun isExactDuplicateCode(newText: String, existingText: String): Boolean {
        val newCode = newText.trim()
        // 对于代码块，检查是否有相同的代码内容
        return existingText.contains(newCode) && newCode.length > 5 &&
               (newCode.startsWith("`") || newCode.contains("```"))
    }

    private fun shouldSkipTextChunk(newText: String, existingText: String): Boolean {
        // 🎯 紧急修复：暂时禁用所有过滤机制，确保内容不丢失
        // 只有在新文本完全为空或纯空白且超长时才跳过
        if (newText.isEmpty()) return true
        if (newText.isBlank() && newText.length > 10000) return true // 极端情况
        
        // 其他情况一律不跳过，确保内容完整性
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
        // 🎯 会话隔离检查：确保只处理属于自己的消息
        if (messageId.get() != null && messageId.get() != currentMessageId) {
            logger.warn("🎯 Ignoring event for different message: expected=${messageId.get()}, got=$currentMessageId")
            return ProcessedEventResult.Cancelled
        }
        
        if (isCancelled.get()) {
            logger.debug("🎯 Event processing cancelled for message $currentMessageId")
            return ProcessedEventResult.Cancelled
        }
        
        if (isCompleted.get()) {
            logger.debug("🎯 Stream already completed for message $currentMessageId, ignoring event")
            return ProcessedEventResult.NoChange
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
                            
                            // ContentFinal事件处理：只有在内容非空时才替换，避免清空已有内容
                            if (event is AppStreamEvent.ContentFinal) {
                                if (eventText.isNotEmpty()) {
                                    // 只有当ContentFinal包含实际内容时才替换
                                    currentTextBuilder.set(StringBuilder(eventText))
                                    processedChunks.clear()
                                    logger.debug("Applied final repaired content from backend: ${eventText.length} chars")
                                    return@withLock ProcessedEventResult.ContentUpdated(eventText)
                                } else {
                                    // 如果ContentFinal是空的，保持现有内容不变
                                    val existingContent = currentTextBuilder.get().toString()
                                    logger.debug("Received empty ContentFinal, keeping existing content: ${existingContent.length} chars")
                                    return@withLock ProcessedEventResult.ContentUpdated(existingContent)
                                }
                            }

                            if (eventText.isNotEmpty() && !isEffectivelyEmpty(eventText)) {
                                if (event is AppStreamEvent.Content && eventText.startsWith("__GEMINI_FINAL_CLEANUP__\n")) {
                                    val cleanedContent = eventText.removePrefix("__GEMINI_FINAL_CLEANUP__\n")
                                    currentTextBuilder.set(StringBuilder(cleanedContent))
                                    processedChunks.clear()
                                    logger.debug("Applied Gemini final cleanup to message content")
                                } else {
                                    // 改进的重复检测逻辑
                                    val currentText = currentTextBuilder.get().toString()
                                    val skipChunk = shouldSkipTextChunk(eventText, currentText)
                                    
                                    if (skipChunk) {
                                        logger.debug("Skipping text chunk due to duplication: ${eventText.take(50)}...")
                                        // 继续处理格式化，但不添加内容
                                    }
     
                                    val normalizedText = normalizeText(eventText)
                                    val textChunkKey = "text_${normalizedText.hashCode()}"
                                    val contentChunkKey = "content_${normalizedText.hashCode()}"
     
                                    // 改进的已处理内容检查
                                    val alreadyProcessed = processedChunks.containsKey(textChunkKey) || processedChunks.containsKey(contentChunkKey)
                                    
                                    if (alreadyProcessed) {
                                        logger.debug("Skipping already processed chunk: ${normalizedText.take(30)}...")
                                    } else if (!skipChunk) { // 只有在不跳过且未处理过的情况下才处理
                                        val preprocessedText = try {
                                            realtimePreprocessor.realtimeFormatPreprocessing(eventText)
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
                                                    // 累积流：添加新的部分
                                                    val delta = regular.substring(existing.length)
                                                    if (delta.isNotEmpty() && !shouldSkipTextChunk(delta, existing)) {
                                                        currentTextBuilder.get().append(delta)
                                                        logger.debug("Appended delta: ${delta.take(30)}...")
                                                    }
                                                } else {
                                                    // 非累积流：检查重叠以防止重复
                                                    var overlap = 0
                                                    val searchRange = minOf(existing.length, regular.length, 200) // 限制搜索范围
                                                    
                                                    // 智能重叠检测，增强对Markdown格式的保护
                                                    val hasProtectedContent = hasProtectedMarkdownContent(regular)
                                                    
                                                    if (!hasProtectedContent) {
                                                        for (i in searchRange downTo 10) { // 最小重叠长度为10
                                                            val suffix = existing.takeLast(i)
                                                            val prefix = regular.take(i)
                                                            if (suffix == prefix && !isMarkdownBoundary(suffix)) {
                                                                overlap = i
                                                                logger.debug("Found safe overlap of $i characters")
                                                                break
                                                            }
                                                        }
                                                    } else {
                                                        logger.debug("Skipping overlap detection for protected Markdown content")
                                                    }
                                                    
                                                    val textToAppend = regular.substring(overlap)
                                                    if (textToAppend.isNotEmpty()) {
                                                        // 增强的内容重要性检测和重复过滤
                                                        val contentType = classifyContentType(textToAppend)
                                                        val shouldSkip = when (contentType) {
                                                            ContentType.MARKDOWN_HEADER -> {
                                                                // 标题内容：检查是否为完全相同的标题
                                                                isExactDuplicateHeader(textToAppend, existing)
                                                            }
                                                            ContentType.CODE_BLOCK -> {
                                                                // 代码块：保护代码格式
                                                                isExactDuplicateCode(textToAppend, existing)
                                                            }
                                                            ContentType.IMPORTANT_TEXT -> {
                                                                // 重要文本：宽松的重复检测
                                                                existing.contains(textToAppend.trim()) && textToAppend.trim().length > 5
                                                            }
                                                            ContentType.REGULAR_TEXT -> {
                                                                // 普通文本：标准重复检测
                                                                shouldSkipTextChunk(textToAppend, existing)
                                                            }
                                                        }
                                                        
                                                        if (!shouldSkip) {
                                                            currentTextBuilder.get().append(textToAppend)
                                                            logger.debug("Appended ${contentType.name.lowercase()} content: ${textToAppend.take(30)}...")
                                                        } else {
                                                            logger.debug("Skipping ${contentType.name.lowercase()} content due to duplication: ${textToAppend.take(30)}...")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        processedChunks[if (event is AppStreamEvent.Text) textChunkKey else contentChunkKey] = normalizedText
                                    } else {
                                        logger.debug("Skipped processing for chunk due to duplication or already processed")
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
                            // 🎯 流结束事件：标记为已完成状态
                            completeStream()
                            // 清理缓存
                            if (formatConfig.enableCaching) {
                                cleanupCache()
                            }
                            ProcessedEventResult.ReasoningComplete
                        }
                        is AppStreamEvent.WebSearchStatus, is AppStreamEvent.StatusUpdate -> {
                            val stage = when(event) {
                                is AppStreamEvent.WebSearchStatus -> event.stage
                                is AppStreamEvent.StatusUpdate -> event.stage
                                else -> ""
                            }
                            ProcessedEventResult.StatusUpdate(stage)
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
                        is AppStreamEvent.ImageGeneration -> {
                            // Not handled by MessageProcessor, ApiHandler will handle it.
                            // Return a neutral event.
                            ProcessedEventResult.StatusUpdate("image_generation_event_received")
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
        logger.debug("🎯 Message processing cancelled for session=${sessionId.get()}, message=${messageId.get()}")
    }
    
    /**
     * 重置处理器状态
     */
    fun reset() {
        isCancelled.set(false)
        isCompleted.set(false)
        currentTextBuilder.set(StringBuilder())
        currentReasoningBuilder.set(StringBuilder())
        processedChunks.clear()
        currentOutputType.set("general")
 
        // 重置<think>标签相关状态
        thinkingBuffer.set(StringBuilder())
        isInsideThinkTag.set(false)
        hasFoundThinkTag.set(false)

        logger.debug("🎯 Message processor reset for session=${sessionId.get()}, message=${messageId.get()}")
    }
    
    /**
     * 🎯 完成流处理，标记为已完成状态
     */
    fun completeStream() {
        isCompleted.set(true)
        logger.debug("🎯 Stream completed for session=${sessionId.get()}, message=${messageId.get()}")
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
    /**
     * 不再进行最终处理，直接返回原消息
     * @param message 待处理的消息
     * @return 原消息（不做任何修改）
     */
    fun finalizeMessageProcessing(message: Message): Message {
        android.util.Log.d("MessageProcessor", "=== finalizeMessageProcessing: DISABLED ===")
        android.util.Log.d("MessageProcessor", "Returning message as-is without any processing")
        // 🎯 完全取消最终处理，直接返回原消息
        return message
    }
}
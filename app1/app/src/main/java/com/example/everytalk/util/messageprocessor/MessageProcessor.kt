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
     * 检查新文本是否只是空白字符或重复内容 - 改进版本
     */
    /**
     * 内容类型枚举
     */
    private enum class ContentType {
        MARKDOWN_HEADER,
        MATH_FORMULA,
        CODE_BLOCK,
        IMPORTANT_TEXT,
        REGULAR_TEXT
    }
    
    /**
     * 检测数学内容
     */
    private fun detectMathContent(text: String): Boolean {
        return text.contains("\\") || text.contains("$") ||
                listOf(
                    "frac", "sqrt", "sum", "int", "lim", "alpha", "beta", "gamma", "delta",
                    "计算", "第一步", "第二步", "公式", "解", "=", "^", "_", "±", "∑", "∫",
                    "\\begin", "\\end", "\\left", "\\right"
                ).any { text.contains(it) }
    }
    
    /**
     * 保护数学格式
     */
    private fun protectMathFormatting(text: String): String {
        // 对数学内容只做最基本的处理，保护重要的数学符号和格式
        return text.trim().let { trimmed ->
            // 确保数学公式前后的空格得到保护
            if (trimmed.contains("$") || trimmed.contains("\\")) {
                trimmed
            } else {
                trimmed
            }
        }
    }
    
    /**
     * 检查是否包含受保护的Markdown内容
     */
    private fun hasProtectedMarkdownContent(text: String): Boolean {
        return text.contains("\\") || text.contains("$") ||
                listOf(
                    "#", "**", "*", "`", "```", ">", "[", "]", "(", ")",
                    "公式解释", "：", ":", "解释", "说明", "步骤", "计算"
                ).any { text.contains(it) }
    }
    
    /**
     * 检查是否为Markdown边界
     */
    private fun isMarkdownBoundary(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("#") || trimmed.startsWith("```") || 
               trimmed.startsWith("*") || trimmed.startsWith(">") ||
               trimmed.endsWith("```") || trimmed.contains("$")
    }
    
    /**
     * 分类内容类型
     */
    private fun classifyContentType(text: String): ContentType {
        val trimmed = text.trim()
        
        return when {
            trimmed.startsWith("#") -> ContentType.MARKDOWN_HEADER
            trimmed.contains("$") || trimmed.contains("\\") || 
            listOf("frac", "sqrt", "公式", "计算", "=").any { trimmed.contains(it) } -> ContentType.MATH_FORMULA
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
     * 检查是否为完全相同的数学公式
     */
    private fun isExactDuplicateFormula(newText: String, existingText: String): Boolean {
        val newFormula = newText.trim()
        // 对于数学公式，要求完全匹配才认为是重复
        return existingText.contains(newFormula) && newFormula.length > 10 &&
               (newFormula.contains("$") || newFormula.contains("\\"))
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

        // 改进的重复检测逻辑 - 更精确地检测重复内容
        if (existingText.isNotEmpty() && existingText.length > newText.length) {
            val normalizedNew = normalizeText(newText)
            val normalizedExisting = normalizeText(existingText)
            
            // 检查是否为完全相同的子串
            if (normalizedExisting.contains(normalizedNew) && normalizedNew.length > 10) {
                // 但是要排除一些特殊情况
                
                // 1. 如果新内容包含重要的标点符号或格式标记，不跳过
                val hasImportantContent = listOf("：", ":", "公式", "解释", "**", "*", "$", "\\", "=").any { normalizedNew.contains(it) }
                if (hasImportantContent) {
                    logger.debug("Not skipping content with important formatting: ${normalizedNew.take(30)}...")
                    return false
                }
                
                // 2. 检查是否为句子的不同部分（如标题和内容）
                val newTrimmed = normalizedNew.trim()
                val existingTrimmed = normalizedExisting.trim()
                
                // 如果新内容是现有内容的精确子串且位置合理，可能是重复
                val indexInExisting = existingTrimmed.indexOf(newTrimmed)
                if (indexInExisting >= 0) {
                    // 检查前后文本，如果是自然的文本流，不跳过
                    val beforeSubstring = existingTrimmed.substring(0, indexInExisting).trim()
                    val afterSubstring = existingTrimmed.substring(indexInExisting + newTrimmed.length).trim()
                    
                    // 如果子串前后都有实质内容，可能是合理的重复（如标题重复），允许
                    if (beforeSubstring.isNotEmpty() && afterSubstring.isNotEmpty()) {
                        logger.debug("Allowing potential title/content repetition: ${newTrimmed.take(30)}...")
                        return false
                    }
                    
                    logger.debug("Skipping duplicate content found in existing text: ${normalizedNew.take(50)}...")
                    return true
                }
            }
            
            // 检查行级重复，但更保守
            val lines = normalizedNew.split("\n").filter { it.trim().isNotEmpty() }
            if (lines.isNotEmpty() && lines.size > 2) { // 只对多行内容进行行级重复检查
                val duplicateLineCount = lines.count { line ->
                    val trimmedLine = line.trim()
                    normalizedExisting.contains(trimmedLine) && trimmedLine.length > 10 // 提高阈值
                }
                // 提高重复阈值到80%，且至少要有3行重复
                if (duplicateLineCount >= 3 && duplicateLineCount.toDouble() / lines.size > 0.8) {
                    logger.debug("Skipping chunk with ${duplicateLineCount}/${lines.size} duplicate lines")
                    return true
                }
            }
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
                                        // 增强的数学内容检测，包括更多数学符号和模式
                                        val isMathContent = detectMathContent(eventText)
                                        
                                        val preprocessedText = try {
                                            if (isMathContent || realtimePreprocessor.shouldSkipProcessing(eventText, "realtimePreprocessing")) {
                                                // 对数学内容只做最基本的处理，保护格式
                                                protectMathFormatting(eventText)
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
                                                            ContentType.MATH_FORMULA -> {
                                                                // 数学公式：更严格的重复检测
                                                                isExactDuplicateFormula(textToAppend, existing)
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
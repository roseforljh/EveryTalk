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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 格式矫正配置
 */
data class FormatCorrectionConfig(
    val enableRealtimePreprocessing: Boolean = true,
    val enableCodeBlockCorrection: Boolean = true,
    val enableMarkdownCorrection: Boolean = true,
    val enableListCorrection: Boolean = true,
    val enableLinkCorrection: Boolean = true,
    val enableTableCorrection: Boolean = true,
    val enableQuoteCorrection: Boolean = true,
    val enableTextStyleCorrection: Boolean = true,
    val enableParagraphCorrection: Boolean = true,
    val enableJsonCorrection: Boolean = true,
    val enableXmlHtmlCorrection: Boolean = true,
    val enableMathCorrection: Boolean = true,
    val enableProgrammingSyntaxCorrection: Boolean = true,
    val correctionIntensity: CorrectionIntensity = CorrectionIntensity.MODERATE,
    // 性能优化配置
    val enablePerformanceOptimization: Boolean = true,
    val maxProcessingTimeMs: Long = 5, // 最大处理时间5毫秒
    val enableCaching: Boolean = true,
    val maxCacheSize: Int = 1000,
    val enableAsyncProcessing: Boolean = true,
    val chunkSizeThreshold: Int = 500, // 超过500字符才进行完整矫正
    val enableProgressiveCorrection: Boolean = true // 渐进式矫正
)

/**
 * 矫正强度级别
 */
enum class CorrectionIntensity {
    LIGHT,      // 轻度矫正：只修复明显错误
    MODERATE,   // 中度矫正：修复常见错误和格式问题
    AGGRESSIVE  // 激进矫正：尽可能修复所有可能的格式问题
}

/**
 * 性能监控数据
 */
data class PerformanceMetrics(
    var totalProcessingTime: Long = 0,
    var averageProcessingTime: Long = 0,
    var maxProcessingTime: Long = 0,
    var processedChunks: Int = 0,
    var cacheHits: Int = 0,
    var cacheMisses: Int = 0,
    var skippedProcessing: Int = 0
) {
    /**
     * 计算缓存命中率
     */
    fun getCacheHitRate(): Double {
        val totalCacheAccess = cacheHits + cacheMisses
        return if (totalCacheAccess > 0) {
            (cacheHits.toDouble() / totalCacheAccess) * 100
        } else {
            0.0
        }
    }
    
    /**
     * 计算跳过处理率
     */
    fun getSkipRate(): Double {
        val totalProcessingAttempts = processedChunks + skippedProcessing
        return if (totalProcessingAttempts > 0) {
            (skippedProcessing.toDouble() / totalProcessingAttempts) * 100
        } else {
            0.0
        }
    }
    
    /**
     * 重置所有指标
     */
    fun reset() {
        totalProcessingTime = 0
        averageProcessingTime = 0
        maxProcessingTime = 0
        processedChunks = 0
        cacheHits = 0
        cacheMisses = 0
        skippedProcessing = 0
    }
    
    /**
     * 生成性能摘要
     */
    fun getSummary(): String {
        return """
            Performance Metrics Summary:
            - Total Processing Time: ${totalProcessingTime}ms
            - Average Processing Time: ${averageProcessingTime}ms
            - Max Processing Time: ${maxProcessingTime}ms
            - Processed Chunks: $processedChunks
            - Cache Hit Rate: ${"%.2f".format(getCacheHitRate())}%
            - Skip Rate: ${"%.2f".format(getSkipRate())}%
        """.trimIndent()
    }
}

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
    
    // <think>标签处理相关状态
    private val thinkingBuffer = AtomicReference(StringBuilder())
    private val isInsideThinkTag = AtomicBoolean(false)
    private val hasFoundThinkTag = AtomicBoolean(false)
    
    /**
     * 更新格式矫正配置
     */
    fun updateFormatConfig(config: FormatCorrectionConfig) {
        this.formatConfig = config
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
        performanceMetrics.totalProcessingTime = 0
        performanceMetrics.averageProcessingTime = 0
        performanceMetrics.maxProcessingTime = 0
        performanceMetrics.processedChunks = 0
        performanceMetrics.cacheHits = 0
        performanceMetrics.cacheMisses = 0
        performanceMetrics.skippedProcessing = 0
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
     * 性能优化的文本处理包装器
     */
    private inline fun <T> performanceOptimizedProcessing(
        text: String,
        operation: String,
        processor: () -> T
    ): T {
        if (!formatConfig.enablePerformanceOptimization) {
            return processor()
        }
        
        val startTime = System.currentTimeMillis()
        
        try {
            val result = processor()
            
            // 更新性能指标
            val processingTime = System.currentTimeMillis() - startTime
            updatePerformanceMetrics(processingTime)
            
            // 如果处理时间超过阈值，记录警告
            if (processingTime > formatConfig.maxProcessingTimeMs) {
                logger.warn("$operation took ${processingTime}ms, exceeding threshold of ${formatConfig.maxProcessingTimeMs}ms for text length: ${text.length}")
            }
            
            return result
        } catch (e: Exception) {
            logger.error("Error in $operation", e)
            throw e
        }
    }
    
    /**
     * 更新性能指标
     */
    private fun updatePerformanceMetrics(processingTime: Long) {
        performanceMetrics.apply {
            processedChunks++
            totalProcessingTime += processingTime
            averageProcessingTime = totalProcessingTime / processedChunks
            if (processingTime > maxProcessingTime) {
                maxProcessingTime = processingTime
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
     * 强大的AI输出格式矫正系统
     * 即使AI输出格式错误，也要尽力矫正为正确格式
     * 包含性能优化和缓存机制
     */
    fun enhancedFormatCorrection(text: String): String {
        if (text.isBlank()) return ""
        
        // 检查是否启用性能优化
        if (!formatConfig.enablePerformanceOptimization) {
            return applyFormatCorrections(text)
        }
        
        // 检查缓存
        if (formatConfig.enableCaching) {
            val cacheKey = text.hashCode().toString()
            correctionCache[cacheKey]?.let { cached ->
                performanceMetrics.cacheHits++
                return cached
            }
            performanceMetrics.cacheMisses++
        }
        
        // 如果文本太长，考虑分块处理
        if (formatConfig.enableAsyncProcessing && text.length > formatConfig.chunkSizeThreshold) {
            return performanceOptimizedProcessing(text, "enhancedFormatCorrection-chunked") {
                processTextInChunks(text)
            }
        }
        
        // 常规处理
        return performanceOptimizedProcessing(text, "enhancedFormatCorrection") {
            val result = applyFormatCorrections(text)
            
            // 缓存结果
            if (formatConfig.enableCaching && text.length < 10000) { // 只缓存较小的文本
                val cacheKey = text.hashCode().toString()
                correctionCache[cacheKey] = result
                cleanupCache()
            }
            
            result
        }
    }
    
    /**
     * 应用格式矫正
     */
    private fun applyFormatCorrections(text: String): String {
        var corrected = text
        
        // 根据配置应用不同的矫正功能
        if (formatConfig.enableCodeBlockCorrection) {
            corrected = fixCodeBlockFormat(corrected)
        }
        
        if (formatConfig.enableMarkdownCorrection) {
            corrected = fixMarkdownHeaders(corrected)
        }
        
        if (formatConfig.enableListCorrection) {
            corrected = fixListFormat(corrected)
        }
        
        if (formatConfig.enableLinkCorrection) {
            corrected = fixLinkFormat(corrected)
        }
        
        if (formatConfig.enableTableCorrection) {
            corrected = fixTableFormat(corrected)
        }
        
        if (formatConfig.enableQuoteCorrection) {
            corrected = fixQuoteFormat(corrected)
        }
        
        if (formatConfig.enableTextStyleCorrection) {
            corrected = fixTextStyleFormat(corrected)
        }
        
        if (formatConfig.enableParagraphCorrection) {
            corrected = fixParagraphFormat(corrected)
        }
        
        // 最后清理多余空白
        corrected = cleanExcessiveWhitespace(corrected)
        
        return corrected
    }
    
    /**
     * 异步处理大文本块
     */
    private suspend fun processLargeTextAsync(text: String): String {
        return withContext(Dispatchers.Default) {
            if (text.length > formatConfig.chunkSizeThreshold) {
                processTextInChunks(text)
            } else {
                applyFormatCorrections(text)
            }
        }
    }
    
    /**
     * 渐进式矫正 - 根据文本长度和复杂度决定矫正级别
     */
    private fun progressiveCorrection(text: String): String {
        if (!formatConfig.enableProgressiveCorrection) {
            return enhancedFormatCorrection(text)
        }
        
        return when {
            text.length < 100 -> {
                // 短文本：只做基本清理
                cleanExcessiveWhitespace(text)
            }
            text.length < 1000 -> {
                // 中等文本：轻度矫正
                val lightConfig = formatConfig.copy(correctionIntensity = CorrectionIntensity.LIGHT)
                val originalConfig = formatConfig
                formatConfig = lightConfig
                val result = enhancedFormatCorrection(text)
                formatConfig = originalConfig
                result
            }
            else -> {
                // 长文本：完整矫正
                enhancedFormatCorrection(text)
            }
        }
    }
    
    /**
     * 智能跳过处理 - 检查是否需要跳过某些处理步骤
     */
    private fun shouldSkipProcessing(text: String, operation: String): Boolean {
        if (!formatConfig.enablePerformanceOptimization) return false
        
        // 检查文本长度阈值
        if (text.length > formatConfig.maxProcessingTimeMs * 10) { // 简单的长度估算
            logger.debug("Skipping $operation for text length: ${text.length}")
            performanceMetrics.skippedProcessing++
            return true
        }
        
        // 检查是否为纯文本（没有特殊格式）
        if (operation == "enhancedFormatCorrection" && isPlainText(text)) {
            performanceMetrics.skippedProcessing++
            return true
        }
        
        return false
    }
    
    /**
     * 检查是否为纯文本
     */
    private fun isPlainText(text: String): Boolean {
        val specialChars = listOf("```", "**", "*", "#", "[", "]", "(", ")", "{", "}", "<", ">", "|")
        return specialChars.none { text.contains(it) }
    }
    
    /**
     * 分块处理大文本
     */
    private fun processTextInChunks(text: String): String {
        val chunkSize = formatConfig.chunkSizeThreshold
        val chunks = text.chunked(chunkSize)
        
        return chunks.joinToString("") { chunk ->
            applyFormatCorrections(chunk)
        }
    }
    
    /**
     * 修复代码块格式
     */
    private fun fixCodeBlockFormat(text: String): String {
        var fixed = text
        
        // 修复不完整的代码块开始标记
        fixed = fixed.replace(Regex("```([a-zA-Z]*)\n?(?!.*```)"), "```$1\n")
        
        // 修复缺失的代码块结束标记
        val codeBlockPattern = Regex("```([a-zA-Z]*)\n(.*?)(?:\n```|$)", RegexOption.DOT_MATCHES_ALL)
        fixed = codeBlockPattern.replace(fixed) { matchResult ->
            val language = matchResult.groupValues[1]
            val code = matchResult.groupValues[2].trimEnd()
            if (matchResult.value.endsWith("```")) {
                matchResult.value
            } else {
                "```$language\n$code\n```"
            }
        }
        
        // 修复单行代码块（反引号）
        fixed = fixed.replace(Regex("`([^`\n]+)(?!`)"), "`$1`")
        
        return fixed
     }
     
     /**
      * 实时格式预处理 - 在文本添加到构建器之前进行初步格式矫正
      * 包含性能优化和缓存机制
      */
     private fun realtimeFormatPreprocessing(text: String): String {
         if (text.isBlank() || !formatConfig.enableRealtimePreprocessing) return text
         
         // 快速跳过检查
         if (!formatConfig.enablePerformanceOptimization) {
             return applyRealtimeCorrections(text)
         }
         
         // 检查预处理缓存
         if (formatConfig.enableCaching) {
             val cacheKey = "preprocess_${text.hashCode()}"
             preprocessingCache[cacheKey]?.let { cached ->
                 performanceMetrics.cacheHits++
                 return cached
             }
             performanceMetrics.cacheMisses++
         }
         
         // 如果文本很短，跳过复杂处理
         if (text.length < 50 && formatConfig.enableProgressiveCorrection) {
             performanceMetrics.skippedProcessing++
             return text
         }
         
         // 性能优化处理
         return performanceOptimizedProcessing(text, "realtimeFormatPreprocessing") {
             val result = applyRealtimeCorrections(text)
             
             // 缓存结果
             if (formatConfig.enableCaching && text.length < 2000) {
                 val cacheKey = "preprocess_${text.hashCode()}"
                 preprocessingCache[cacheKey] = result
                 cleanupCache()
             }
             
             result
         }
     }
     
     /**
      * 应用实时矫正
      */
     private fun applyRealtimeCorrections(text: String): String {
         var preprocessed = text
         
         // 根据矫正强度应用不同级别的预处理
         when (formatConfig.correctionIntensity) {
             CorrectionIntensity.LIGHT -> {
                 // 轻度矫正：只做基本清理
                 preprocessed = cleanExcessiveWhitespace(preprocessed)
             }
             CorrectionIntensity.MODERATE -> {
                 // 中度矫正：快速修复 + 基本预处理
                 preprocessed = quickFormatFix(preprocessed)
                 if (formatConfig.enableCodeBlockCorrection) {
                     preprocessed = preprocessCodeBlocks(preprocessed)
                 }
                 if (formatConfig.enableMarkdownCorrection) {
                     preprocessed = preprocessMarkdown(preprocessed)
                 }
                 preprocessed = cleanExcessiveWhitespace(preprocessed)
             }
             CorrectionIntensity.AGGRESSIVE -> {
                 // 激进矫正：全面预处理
                 preprocessed = quickFormatFix(preprocessed)
                 if (formatConfig.enableCodeBlockCorrection) {
                     preprocessed = preprocessCodeBlocks(preprocessed)
                 }
                 if (formatConfig.enableMarkdownCorrection) {
                     preprocessed = preprocessMarkdown(preprocessed)
                 }
                 // 在激进模式下，还会应用部分完整矫正
                 preprocessed = enhancedFormatCorrection(preprocessed)
             }
         }
         
         return preprocessed
     }
     
     /**
      * 快速修复常见格式错误
      */
     private fun quickFormatFix(text: String): String {
         var fixed = text
         
         // 修复常见的标点符号问题
         fixed = fixed.replace(Regex("([.!?])([A-Z])"), "$1 $2")
         fixed = fixed.replace(Regex("([。！？])([\\u4e00-\\u9fa5])"), "$1$2")
         
         // 修复常见的括号问题
         fixed = fixed.replace(Regex("\\(\\s+"), "(")
         fixed = fixed.replace(Regex("\\s+\\)"), ")")
         
         // 修复常见的引号问题
         fixed = fixed.replace(Regex("\"\\s+"), "\"")
         fixed = fixed.replace(Regex("\\s+\""), "\"")
         
         return fixed
     }
     
     /**
      * 预处理代码块标记
      */
     private fun preprocessCodeBlocks(text: String): String {
         var processed = text
         
         // 确保代码块标记前后有换行
         processed = processed.replace(Regex("([^\\n])```"), "$1\n```")
         processed = processed.replace(Regex("```([^\\n])"), "```\n$1")
         
         // 修复常见的代码块语言标记错误
         processed = processed.replace(Regex("```(python|java|javascript|kotlin|swift|cpp|c\\+\\+)\\s*\n"), "```$1\n")
         
         return processed
     }
     
     /**
      * 预处理Markdown标记
      */
     private fun preprocessMarkdown(text: String): String {
         var processed = text
         
         // 确保标题标记格式正确
         processed = processed.replace(Regex("^(#{1,6})([^\\s#])"), "$1 $2")
         processed = processed.replace(Regex("\n(#{1,6})([^\\s#])"), "\n$1 $2")
         
         // 确保列表标记格式正确
         processed = processed.replace(Regex("^([\\-\\*\\+])([^\\s])"), "$1 $2")
         processed = processed.replace(Regex("\n([\\-\\*\\+])([^\\s])"), "\n$1 $2")
         
         return processed
     }
     
     /**
      * 清理文本中的多余空白段落，特别针对OpenAI兼容接口的输出
      */
     private fun cleanExcessiveWhitespace(text: String): String {
         if (text.isBlank()) return ""
         
         // 将连续的空行（3个或更多换行符）替换为最多2个换行符
         var cleaned = text.replace(Regex("\n{3,}"), "\n\n")
         
         // 移除行尾的空白字符，但保留换行符
         cleaned = cleaned.replace(Regex("[ \t]+\n"), "\n")
         
         // 移除文本开头和结尾的多余空白，但保留内部格式
         cleaned = cleaned.trim()
         
         return cleaned
     }
    
    /**
     * 修复Markdown标题格式
     */
    private fun fixMarkdownHeaders(text: String): String {
        var fixed = text
        
        // 修复标题格式：确保#后面有空格
        fixed = fixed.replace(Regex("^(#{1,6})([^#\\s])"), "$1 $2")
        fixed = fixed.replace(Regex("\n(#{1,6})([^#\\s])"), "\n$1 $2")
        
        // 修复标题前后的换行
        fixed = fixed.replace(Regex("([^\n])\n(#{1,6} .+)"), "$1\n\n$2")
        fixed = fixed.replace(Regex("(#{1,6} .+)\n([^\n#])"), "$1\n\n$2")
        
        return fixed
    }
    
    /**
     * 修复列表格式
     */
    private fun fixListFormat(text: String): String {
        var fixed = text
        
        // 修复无序列表：确保-、*、+后面有空格
        fixed = fixed.replace(Regex("^(\\s*)([\\-\\*\\+])([^\\s])"), "$1$2 $3")
        fixed = fixed.replace(Regex("\n(\\s*)([\\-\\*\\+])([^\\s])"), "\n$1$2 $3")
        
        // 修复有序列表：确保数字.后面有空格
        fixed = fixed.replace(Regex("^(\\s*)(\\d+\\.)([^\\s])"), "$1$2 $3")
        fixed = fixed.replace(Regex("\n(\\s*)(\\d+\\.)([^\\s])"), "\n$1$2 $3")
        
        // 修复列表项之间的换行
        fixed = fixed.replace(Regex("(\\s*[\\-\\*\\+] .+)\n([^\\s\\-\\*\\+\\n])"), "$1\n\n$2")
        fixed = fixed.replace(Regex("(\\s*\\d+\\. .+)\n([^\\s\\d\\n])"), "$1\n\n$2")
        
        return fixed
    }
    
    /**
     * 修复链接格式
     */
    private fun fixLinkFormat(text: String): String {
        var fixed = text
        
        // 修复不完整的Markdown链接格式
        fixed = fixed.replace(Regex("\\[([^\\]]+)\\]\\s*\\(([^\\)]+)\\)"), "[$1]($2)")
        
        // 修复缺失的链接文本
        fixed = fixed.replace(Regex("\\[\\]\\(([^\\)]+)\\)"), "[$1]($1)")
        
        // 修复纯URL，转换为链接格式
        fixed = fixed.replace(Regex("(?<!\\[|\\()https?://[^\\s\\)\\]]+(?!\\)|\\])")) { matchResult ->
            val url = matchResult.value
            "[$url]($url)"
        }
        
        return fixed
    }
    
    /**
     * 修复表格格式
     */
    private fun fixTableFormat(text: String): String {
        var fixed = text
        
        // 修复表格分隔符
        fixed = fixed.replace(Regex("\\|\\s*-+\\s*\\|"), "| --- |")
        fixed = fixed.replace(Regex("\\|\\s*-+\\s*"), "| --- ")
        fixed = fixed.replace(Regex("\\s*-+\\s*\\|"), " --- |")
        
        // 确保表格行前后有换行
        val tablePattern = Regex("(\\|.+\\|)", RegexOption.MULTILINE)
        fixed = tablePattern.replace(fixed) { matchResult ->
            val line = matchResult.value
            if (matchResult.range.first == 0 || fixed[matchResult.range.first - 1] != '\n') {
                "\n$line"
            } else {
                line
            }
        }
        
        return fixed
    }
    
    /**
     * 修复引用格式
     */
    private fun fixQuoteFormat(text: String): String {
        var fixed = text
        
        // 修复引用格式：确保>后面有空格
        fixed = fixed.replace(Regex("^(>+)([^\\s>])"), "$1 $2")
        fixed = fixed.replace(Regex("\n(>+)([^\\s>])"), "\n$1 $2")
        
        // 修复引用块前后的换行
        fixed = fixed.replace(Regex("([^\n])\n(> .+)"), "$1\n\n$2")
        fixed = fixed.replace(Regex("(> .+)\n([^>\n])"), "$1\n\n$2")
        
        return fixed
    }
    
    /**
     * 修复粗体和斜体格式
     */
    private fun fixTextStyleFormat(text: String): String {
        var fixed = text
        
        // 修复不完整的粗体格式
        fixed = fixed.replace(Regex("\\*\\*([^*]+)(?!\\*\\*)"), "**$1**")
        fixed = fixed.replace(Regex("(?<!\\*)\\*\\*([^*]+)\\*(?!\\*)"), "**$1**")
        
        // 修复不完整的斜体格式
        fixed = fixed.replace(Regex("(?<!\\*)\\*([^*\\s][^*]*[^*\\s])\\*(?!\\*)"), "*$1*")
        
        // 修复下划线格式
        fixed = fixed.replace(Regex("__([^_]+)(?!__)"), "__$1__")
        fixed = fixed.replace(Regex("(?<!_)_([^_\\s][^_]*[^_\\s])_(?!_)"), "_$1_")
        
        return fixed
    }
    
    /**
     * 修复段落格式
     */
    private fun fixParagraphFormat(text: String): String {
        var fixed = text
        
        // 修复段落之间的间距
        fixed = fixed.replace(Regex("([.!?])\\s*\n([A-Z])"), "$1\n\n$2")
        
        // 修复句子内部的换行
        fixed = fixed.replace(Regex("([^.!?\\n])\\s*\n([a-z])"), "$1 $2")
        
        // 修复中文段落格式
        fixed = fixed.replace(Regex("([。！？])\\s*\n([\\u4e00-\\u9fa5])"), "$1\n\n$2")
        fixed = fixed.replace(Regex("([^。！？\\n])\\s*\n([\\u4e00-\\u9fa5])"), "$1$2")
        
        return fixed
    }
    
    /**
     * 智能检测和修复常见的AI输出错误
     * 包含性能优化和缓存机制
     */
    fun intelligentErrorCorrection(text: String): String {
        if (text.isBlank()) return text
        
        // 检查是否启用性能优化
        if (!formatConfig.enablePerformanceOptimization) {
            return applyErrorCorrections(text)
        }
        
        // 检查缓存
        if (formatConfig.enableCaching) {
            val cacheKey = "error_${text.hashCode()}"
            correctionCache[cacheKey]?.let { cached ->
                performanceMetrics.cacheHits++
                return cached
            }
            performanceMetrics.cacheMisses++
        }
        
        // 性能优化处理
        return performanceOptimizedProcessing(text, "intelligentErrorCorrection") {
            val result = applyErrorCorrections(text)
            
            // 缓存结果
            if (formatConfig.enableCaching && text.length < 5000) {
                val cacheKey = "error_${text.hashCode()}"
                correctionCache[cacheKey] = result
                cleanupCache()
            }
            
            result
        }
    }
    
    /**
     * 应用错误矫正
     */
    private fun applyErrorCorrections(text: String): String {
        var corrected = text
        
        // 根据配置应用不同的错误矫正功能
        if (formatConfig.enableJsonCorrection) {
            corrected = fixJsonFormat(corrected)
        }
        
        if (formatConfig.enableXmlHtmlCorrection) {
            corrected = fixXmlHtmlTags(corrected)
        }
        
        if (formatConfig.enableMathCorrection) {
            corrected = fixMathFormat(corrected)
        }
        
        if (formatConfig.enableProgrammingSyntaxCorrection) {
            corrected = fixProgrammingSyntax(corrected)
        }
        
        return corrected
    }
    
    /**
     * 修复JSON格式
     */
    private fun fixJsonFormat(text: String): String {
        var fixed = text
        
        // 修复JSON中缺失的引号
        fixed = fixed.replace(Regex("(\\{|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:"), "$1\"$2\":")
        
        // 修复JSON中缺失的逗号
        fixed = fixed.replace(Regex("\"\\s*\n\\s*\""), "\",\n\"")
        
        // 修复JSON中多余的逗号
        fixed = fixed.replace(Regex(",\\s*(\\}|\\])"), "$1")
        
        return fixed
    }
    
    /**
     * 修复XML/HTML标签
     */
    private fun fixXmlHtmlTags(text: String): String {
        var fixed = text
        
        // 修复未闭合的标签
        val openTags = mutableListOf<String>()
        val tagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
        
        tagPattern.findAll(fixed).forEach { match ->
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2].lowercase()
            
            if (isClosing) {
                openTags.removeLastOrNull()
            } else {
                openTags.add(tagName)
            }
        }
        
        // 添加缺失的闭合标签
        openTags.reversed().forEach { tagName ->
            fixed += "</$tagName>"
        }
        
        return fixed
    }
    
    /**
     * 修复数学公式格式
     */
    private fun fixMathFormat(text: String): String {
        var fixed = text
        
        // 修复LaTeX数学公式
        fixed = fixed.replace(Regex("\\$([^$]+)(?!\\$)"), "$$1$")
        fixed = fixed.replace(Regex("\\$\\$([^$]+)(?!\\$\\$)"), "$$$1$$")
        
        return fixed
    }
    
    /**
     * 修复编程语言语法错误
     */
    private fun fixProgrammingSyntax(text: String): String {
        var fixed = text
        
        // 修复Python缩进
        if (fixed.contains("def ") || fixed.contains("class ") || fixed.contains("if ") || fixed.contains("for ") || fixed.contains("while ")) {
            fixed = fixPythonIndentation(fixed)
        }
        
        // 修复JavaScript/Java括号
        if (fixed.contains("function ") || fixed.contains("public ") || fixed.contains("private ")) {
            fixed = fixBrackets(fixed)
        }
        
        return fixed
    }
    
    /**
     * 修复Python缩进
     */
    private fun fixPythonIndentation(text: String): String {
        val lines = text.split("\n")
        val fixedLines = mutableListOf<String>()
        var indentLevel = 0
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                fixedLines.add("")
                continue
            }
            
            // 检查是否需要减少缩进
            if (trimmed.startsWith("else") || trimmed.startsWith("elif") || 
                trimmed.startsWith("except") || trimmed.startsWith("finally")) {
                indentLevel = maxOf(0, indentLevel - 1)
            }
            
            // 添加正确的缩进
            val indentedLine = "    ".repeat(indentLevel) + trimmed
            fixedLines.add(indentedLine)
            
            // 检查是否需要增加缩进
            if (trimmed.endsWith(":")) {
                indentLevel++
            }
        }
        
        return fixedLines.joinToString("\n")
    }
    
    /**
     * 修复括号匹配
     */
    private fun fixBrackets(text: String): String {
        var fixed = text
        val stack = mutableListOf<Char>()
        val brackets = mapOf('(' to ')', '[' to ']', '{' to '}')
        
        for (char in fixed) {
            when (char) {
                '(', '[', '{' -> stack.add(char)
                ')', ']', '}' -> {
                    if (stack.isNotEmpty() && brackets[stack.last()] == char) {
                        stack.removeAt(stack.size - 1)
                    }
                }
            }
        }
        
        // 添加缺失的闭合括号
        while (stack.isNotEmpty()) {
            val openBracket = stack.removeAt(stack.size - 1)
            fixed += brackets[openBracket]
        }
        
        return fixed
    }
    
    /**
     * 检查新文本是否只是空白字符或重复内容
     */
    private fun shouldSkipTextChunk(newText: String, existingText: String): Boolean {
        // 如果新文本只包含空白字符，跳过
        if (newText.isBlank()) return true
        
        // 如果新文本只包含换行符和空格，且数量过多，跳过
        val whitespaceOnly = newText.replace(Regex("[^\n \t]"), "")
        if (whitespaceOnly == newText && newText.length > 10) {
            return true
        }
        
        return false
    }
    
    /**
     * 处理包含<think>标签的文本，动态分离思考内容和正式内容
     */
    private fun processThinkTags(newText: String): Pair<String?, String?> {
        val buffer = thinkingBuffer.get()
        val fullText = buffer.toString() + newText
        
        var thinkingContent: String? = null
        var regularContent: String? = null
        
        // 查找<think>标签
        val thinkStartPattern = "<think>"
        val thinkEndPattern = "</think>"
        
        val thinkStartIndex = fullText.indexOf(thinkStartPattern)
        val thinkEndIndex = fullText.indexOf(thinkEndPattern)
        
        when {
            // 找到完整的<think>...</think>
            thinkStartIndex != -1 && thinkEndIndex != -1 && thinkEndIndex > thinkStartIndex -> {
                // 提取思考内容（不包括标签）
                val thinkContent = fullText.substring(
                    thinkStartIndex + thinkStartPattern.length,
                    thinkEndIndex
                )
                thinkingContent = thinkContent
                
                // 提取</think>之后的内容作为正式内容
                val afterThinkIndex = thinkEndIndex + thinkEndPattern.length
                if (afterThinkIndex < fullText.length) {
                    regularContent = fullText.substring(afterThinkIndex)
                }
                
                // 清空缓冲区，标记已找到完整标签
                thinkingBuffer.set(StringBuilder())
                hasFoundThinkTag.set(true)
                isInsideThinkTag.set(false)
            }
            // 找到<think>但还没找到</think> - 正在思考标签内部
            thinkStartIndex != -1 && thinkEndIndex == -1 -> {
                isInsideThinkTag.set(true)
                // 提取<think>标签后的内容作为正在输出的思考内容
                val thinkingStartIndex = thinkStartIndex + thinkStartPattern.length
                if (thinkingStartIndex < fullText.length) {
                    thinkingContent = fullText.substring(thinkingStartIndex)
                }
                // 更新缓冲区
                thinkingBuffer.set(StringBuilder(fullText))
            }
            // 没有找到<think>标签
            thinkStartIndex == -1 -> {
                if (isInsideThinkTag.get()) {
                    // 仍在<think>标签内，继续缓冲并返回新增的思考内容
                    thinkingContent = newText
                    thinkingBuffer.set(StringBuilder(fullText))
                } else if (hasFoundThinkTag.get()) {
                    // 已经处理过<think>标签，这是正式内容
                    regularContent = newText
                } else {
                    // 还没遇到<think>标签，这是正式内容
                    regularContent = newText
                }
            }
        }
        
        return Pair(thinkingContent, regularContent)
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
                       is AppStreamEvent.Text -> {
                           if (event.text.isNotEmpty() && !isEffectivelyEmpty(event.text)) {
                            // 检查是否应该跳过这个文本块
                               if (shouldSkipTextChunk(event.text, currentTextBuilder.get().toString())) {
                                   return@withLock ProcessedEventResult.NoChange
                               }
                               
                               val normalizedText = normalizeText(event.text)
                               val chunkKey = "content_${normalizedText.hashCode()}"
                               if (!processedChunks.containsKey(chunkKey)) {
                                   // 智能跳过检查
                                   val preprocessedText = if (shouldSkipProcessing(event.text, "realtimePreprocessing")) {
                                       event.text
                                   } else {
                                       realtimeFormatPreprocessing(event.text)
                                   }
                                   
                                   // 使用新的<think>标签处理逻辑
                                   val (thinkingContent, regularContent) = processThinkTags(preprocessedText)
                                   
                                   // 如果有思考内容，添加到推理构建器
                                   thinkingContent?.let { thinking ->
                                       if (thinking.isNotEmpty() && !shouldSkipTextChunk(thinking, currentReasoningBuilder.get().toString())) {
                                           currentReasoningBuilder.get().append(thinking)
                                           // 返回思考内容更新，这样UI可以实时显示
                                           processedChunks[chunkKey] = normalizedText
                                           return@withLock ProcessedEventResult.ReasoningUpdated(currentReasoningBuilder.get().toString())
                                       }
                                   }
                                   
                                   // 如果有正式内容，添加到文本构建器
                                   regularContent?.let { regular ->
                                       if (regular.isNotEmpty() && !shouldSkipTextChunk(regular, currentTextBuilder.get().toString())) {
                                           currentTextBuilder.get().append(regular)
                                       }
                                   }
                                   
                                   processedChunks[chunkKey] = normalizedText
                               }
                           }
                           // 应用增强的格式矫正和清理累积的文本内容
                           val rawContent = currentTextBuilder.get().toString()
                           val finalContent = if (shouldSkipProcessing(rawContent, "enhancedFormatCorrection")) {
                               cleanExcessiveWhitespace(rawContent)
                           } else {
                               val corrected = if (formatConfig.enableProgressiveCorrection) {
                                   progressiveCorrection(rawContent)
                               } else {
                                   enhancedFormatCorrection(rawContent)
                               }
                               intelligentErrorCorrection(corrected)
                           }
                           ProcessedEventResult.ContentUpdated(finalContent)
                       }
                       is AppStreamEvent.Content -> {
                           if (event.text.isNotEmpty() && !isEffectivelyEmpty(event.text)) {
                               // Check if this is a Gemini final cleanup event
                               if (event.text.startsWith("__GEMINI_FINAL_CLEANUP__\n")) {
                                   // Replace the entire content with the cleaned version
                                   val cleanedContent = event.text.removePrefix("__GEMINI_FINAL_CLEANUP__\n")
                                   currentTextBuilder.set(StringBuilder(cleanedContent))
                                   // Clear processed chunks to allow the new content
                                   processedChunks.clear()
                                   logger.debug("Applied Gemini final cleanup to message content")
                               } else {
                                  // 检查是否应该跳过这个文本块
                                   if (shouldSkipTextChunk(event.text, currentTextBuilder.get().toString())) {
                                       return@withLock ProcessedEventResult.NoChange
                                   }
                                   
                                   val normalizedText = normalizeText(event.text)
                                   val chunkKey = "content_${normalizedText.hashCode()}"
                                   if (!processedChunks.containsKey(chunkKey)) {
                                       // 智能跳过检查
                                       val preprocessedText = if (shouldSkipProcessing(event.text, "realtimePreprocessing")) {
                                           event.text
                                       } else {
                                           realtimeFormatPreprocessing(event.text)
                                       }
                                       
                                       // 使用新的<think>标签处理逻辑
                                       val (thinkingContent, regularContent) = processThinkTags(preprocessedText)
                                       
                                       // 如果有思考内容，添加到推理构建器
                                       thinkingContent?.let { thinking ->
                                           if (thinking.isNotEmpty() && !shouldSkipTextChunk(thinking, currentReasoningBuilder.get().toString())) {
                                               currentReasoningBuilder.get().append(thinking)
                                               // 返回思考内容更新，这样UI可以实时显示
                                               processedChunks[chunkKey] = normalizedText
                                               return@withLock ProcessedEventResult.ReasoningUpdated(currentReasoningBuilder.get().toString())
                                           }
                                       }
                                       
                                       // 如果有正式内容，添加到文本构建器
                                       regularContent?.let { regular ->
                                           if (regular.isNotEmpty() && !shouldSkipTextChunk(regular, currentTextBuilder.get().toString())) {
                                               currentTextBuilder.get().append(regular)
                                           }
                                       }
                                       
                                       processedChunks[chunkKey] = normalizedText
                                   }
                               }
                           }
                           // 应用增强的格式矫正和清理累积的文本内容
                           val rawContent = currentTextBuilder.get().toString()
                           val finalContent = if (shouldSkipProcessing(rawContent, "enhancedFormatCorrection")) {
                               cleanExcessiveWhitespace(rawContent)
                           } else {
                               val corrected = if (formatConfig.enableProgressiveCorrection) {
                                   progressiveCorrection(rawContent)
                               } else {
                                   enhancedFormatCorrection(rawContent)
                               }
                               intelligentErrorCorrection(corrected)
                           }
                           ProcessedEventResult.ContentUpdated(finalContent)
                       }
                       is AppStreamEvent.Reasoning -> {
                           if (event.text.isNotEmpty() && !isEffectivelyEmpty(event.text)) {
                               val normalizedText = normalizeText(event.text)
                               val chunkKey = "reasoning_${normalizedText.hashCode()}"
                               if (!processedChunks.containsKey(chunkKey)) {
                                   // 智能跳过检查
                                   val preprocessedText = if (shouldSkipProcessing(event.text, "realtimePreprocessing")) {
                                       event.text
                                   } else {
                                       realtimeFormatPreprocessing(event.text)
                                   }
                                   currentReasoningBuilder.get().append(preprocessedText)
                                   processedChunks[chunkKey] = normalizedText
                               }
                           }
                           val rawReasoning = currentReasoningBuilder.get().toString()
                           val finalReasoning = if (shouldSkipProcessing(rawReasoning, "enhancedFormatCorrection")) {
                               cleanExcessiveWhitespace(rawReasoning)
                           } else {
                               val corrected = enhancedFormatCorrection(rawReasoning)
                               intelligentErrorCorrection(corrected)
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
        
        // 智能跳过检查
        if (shouldSkipProcessing(rawText, "enhancedFormatCorrection")) {
            return cleanExcessiveWhitespace(rawText)
        }
        
        // 使用渐进式矫正或完整矫正
        val corrected = if (formatConfig.enableProgressiveCorrection) {
            progressiveCorrection(rawText)
        } else {
            enhancedFormatCorrection(rawText)
        }
        
        return intelligentErrorCorrection(corrected)
    }
    
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
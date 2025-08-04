package com.example.everytalk.util.messageprocessor

import com.example.everytalk.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 格式矫正器，负责处理文本格式的矫正
 */
class FormatCorrector(
    private var formatConfig: FormatCorrectionConfig,
    private val performanceMetrics: PerformanceMetrics,
    private val correctionCache: ConcurrentHashMap<String, String>,
    private val preprocessingCache: ConcurrentHashMap<String, String>
) {
    private val logger = AppLogger.forComponent("FormatCorrector")
    
    fun updateConfig(config: FormatCorrectionConfig) {
        this.formatConfig = config
    }
    
    /**
     * 安全的正则表达式替换，避免组引用错误
     */
    private fun safeRegexReplace(
        text: String,
        regex: Regex,
        replacement: (MatchResult) -> String
    ): String {
        return try {
            regex.replace(text, replacement)
        } catch (e: Exception) {
            logger.warn("Error in regex replacement: ${e.message}, returning original text")
            text
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
     * 智能跳过处理 - 检查是否需要跳过某些处理步骤
     */
    fun shouldSkipProcessing(text: String, operation: String): Boolean {
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
    fun progressiveCorrection(text: String): String {
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
     * 清理文本中的多余空白段落，特别针对OpenAI兼容接口的输出
     * 保护数学公式和LaTeX语法
     */
    fun cleanExcessiveWhitespace(text: String): String {
        if (text.isBlank()) return ""
        
        var cleaned = text
        
        // 检查是否包含数学公式，如果包含则使用更保守的清理策略
        val hasMathContent = text.contains("$") || text.contains("\\") ||
                           listOf("frac", "sqrt", "sum", "int", "lim").any { text.contains("\\$it") }
        
        if (hasMathContent) {
            // 对于包含数学公式的文本，只做最基本的清理
            cleaned = cleaned.replace(Regex("[ \t]+\n"), "\n") // 移除行尾空白
            cleaned = cleaned.replace(Regex("\n{5,}"), "\n\n\n") // 只处理过多的空行
            cleaned = cleaned.trim()
            return cleaned
        }
        
        // 1. 移除行尾的空白字符，但保留换行符
        cleaned = cleaned.replace(Regex("[ \t]+\n"), "\n")
        
        // 2. 谨慎处理行首空白字符 - 只移除明显多余的空白，保留有意义的缩进
        // 只移除行首超过4个空格的情况，保护代码缩进和列表缩进
        cleaned = cleaned.replace(Regex("\n[ \t]{5,}"), "\n    ")
        
        // 3. 将连续的空行（3个或更多换行符）替换为最多2个换行符
        cleaned = cleaned.replace(Regex("\n{4,}"), "\n\n\n")
        
        // 4. 处理段落间的空白：确保段落之间有适当的空行
        cleaned = cleaned.replace(Regex("([.!?。！？])\\s*\n\\s*\n\\s*([A-Z\\u4e00-\\u9fa5])"), "$1\n\n$2")
        
        // 5. 移除连续的空格（超过3个），但保留一些空格用于格式化
        cleaned = cleaned.replace(Regex(" {4,}"), "  ")
        
        // 6. 处理特殊情况：移除代码块前后多余的空行
        cleaned = cleaned.replace(Regex("\n{3,}```"), "\n\n```")
        cleaned = cleaned.replace(Regex("```\n{3,}"), "```\n\n")
        
        // 7. 处理列表项前后的空白 - 更保守的处理
        cleaned = cleaned.replace(Regex("\n{3,}([\\-\\*\\+]\\s)"), "\n\n$1")
        cleaned = cleaned.replace(Regex("\n{3,}(\\d+\\.\\s)"), "\n\n$1")
        
        // 8. 移除文本开头和结尾的多余空白，但保留一些基本格式
        cleaned = cleaned.trim()
        
        // 9. 额外保护：如果清理后的文本明显比原文本短很多，可能是过度清理了
        if (cleaned.length < text.length * 0.3 && text.length > 50) {
            logger.warn("Excessive whitespace cleaning may have removed too much content, reverting to original")
            return text.trim()
        }
        
        // 10. 确保不会产生完全空白的结果
        if (cleaned.isBlank()) return text.trim()
        
        return cleaned
    }
    
    /**
     * 清理缓存
     */
    private fun cleanupCache() {
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
}
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
     * 应用格式矫正 - 改进版：优化处理顺序，避免格式冲突
     */
    private fun applyFormatCorrections(text: String): String {
        var corrected = text
        
        // 第一阶段：处理块级元素（优先级最高，避免被其他格式干扰）
        if (formatConfig.enableCodeBlockCorrection) {
            corrected = fixCodeBlockFormat(corrected)
        }
        
        // 第二阶段：处理结构化格式
        if (formatConfig.enableMarkdownCorrection) {
            corrected = fixMarkdownHeaders(corrected)
        }
        
        if (formatConfig.enableListCorrection) {
            corrected = fixListFormat(corrected)
        }
        
        if (formatConfig.enableTableCorrection) {
            corrected = fixTableFormat(corrected)
        }
        
        if (formatConfig.enableQuoteCorrection) {
            corrected = fixQuoteFormat(corrected)
        }
        
        // 第三阶段：处理内联格式（在结构化格式之后）
        if (formatConfig.enableLinkCorrection) {
            corrected = fixLinkFormat(corrected)
        }
        
        if (formatConfig.enableTextStyleCorrection) {
            corrected = fixTextStyleFormatSafely(corrected)
        }
        
        // 第四阶段：处理段落和空白（最后处理）
        if (formatConfig.enableParagraphCorrection) {
            corrected = fixParagraphFormat(corrected)
        }
        
        // 最后清理多余空白
        corrected = cleanExcessiveWhitespace(corrected)
        
        // 最终检查：移除重复的格式标记
        corrected = removeDuplicateFormatMarkers(corrected)
        
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
        
        // 更保守的代码块修复策略，只修复明确缺少结束标记的代码块
        // 首先检查是否有不完整的代码块（开始标记后没有对应的结束标记）
        val incompleteCodeBlockPattern = Regex("```([a-zA-Z0-9+#-]*)\\n([\\s\\S]*?)(?=\\n```|$)", RegexOption.DOT_MATCHES_ALL)
        
        // 只有当文本中确实存在不匹配的```时才进行修复
        val codeBlockStarts = text.split("```").size - 1
        if (codeBlockStarts % 2 != 0) { // 奇数个```说明有不完整的代码块
            fixed = incompleteCodeBlockPattern.replace(fixed) { matchResult ->
                val language = matchResult.groupValues[1]
                val codeContent = matchResult.groupValues[2].trim()
                
                // 严格条件：只修复真正的代码内容
                if (codeContent.isNotEmpty() && 
                    !matchResult.value.endsWith("```") &&
                    !codeContent.contains("。") && // 避免包含中文句号的普通文本
                    !codeContent.contains("，") && // 避免包含中文逗号的普通文本
                    !codeContent.contains("？") && // 避免包含中文问号的普通文本
                    !codeContent.contains("！") && // 避免包含中文感叹号的普通文本
                    codeContent.lines().size <= 20 && // 更严格的行数限制
                    codeContent.length <= 500) { // 添加长度限制
                    "```$language\n$codeContent\n```"
                } else {
                    matchResult.value
                }
            }
        }
        
        // 修复单行代码块（反引号）- 更保守的策略
        fixed = fixed.replace(Regex("`([^`\n]{1,100})(?!`)")) { match ->
            val content = match.groupValues[1]
            if (!content.contains("。") && !content.contains("，")) {
                "`$content`"
            } else {
                match.value
            }
        }
        
        return fixed
    }
    
    /**
     * 检查位置是否在受保护的范围内（如代码块）
     */
    private fun isInProtectedRange(position: Int, protectedRanges: List<IntRange>): Boolean {
        return protectedRanges.any { range -> position in range }
    }
    
    /**
     * 移除重复的格式标记
     */
    private fun removeDuplicateFormatMarkers(text: String): String {
        var cleaned = text
        
        // 移除重复的粗体标记：****text**** -> **text**
        cleaned = cleaned.replace(Regex("\\*{3,}([^*]+?)\\*{3,}")) { match ->
            val content = match.groupValues[1]
            "**$content**"
        }
        
        // 移除重复的斜体标记：***text*** -> *text*（但不要处理正常的粗体**text**）
        cleaned = cleaned.replace(Regex("(?<!\\*)\\*{3}([^*]+?)\\*{3}(?!\\*)")) { match ->
            val content = match.groupValues[1]
            // 三个星号的情况，转换为斜体
            "*$content*"
        }
        
        // 移除重复的下划线标记
        cleaned = cleaned.replace(Regex("_{3,}([^_]+?)_{3,}")) { match ->
            val content = match.groupValues[1]
            "__${content}__"
        }
        
        // 修复混合的格式标记：**text* -> **text**
        cleaned = cleaned.replace(Regex("\\*\\*([^*]+?)\\*(?!\\*)")) { match ->
            val content = match.groupValues[1]
            "**$content**"
        }
        
        // 修复混合的格式标记：*text** -> **text**
        cleaned = cleaned.replace(Regex("(?<!\\*)\\*([^*]+?)\\*\\*")) { match ->
            val content = match.groupValues[1]
            "**$content**"
        }
        
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
        
        // 增强：将常见伪列表符号与全角星号统一为标准 Markdown 列表
        fixed = fixed.replace(Regex("(?m)^(\\s*)[•●◦▪▫·–—−]+\\s*(\\S)"), "$1- $2")
        fixed = fixed.replace(Regex("＊"), "*")
        
        // 修复无序列表：确保-、*、+后面有空格
        fixed = fixed.replace(Regex("^(\\s*)([\\-\\*\\+])([^\\s])"), "$1$2 $3")
        fixed = fixed.replace(Regex("\n(\\s*)([\\-\\*\\+])([^\\s])"), "\n$1$2 $3")
        
        // 中文有序列表：将 1、/1．/1. 统一为 1. 且补空格
        fixed = fixed.replace(Regex("(?m)^(\\s*)(\\d+)[、．.]+\\s*(\\S)"), "$1$2. $3")
        
        // 修复有序列表：确保数字.后面有空格
        fixed = fixed.replace(Regex("^(\\s*)(\\d+\\.)([^\\s])"), "$1$2 $3")
        fixed = fixed.replace(Regex("\n(\\s*)(\\d+\\.)([^\\s])"), "\n$1$2 $3")
        
        // 列表块前补空行（上一行不是空行且下一行是列表项）
        fixed = fixed.replace(Regex("(?m)([^\\n])\\n(\\s*(?:[\\-\\*\\+]|\\d+\\.)\\s)"), "$1\n\n$2")

        // 修复列表项之间的换行
        fixed = fixed.replace(Regex("(\\s*[\\-\\*\\+] .+)\n([^\\s\\-\\*\\+\\n])"), "$1\n\n$2")
        fixed = fixed.replace(Regex("(\\s*\\d+\\. .+)\n([^\\s\\d\\n])"), "$1\n\n$2")
        
        return fixed
    }
    
    /**
     * 修复链接格式 - 改进版：避免与其他格式冲突
     */
    private fun fixLinkFormat(text: String): String {
        var fixed = text
        
        // 修复不完整的Markdown链接格式，但要避免在代码块中处理
        fixed = safeRegexReplace(fixed, Regex("\\[([^\\]]+)\\]\\s*\\(([^\\)]+)\\)")) { match ->
            "[${match.groupValues[1]}](${match.groupValues[2]})"
        }
        
        // 修复缺失的链接文本
        fixed = safeRegexReplace(fixed, Regex("\\[\\]\\(([^\\)]+)\\)")) { match ->
            val url = match.groupValues[1]
            "[$url]($url)"
        }
        
        // 修复纯URL，转换为链接格式（但要更加谨慎）
        fixed = safeRegexReplace(fixed, Regex("(?<!\\[|\\(|`)https?://[^\\s\\)\\]`]+(?!\\)|\\]|`)")) { match ->
            val url = match.value
            // 检查URL是否已经在链接或代码中
            "[$url]($url)"
        }
        
        return fixed
    }
    
    /**
     * 修复表格格式 - 改进版：更智能的表格检测和修复
     */
    private fun fixTableFormat(text: String): String {
        var fixed = text
        
        // 只在确实是表格的情况下进行修复
        val tableLinePattern = Regex("\\|[^\\n]*\\|", RegexOption.MULTILINE)
        val tableLines = tableLinePattern.findAll(fixed).toList()
        
        if (tableLines.size >= 2) { // 至少需要2行才认为是表格
            // 修复表格分隔符
            fixed = fixed.replace(Regex("\\|\\s*-+\\s*\\|"), "| --- |")
            fixed = fixed.replace(Regex("\\|\\s*-+\\s*(?=\\|)"), "| --- ")
            fixed = fixed.replace(Regex("(?<=\\|)\\s*-+\\s*\\|"), " --- |")
            
            // 确保表格前后有适当的换行
            fixed = fixed.replace(Regex("([^\\n])\\n(\\|[^\\n]*\\|)")) { match ->
                "${match.groupValues[1]}\n\n${match.groupValues[2]}"
            }
            
            fixed = fixed.replace(Regex("(\\|[^\\n]*\\|)\\n([^\\n\\|])")) { match ->
                "${match.groupValues[1]}\n\n${match.groupValues[2]}"
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
     * 极度保守的文本样式格式修复，只修复明显的格式错误
     */
    private fun fixTextStyleFormatSafely(text: String): String {
        // 如果文本包含大量中文或复杂内容，跳过处理
        if (text.length > 1000 || text.count { it.toString().matches("[\u4e00-\u9fa5]".toRegex()) } > text.length * 0.3) {
            return text
        }
        
        // 首先检查是否包含代码块，如果有则跳过代码块内容
        val codeBlockRegex = "```[\\s\\S]*?```".toRegex()
        val inlineCodeRegex = "`[^`]+`".toRegex()
        
        val codeBlocks = codeBlockRegex.findAll(text).map { it.range }.toList()
        val inlineCodes = inlineCodeRegex.findAll(text).map { it.range }.toList()
        val protectedRanges = (codeBlocks + inlineCodes).sortedBy { it.first }
        
        return fixTextStyleFormatWithProtection(text, protectedRanges)
    }
    
    /**
     * 极简的文本样式格式修复，只处理最明显的错误
     */
    private fun fixTextStyleFormatWithProtection(text: String, protectedRanges: List<IntRange>): String {
        var fixed = text
        
        try {
            // 只修复最明显的不完整粗体格式：**text 在行尾
            fixed = fixed.replace(Regex("\\*\\*([^*\n]{1,50})$")) { matchResult ->
                val content = matchResult.groupValues[1].trim()
                if (content.isNotEmpty() && !content.contains("```")) {
                    "**$content**"
                } else {
                    matchResult.value
                }
            }
            
            // 只修复最明显的不完整粗体格式：**text 后面跟着换行
            fixed = fixed.replace(Regex("\\*\\*([^*\n]{1,50})(?=\n)")) { matchResult ->
                val content = matchResult.groupValues[1].trim()
                if (content.isNotEmpty() && !content.contains("```")) {
                    "**$content**"
                } else {
                    matchResult.value
                }
            }
            
        } catch (e: Exception) {
            // 如果出现任何错误，返回原文本
            return text
        }
        
        return fixed
    }
    
    /**
     * 修复段落格式
     */
    private fun fixParagraphFormat(text: String): String {
        var fixed = text
        
        // 只修复明显的段落间距问题，不强制合并换行
        // 修复段落之间的间距（句号后跟大写字母）
        fixed = fixed.replace(Regex("([.!?])\\s*\n([A-Z])"), "$1\n\n$2")
        
        // 修复中文段落格式（中文标点后跟中文字符）
        fixed = fixed.replace(Regex("([。！？])\\s*\n([\\u4e00-\\u9fa5])"), "$1\n\n$2")
        
        // 移除过度的换行合并逻辑，保持原有的换行结构
        // 不再强制将句子内部的换行替换为空格
        
        return fixed
    }
    
    /**
     * 清理文本中的多余空白段落，特别针对OpenAI兼容接口的输出
     * 保护数学公式和LaTeX语法，避免强制换行
     */
    fun cleanExcessiveWhitespace(text: String): String {
        if (text.isBlank()) return ""
        
        var cleaned = text
        
        // 检查是否包含数学公式，如果包含则使用更保守的清理策略
        val hasMathContent = text.contains("$") || text.contains("\\") ||
                           listOf("frac", "sqrt", "sum", "int", "lim").any { text.contains("\\$it") }
        
        if (hasMathContent) {
            // 对于包含数学公式的文本，采用智能清理策略
            cleaned = smartMathContentCleaning(cleaned)
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
     * 智能数学内容清理 - 避免破坏数学公式的行内显示，修复重复文本问题
     */
    private fun smartMathContentCleaning(text: String): String {
        var cleaned = text
        
        // 1. 移除行尾空白，但保护数学公式
        cleaned = cleaned.replace(Regex("[ \t]+\n"), "\n")
        
        // 2. 处理数学公式周围的空白 - 关键修复
        // 只处理被错误分离到单独行的短数学公式，保持原本应该行内显示的公式
        // 修复：只有当数学公式前后都是换行符且公式较短时才合并为行内显示
        cleaned = cleaned.replace(Regex("\n\\s*(\\\$[^$\n]{1,20}\\\$)\\s*\n(?=[\u4e00-\u9fa5a-zA-Z])"), " $1 ")
        
        // 3. 处理块级数学公式的合理间距
        cleaned = cleaned.replace(Regex("\\s*\n{3,}\\s*(\\\$\\\$[^$]+\\\$\\\$)\\s*\n{3,}\\s*"), "\n\n$1\n\n")
        
        // 4. 修复被错误分离的中文文本，但要避免重复合并
        // 改进：检查是否已经是连续的中文文本，避免重复处理
        cleaned = safeRegexReplace(cleaned, Regex("([\u4e00-\u9fa5])\\s*\n+\\s*([\u4e00-\u9fa5])")) { matchResult ->
            val char1 = matchResult.groupValues[1]
            val char2 = matchResult.groupValues[2]
            // 检查是否可能导致重复
            val beforeMatch = cleaned.substring(0, matchResult.range.first)
            val afterMatch = cleaned.substring(matchResult.range.last + 1)
            
            // 如果前后文本已经包含这些字符，可能会造成重复，保持原样
            if (beforeMatch.endsWith(char1 + char2) || afterMatch.startsWith(char1 + char2)) {
                matchResult.value // 保持原样
            } else {
                "$char1$char2"
            }
        }
        
        // 5. 处理中文标点符号后的不当换行，避免重复
        cleaned = safeRegexReplace(cleaned, Regex("([，。！？；：])\\s*\n+\\s*([\u4e00-\u9fa5])")) { matchResult ->
            val punctuation = matchResult.groupValues[1]
            val nextChar = matchResult.groupValues[2]
            
            // 检查是否已经是正确格式
            val beforeMatch = cleaned.substring(0, maxOf(0, matchResult.range.first - 10))
            val pattern = "$punctuation $nextChar"
            
            if (beforeMatch.contains(pattern)) {
                matchResult.value // 如果已经存在正确格式，保持原样
            } else {
                "$punctuation $nextChar"
            }
        }
        
        // 6. 只处理过多的空行（超过4行的情况）
        cleaned = cleaned.replace(Regex("\n{5,}"), "\n\n\n")
        
        // 7. 保护LaTeX命令周围的格式
        cleaned = cleaned.replace(Regex("\\s*\n+\\s*(\\\\[a-zA-Z]+)\\s*\n+\\s*"), " $1 ")
        
        // 8. 检查重复段落并移除
        cleaned = removeDuplicateSegments(cleaned)
        
        // 9. 最终清理
        cleaned = cleaned.trim()
        
        return cleaned
    }
    
    /**
     * 移除重复的文本段落
     */
    private fun removeDuplicateSegments(text: String): String {
        val lines = text.split("\n")
        val processedLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            
            // 如果这一行不为空且不是重复行，添加到结果中
            if (trimmedLine.isNotEmpty()) {
                // 检查最近几行是否有重复
                val recentLines = processedLines.takeLast(3)
                val isDuplicate = recentLines.any { it.trim() == trimmedLine }
                
                if (!isDuplicate) {
                    processedLines.add(line)
                } else {
                    logger.debug("Removing duplicate line: $trimmedLine")
                }
            } else {
                // 空行直接添加，但避免连续多个空行
                if (processedLines.lastOrNull()?.trim()?.isNotEmpty() == true) {
                    processedLines.add(line)
                }
            }
        }
        
        return processedLines.joinToString("\n")
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
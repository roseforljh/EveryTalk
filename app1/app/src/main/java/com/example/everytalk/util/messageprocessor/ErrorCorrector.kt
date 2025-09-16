package com.example.everytalk.util.messageprocessor

import com.example.everytalk.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 错误矫正器，负责修复常见的AI输出错误
 */
class ErrorCorrector(
    private var formatConfig: FormatCorrectionConfig,
    private val performanceMetrics: PerformanceMetrics,
    private val correctionCache: ConcurrentHashMap<String, String>
) {
    private val logger = AppLogger.forComponent("ErrorCorrector")

    fun updateConfig(config: FormatCorrectionConfig) {
        this.formatConfig = config
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

        if (formatConfig.enableMarkdownCorrection) {
            corrected = fixCommonFormatErrors(corrected)
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
        fixed = fixed.replace(Regex("(\\{|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:"), "${'$'}1\"${'$'}2\":")

        // 修复JSON中缺失的逗号
        fixed = fixed.replace(Regex("\"\\s*\n\\s*\""), "\",\n\"")

        // 修复JSON中多余的逗号
        fixed = fixed.replace(Regex(",\\s*(\\}|\\])"), "${'$'}1")

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
            fixed += "</${'$'}tagName>"
        }

        return fixed
    }

    /**
     * 修复常见的格式错误，基于检测到的实际问题进行智能纠正
     * - 智能换行优化：减少破坏性操作，保护正确格式
     * - 标点符号修复：处理不当的空格和标点使用
     * - 不再进行激进的自动包裹，只做最小化的 $ / $$ 平衡
     */
    private fun fixCommonFormatErrors(text: String): String {
        var fixed = text

        // 修复换行符
        fixed = fixed.replace(Regex("\\n\\n+"), "\n")

        // 修复标点符号周围的空格
        fixed = fixed.replace(Regex("(\\S)([.,!?])"), "${'$'}1 ${'$'}2")

        return fixed
    }

    /**
     * 转义 Markdown 特殊字符（保守方式）
     */
    private fun escapeSpecialCharacters(text: String): String {
        var fixed = text

        // 转义反引号
        fixed = fixed.replace(Regex("`"), "``")

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
                logger.warn("${'$'}operation took ${'$'}{processingTime}ms, exceeding threshold of ${'$'}{formatConfig.maxProcessingTimeMs}ms for text length: ${'$'}{text.length}")
            }

            return result
        } catch (e: Exception) {
            logger.error("Error in ${'$'}operation", e)
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
     * 清理缓存
     */
    private fun cleanupCache() {
        if (formatConfig.enableCaching) {
            // 如果缓存超过最大大小，清理最旧的条目
            if (correctionCache.size > formatConfig.maxCacheSize) {
                val toRemove = correctionCache.size - formatConfig.maxCacheSize / 2
                correctionCache.keys.take(toRemove).forEach { correctionCache.remove(it) }
            }
        }
    }
}
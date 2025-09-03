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
     * 修复数学公式格式（保守策略）
     * - 保护代码块与行内代码
     * - 不再进行激进的自动包裹，只做最小化的 $ / $$ 平衡
     */
    private fun fixMathFormat(text: String): String {
        var working = text
        return try {
            val placeholders = mutableListOf<String>()
            // 保护代码块（```...```）
            working = protectSegments(working, Regex("(?s)```.*?```"), placeholders, "CODE_BLOCK")
            // 保护行内代码（`...`）
            working = protectSegments(working, Regex("`[^`\n]+`"), placeholders, "INLINE_CODE")

            // 先平衡块级数学 $$ ... $$
            working = balanceBlockMath(working)
            // 再平衡行内数学 $ ... $
            working = balanceInlineMath(working)

            // 恢复被保护的片段
            working = restoreSegments(working, placeholders)
            working
        } catch (e: Exception) {
            logger.warn("Error in math format correction: ${'$'}{e.message}, returning original text")
            text
        }
    }

    // 保护匹配到的片段，替换为占位符
    private fun protectSegments(input: String, pattern: Regex, store: MutableList<String>, tag: String): String {
        var result = input
        val matches = pattern.findAll(result).toList()
        matches.asReversed().forEach { m ->
            val token = "<<PROTECTED_${'$'}{store.size}_${'$'}tag>>"
            store.add(m.value)
            result = result.replaceRange(m.range, token)
        }
        return result
    }

    // 根据索引还原占位符
    private fun restoreSegments(input: String, store: MutableList<String>): String {
        var result = input
        store.forEachIndexed { idx, original ->
            val tokenRegex = Regex("<<PROTECTED_${'$'}idx_[A-Z_]+>>")
            result = result.replace(tokenRegex, original)
        }
        return result
    }

    // 平衡 $$ 块级数学。如果 $$ 出现次数为奇数，则在末尾补一个 $$
    private fun balanceBlockMath(text: String): String {
        var result = text
        val dollarDollar = Regex("(?<!\\\\)\\$\\$")
        val count = dollarDollar.findAll(result).count()
        if (count % 2 != 0) {
            // 在文本末尾补齐，避免破坏中间内容
            result += "\n$$"
        }
        return result
    }

    // 平衡单美元 $ 的行内数学。仅在检测到数学指示符时才补齐，避免把 $100 之类当作数学
    private fun balanceInlineMath(text: String): String {
        val lines = text.split("\n")
        val out = StringBuilder()
        var inBlockMath = false
        val dollarDollar = Regex("(?<!\\\\)\\$\\$")
        val singleDollar = Regex("(?<!\\\\)\\$")

        lines.forEachIndexed { idx, rawLine ->
            var line = rawLine

            // 统计本行出现的未转义 $$，用于块级数学状态切换
            val ddCount = dollarDollar.findAll(line).count()
            if (!inBlockMath) {
                // 在不处于块级数学时，才尝试平衡行内 $
                // 去掉 $$ 后再统计单 $
                val lineWithoutDD = dollarDollar.replace(line, "")
                val singleCount = singleDollar.findAll(lineWithoutDD).count()
                if (singleCount % 2 != 0) {
                    // 有奇数个单 $，尝试判断是否是数学
                    val lastDollarIndex = lineWithoutDD.lastIndexOf('$')
                    val tail = if (lastDollarIndex >= 0 && lastDollarIndex < lineWithoutDD.length - 1) {
                        lineWithoutDD.substring(lastDollarIndex + 1)
                    } else ""
                    if (hasMathIndicators(tail)) {
                        line += "$"
                    }
                }
            }
            // 输出本行
            out.append(line)
            if (idx < lines.size - 1) out.append('\n')

            // 依据本行 $$ 数量奇偶切换块级状态
            if (ddCount % 2 == 1) {
                inBlockMath = !inBlockMath
            }
        }
        return out.toString()
    }

    // 粗略判断是否可能是数学内容，避免把货币/环境变量误当数学
    private fun hasMathIndicators(text: String): Boolean {
        if (text.isBlank()) return false
        // 使用原始字符串，避免跨行与转义问题
        val pattern = Regex("""(\\\\|\^|_|=|[+\u2212\-*/])|(\b(sin|cos|tan|log|ln|exp|sqrt)\b)|(\d+\s*[+\-*/=]\s*\d+)""")
        return pattern.containsMatchIn(text)
    }

    /**
     * 自动识别并包装数学表达式
     */
    private fun autoWrapMathExpressions(text: String): String {
        var result = text

        try {
            // 特殊处理：检查是否已经在$符号内
            val existingMathRegex = Regex("\\$([^$]+)\\$|\\$\\$([^$]+)\\$\\$")
            val existingMathRanges = existingMathRegex.findAll(result).map { it.range }.toList()

            // 优先处理常见的数学定理表述（更精确的模式）
            val priorityPatterns = listOf(
                // 勾股定理：a^2 + b^2 = c^2
                Regex("\\b([a-zA-Z])\\^2\\s*\\+\\s*([a-zA-Z])\\^2\\s*=\\s*([a-zA-Z])\\^2\\b"),
                // 数值计算：3^2 + 4^2 = 9 + 16 = 25
                Regex("\\b(\\d+)\\^2\\s*\\+\\s*(\\d+)\\^2\\s*=\\s*(\\d+)\\s*\\+\\s*(\\d+)\\s*=\\s*(\\d+)\\b"),
                // 开方表达式：√{25} = 5, \\sqrt{25}
                Regex("\\\\sqrt\\{([^}]*)\\}\\s*=\\s*([\\d]+)"),
                Regex("√\\{([^}]*)\\}\\s*=\\s*([\\d]+)"),
                // 更通用的开方：√25 = 5
                Regex("√([\\d]+)\\s*=\\s*([\\d]+)"),
                // 数学等式（更严格的模式，避免匹配普通文本）
                Regex("\\b([a-zA-Z]\\^[0-9]+|\\d+\\^[0-9]+|[a-zA-Z]+_[0-9]+)\\s*[+\\-*/]\\s*([a-zA-Z]\\^[0-9]+|\\d+\\^[0-9]+|[a-zA-Z]+_[0-9]+)\\s*=\\s*([a-zA-Z0-9^+\\-*/]+)\\b")
            )

            // 先处理优先级高的模式
            for (pattern in priorityPatterns) {
                val matches = pattern.findAll(result).toList()

                // 从后往前替换，避免索引偏移问题
                for (match in matches.reversed()) {
                    // 检查是否已经在数学公式内
                    val isInExistingMath = existingMathRanges.any { range ->
                        match.range.first >= range.first && match.range.last <= range.last
                    }

                    if (!isInExistingMath) {
                        val matchedText = match.value
                        val wrappedText = "${'$'}${'$'}matchedText${'$'}"
                        result = result.replaceRange(match.range, wrappedText)
                    }
                }
            }

            // 其他数学表达式模式
            val mathPatterns = listOf(
                // LaTeX命令: \\boxed{...}, \\frac{...}{...}, \\sqrt{...}
                Regex("\\\\boxed\\{([^}]*)\\}"),
                Regex("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}"),
                Regex("\\\\sqrt\\{([^}]*)\\}"),
                
                // 上标表达式: a^2, x^{2+3}, e^{-x}
                Regex("\\b([a-zA-Z]\\w*)\\^\\{([^}]+)\\}"),
                Regex("\\b([a-zA-Z]\\w*)\\^([a-zA-Z0-9+\\-*/]+)\\b"),

                // 下标表达式: a_1, x_{i+1}
                Regex("\\b([a-zA-Z]\\w*)_\\{([^}]+)\\}"),
                Regex("\\b([a-zA-Z]\\w*)_([a-zA-Z0-9+\\-*/]+)\\b"),

                // 数学函数: sin(x), cos(x), log(x), ln(x)
                Regex("\\b(sin|cos|tan|sec|csc|cot|log|ln|exp)\\(([^)]+)\\)"),

                // 希腊字母
                Regex("\\b(α|β|γ|δ|ε|ζ|η|θ|ι|κ|λ|μ|ν|ξ|ο|π|ρ|σ|τ|υ|φ|χ|ψ|ω|Α|Β|Γ|Δ|Ε|Ζ|Η|Θ|Ι|Κ|Λ|Μ|Ν|Ξ|Ο|Π|Ρ|Σ|Τ|Υ|Φ|Χ|Ψ|Ω)\\b"),

                // 数学符号
                Regex("\\b(∞|∂|∇|∈|∉|∪|∩|⊂|⊃|⊆|⊇|∫|Σ)\\w*\\b")
            )

            // 更新现有数学公式范围
            val updatedMathRanges = existingMathRegex.findAll(result).map { it.range }.toList()

            for (pattern in mathPatterns) {
                val matches = pattern.findAll(result).toList()

                // 从后往前替换，避免索引偏移问题
                for (match in matches.reversed()) {
                    // 检查是否已经在数学公式内
                    val isInExistingMath = updatedMathRanges.any { range ->
                        match.range.first >= range.first && match.range.last <= range.last
                    }

                    if (!isInExistingMath) {
                        val matchedText = match.value
                        val wrappedText = "${'$'}${'$'}matchedText${'$'}"
                        result = result.replaceRange(match.range, wrappedText)
                    }
                }
            }

        } catch (e: Exception) {
            logger.warn("Error in auto math wrapping: ${'$'}{e.message}, returning original text")
            return text
        }

        return result
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
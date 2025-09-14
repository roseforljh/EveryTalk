package com.example.everytalk.util.messageprocessor

import com.example.everytalk.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 实时预处理器，负责在文本添加到构建器之前进行初步格式矫正
 */
class RealtimePreprocessor(
    private var formatConfig: FormatCorrectionConfig,
    private val performanceMetrics: PerformanceMetrics,
    private val preprocessingCache: ConcurrentHashMap<String, String>
) {
    private val logger = AppLogger.forComponent("RealtimePreprocessor")
    
    fun updateConfig(config: FormatCorrectionConfig) {
        this.formatConfig = config
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
        
        // 如果处于未闭合的代码围栏中，跳过处理避免破坏代码块
        val fenceCount = "```".toRegex().findAll(text).count()
        val insideFence = fenceCount % 2 == 1
        if (insideFence) {
            performanceMetrics.skippedProcessing++
            return true
        }
        
        // 检查文本长度阈值 - 提高阈值，避免跳过正常长度的文本
        if (text.length > formatConfig.maxProcessingTimeMs * 50) { // 提高阈值
            logger.debug("Skipping $operation for text length: ${text.length}")
            performanceMetrics.skippedProcessing++
            return true
        }
        
        // 如果文本为空或只包含空白字符，跳过处理
        if (text.isBlank()) {
            performanceMetrics.skippedProcessing++
            return true
        }
        
        return false
    }
    
    /**
     * 实时格式预处理 - 在文本添加到构建器之前进行初步格式矫正
     * 包含性能优化和缓存机制
     */
    fun realtimeFormatPreprocessing(text: String): String {
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
                val formatCorrector = FormatCorrector(formatConfig, performanceMetrics, ConcurrentHashMap(), preprocessingCache)
                preprocessed = formatCorrector.enhancedFormatCorrection(preprocessed)
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
        // 如果处于未闭合的代码围栏中，直接返回原文，避免在流式过程中破坏代码块
        val fenceCount = "```".toRegex().findAll(text).count()
        if (fenceCount % 2 == 1) return text
        
        var processed = text
        
        // 确保代码块标记前后有换行（在完整围栏外才做）
        processed = processed.replace(Regex("([^\\n])```"), "$1\n```")
        processed = processed.replace(Regex("```([^\\n])"), "```\n$1")
        
        // 修复常见的代码块语言标记错误
        processed = processed.replace(Regex("```(python|java|javascript|kotlin|swift|cpp|c\\+\\+)\\s*\n"), "```$1\n")
        
        return processed
    }
    
    /**
     * 预处理Markdown标记 - 简化版本，只处理基本格式
     */
    private fun preprocessMarkdown(text: String): String {
        if (text.isBlank()) return text
        
        // 逐行处理，遇到代码围栏时切换状态，围栏内不做任何修改
        val lines = text.split("\n").toMutableList()
        var insideFence = false
        for (i in lines.indices) {
            var line = lines[i]
            if (line.contains("```") ){
                // 如果当前行出现```，切换一次状态（支持单行开合或多行块）
                val count = "```".toRegex().findAll(line).count()
                // 奇数次出现视为切换
                if (count % 2 == 1) {
                    insideFence = !insideFence
                }
                // 仍然对围栏标记本身不进行修改
                continue
            }
            if (!insideFence) {
                // 确保标题标记格式正确（只在围栏外处理）
                line = line.replace(Regex("^(#{1,6})([^\\s#])"), "$1 $2")
                // 统一列表符号与空格：将全角星号/圆点替换，补空格
                line = line.replace(Regex("^(\\s*)[＊﹡]([^\\s])"), "$1* $2")
                line = line.replace(Regex("^(\\s*)[•·・﹒∙]([^\\s])"), "$1- $2")
                line = line.replace(Regex("^(\\s*)([*+\\-])(?![ *+\\-])(\\S)"), "$1$2 $3")
                line = line.replace(Regex("^(\\s*)(\\d+)([.)])(\\S)"), "$1$2$3 $4")
                lines[i] = line
            }
        }
        return lines.joinToString("\n")
    }
    
    /**
     * 清理文本中的多余空白段落，特别针对OpenAI兼容接口的输出
     */
    private fun cleanExcessiveWhitespace(text: String): String {
        if (text.isBlank()) return ""
        
        var cleaned = text
        
        // 1. 移除行尾的空白字符，但保留换行符
        cleaned = cleaned.replace(Regex("[ \t]+\n"), "\n")
        
        // 2. 移除行首的多余空白字符（保留必要的缩进）
        cleaned = cleaned.replace(Regex("\n[ \t]+"), "\n")
        
        // 3. 将连续的空行（2个或更多换行符）替换为最多1个空行
        cleaned = cleaned.replace(Regex("\n{3,}"), "\n\n")
        cleaned = cleaned.replace(Regex("\n{2,}"), "\n\n")
        
        // 4. 处理段落间的空白：确保段落之间只有一个空行
        cleaned = cleaned.replace(Regex("([.!?])\\s*\n\\s*\n\\s*([A-Z\\u4e00-\\u9fa5])"), "$1\n\n$2")
        
        // 5. 移除连续的空格（超过2个）
        cleaned = cleaned.replace(Regex(" {3,}"), " ")
        
        // 6. 处理特殊情况：移除代码块前后多余的空行
        cleaned = cleaned.replace(Regex("\n{2,}```"), "\n```")
        cleaned = cleaned.replace(Regex("```\n{2,}"), "```\n")
        
        // 7. 处理列表项前后的空白
        cleaned = cleaned.replace(Regex("\n{2,}([\\-\\*\\+]\\s)"), "\n$1")
        cleaned = cleaned.replace(Regex("\n{2,}(\\d+\\.\\s)"), "\n$1")
        
        // 8. 移除文本开头和结尾的多余空白
        cleaned = cleaned.trim()
        
        // 9. 确保不会产生完全空白的结果
        if (cleaned.isBlank()) return ""
        
        return cleaned
    }
    
    /**
     * 清理缓存
     */
    private fun cleanupCache() {
        if (formatConfig.enableCaching) {
            // 如果缓存超过最大大小，清理最旧的条目
            if (preprocessingCache.size > formatConfig.maxCacheSize) {
                val toRemove = preprocessingCache.size - formatConfig.maxCacheSize / 2
                preprocessingCache.keys.take(toRemove).forEach { preprocessingCache.remove(it) }
            }
        }
    }
}
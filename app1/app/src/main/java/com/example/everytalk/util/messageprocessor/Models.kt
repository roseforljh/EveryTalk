package com.example.everytalk.util.messageprocessor

import com.example.everytalk.data.DataClass.WebSearchResult

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
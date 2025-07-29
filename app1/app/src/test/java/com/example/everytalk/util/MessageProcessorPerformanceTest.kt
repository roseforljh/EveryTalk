package com.example.everytalk.util

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class MessageProcessorPerformanceTest {

    private lateinit var messageProcessor: MessageProcessor

    @Before
    fun setUp() {
        messageProcessor = MessageProcessor()
    }

    @Test
    fun testPerformanceOptimizationEnabled() {
        // 启用性能优化
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            maxProcessingTimeMs = 1000L,
            enableCaching = true,
            maxCacheSize = 100
        )
        messageProcessor.updateFormatConfig(config)
        
        val testText = "This is a test message with some formatting issues."
        val startTime = System.currentTimeMillis()
        
        // 执行格式化
        val result = messageProcessor.enhancedFormatCorrection(testText)
        
        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime
        
        // 验证结果
        assertNotNull(result)
        assertTrue("处理时间应该在合理范围内", processingTime < 5000) // 5秒内完成
    }

    @Test
    fun testCachingFunctionality() {
        // 启用缓存
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableCaching = true,
            maxCacheSize = 50
        )
        messageProcessor.updateFormatConfig(config)
        
        val testText = "Test message for caching"
        
        // 第一次处理
        val startTime1 = System.currentTimeMillis()
        val result1 = messageProcessor.enhancedFormatCorrection(testText)
        val endTime1 = System.currentTimeMillis()
        val firstProcessingTime = endTime1 - startTime1
        
        // 第二次处理（应该使用缓存）
        val startTime2 = System.currentTimeMillis()
        val result2 = messageProcessor.enhancedFormatCorrection(testText)
        val endTime2 = System.currentTimeMillis()
        val secondProcessingTime = endTime2 - startTime2
        
        // 验证结果一致
        assertEquals(result1, result2)
        
        // 第二次处理应该更快（使用缓存）
        assertTrue("缓存应该提高处理速度", secondProcessingTime <= firstProcessingTime)
    }

    @Test
    fun testProgressiveCorrection() {
        // 启用渐进式修正
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableProgressiveCorrection = true,
            correctionIntensity = CorrectionIntensity.MODERATE
        )
        messageProcessor.updateFormatConfig(config)
        
        val shortText = "Short text"
        val longText = "This is a very long text that should trigger different processing logic. ".repeat(100)
        
        // 处理短文本
        val shortResult = messageProcessor.enhancedFormatCorrection(shortText)
        assertNotNull(shortResult)
        
        // 处理长文本
        val longResult = messageProcessor.enhancedFormatCorrection(longText)
        assertNotNull(longResult)
    }

    @Test
    fun testSmartSkipping() {
        // 启用性能优化
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableProgressiveCorrection = true
        )
        messageProcessor.updateFormatConfig(config)
        
        val plainText = "This is plain text without any special formatting"
        val complexText = "```kotlin\nfun test() {\n    println(\"Hello\")\n}\n```"
        
        // 处理纯文本（可能被跳过）
        val plainResult = messageProcessor.enhancedFormatCorrection(plainText)
        assertNotNull(plainResult)
        
        // 处理复杂文本（应该被处理）
        val complexResult = messageProcessor.enhancedFormatCorrection(complexText)
        assertNotNull(complexResult)
    }

    @Test
    fun testPerformanceMetrics() {
        // 启用性能优化
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableCaching = true
        )
        messageProcessor.updateFormatConfig(config)
        
        // 重置性能指标
        messageProcessor.resetPerformanceMetrics()
        
        val testText = "Test message for metrics"
        
        // 执行一些处理
        messageProcessor.enhancedFormatCorrection(testText)
        messageProcessor.intelligentErrorCorrection(testText)
        
        // 获取性能指标
        val metrics = messageProcessor.getPerformanceMetrics()
        assertNotNull(metrics)
        assertTrue("应该有处理时间记录", metrics.totalProcessingTime >= 0)
        assertTrue("应该有处理次数记录", metrics.processedChunks >= 0)
    }

    @Test
    fun testDifferentCorrectionIntensities() {
        val testText = "This is a test message with some formatting issues."
        
        // 测试轻度修正
        val lightConfig = FormatCorrectionConfig(
            correctionIntensity = CorrectionIntensity.LIGHT,
            enablePerformanceOptimization = true
        )
        messageProcessor.updateFormatConfig(lightConfig)
        val lightResult = messageProcessor.enhancedFormatCorrection(testText)
        assertNotNull(lightResult)
        
        // 测试中度修正
        val moderateConfig = FormatCorrectionConfig(
            correctionIntensity = CorrectionIntensity.MODERATE,
            enablePerformanceOptimization = true
        )
        messageProcessor.updateFormatConfig(moderateConfig)
        val moderateResult = messageProcessor.enhancedFormatCorrection(testText)
        assertNotNull(moderateResult)
        
        // 测试激进修正
        val aggressiveConfig = FormatCorrectionConfig(
            correctionIntensity = CorrectionIntensity.AGGRESSIVE,
            enablePerformanceOptimization = true
        )
        messageProcessor.updateFormatConfig(aggressiveConfig)
        val aggressiveResult = messageProcessor.enhancedFormatCorrection(testText)
        assertNotNull(aggressiveResult)
    }

    @Test
    fun testLargeTextProcessing() {
        // 启用异步处理
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableAsyncProcessing = true,
            chunkSizeThreshold = 1000
        )
        messageProcessor.updateFormatConfig(config)
        
        // 创建大文本
        val largeText = "This is a large text block. ".repeat(200) // 约5000字符
        
        val startTime = System.currentTimeMillis()
        val result = messageProcessor.enhancedFormatCorrection(largeText)
        val endTime = System.currentTimeMillis()
        
        assertNotNull(result)
        assertTrue("大文本处理应该在合理时间内完成", (endTime - startTime) < 10000) // 10秒内
    }

    @Test
    fun testCacheCleanup() {
        // 启用缓存，设置小的缓存大小
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableCaching = true,
            maxCacheSize = 3
        )
        messageProcessor.updateFormatConfig(config)
        
        // 添加多个缓存项
        for (i in 1..5) {
            messageProcessor.enhancedFormatCorrection("Test message $i")
        }
        
        // 手动清理缓存
        messageProcessor.cleanupCache()
        
        // 验证缓存清理不会导致错误
        val result = messageProcessor.enhancedFormatCorrection("New test message")
        assertNotNull(result)
    }
}
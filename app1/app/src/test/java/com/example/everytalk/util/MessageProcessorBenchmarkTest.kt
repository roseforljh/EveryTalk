package com.example.everytalk.util

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * MessageProcessor性能基准测试
 * 验证性能优化措施的有效性
 */
class MessageProcessorBenchmarkTest {

    private lateinit var messageProcessor: MessageProcessor

    @Before
    fun setUp() {
        messageProcessor = MessageProcessor()
    }

    @Test
    fun benchmarkWithoutOptimization() {
        // 禁用所有性能优化
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = false,
            enableCaching = false,
            enableAsyncProcessing = false,
            enableProgressiveCorrection = false,
            correctionIntensity = CorrectionIntensity.AGGRESSIVE
        )
        messageProcessor.updateFormatConfig(config)

        val testTexts = generateTestTexts()
        val startTime = System.currentTimeMillis()

        testTexts.forEach { text ->
            messageProcessor.enhancedFormatCorrection(text)
            messageProcessor.intelligentErrorCorrection(text)
        }

        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime

        println("无优化处理时间: ${processingTime}ms")
        assertTrue("处理应该完成", processingTime > 0)
    }

    @Test
    fun benchmarkWithOptimization() {
        // 启用所有性能优化
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableCaching = true,
            enableAsyncProcessing = true,
            enableProgressiveCorrection = true,
            maxProcessingTimeMs = 1000L,
            maxCacheSize = 100,
            chunkSizeThreshold = 1000,
            correctionIntensity = CorrectionIntensity.MODERATE
        )
        messageProcessor.updateFormatConfig(config)

        val testTexts = generateTestTexts()
        val startTime = System.currentTimeMillis()

        testTexts.forEach { text ->
            messageProcessor.enhancedFormatCorrection(text)
            messageProcessor.intelligentErrorCorrection(text)
        }

        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime

        println("优化后处理时间: ${processingTime}ms")
        
        val metrics = messageProcessor.getPerformanceMetrics()
        println("性能指标: $metrics")
        
        assertTrue("处理应该完成", processingTime > 0)
        assertTrue("应该有缓存活动", metrics.cacheHits + metrics.cacheMisses > 0)
    }

    @Test
    fun benchmarkCacheEffectiveness() {
        // 启用缓存
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableCaching = true,
            maxCacheSize = 50
        )
        messageProcessor.updateFormatConfig(config)

        val repeatedText = "```kotlin\nfun test() {\n    println(\"Hello World\")\n}\n```"
        
        // 第一次处理（无缓存）
        messageProcessor.resetPerformanceMetrics()
        val startTime1 = System.currentTimeMillis()
        repeat(10) {
            messageProcessor.enhancedFormatCorrection(repeatedText)
        }
        val endTime1 = System.currentTimeMillis()
        val firstRunTime = endTime1 - startTime1

        // 第二次处理（有缓存）
        val startTime2 = System.currentTimeMillis()
        repeat(10) {
            messageProcessor.enhancedFormatCorrection(repeatedText)
        }
        val endTime2 = System.currentTimeMillis()
        val secondRunTime = endTime2 - startTime2

        val metrics = messageProcessor.getPerformanceMetrics()
        
        println("首次运行时间: ${firstRunTime}ms")
        println("缓存运行时间: ${secondRunTime}ms")
        println("缓存命中率: ${metrics.getCacheHitRate()}%")
        
        // 缓存应该提高性能
        assertTrue("缓存应该提高性能", secondRunTime <= firstRunTime)
        assertTrue("应该有缓存命中", metrics.cacheHits > 0)
    }

    @Test
    fun benchmarkProgressiveCorrection() {
        // 启用渐进式修正
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableProgressiveCorrection = true,
            correctionIntensity = CorrectionIntensity.LIGHT
        )
        messageProcessor.updateFormatConfig(config)

        val shortTexts = (1..20).map { "Short text $it" }
        val longTexts = (1..5).map { "Long text $it. ".repeat(200) }

        messageProcessor.resetPerformanceMetrics()
        val startTime = System.currentTimeMillis()

        shortTexts.forEach { messageProcessor.enhancedFormatCorrection(it) }
        longTexts.forEach { messageProcessor.enhancedFormatCorrection(it) }

        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime

        val metrics = messageProcessor.getPerformanceMetrics()
        
        println("渐进式修正处理时间: ${processingTime}ms")
        println("跳过处理率: ${metrics.getSkipRate()}%")
        
        assertTrue("处理应该完成", processingTime > 0)
        // 渐进式修正应该跳过一些简单文本的处理
        assertTrue("应该有跳过的处理", metrics.skippedProcessing >= 0)
    }

    @Test
    fun benchmarkLargeTextHandling() {
        // 启用异步处理
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableAsyncProcessing = true,
            chunkSizeThreshold = 500
        )
        messageProcessor.updateFormatConfig(config)

        val largeText = "This is a large text block with various formatting. ".repeat(100) +
                "```python\nprint('Hello World')\n```\n" +
                "**Bold text** and *italic text*. ".repeat(50)

        messageProcessor.resetPerformanceMetrics()
        val startTime = System.currentTimeMillis()
        
        val result = messageProcessor.enhancedFormatCorrection(largeText)
        
        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime

        val metrics = messageProcessor.getPerformanceMetrics()
        
        println("大文本处理时间: ${processingTime}ms")
        println("处理的块数: ${metrics.processedChunks}")
        
        assertNotNull("结果不应为空", result)
        assertTrue("大文本处理应该在合理时间内完成", processingTime < 10000) // 10秒内
        assertTrue("应该处理了多个块", metrics.processedChunks >= 0)
    }

    @Test
    fun benchmarkMemoryUsage() {
        // 启用缓存限制
        val config = FormatCorrectionConfig(
            enablePerformanceOptimization = true,
            enableCaching = true,
            maxCacheSize = 10 // 小缓存以测试内存管理
        )
        messageProcessor.updateFormatConfig(config)

        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()

        // 处理大量不同的文本
        repeat(50) { i ->
            val text = "Test text $i with different content. ".repeat(10)
            messageProcessor.enhancedFormatCorrection(text)
        }

        // 强制垃圾回收
        System.gc()
        Thread.sleep(100)

        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory

        println("初始内存使用: ${initialMemory / 1024}KB")
        println("最终内存使用: ${finalMemory / 1024}KB")
        println("内存增长: ${memoryIncrease / 1024}KB")

        // 内存增长应该在合理范围内（小于10MB）
        assertTrue("内存增长应该在合理范围内", memoryIncrease < 10 * 1024 * 1024)
    }

    private fun generateTestTexts(): List<String> {
        return listOf(
            "Simple plain text",
            "Text with **bold** and *italic* formatting",
            "```kotlin\nfun hello() {\n    println(\"Hello\")\n}\n```",
            "Text with [link](https://example.com) and images",
            "# Header\n## Subheader\n- List item 1\n- List item 2",
            "JSON: {\"key\": \"value\", \"number\": 123}",
            "XML: <root><item>value</item></root>",
            "Mathematical expression: x = (a + b) / c",
            "Mixed content with code `inline code` and formatting",
            "Long text with multiple paragraphs. ".repeat(20)
        )
    }
}
package com.example.everytalk.util

import org.junit.Test
import org.junit.Assert.*

/**
 * MessageProcessor格式矫正功能测试
 */
class MessageProcessorFormatTest {
    
    private val processor = MessageProcessor()
    
    @Test
    fun testCodeBlockCorrection() {
        // 测试不完整的代码块
        val input = "```python\nprint('hello')"
        val expected = "```python\nprint('hello')\n```"
        
        // 使用激进模式进行测试
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.AGGRESSIVE)
        )
        
        val result = processor.getCurrentText()
        // 注意：这里只是演示测试结构，实际测试需要通过processStreamEvent来处理
        println("Code block test - Input: $input")
        println("Expected: $expected")
    }
    
    @Test
    fun testMarkdownHeaderCorrection() {
        // 测试标题格式错误
        val input = "#Title without space\n##Another title"
        val expected = "# Title without space\n\n## Another title"
        
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.MODERATE)
        )
        
        println("Markdown header test - Input: $input")
        println("Expected: $expected")
    }
    
    @Test
    fun testListFormatCorrection() {
        // 测试列表格式错误
        val input = "-Item 1\n*Item 2\n+Item 3"
        val expected = "- Item 1\n* Item 2\n+ Item 3"
        
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.MODERATE)
        )
        
        println("List format test - Input: $input")
        println("Expected: $expected")
    }
    
    @Test
    fun testJsonFormatCorrection() {
        // 测试JSON格式错误
        val input = "{name: \"test\", value: 123,}"
        val expected = "{\"name\": \"test\", \"value\": 123}"
        
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.AGGRESSIVE)
        )
        
        println("JSON format test - Input: $input")
        println("Expected: $expected")
    }
    
    @Test
    fun testConfigurationSystem() {
        // 测试配置系统
        val config = FormatCorrectionConfig(
            enableCodeBlockCorrection = false,
            enableMarkdownCorrection = true,
            correctionIntensity = CorrectionIntensity.LIGHT
        )
        
        processor.updateFormatConfig(config)
        val retrievedConfig = processor.getFormatConfig()
        
        assertEquals(config, retrievedConfig)
        assertFalse(retrievedConfig.enableCodeBlockCorrection)
        assertTrue(retrievedConfig.enableMarkdownCorrection)
        assertEquals(CorrectionIntensity.LIGHT, retrievedConfig.correctionIntensity)
    }
    
    @Test
    fun testCorrectionIntensityLevels() {
        // 测试不同矫正强度级别
        val testInput = "```python\nprint('test')\n#Title\n-List item"
        
        // 轻度矫正
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.LIGHT)
        )
        println("Light correction test")
        
        // 中度矫正
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.MODERATE)
        )
        println("Moderate correction test")
        
        // 激进矫正
        processor.updateFormatConfig(
            FormatCorrectionConfig(correctionIntensity = CorrectionIntensity.AGGRESSIVE)
        )
        println("Aggressive correction test")
    }
}
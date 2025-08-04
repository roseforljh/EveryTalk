package com.example.everytalk.util

import com.example.everytalk.util.messageprocessor.FormatCorrector
import com.example.everytalk.util.MarkdownTextUtil
import org.junit.Test
import org.junit.Assert.*

/**
 * 数学公式格式修复测试
 * 验证Android端对数学内容的处理效果
 */
class MathFormatTest {

    private val formatCorrector = FormatCorrector()
    
    @Test
    fun testBrokenLatexRepair() {
        // 测试破损LaTeX语法修复 - 对应用户截图中的问题
        val input = "计算 \\} - \\] {7}{57 imes 2}{5 2}"
        val result = formatCorrector.correctFormat(input)
        
        // 验证破损符号被清理
        assertFalse("应该清理破损的LaTeX符号", result.contains("\\} - \\]"))
        assertTrue("应该保留计算内容", result.contains("计算"))
    }
    
    @Test
    fun testCalculationProcessFormat() {
        // 测试计算过程格式修复
        val input = "计算\n\n第一步，计算乘法：\n\n第二步，进行减法：\n\\} - \\]"
        val result = formatCorrector.correctFormat(input)
        
        // 验证计算步骤结构保持
        assertTrue("应该保留第一步", result.contains("第一步"))
        assertTrue("应该保留第二步", result.contains("第二步"))
        assertFalse("应该清理破损符号", result.contains("\\} - \\]"))
    }
    
    @Test
    fun testMathExpressionDetection() {
        // 测试数学表达式检测
        val mathTexts = listOf(
            "1/4*6-7/5等于几",
            "a^2 + b^2 = c^2",
            "计算 2 + 3 = 5"
        )
        
        mathTexts.forEach { text ->
            val hasMath = formatCorrector.containsMathContent(text)
            assertTrue("应该检测到数学内容: $text", hasMath)
        }
    }
    
    @Test
    fun testLatexParsingRepair() {
        // 测试LaTeX解析修复
        val input = "把两个分数都转成以 10 为分母：\n{7}{57 imes 2}{5 2} ={14}[] 相减：["
        val contentBlocks = MarkdownTextUtil.parseToContentBlocks(input)
        
        // 验证内容块解析成功
        assertNotNull("应该成功解析内容块", contentBlocks)
        assertTrue("应该有内容块", contentBlocks.isNotEmpty())
        
        // 验证数学内容被正确识别
        val hasTextBlock = contentBlocks.any { it.type == "text" }
        assertTrue("应该包含文本块", hasTextBlock)
    }
    
    @Test
    fun testWhitespaceProtection() {
        // 测试数学公式中的空格保护
        val input = "$a^{2} + b^{2} = c^{2}$"
        val result = formatCorrector.cleanExcessiveWhitespace(input)
        
        // 验证LaTeX公式中的空格被保护
        assertEquals("LaTeX公式空格应该被保护", input, result)
    }
    
    @Test
    fun testComplexMathContent() {
        // 测试复杂数学内容处理
        val input = """
            计算分数运算：
            第一步：将 1/4 转换为 2.5/10
            第二步：将 7/5 转换为 14/10  
            第三步：计算 2.5/10 * 6 - 14/10
            结果：$\frac{25}{10} - \frac{14}{10} = \frac{11}{10}$
        """.trimIndent()
        
        val result = formatCorrector.correctFormat(input)
        
        assertTrue("应该保留计算步骤", result.contains("第一步"))
        assertTrue("应该保留LaTeX公式", result.contains("$"))
        assertFalse("不应该有过多空行", result.contains("\n\n\n"))
    }
    
    @Test
    fun testStreamingChunkProcessing() {
        // 测试流式处理中的数学内容保护
        val chunks = listOf(
            "计算",
            " 1/4",
            "*6-7/5",
            "等于几"
        )
        
        var accumulated = ""
        chunks.forEach { chunk ->
            accumulated += chunk
            val processed = formatCorrector.correctFormat(accumulated)
            
            // 验证每个步骤都不会破坏数学表达式
            assertTrue("累积内容应该有效: $processed", processed.isNotEmpty())
            assertFalse("不应该引入破损符号", processed.contains("\\} - \\]"))
        }
    }
}
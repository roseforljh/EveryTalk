package com.example.everytalk.util

import com.example.everytalk.ui.components.MarkdownPart
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import org.junit.Assert.*
import org.junit.Test

/**
 * Markdown 数学公式解析行为测试
 */
class MarkdownParserMathTest {

    @Test
    fun parse_inline_math_basic() {
        val md = "这是公式 \$a^2 + b^2 = c^2\$ 在行内。"
        val parts = parseMarkdownParts(md)
        assertTrue(
            "应识别出行内公式",
            parts.any { it is MarkdownPart.InlineMath && (it as MarkdownPart.InlineMath).latex.contains("a^2") }
        )
    }

    @Test
    fun parse_display_math_basic() {
        val md = "推导如下：\n\n\$\$\\frac{a}{b}\$\$\n\n结束"
        val parts = parseMarkdownParts(md)
        assertTrue(
            "应识别出块级公式",
            parts.any { it is MarkdownPart.MathBlock && (it as MarkdownPart.MathBlock).latex.contains("\\frac") }
        )
    }

    @Test
    fun ignore_currency_and_prices() {
        val md = "价格是 \$20，另外还有\$30 的优惠券。"
        val parts = parseMarkdownParts(md)
        assertTrue(
            "金额不应被识别为数学公式",
            parts.none { it is MarkdownPart.InlineMath || it is MarkdownPart.MathBlock }
        )
    }

    @Test
    fun escaped_dollar_not_math() {
        val md = "价格是 \\$20 美元。"
        val parts = parseMarkdownParts(md)
        assertTrue(
            "转义的美元符号不应被识别为数学公式",
            parts.none { it is MarkdownPart.InlineMath || it is MarkdownPart.MathBlock }
        )
    }

    @Test
    fun respect_code_fence_with_dollar() {
        val md = "示例：\n```bash\necho \$PATH\n```\n并非数学。"
        val parts = parseMarkdownParts(md)
        assertTrue("应识别出代码块", parts.any { it is MarkdownPart.CodeBlock })
        assertTrue(
            "代码块中的美元符不应触发数学解析",
            parts.none { it is MarkdownPart.InlineMath || it is MarkdownPart.MathBlock }
        )
    }

    @Test
    fun bracket_delimiters_supported() {
        val md = "这里有 \\(x^2+y^2\\) 和 \\[\\int_0^1 x dx\\]"
        val parts = parseMarkdownParts(md)
        assertTrue(
            "应识别 \\( ... \\) 为行内公式",
            parts.any { it is MarkdownPart.InlineMath && (it as MarkdownPart.InlineMath).latex.contains("x^2+y^2") }
        )
        assertTrue(
            "应识别 \\[ ... \\] 为块级公式",
            parts.any { it is MarkdownPart.MathBlock && (it as MarkdownPart.MathBlock).latex.contains("\\int_0^1") }
        )
    }
}
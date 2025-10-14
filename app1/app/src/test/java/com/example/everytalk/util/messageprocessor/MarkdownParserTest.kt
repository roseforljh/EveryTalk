package com.example.everytalk.util.messageprocessor

import  com.example.everytalk.ui.components.MarkdownPart
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `inline dollar math should be parsed`() {
        val text = "这是爱因斯坦公式 \\\\$2\\\\$3"
        val parts = parseMarkdownParts(text)
        assertTrue(parts.any { it is MarkdownPart.MathBlock && !it.displayMode })
    }

    @Test
    fun `inline dollar math adjacent to Chinese should be parsed`() {
        val text = "中文\\$\\alpha\\$中文"
        val parts = parseMarkdownParts(text)
        assertTrue(parts.any { it is MarkdownPart.MathBlock && !it.displayMode })
    }

    @Test
    fun `latex inline parentheses should be parsed`() {
        val text = "欧拉公式 \\(e^{i\\pi}+1=0\\)"
        val parts = parseMarkdownParts(text)
        assertTrue(parts.any { it is MarkdownPart.MathBlock && !it.displayMode })
    }

    @Test
    fun `latex block brackets should be parsed`() {
        val text = "\\[ \\int_{-\\infty}^{\\infty} e^{-x^2} \\, dx = \\sqrt{\\pi} \\]"
        val parts = parseMarkdownParts(text)
        assertTrue(parts.any { it is MarkdownPart.MathBlock && it.displayMode })
    }

    @Test
    fun `double dollar block should be parsed`() {
        val text = "\\$\\$ \\sum_{k=1}^{n} k = \\frac{n(n+1)}{2} \\$\\$"
        val parts = parseMarkdownParts(text)
        assertTrue(parts.any { it is MarkdownPart.MathBlock && it.displayMode })
    }

    @Test
    fun ````math fenced code should be parsed as display math`() {
        val text = """
            ```math
            \\frac{a}{b}
            ```
        """.trimIndent()
        val parts = parseMarkdownParts(text)
        assertTrue(parts.any { it is MarkdownPart.MathBlock && it.displayMode })
    }
}
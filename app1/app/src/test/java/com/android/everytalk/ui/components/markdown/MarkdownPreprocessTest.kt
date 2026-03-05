package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessTest {

    @Test
    fun `preprocess keeps block double dollar and converts inline single dollar math to double dollar`() {
        val input = """
            [double dollar]
            x^2 + y^2 = z^2
            [double dollar]
            同时有行内 ${'$'}SYN=1${'$'}
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output, output.contains("\$\$x^2 + y^2 = z^2\$\$"))
        assertTrue(output, output.contains("\$\$SYN=1\$\$"))
    }

    @Test
    fun `preprocess normalizes inline double dollar to renderable form`() {
        val input = "标记 \$\$SYN=1\$\$ 与 \$\$ACK=1\$\$"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("\$\$SYN=1\$\$"))
        assertTrue(output.contains("\$\$ACK=1\$\$"))
    }

    @Test
    fun `currency format should not break math formulas`() {
        val input = """
            - 第一部分：${'$'}1 - \frac{x^2}{2!} + \dots = \cos x${'$'}
            售价 ${'$'}12 或者 ${'$'}${'$'}24
        """.trimIndent()
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output, output.contains("\$\$1 - \\frac{x^2}{2!} + \\dots = \\cos x\$\$"))
        assertTrue(output, output.contains("\\\$12"))
        assertTrue(output, output.contains("\\\$24"))
    }

    @Test
    fun `sports score wrapped by single dollar should be rendered as plain score without dollar`() {
        val input = "比分可能以 ${'$'}1:0${'$'} 或 ${'$'}3：2${'$'} 形式出现"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("1:0"))
        assertTrue(output.contains("3：2"))
        assertFalse(output.contains("${'$'}1:0${'$'}"))
        assertFalse(output.contains("${'$'}3：2${'$'}"))
    }
}

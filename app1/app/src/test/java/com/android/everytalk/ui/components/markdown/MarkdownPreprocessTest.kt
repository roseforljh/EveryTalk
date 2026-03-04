package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessTest {

    @Test
    fun `preprocess keeps block double dollar and converts inline single dollar to double dollar`() {
        val input = """
            [double dollar]
            x^2 + y^2 = z^2
            [double dollar]
            同时有行内 ${'$'}SYN=1${'$'}。
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("\$\$x^2 + y^2 = z^2\$\$"))
        assertTrue(output.contains("\$\$SYN=1\$\$"))
    }

    @Test
    fun `preprocess converts inline double dollar to renderable form`() {
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

        assertTrue("Math formula should have been converted to double dollar", output.contains("\$\$1 - \\frac{x^2}{2!} + \\dots = \\cos x\$\$"))
        assertTrue("Currency should be escaped", output.contains("\\\$12"))
        assertTrue("Double currency should be escaped", output.contains("\\\$24"))
    }

    @Test
    fun `sports score wrapped by single dollar should be rendered as plain score without dollar`() {
        val input = "比分可能会以 ${'$'}1:0${'$'} 或 ${'$'}3：2${'$'} 的比分再次捧起大耳朵杯"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue("Sports score should be kept as plain text", output.contains("1:0"))
        assertTrue("Full-width colon sports score should be kept as plain text", output.contains("3：2"))
        assertTrue("Dollar wrapper should be removed for score", !output.contains("${'$'}1:0${'$'}"))
        assertTrue("Dollar wrapper should be removed for full-width score", !output.contains("${'$'}3：2${'$'}"))
    }
}

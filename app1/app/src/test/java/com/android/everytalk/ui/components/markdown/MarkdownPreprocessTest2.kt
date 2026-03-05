package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessTest2 {

    @Test
    fun `inline math and currency should coexist without corruption`() {
        val input = """
            - 第一部分：${'$'}1 - \frac{x^2}{2!} + \dots = \cos x${'$'}
            售价 ${'$'}12 或者 ${'$'}${'$'}24
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output, output.contains("\$\$1 - \\frac{x^2}{2!} + \\dots = \\cos x\$\$"))
        assertTrue(output, output.contains("\\\$12"))
        assertTrue(output, output.contains("\\\$24"))
    }
}

package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPreprocessPureMathBlockTest {

    @Test
    fun `pure dollar math block should bypass generic markdown preprocessing`() {
        val input = """

            $$\frac{a+b}{2} > 10 \text{ and } $12$$

        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)
        assertEquals(input.trim(), output)
    }

    @Test
    fun `pure bracket math block should bypass generic markdown preprocessing`() {
        val input = """

            \[\sum_{i=1}^{n} x_i = #tag and $99\]

        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)
        assertEquals(input.trim(), output)
    }
}


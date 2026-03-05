package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessStreamingMathBoundaryTest {

    @Test
    fun `unclosed block math delimiter should be escaped`() {
        val input = "${'$'}${'$'}E=mc^2"
        val output = preprocessAiMarkdown(input, isStreaming = true)

        assertEquals("\\${'$'}\\${'$'}E=mc^2", output)
    }

    @Test
    fun `closed block math delimiter should remain renderable`() {
        val input = "${'$'}${'$'}E=mc^2${'$'}${'$'}"
        val output = preprocessAiMarkdown(input, isStreaming = true)

        assertEquals("${'$'}${'$'}E=mc^2${'$'}${'$'}", output)
    }

    @Test
    fun `unclosed inline math delimiter should be escaped`() {
        val input = "calc: ${'$'}E=mc^2"
        val output = preprocessAiMarkdown(input, isStreaming = true)

        assertEquals("calc: \\${'$'}E=mc^2", output)
    }

    @Test
    fun `currency should not be treated as math in streaming`() {
        val input = "price is ${'$'}12"
        val output = preprocessAiMarkdown(input, isStreaming = true)

        assertTrue(output.contains("\\${'$'}12"))
    }
}

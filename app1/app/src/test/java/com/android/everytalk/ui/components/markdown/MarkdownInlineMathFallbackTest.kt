package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineMathFallbackTest {

    @Test
    fun `simple inline math symbols should fallback to unicode glyphs`() {
        val input = "公式里的 ${'$'}\\hbar${'$'}、${'$'}\\partial${'$'}、${'$'}\\nabla${'$'} 和 ${'$'}\\nabla^2${'$'} 需要可见"

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("ℏ"))
        assertTrue(output.contains("∂"))
        assertTrue(output.contains("∇"))
        assertTrue(output.contains("∇²"))
    }
}

package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEntityAndLatexFallbackTest {

    @Test
    fun `html entities in plain text should not be shown as literal entity`() {
        val input = "限制条件: n_A &gt; 2n_B, 且 a &lt; 297"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertFalse(output.contains("&amp;gt;"))
        assertFalse(output.contains("&amp;lt;"))
        assertTrue(output.contains(">"))
        assertTrue(output.contains("<"))
    }

    @Test
    fun `double escaped html entities should also decode before final escaping`() {
        val input = "限制条件: n_A &amp;gt; 2n_B, 且 a &amp;lt; 297"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertFalse(output.contains("&amp;gt;"))
        assertFalse(output.contains("&amp;lt;"))
        assertTrue(output.contains(">"))
        assertTrue(output.contains("<"))
    }

    @Test
    fun `plain latex command outside math should degrade to readable symbol`() {
        val input = "a^3 < 33(3a)^2 \\implies a < 297"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("⇒"))
        assertFalse(output.contains("\\implies"))
    }
}

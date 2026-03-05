package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessTest3 {

    @Test
    fun `sports score wrapped by dollar should stay plain text`() {
        val input = "比分可能是 ${'$'}1:0${'$'} 或 ${'$'}3：2${'$'}"
        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("1:0"))
        assertTrue(output.contains("3：2"))
        assertFalse(output.contains("${'$'}1:0${'$'}"))
        assertFalse(output.contains("${'$'}3：2${'$'}"))
    }
}

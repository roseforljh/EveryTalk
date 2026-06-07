package com.android.everytalk.ui.components.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockCardAutoScrollTest {

    @Test
    fun `streaming code block should auto scroll when content overflows`() {
        assertTrue(
            shouldAutoScrollCodeBlockContent(
                isStreaming = true,
                isPreviewMode = false,
                maxScrollValue = 120,
            )
        )
    }

    @Test
    fun `non streaming code block should keep restored top position`() {
        assertFalse(
            shouldAutoScrollCodeBlockContent(
                isStreaming = false,
                isPreviewMode = false,
                maxScrollValue = 120,
            )
        )
    }

    @Test
    fun `preview mode should not auto scroll code content`() {
        assertFalse(
            shouldAutoScrollCodeBlockContent(
                isStreaming = true,
                isPreviewMode = true,
                maxScrollValue = 120,
            )
        )
    }

    @Test
    fun `code block without overflow should not request auto scroll`() {
        assertFalse(
            shouldAutoScrollCodeBlockContent(
                isStreaming = true,
                isPreviewMode = false,
                maxScrollValue = 0,
            )
        )
    }
}

package com.android.everytalk.ui.components.content

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeBlockCardScrollTest {

    @Test
    fun `streaming code block scrolls to bottom`() {
        assertEquals(320, resolveCodeBlockScrollTarget(isStreaming = true, maxValue = 320))
    }

    @Test
    fun `non streaming code block resets to top`() {
        assertEquals(0, resolveCodeBlockScrollTarget(isStreaming = false, maxValue = 320))
    }
}

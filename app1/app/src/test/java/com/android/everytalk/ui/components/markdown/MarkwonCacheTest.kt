package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkwonCacheTest {

    @Test
    fun `heading relative sizes follow chatgpt title scale`() {
        assertEquals(1.25f, chatGptHeadingRelativeSizeMultiplier(1), 0.001f)
        assertEquals(1.125f, chatGptHeadingRelativeSizeMultiplier(2), 0.001f)
        assertEquals(1.0f, chatGptHeadingRelativeSizeMultiplier(3), 0.001f)
        assertEquals(1.0f, chatGptHeadingRelativeSizeMultiplier(6), 0.001f)
    }
}

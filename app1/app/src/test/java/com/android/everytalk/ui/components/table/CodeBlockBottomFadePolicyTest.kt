package com.android.everytalk.ui.components.table

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockBottomFadePolicyTest {
    @Test
    fun `bottom fade only appears when code block reaches max height`() {
        val maxHeightPx = 350f

        assertFalse(shouldDrawCodeBlockBottomFade(contentHeightPx = 320, maxHeightPx = maxHeightPx))
        assertTrue(shouldDrawCodeBlockBottomFade(contentHeightPx = 350, maxHeightPx = maxHeightPx))
        assertTrue(shouldDrawCodeBlockBottomFade(contentHeightPx = 349, maxHeightPx = maxHeightPx))
    }
}

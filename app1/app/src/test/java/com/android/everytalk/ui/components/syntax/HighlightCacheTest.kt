package com.android.everytalk.ui.components.syntax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightCacheTest {

    @Test
    fun `highlight gate keeps streaming and oversized code bounded`() {
        assertTrue(HighlightCache.shouldHighlight("x".repeat(500), isStreaming = true))
        assertFalse(HighlightCache.shouldHighlight("x".repeat(501), isStreaming = true))
        assertTrue(HighlightCache.shouldHighlight("x".repeat(20_000), isStreaming = false))
        assertFalse(HighlightCache.shouldHighlight("x".repeat(20_001), isStreaming = false))
    }

    @Test
    fun `cache evicts by total content weight`() {
        HighlightCache.clear()
        try {
            val code = "x".repeat(90_000)
            val theme = SyntaxHighlightTheme.Light
            HighlightCache.highlight("first:$code", null, false, theme)
            HighlightCache.highlight("second:$code", null, false, theme)
            HighlightCache.highlight("third:$code", null, false, theme)

            assertEquals(2, HighlightCache.size())
        } finally {
            HighlightCache.clear()
        }
    }
}

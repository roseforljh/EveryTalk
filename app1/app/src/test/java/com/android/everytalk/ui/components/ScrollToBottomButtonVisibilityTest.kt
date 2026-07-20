package com.android.everytalk.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollToBottomButtonVisibilityTest {
    @Test
    fun `button remains visible whenever list can scroll forward`() {
        assertTrue(shouldShowScrollToBottomButtonForFrame(baseVisible = true))
        assertFalse(shouldShowScrollToBottomButtonForFrame(baseVisible = false))
    }

    @Test
    fun `dark theme uses explicit visible border color`() {
        assertEquals(Color.White.copy(alpha = 0.42f), scrollToBottomButtonDarkBorderColor())
        assertNull(scrollToBottomButtonBorder(isDarkTheme = false))
    }

    @Test
    fun `button uses stable fade in duration`() {
        assertEquals(360, scrollToBottomButtonFadeInMillis())
    }
}

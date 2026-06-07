package com.android.everytalk.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollToBottomButtonVisibilityTest {
    @Test
    fun `button is visible only after scrolling stops during idle hold`() {
        assertFalse(
            shouldShowScrollToBottomButtonForFrame(
                baseVisible = true,
                isScrollInProgress = true,
                idleHoldVisible = true
            )
        )

        assertTrue(
            shouldShowScrollToBottomButtonForFrame(
                baseVisible = true,
                isScrollInProgress = false,
                idleHoldVisible = true
            )
        )

        assertFalse(
            shouldShowScrollToBottomButtonForFrame(
                baseVisible = true,
                isScrollInProgress = false,
                idleHoldVisible = false
            )
        )
    }

    @Test
    fun `dark theme uses explicit visible border color`() {
        assertEquals(Color.White.copy(alpha = 0.42f), scrollToBottomButtonDarkBorderColor())
        assertNull(scrollToBottomButtonBorder(isDarkTheme = false))
    }

    @Test
    fun `button waits after scroll stops and fades in slower`() {
        assertEquals(1000L, scrollToBottomButtonAppearDelayMillis())
        assertEquals(360, scrollToBottomButtonFadeInMillis())
    }
}

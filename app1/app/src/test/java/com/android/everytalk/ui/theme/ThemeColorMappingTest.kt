package com.android.everytalk.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorMappingTest {

    @Test
    fun `dark theme primary text colors are pure white`() {
        assertEquals(Color(0xFFFFFFFF), DarkOnBackground)
        assertEquals(Color(0xFFFFFFFF), DarkOnSurface)
        assertEquals(Color(0xFFFFFFFF), DarkOnCard)
        assertEquals(Color(0xFFFFFFFF), DarkTextPrimary)
    }

    @Test
    fun `light theme primary text colors are pure black`() {
        assertEquals(Color(0xFF000000), lightThemeOnBackground())
        assertEquals(Color(0xFF000000), lightThemeOnSurface())
    }

    @Test
    fun `secondary text colors remain grayscale`() {
        assertEquals(Color(0xFF49454F), lightThemeOnSurfaceVariant())
        assertEquals(Color(0xFFBBBBBB), DarkOnSurfaceVariant)
    }
}

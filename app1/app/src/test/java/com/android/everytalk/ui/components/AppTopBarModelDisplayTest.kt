package com.android.everytalk.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTopBarModelDisplayTest {

    @Test
    fun `gpt text is black in light theme and white in dark theme`() {
        assertEquals(
            TopBarModelDisplayInfo(label = "GPT", textColor = Color.Black),
            resolveTopBarModelDisplayInfo("gpt-5", isDark = false)
        )
        assertEquals(
            TopBarModelDisplayInfo(label = "GPT", textColor = Color.White),
            resolveTopBarModelDisplayInfo("gpt-5", isDark = true)
        )
    }

    @Test
    fun `grok text matches gpt colors`() {
        assertEquals(
            TopBarModelDisplayInfo(label = "Grok", textColor = Color.Black),
            resolveTopBarModelDisplayInfo("grok-4", isDark = false)
        )

        val darkInfo = resolveTopBarModelDisplayInfo("grok-4", isDark = true)

        assertEquals(TopBarModelDisplayInfo(label = "Grok", textColor = Color.White), darkInfo)
    }

    @Test
    fun `grok and gpt use same theme colors`() {
        assertEquals(
            resolveTopBarModelDisplayInfo("gpt-5", isDark = false).textColor,
            resolveTopBarModelDisplayInfo("grok-4", isDark = false).textColor
        )
        assertEquals(
            resolveTopBarModelDisplayInfo("gpt-5", isDark = true).textColor,
            resolveTopBarModelDisplayInfo("grok-4", isDark = true).textColor
        )
    }
}

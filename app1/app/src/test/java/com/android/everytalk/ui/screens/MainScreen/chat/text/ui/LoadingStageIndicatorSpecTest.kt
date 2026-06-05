package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingStageIndicatorSpecTest {

    @Test
    fun `loading stage falls back to default text when item text is blank`() {
        assertEquals("连接中", resolveLoadingStageDisplayText(null, "连接中"))
        assertEquals("连接中", resolveLoadingStageDisplayText("", "连接中"))
    }

    @Test
    fun `loading stage keeps explicit text when present`() {
        assertEquals("我在整理思路…", resolveLoadingStageDisplayText("我在整理思路…", "连接中"))
    }

    @Test
    fun `loading stage visual spec uses stable fixed height`() {
        assertTrue(loadingStageViewportHeightDp() > 0f)
    }

    @Test
    fun `loading stage mask height is smaller than viewport`() {
        assertTrue(loadingStageMaskHeightDp() < loadingStageViewportHeightDp())
    }

    @Test
    fun `loading stage indicator dot size is positive`() {
        assertTrue(loadingStageBreathingDotSizeDp() > 0f)
    }

    @Test
    fun `loading stage dot is black in light theme`() {
        assertEquals(Color.Black, loadingStageDotColor(isLightTheme = true))
    }

    @Test
    fun `loading stage background is transparent`() {
        assertEquals(Color.Transparent, loadingStageBackgroundColor())
    }
}

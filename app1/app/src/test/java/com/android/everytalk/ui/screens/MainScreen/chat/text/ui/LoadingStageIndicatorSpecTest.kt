package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingStageIndicatorSpecTest {

    @Test
    fun `loading stage stays textless when backend progress is blank`() {
        assertEquals("", resolveLoadingStageDisplayText(null))
        assertEquals("", resolveLoadingStageDisplayText(""))
    }

    @Test
    fun `loading stage keeps explicit text when present`() {
        assertEquals("后端进度：正在准备结果", resolveLoadingStageDisplayText("后端进度：正在准备结果"))
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

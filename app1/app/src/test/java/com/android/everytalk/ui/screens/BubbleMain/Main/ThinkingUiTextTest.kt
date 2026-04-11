package com.android.everytalk.ui.screens.BubbleMain.Main

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingUiTextTest {

    @Test
    fun `reasoning placeholder uses neutral text while streaming`() {
        assertEquals(
            "正在思考...",
            reasoningPlaceholderText(displayedReasoningText = "", isReasoningStreaming = true)
        )
    }

    @Test
    fun `reasoning placeholder prefers actual reasoning text`() {
        assertEquals(
            "分析中",
            reasoningPlaceholderText(displayedReasoningText = "分析中", isReasoningStreaming = true)
        )
    }

    @Test
    fun `reasoning placeholder stays empty when not streaming and no text`() {
        assertEquals(
            "",
            reasoningPlaceholderText(displayedReasoningText = "", isReasoningStreaming = false)
        )
    }
}

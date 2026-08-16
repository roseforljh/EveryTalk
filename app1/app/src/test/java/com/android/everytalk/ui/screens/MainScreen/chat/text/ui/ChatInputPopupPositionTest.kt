package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputPopupPositionTest {
    @Test
    fun `键盘显示时悬浮卡贴住输入框锚点上方`() {
        val anchored = resolveChatInputPopupY(
            windowHeightPx = 1200,
            anchorTopPx = 1080,
            inputContentHeightPx = 720,
            popupHeightPx = 120,
            marginPx = 8,
        )
        val oldHeightBased = resolveChatInputPopupY(
            windowHeightPx = 1200,
            anchorTopPx = 0,
            inputContentHeightPx = 720,
            popupHeightPx = 120,
            marginPx = 8,
        )

        assertEquals(952, anchored)
        assertTrue(anchored > oldHeightBased)
    }

    @Test
    fun `悬浮卡不会越过屏幕顶部`() {
        assertEquals(
            0,
            resolveChatInputPopupY(
                windowHeightPx = 600,
                anchorTopPx = 40,
                inputContentHeightPx = 100,
                popupHeightPx = 180,
                marginPx = 8,
            ),
        )
    }
}

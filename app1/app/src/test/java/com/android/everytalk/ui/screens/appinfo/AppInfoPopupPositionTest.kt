package com.android.everytalk.ui.screens.appinfo

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoPopupPositionTest {
    @Test
    fun `弹层右边缘贴着触发行而不是屏幕左侧`() {
        assertEquals(
            IntOffset(169, 356),
            appInfoPopupOffset(IntRect(16, 300, 395, 350), IntSize(411, 900), IntSize(226, 178), 6, 8),
        )
    }

    @Test
    fun `底部不足时向上展开`() {
        assertEquals(
            IntOffset(169, 516),
            appInfoPopupOffset(IntRect(16, 700, 395, 750), IntSize(411, 900), IntSize(226, 178), 6, 8),
        )
    }

    @Test
    fun `十色长卡片两侧不足时保持在窗口内`() {
        assertEquals(
            IntOffset(169, 336),
            appInfoPopupOffset(IntRect(16, 400, 395, 450), IntSize(411, 900), IntSize(226, 556), 6, 8),
        )
    }
}

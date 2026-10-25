package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 液桥随圆心距单调变细，间隙达到半个半径时正好断开，完全分离态不能残留颈部。 */
class LiquidComposerNeckTest {
    @Test fun `液桥从满宽逐渐收细并在约定间隙断开`() {
        val r = 22f
        assertEquals(r, liquidNeckWaist(0.0001f, r), 0.01f)
        val samples = (1..54).map { liquidNeckWaist(it.toFloat(), r) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0f, liquidNeckWaist(r * 2.5f, r), 0f)
        // 完全分离时两端间隙 16dp，圆心距 60dp，必须已经断开。
        assertEquals(0f, liquidNeckWaist(60f, r), 0f)
    }
}

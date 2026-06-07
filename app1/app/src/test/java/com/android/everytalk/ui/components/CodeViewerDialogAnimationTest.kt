package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeViewerDialogAnimationTest {

    @Test
    fun `closing code viewer returns to entry scale and transparent alpha`() {
        val target = resolveCodeViewerDialogAnimationTarget(
            hasEntered = true,
            isClosing = true,
        )

        assertEquals(CODE_VIEWER_DIALOG_EDGE_SCALE, target.scale, 0.0001f)
        assertEquals(0f, target.alpha, 0.0001f)
    }

    @Test
    fun `closing code viewer alpha uses same duration as scale for reverse entry animation`() {
        assertEquals(CODE_VIEWER_DIALOG_TRANSITION_MILLIS, CODE_VIEWER_DIALOG_ALPHA_MILLIS)
    }

    @Test
    fun `code viewer disables platform dim mask so exit animation owns the visual fade`() {
        assertEquals(0f, CODE_VIEWER_DIALOG_WINDOW_DIM_AMOUNT, 0.0001f)
    }
}

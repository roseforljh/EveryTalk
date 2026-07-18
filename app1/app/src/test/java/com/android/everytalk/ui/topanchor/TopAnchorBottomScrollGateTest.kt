package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopAnchorBottomScrollGateTest {
    @Test
    fun `non user scroll is blocked during runtime even with zero reserve`() {
        assertFalse(
            shouldAllowBottomScroll(
                isUserAction = false,
                suppressesBottomScroll = true,
                isAtBottom = true,
                reason = BottomScrollReason.ImageLoaded
            )
        )
    }

    @Test
    fun `user bottom action is allowed`() {
        assertTrue(
            shouldAllowBottomScroll(
                isUserAction = true,
                suppressesBottomScroll = true,
                isAtBottom = false,
                reason = BottomScrollReason.Button
            )
        )
    }

    @Test
    fun `thread switch is allowed when no runtime suppresses bottom scroll`() {
        assertTrue(
            shouldAllowBottomScroll(
                isUserAction = false,
                suppressesBottomScroll = false,
                isAtBottom = false,
                reason = BottomScrollReason.ThreadSwitch
            )
        )
    }
}

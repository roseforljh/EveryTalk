package com.android.everytalk.ui.screens.BubbleMain.Main

import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBubbleHeightPolicyTest {

    @Test
    fun `expanded overflowing user bubble remains height constrained so collapse control stays visible`() {
        assertTrue(
            shouldConstrainUserBubbleHeight(
                sender = Sender.User,
                hasOverflow = true,
                isExpanded = true,
            )
        )
    }

    @Test
    fun `collapsed user bubble max height is lower than old forty percent cap`() {
        val maxHeight = resolveUserBubbleMaxHeightDp(
            screenHeightDp = 1000f,
            isExpanded = false,
        )

        assertEquals(320f, maxHeight, 0.0001f)
    }

    @Test
    fun `expanded user bubble still stays below full screen height`() {
        val maxHeight = resolveUserBubbleMaxHeightDp(
            screenHeightDp = 1000f,
            isExpanded = true,
        )

        assertEquals(560f, maxHeight, 0.0001f)
    }

    @Test
    fun `collapsed user bubble content does not reserve bottom space so gradient covers content`() {
        val bottomPadding = resolveUserBubbleContentBottomPaddingDp(
            sender = Sender.User,
            isExpanded = false,
        )

        assertEquals(0f, bottomPadding, 0.0001f)
    }

    @Test
    fun `expanded user bubble content reserves bottom space for collapse control`() {
        val bottomPadding = resolveUserBubbleContentBottomPaddingDp(
            sender = Sender.User,
            isExpanded = true,
        )

        assertEquals(28f, bottomPadding, 0.0001f)
    }
}

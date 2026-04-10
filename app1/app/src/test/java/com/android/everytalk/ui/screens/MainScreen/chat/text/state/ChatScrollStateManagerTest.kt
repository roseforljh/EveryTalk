package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollStateManagerTest {

    @Test
    fun `manual scroll away from bottom keeps auto scroll locked`() {
        val locked = resolvePreventAutoScroll(
            currentValue = false,
            isProgrammaticScroll = false,
            isStrictlyAtBottom = false
        )

        assertTrue(locked)
    }

    @Test
    fun `reaching bottom manually releases auto scroll lock`() {
        val locked = resolvePreventAutoScroll(
            currentValue = true,
            isProgrammaticScroll = false,
            isStrictlyAtBottom = true
        )

        assertFalse(locked)
    }

    @Test
    fun `programmatic scroll keeps existing lock state`() {
        val locked = resolvePreventAutoScroll(
            currentValue = true,
            isProgrammaticScroll = true,
            isStrictlyAtBottom = true
        )

        assertTrue(locked)
    }
}

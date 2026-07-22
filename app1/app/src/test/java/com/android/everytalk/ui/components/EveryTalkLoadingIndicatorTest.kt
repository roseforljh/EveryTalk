package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class EveryTalkLoadingIndicatorTest {
    @Test
    fun `loading elapsed text is clamped and formatted in seconds`() {
        assertEquals("0s", everyTalkLoadingElapsedText(-1L))
        assertEquals("1s", everyTalkLoadingElapsedText(1_999L))
        assertEquals("6s", everyTalkLoadingElapsedText(6_000L))
    }
}

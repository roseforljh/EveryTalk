package com.android.everytalk.statecontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderPromptCapabilityScopeTest {

    @Test
    fun `only regular text providers should receive capability selection`() {
        assertTrue(
            shouldUsePromptCapabilities(
                isImageGeneration = false,
            ),
        )
        assertTrue(
            shouldUsePromptCapabilities(
                isImageGeneration = false,
            ),
        )
        assertFalse(
            shouldUsePromptCapabilities(
                isImageGeneration = true,
            ),
        )
    }
}

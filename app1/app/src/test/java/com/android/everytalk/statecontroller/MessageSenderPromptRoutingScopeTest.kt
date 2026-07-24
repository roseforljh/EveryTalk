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
                provider = "OpenAI",
                channel = "openai",
                model = "gpt-5.4",
            ),
        )
        assertTrue(
            shouldUsePromptCapabilities(
                isImageGeneration = false,
                provider = "Gemini",
                channel = "gemini",
                model = "gemini-2.5-pro",
            ),
        )
        assertFalse(
            shouldUsePromptCapabilities(
                isImageGeneration = true,
                provider = "OpenAI",
                channel = "openai",
                model = "gpt-image-1",
            ),
        )
        assertFalse(
            shouldUsePromptCapabilities(
                isImageGeneration = false,
                provider = "OpenClaw",
                channel = "openclaw",
                model = "openclaw",
            ),
        )
    }
}

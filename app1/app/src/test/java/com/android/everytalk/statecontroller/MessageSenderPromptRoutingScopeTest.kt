package com.android.everytalk.statecontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderPromptRoutingScopeTest {

    @Test
    fun `only regular text providers should receive prompt directives`() {
        assertTrue(
            shouldDecoratePromptDirectives(
                isImageGeneration = false,
                provider = "OpenAI",
                channel = "openai",
                model = "gpt-5.4",
            ),
        )
        assertFalse(
            shouldDecoratePromptDirectives(
                isImageGeneration = true,
                provider = "OpenAI",
                channel = "openai",
                model = "gpt-image-1",
            ),
        )
        assertFalse(
            shouldDecoratePromptDirectives(
                isImageGeneration = false,
                provider = "OpenClaw",
                channel = "openclaw",
                model = "openclaw",
            ),
        )
    }
}

package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawSettingsRulesTest {

    @Test
    fun `openclaw address preview keeps gateway url unchanged`() {
        val preview = OpenClawSettingsRules.buildFullEndpointPreview(
            base = "ws://127.0.0.1:18789",
            provider = "OpenClaw",
            channel = "OpenClaw"
        )

        assertEquals("ws://127.0.0.1:18789", preview)
    }

    @Test
    fun `openclaw hint explains direct gateway connection`() {
        val hint = OpenClawSettingsRules.buildEndpointHintForPreview(
            base = "ws://127.0.0.1:18789",
            provider = "OpenClaw",
            channel = "OpenClaw"
        )

        assertEquals("OpenClaw Gateway：按输入直连，不追加任何路径", hint)
    }

    @Test
    fun `image mode openai compatible preview uses image generation endpoint`() {
        val preview = OpenClawSettingsRules.buildFullEndpointPreview(
            base = "https://api.example.com",
            provider = "OpenAI Compatible",
            channel = "OpenAI兼容",
            isImageMode = true
        )

        assertEquals("https://api.example.com/v1/images/generations", preview)
    }

    @Test
    fun `image mode preview converts chat endpoint to image generation endpoint`() {
        val preview = OpenClawSettingsRules.buildFullEndpointPreview(
            base = "https://api.example.com/v1/chat/completions",
            provider = "OpenAI Compatible",
            channel = "OpenAI兼容",
            isImageMode = true
        )

        assertEquals("https://api.example.com/v1/images/generations", preview)
    }
}

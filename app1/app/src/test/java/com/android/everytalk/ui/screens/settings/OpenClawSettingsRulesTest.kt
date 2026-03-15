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
}

package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawBridgeSettingsRulesTest {

    @Test
    fun `bridge mode preview keeps bridge url unchanged`() {
        val preview = OpenClawSettingsRules.buildFullEndpointPreview(
            base = "wss://bridge.example.com/et-bridge/ws",
            provider = "OpenClaw",
            channel = "OpenClaw",
            accessMode = "bridge"
        )

        assertEquals("wss://bridge.example.com/et-bridge/ws", preview)
    }

    @Test
    fun `bridge mode hint explains bridge connection`() {
        val hint = OpenClawSettingsRules.buildEndpointHintForPreview(
            base = "wss://bridge.example.com/et-bridge/ws",
            provider = "OpenClaw",
            channel = "OpenClaw",
            accessMode = "bridge"
        )

        assertEquals("OpenClaw Bridge：按输入连接中转服务，不追加任何路径", hint)
    }
}

package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawSettingsLabelsTest {

    @Test
    fun `openclaw channel uses gateway labels`() {
        assertEquals(
            "Gateway Address",
            OpenClawSettingsRules.addressLabelFor(provider = "OpenClaw", channel = "OpenClaw")
        )
        assertEquals(
            "Gateway Token",
            OpenClawSettingsRules.keyLabelFor(provider = "OpenClaw", channel = "OpenClaw")
        )
    }

    @Test
    fun `openclaw remote uses gateway labels and remote hint`() {
        assertEquals(
            "Gateway Address",
            OpenClawSettingsRules.addressLabelFor(provider = "OpenClaw Remote", channel = "OpenClaw")
        )
        assertEquals(
            "Gateway Token",
            OpenClawSettingsRules.keyLabelFor(provider = "OpenClaw Remote", channel = "OpenClaw")
        )
        assertEquals(
            "OpenClaw 远程控制：填写 ws:// 或 wss:// Gateway 地址，像聊天一样远程控制你的龙虾",
            OpenClawSettingsRules.buildEndpointHintForPreview(
                base = "wss://gateway.example.com",
                provider = "OpenClaw Remote",
                channel = "OpenClaw"
            )
        )
    }

    @Test
    fun `non openclaw provider keeps api labels`() {
        assertEquals(
            "API接口地址",
            OpenClawSettingsRules.addressLabelFor(provider = "openai compatible", channel = "OpenAI兼容")
        )
        assertEquals(
            "API密钥",
            OpenClawSettingsRules.keyLabelFor(provider = "openai compatible", channel = "OpenAI兼容")
        )
    }
}

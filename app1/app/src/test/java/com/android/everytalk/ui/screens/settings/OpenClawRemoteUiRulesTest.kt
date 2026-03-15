package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawRemoteUiRulesTest {

    @Test
    fun `remote provider uses dedicated save success message`() {
        assertEquals(
            "已添加远程龙虾连接",
            OpenClawSettingsRules.addSuccessMessageFor(provider = "OpenClaw Remote")
        )
    }

    @Test
    fun `remote provider rejects invalid gateway address with dedicated message`() {
        assertEquals(
            "OpenClaw Remote 仅支持 ws:// 或 wss:// Gateway 地址",
            OpenClawSettingsRules.validateRemoteConfigOrNull(
                provider = "OpenClaw Remote",
                address = "https://gateway.example.com"
            )
        )
    }

    @Test
    fun `remote provider accepts websocket gateway address`() {
        assertTrue(
            OpenClawSettingsRules.validateRemoteConfigOrNull(
                provider = "OpenClaw Remote",
                address = "wss://gateway.example.com"
            ) == null
        )
    }
}

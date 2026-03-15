package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawUiRulesTest {

    @Test
    fun `openclaw provider is not deletable`() {
        assertFalse(OpenClawSettingsRules.canDeleteProvider("OpenClaw"))
    }

    @Test
    fun `custom provider remains deletable`() {
        assertTrue(OpenClawSettingsRules.canDeleteProvider("My Custom Provider"))
    }

    @Test
    fun `openclaw group is not pinned`() {
        assertFalse(OpenClawSettingsRules.isPinnedSettingsGroup("OpenClaw"))
    }

    @Test
    fun `openclaw group keeps card editable`() {
        assertTrue(OpenClawSettingsRules.isSettingsGroupEditable("OpenClaw"))
    }

    @Test
    fun `openclaw group cannot expand models`() {
        assertFalse(OpenClawSettingsRules.canExpandSettingsModels("OpenClaw"))
    }

    @Test
    fun `regular provider group can expand models`() {
        assertTrue(OpenClawSettingsRules.canExpandSettingsModels("openai compatible"))
    }

    @Test
    fun `openclaw remote uses dedicated connection title and subtitle`() {
        assertEquals(
            "远程龙虾连接",
            OpenClawSettingsRules.displayTitleForSettingsGroup("OpenClaw Remote")
        )
        assertEquals(
            "通过 Gateway 远程控制部署在 VPS/电脑上的龙虾",
            OpenClawSettingsRules.displaySubtitleForSettingsGroup("OpenClaw Remote")
        )
    }

    @Test
    fun `openclaw remote uses gateway and token summary labels`() {
        assertEquals(
            "Gateway: wss://gateway.example.com",
            OpenClawSettingsRules.connectionSummaryLabel(
                provider = "OpenClaw Remote",
                address = "wss://gateway.example.com"
            )
        )
        assertEquals(
            "Token: 已配置",
            OpenClawSettingsRules.secretSummaryLabel(
                provider = "OpenClaw Remote",
                secret = "abc123"
            )
        )
    }

    @Test
    fun `openclaw remote shows remote target label from session key`() {
        assertEquals(
            "当前控制目标: gateway.example.com",
            OpenClawSettingsRules.remoteTargetLabel("et:remote:gateway.example.com")
        )
    }
}

package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEndpointRulesTest {
    @Test
    fun `anthropic root address previews messages endpoint`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            SettingsEndpointRules.buildFullEndpointPreview(
                base = "https://api.anthropic.com",
                provider = "Anthropic",
                channel = "Anthropic",
            ),
        )
    }

    @Test
    fun `anthropic full messages address is not duplicated`() {
        assertEquals(
            "https://proxy.example/v1/messages",
            SettingsEndpointRules.buildFullEndpointPreview(
                base = "https://proxy.example/v1/messages",
                provider = "custom",
                channel = "Anthropic",
            ),
        )
    }

    @Test
    fun `predefined provider protection remains generic`() {
        assertFalse(SettingsEndpointRules.canDeleteProvider("Anthropic"))
        assertTrue(SettingsEndpointRules.canDeleteProvider("自定义平台"))
    }
}

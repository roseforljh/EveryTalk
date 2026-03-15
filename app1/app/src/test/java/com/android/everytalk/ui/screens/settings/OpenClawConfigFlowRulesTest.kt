package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawConfigFlowRulesTest {

    @Test
    fun `openclaw provider skips model selection flow`() {
        assertTrue(
            OpenClawSettingsRules.shouldSaveWithoutModel(
                provider = "OpenClaw",
                channel = "OpenClaw"
            )
        )
    }

    @Test
    fun `non openclaw provider still requires model flow`() {
        assertFalse(
            OpenClawSettingsRules.shouldSaveWithoutModel(
                provider = "openai compatible",
                channel = "OpenAI兼容"
            )
        )
    }

    @Test
    fun `remote control provider skips model selection flow`() {
        assertTrue(
            OpenClawSettingsRules.shouldSaveWithoutModel(
                provider = "OpenClaw Remote",
                channel = "OpenClaw"
            )
        )
    }

    @Test
    fun `remote control provider uses gateway labels`() {
        assertEquals(
            "Gateway Address",
            OpenClawSettingsRules.addressLabelFor(provider = "OpenClaw Remote", channel = "OpenClaw")
        )
        assertEquals(
            "Gateway Token",
            OpenClawSettingsRules.keyLabelFor(provider = "OpenClaw Remote", channel = "OpenClaw")
        )
    }
}

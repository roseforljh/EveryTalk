package com.android.everytalk.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawRemoteValidationTest {

    @Test
    fun `remote gateway address accepts websocket urls`() {
        assertTrue(OpenClawSettingsRules.isValidRemoteGatewayAddress("ws://192.168.1.2:18789"))
        assertTrue(OpenClawSettingsRules.isValidRemoteGatewayAddress("wss://gateway.example.com/socket"))
    }

    @Test
    fun `remote gateway address rejects plain http urls`() {
        assertFalse(OpenClawSettingsRules.isValidRemoteGatewayAddress("https://gateway.example.com"))
        assertFalse(OpenClawSettingsRules.isValidRemoteGatewayAddress("http://127.0.0.1:18789"))
    }
}

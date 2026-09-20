package com.android.everytalk.ui.screens.mcp

import com.android.everytalk.data.mcp.McpTransportType
import com.android.everytalk.data.mcp.Context7Mcp
import com.android.everytalk.data.mcp.McpServerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerPresetTest {

    @Test
    fun `context7 preset uses remote http endpoint with header auth`() {
        val preset = McpServerPreset.CONTEXT7

        assertEquals("Context7", preset.displayName)
        assertEquals(McpTransportType.HTTP, preset.transportType)
        assertEquals("https://mcp.context7.com/mcp", preset.buildUrl("test-key"))
        assertEquals(
            mapOf("CONTEXT7_API_KEY" to "test-key"),
            preset.buildHeaders("test-key")
        )
        assertFalse(preset.requiresApiKey)
        assertTrue(preset.buildHeaders("").isEmpty())
        val config = Context7Mcp.defaultConfig()
        assertTrue(config is McpServerConfig.StreamableHTTPServer)
        assertEquals(preset.buildUrl(""), config.url)
        assertEquals(Context7Mcp.defaultConfig().id, config.id)
        assertTrue(config.enabled)
        assertTrue(config.headers.isEmpty())
    }
}

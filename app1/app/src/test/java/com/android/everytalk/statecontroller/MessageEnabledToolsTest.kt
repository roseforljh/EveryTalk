package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.MessageToolIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEnabledToolsTest {
    @Test
    fun `text request snapshots enabled tools in stable display order`() {
        assertEquals(
            listOf(MessageToolIds.WEB_SEARCH, MessageToolIds.MCP),
            enabledMessageToolIdsForRequest(
                isImageGeneration = false,
                webSearchEnabled = true,
                mcpEnabled = true,
            ),
        )
    }

    @Test
    fun `image request does not retain text tool logos`() {
        assertTrue(
            enabledMessageToolIdsForRequest(
                isImageGeneration = true,
                webSearchEnabled = true,
                mcpEnabled = true,
            ).isEmpty()
        )
    }
}

package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class AddMenuPreferenceStateTest {

    @Test
    fun `conversation toggle state defaults to all disabled`() {
        val state = ConversationFunctionToggleState()

        assertEquals(false, state.webSearchEnabled)
        assertEquals(false, state.codeExecutionEnabled)
        assertEquals(false, state.mcpEnabled)
    }
}

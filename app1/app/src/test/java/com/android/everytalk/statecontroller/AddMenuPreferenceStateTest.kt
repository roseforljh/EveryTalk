package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AddMenuPreferenceStateTest {

    @Test
    fun `conversation toggle state defaults to all disabled`() {
        val state = ConversationFunctionToggleState()

        assertEquals(false, state.webSearchEnabled)
        assertEquals(false, state.codeExecutionEnabled)
        assertEquals(false, state.mcpEnabled)
    }

    @Test
    fun `image conversation applies its own toggle state instead of text conversation state`() {
        val holder = ViewModelStateHolder()
        val textConversationId = holder._currentConversationId.value
        val imageConversationId = "image_history_1"

        holder.conversationFunctionToggleStates.value = mapOf(
            textConversationId to ConversationFunctionToggleState(
                webSearchEnabled = true,
                codeExecutionEnabled = true,
                mcpEnabled = true
            ),
            imageConversationId to ConversationFunctionToggleState(
                webSearchEnabled = false,
                codeExecutionEnabled = false,
                mcpEnabled = false
            )
        )

        holder._currentImageGenerationConversationId.value = imageConversationId
        holder.applyCurrentImageConversationFunctionToggleState()

        assertFalse(holder._isWebSearchEnabled.value)
        assertFalse(holder._isCodeExecutionEnabled.value)
        assertFalse(holder._isMcpEnabledForNextRequest.value)
    }

}

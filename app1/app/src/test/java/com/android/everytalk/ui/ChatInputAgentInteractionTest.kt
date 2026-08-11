package com.android.everytalk.ui

import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.AgentToggleAction
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolveAgentToggleAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputAgentInteractionTest {
    @Test
    fun `短按根据当前状态关闭开启或要求选服`() {
        assertEquals(AgentToggleAction.DISABLE, resolveAgentToggleAction(true, false, true))
        assertEquals(AgentToggleAction.DISABLE, resolveAgentToggleAction(false, true, true))
        assertEquals(AgentToggleAction.OPEN_SERVER_PICKER, resolveAgentToggleAction(false, false, false))
        assertEquals(AgentToggleAction.ENABLE_SELECTED, resolveAgentToggleAction(false, false, true))
    }
}

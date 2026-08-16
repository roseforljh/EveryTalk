package com.android.everytalk.ui

import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.AgentToggleAction
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolveAgentToggleAction
import com.android.everytalk.statecontroller.AgentResourceState
import com.android.everytalk.statecontroller.ConversationFunctionToggleState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputAgentInteractionTest {
    @Test
    fun `短按根据当前状态关闭开启或要求选服`() {
        assertEquals(AgentToggleAction.DISABLE, resolveAgentToggleAction(true, false, true))
        assertEquals(AgentToggleAction.DISABLE, resolveAgentToggleAction(false, true, true))
        assertEquals(AgentToggleAction.OPEN_SERVER_PICKER, resolveAgentToggleAction(false, false, false))
        assertEquals(AgentToggleAction.ENABLE_SELECTED, resolveAgentToggleAction(false, false, true))
        assertEquals(
            AgentToggleAction.CONFIRM_WORKSPACE_RECREATION,
            resolveAgentToggleAction(false, false, true, requiresWorkspaceRecreation = true),
        )
    }

    @Test
    fun `删除资源状态和恢复路径可以持久化`() {
        val state = ConversationFunctionToggleState(
            agentResourceState = AgentResourceState.WORKSPACE_DELETED,
            detachedComputerName = "开发机",
            detachedWorkspacePath = "/home/user/.everytalk/workspaces/ws_old",
        )

        assertEquals(
            state,
            Json.decodeFromString<ConversationFunctionToggleState>(Json.encodeToString(state)),
        )
    }
}

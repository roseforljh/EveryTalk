package com.android.everytalk.ui

import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.AgentToggleAction
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolveAgentToggleAction
import com.android.everytalk.statecontroller.AgentResourceState
import com.android.everytalk.statecontroller.ConversationFunctionToggleState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `Agent批准会等待系统权限结果并跨弹窗保留到恢复原Run`() {
        val source = chatInputSource().readText(Charsets.UTF_8)
        val permissionFlow = source.substringAfter("val notificationPermissionLauncher")
            .substringBefore("fun requestAgentAction")
        val approvalDialog = source.substringAfter("agentEnableApprovalRequest?.takeIf")
            .substringBefore("skillSecretApprovalRequest?.let")

        assertTrue(permissionFlow.contains("pendingNotificationPermissionAction?.let(::performAgentAction)"))
        assertTrue(permissionFlow.contains("notificationPermissionLauncher.launch"))
        assertTrue(permissionFlow.contains("return"))
        assertFalse(permissionFlow.contains("ActivityResultContracts.RequestPermission(),\n    ) { }"))
        assertTrue(approvalDialog.contains("pendingApprovalForComputerSelection = request"))
        assertTrue(approvalDialog.contains("pendingAgentAction == null"))
        assertTrue(approvalDialog.contains("pendingNotificationPermissionAction == null"))
        assertTrue(approvalDialog.contains("respondToAgentEnableApproval"))

        val actionsSource = viewModelActionsSource().readText(Charsets.UTF_8)
        val selectionFlow = actionsSource.substringAfter("internal fun AppViewModel.selectComputerForCurrentConversation(")
            .substringBefore("internal fun AppViewModel.respondToAgentEnableApproval(")
        assertFalse(selectionFlow.contains("canPostAgentEventNotifications"))
        assertTrue(selectionFlow.contains("onFailure?.invoke()"))
    }

    private fun chatInputSource(): File {
        val relativePath = "ui/screens/MainScreen/chat/text/ui/ChatInputArea.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 ChatInputArea.kt" }
    }

    private fun viewModelActionsSource(): File {
        val relativePath = "statecontroller/viewmodel/AppViewModelActions.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 AppViewModelActions.kt" }
    }
}

package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证会话 ID 迁移不会破坏已经冻结的 Workspace 请求。 */
class ComputerRequestWorkspaceBindingTest {
    private val requestContext = ComputerRequestContext(
        conversationId = "temporary-conversation",
        computerId = "computer-1",
        workspaceId = "workspace-1",
    )
    private val migratedWorkspace = ComputerWorkspace(
        id = "workspace-1",
        computerId = "computer-1",
        conversationId = "stable-conversation",
        runMode = ComputerRunMode.DIRECT,
        hostPath = "/workspace",
        status = ComputerWorkspaceStatus.READY,
    )

    @Test
    fun `conversation migration keeps an in-flight workspace request valid`() {
        assertTrue(migratedWorkspace.matchesRequestContext(requestContext))
    }

    @Test
    fun `workspace binding still rejects a changed target or unavailable workspace`() {
        assertFalse(migratedWorkspace.copy(id = "workspace-2").matchesRequestContext(requestContext))
        assertFalse(migratedWorkspace.copy(computerId = "computer-2").matchesRequestContext(requestContext))
        assertFalse(
            migratedWorkspace.copy(status = ComputerWorkspaceStatus.ERROR)
                .matchesRequestContext(requestContext),
        )
    }
}

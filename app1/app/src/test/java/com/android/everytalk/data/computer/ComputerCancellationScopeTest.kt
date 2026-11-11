package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.toEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** 不建立 SSH 连接，验证停止按钮的目标范围不会被空查询结果扩大。 */
class ComputerCancellationScopeTest {
    @Test
    fun `省略执行目标和预览目标时审批边界也默认主机`() = runTest {
        val appContext = mockk<Context>()
        every { appContext.applicationContext } returns appContext
        val repository = mockk<ComputerRepository>()
        val dao = mockk<ComputerDao>()
        val request = ComputerRequestContext("session", "server", "ws_legacy")
        val workspace = ComputerWorkspace("ws_legacy", "server", "session", ComputerRunMode.DIRECT,
            "/home/user/work", status = ComputerWorkspaceStatus.READY)
        every { repository.dao() } returns dao
        coEvery { dao.getWorkspaceById(workspace.id) } returns workspace.toEntity()
        coEvery { repository.refreshPermissionMode(any()) } answers { firstArg() }
        coEvery { repository.getComputer("server") } returns Computer(
            id = "server", displayName = "server", host = "example.com", port = 22, username = "user",
            authKind = ComputerAuthKind.PASSWORD, runMode = ComputerRunMode.DIRECT)
        ComputerToolExecutor(appContext, repository, mockk(), mockk(), mockk()).use { executor ->
            val args = buildJsonObject { put("command", "systemctl restart nginx") }
            val approval = executor.approvalRequest(ComputerToolNames.EXEC, args, "call", request,
                ComputerToolApprovalPhase.BEFORE_EXECUTION) as ComputerToolApprovalRequest.HostCommand
            assertEquals("~", approval.request.cwd)
            assertTrue(executor.approvalRequest(ComputerToolNames.OPEN_PORT,
                buildJsonObject { put("port", 8080); put("visibility", "public") }, "preview", request,
                ComputerToolApprovalPhase.BEFORE_EXECUTION) is ComputerToolApprovalRequest.PublicPreview)
        }
    }

    private fun execution(id: String, background: Boolean = false) = ComputerExecution(
        id = id, toolCallId = "tool_$id", computerId = "computer", workspaceId = "workspace",
        toolName = "exec", requestHash = "a".repeat(64), status = ComputerExecutionStatus.RUNNING,
        runId = "run-1", remoteStatus = ComputerRemoteStatus.RUNNING,
        completionMode = if (background) ComputerExecutionCompletionMode.RETURN_HANDLE
            else ComputerExecutionCompletionMode.WAIT_FOR_RESULT,
    ).toEntity()

}

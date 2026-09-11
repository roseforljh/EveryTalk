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
import org.junit.Test

/** 不建立 SSH 连接，验证停止按钮的目标范围不会被空查询结果扩大。 */
class ComputerCancellationScopeTest {
    private fun execution(id: String, background: Boolean = false) = ComputerExecution(
        id = id, toolCallId = "tool_$id", computerId = "computer", workspaceId = "workspace",
        toolName = "exec", requestHash = "a".repeat(64), status = ComputerExecutionStatus.RUNNING,
        runId = "run-1", remoteStatus = ComputerRemoteStatus.RUNNING,
        completionMode = if (background) ComputerExecutionCompletionMode.RETURN_HANDLE
            else ComputerExecutionCompletionMode.WAIT_FOR_RESULT,
    ).toEntity()

    @Test
    fun `强制停止取消当前Run的前台和后台任务但不扩大到会话`() = runTest {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val dao = mockk<ComputerDao>(relaxed = true)
        val repository = mockk<ComputerRepository>()
        every { repository.dao() } returns dao
        coEvery { dao.getCancellableRemoteExecutionsForRun("run-1") } returns listOf(
            execution("execution_current"), execution("execution_service", background = true),
        )
        coEvery { repository.cancelRemoteExecution("execution_current") } returns ComputerRemoteExecutionSnapshot(
            executionId = "execution_current", processId = "process_execution_current",
            status = ComputerRemoteStatus.CANCELLED,
        )
        coEvery { repository.cancelRemoteExecution("execution_service") } returns ComputerRemoteExecutionSnapshot(
            executionId = "execution_service", processId = "process_execution_service",
            status = ComputerRemoteStatus.CANCELLED,
        )
        ComputerToolExecutor(context, repository, mockk(), mockk(), mockk()).use { executor ->
            assertTrue(executor.cancelActiveExecutions("session-1", "run-1"))
            coVerify(exactly = 1) { repository.cancelRemoteExecution("execution_current") }
            coVerify(exactly = 1) { repository.cancelRemoteExecution("execution_service") }
            coVerify(exactly = 1) { dao.markRemoteExecutionCancellationRequested("execution_service", any()) }
            coVerify(exactly = 0) { dao.getCancellableRemoteExecutionsForConversation(any()) }
        }
    }

    @Test
    fun `只有明确登记的停止才允许重试且终态不再重试`() {
        val running = execution("execution_current")
        assertFalse(running.shouldRetryRemoteCancellation())
        assertFalse(running.copy(status = "CANCELLED").shouldRetryRemoteCancellation())
        assertTrue(running.copy(cancelRequestedAt = 1L).shouldRetryRemoteCancellation())
        assertTrue(running.copy(cancelRequestedAt = 1L, status = "UNKNOWN", remoteStatus = "UNKNOWN",
            errorCode = ComputerErrorCodes.EXECUTION_CANCEL_FAILED).shouldRetryRemoteCancellation())
        for (terminal in listOf("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT")) {
            assertFalse(running.copy(cancelRequestedAt = 1L, remoteStatus = terminal).shouldRetryRemoteCancellation())
        }
        assertFalse(execution("execution_service", background = true).shouldRetryRemoteCancellation())
        assertTrue(execution("execution_service", background = true).copy(cancelRequestedAt = 1L).shouldRetryRemoteCancellation())
    }

    @Test
    fun `指定Run没有远端任务时不能取消同会话其他任务`() = runTest {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val dao = mockk<ComputerDao>()
        val repository = mockk<ComputerRepository>()
        every { repository.dao() } returns dao
        coEvery { dao.getCancellableRemoteExecutionsForRun("run-1") } returns emptyList()
        coEvery { dao.getCancellableRemoteExecutionsForConversation(any()) } returns emptyList()
        ComputerToolExecutor(context, repository, mockk(), mockk(), mockk()).use { executor ->
            assertTrue(executor.cancelActiveExecutions("session-1", "run-1"))
            coVerify(exactly = 0) { dao.getCancellableRemoteExecutionsForConversation(any()) }
            coVerify(exactly = 0) { repository.cancelRemoteExecution(any()) }
        }
    }
}

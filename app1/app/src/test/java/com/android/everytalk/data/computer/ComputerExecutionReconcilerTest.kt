package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定本地 Tool 状态与 VPS 远端状态分离后的核心转换。 */
class ComputerExecutionReconcilerTest {
    private val dao = mockk<ComputerDao>(relaxed = true)
    private val executionId = "execution_reconcile"
    private val processId = "process_$executionId"
    private val requestHash = "a".repeat(64)

    @Test
    fun `前台远端成功会把Tool标为成功`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit

        val result = reconciler(
            ComputerRemoteExecutionQuery.State(statePayload("SUCCEEDED", exitCode = 0)),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.UPDATED, result.outcome)
        coVerify {
            dao.updateRemoteExecutionObservation(
                executionId = executionId,
                target = "CONTAINER",
                remoteProcessId = processId,
                remoteStatus = "SUCCEEDED",
                remoteExitCode = 0,
                observedAt = 10_000L,
                localStatus = "SUCCEEDED",
                finishedAt = 10_000L,
                localExitCode = 0,
                errorCode = null,
            )
        }
    }

    @Test
    fun `后台句柄远端仍运行时不把Tool改回运行中`() = runBlocking {
        val execution = execution(status = "SUCCEEDED", completionMode = "RETURN_HANDLE")
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit

        reconciler(
            ComputerRemoteExecutionQuery.State(statePayload("RUNNING")),
        ).reconcile(execution)

        coVerify {
            dao.updateRemoteExecutionObservation(
                executionId = executionId,
                target = "CONTAINER",
                remoteProcessId = processId,
                remoteStatus = "RUNNING",
                remoteExitCode = null,
                observedAt = 10_000L,
                localStatus = null,
                finishedAt = null,
                localExitCode = null,
                errorCode = null,
            )
        }
    }

    @Test
    fun `网络不可用保留最后状态且不写入数据库`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution

        val result = reconciler(
            ComputerRemoteExecutionQuery.Unavailable("offline", connectionFailure = true),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.STILL_UNAVAILABLE, result.outcome)
        assertTrue(result.connectionFailure)
        coVerify(exactly = 0) { dao.updateRemoteExecutionObservation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `配置错误不可伪装成SSH断线`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution

        val result = reconciler(
            ComputerRemoteExecutionQuery.Unavailable("容器环境未配置", connectionFailure = false),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.STILL_UNAVAILABLE, result.outcome)
        assertFalse(result.connectionFailure)
    }

    @Test
    fun `Container断线后恢复查询不会重复执行`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit
        var attempt = 0
        val gateway = ComputerRemoteExecutionGateway {
            if (attempt++ == 0) ComputerRemoteExecutionQuery.Unavailable("断线")
            else ComputerRemoteExecutionQuery.State(statePayload("SUCCEEDED", exitCode = 0))
        }
        val reconciler = ComputerExecutionReconciler(dao, gateway, nowMillis = { 10_000L })

        assertEquals(
            ComputerExecutionReconciliationOutcome.STILL_UNAVAILABLE,
            reconciler.reconcile(execution).outcome,
        )
        assertEquals(
            ComputerExecutionReconciliationOutcome.UPDATED,
            reconciler.reconcile(execution).outcome,
        )
        assertEquals(2, attempt)
    }

    @Test
    fun `Host断线后恢复查询使用Host目标`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
            .copy(target = ComputerExecTarget.HOST.name)
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit

        var receivedTarget: ComputerExecTarget? = null
        val reconciler = ComputerExecutionReconciler(
            dao = dao,
            gateway = ComputerRemoteExecutionGateway {
                receivedTarget = ComputerRemoteExecutionParser.parseState(
                    statePayload("SUCCEEDED", exitCode = 0, target = ComputerExecTarget.HOST),
                    expectedTarget = ComputerExecTarget.HOST,
                ).target
                ComputerRemoteExecutionQuery.State(
                    statePayload("SUCCEEDED", exitCode = 0, target = ComputerExecTarget.HOST),
                )
            },
            nowMillis = { 10_000L },
        )

        assertEquals(ComputerExecutionReconciliationOutcome.UPDATED, reconciler.reconcile(execution).outcome)
        assertEquals(ComputerExecTarget.HOST, receivedTarget)
    }

    @Test
    fun remoteMissingIsRecordedAsMissing() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit

        val result = reconciler(ComputerRemoteExecutionQuery.Missing).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.MISSING, result.outcome)
        coVerify {
            dao.updateRemoteExecutionObservation(
                executionId = executionId,
                target = "CONTAINER",
                remoteProcessId = processId,
                remoteStatus = "MISSING",
                remoteExitCode = null,
                observedAt = 10_000L,
                localStatus = "UNKNOWN",
                finishedAt = 10_000L,
                localExitCode = null,
                errorCode = "EXECUTION_NOT_FOUND",
            )
        }
    }

    @Test
    fun invalidIdentityUsesSpecificConflictCode() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.markRemoteExecutionUnknown(executionId, ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT)
        } returns Unit
        val wrongHash = "b".repeat(64)

        val result = reconciler(
            ComputerRemoteExecutionQuery.State(
                statePayload("RUNNING").replace("request_hash=$requestHash", "request_hash=$wrongHash"),
            ),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.INVALID, result.outcome)
        coVerify {
            dao.markRemoteExecutionUnknown(
                executionId = executionId,
                errorCode = ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT,
                observedAt = 10_000L,
                localStatus = ComputerExecutionStatus.FAILED.name,
                finishedAt = 10_000L,
            )
        }
        coVerify(exactly = 0) {
            dao.updateRemoteExecutionObservation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `后台句柄已完成时协议损坏不覆盖本地终态`() = runBlocking {
        val execution = execution(status = "SUCCEEDED", completionMode = "RETURN_HANDLE")
        coEvery { dao.getExecutionById(executionId) } returns execution

        val result = reconciler(
            ComputerRemoteExecutionQuery.State(
                statePayload("RUNNING").replace("request_hash=$requestHash", "request_hash=${"b".repeat(64)}"),
            ),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.INVALID, result.outcome)
        coVerify {
            dao.markRemoteExecutionUnknown(
                executionId = executionId,
                errorCode = ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT,
                observedAt = 10_000L,
                localStatus = null,
                finishedAt = null,
            )
        }
    }

    @Test
    fun `请求哈希冲突不会进入UNKNOWN重试状态`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution

        val result = reconciler(
            ComputerRemoteExecutionQuery.Invalid(
                message = "request hash conflict",
                code = ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT,
            ),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.INVALID, result.outcome)
        coVerify {
            dao.markRemoteExecutionUnknown(
                executionId = executionId,
                errorCode = ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT,
                observedAt = 10_000L,
                localStatus = ComputerExecutionStatus.FAILED.name,
                finishedAt = 10_000L,
            )
        }
    }

    @Test
    fun `解析出的请求哈希冲突使用专用错误码`() = runBlocking {
        val execution = execution(status = "RUNNING", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.markRemoteExecutionUnknown(executionId, ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT)
        } returns Unit

        val result = reconciler(
            ComputerRemoteExecutionQuery.State(
                statePayload("RUNNING").replace("request_hash=$requestHash", "request_hash=${"b".repeat(64)}"),
            ),
        ).reconcile(execution)

        assertEquals(ComputerExecutionReconciliationOutcome.INVALID, result.outcome)
        coVerify {
            dao.markRemoteExecutionUnknown(
                executionId = executionId,
                errorCode = ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT,
                observedAt = 10_000L,
                localStatus = ComputerExecutionStatus.FAILED.name,
                finishedAt = 10_000L,
            )
        }
    }

    @Test
    fun failedLocalExecutionIsNotReconciledAgain() = runBlocking {
        val execution = execution(status = "FAILED", completionMode = "WAIT_FOR_RESULT")
        coEvery { dao.getRemoteExecutionsForWorkspace("workspace-1") } returns listOf(execution)

        val result = reconciler(
            ComputerRemoteExecutionQuery.State(statePayload("RUNNING")),
        ).reconcileActiveForWorkspace("workspace-1")

        assertEquals(0, result.size)
        coVerify(exactly = 0) { dao.updateRemoteExecutionObservation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `取消失败产生的未知状态仍会继续远端对账`() {
        val execution = execution(status = "UNKNOWN", completionMode = "WAIT_FOR_RESULT").copy(
            remoteStatus = ComputerRemoteStatus.UNKNOWN.name,
            errorCode = ComputerErrorCodes.EXECUTION_CANCEL_FAILED,
        )

        assertTrue(execution.shouldReconcileRemote())
    }

    @Test
    fun `本地先取消但远端仍运行时继续对账`() {
        val execution = execution(status = ComputerExecutionStatus.CANCELLED.name, completionMode = "WAIT_FOR_RESULT").copy(
            remoteStatus = ComputerRemoteStatus.RUNNING.name,
        )

        assertTrue(execution.shouldReconcileRemote())
    }

    @Test
    fun `取消意图已落库但还没有远端状态时继续对账`() {
        val execution = execution(status = ComputerExecutionStatus.CANCELLED.name, completionMode = "WAIT_FOR_RESULT").copy(
            remoteStatus = null,
            errorCode = ComputerErrorCodes.EXECUTION_CANCEL_REQUESTED,
        )

        assertTrue(execution.shouldReconcileRemote())
    }

    @Test
    fun `恢复查询允许暂时离线和待升级状态但不会绕过主机密钥异常`() {
        assertTrue(ComputerStatus.READY.canAttemptExecutionRecovery())
        assertTrue(ComputerStatus.OFFLINE.canAttemptExecutionRecovery())
        assertTrue(ComputerStatus.DISCONNECTED.canAttemptExecutionRecovery())
        assertTrue(ComputerStatus.CONFIGURATION_REQUIRED.canAttemptExecutionRecovery())
        assertTrue(!ComputerStatus.HOST_KEY_CHANGED.canAttemptExecutionRecovery())
    }

    @Test
    fun `远端取消确认前不把本地终态改回运行中`() = runBlocking {
        val execution = execution(
            status = ComputerExecutionStatus.CANCELLED.name,
            completionMode = "WAIT_FOR_RESULT",
        ).copy(remoteStatus = ComputerRemoteStatus.RUNNING.name)
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit

        reconciler(ComputerRemoteExecutionQuery.State(statePayload("RUNNING"))).reconcile(execution)

        coVerify {
            dao.updateRemoteExecutionObservation(
                executionId = executionId,
                target = "CONTAINER",
                remoteProcessId = processId,
                remoteStatus = "RUNNING",
                remoteExitCode = null,
                observedAt = 10_000L,
                localStatus = null,
                finishedAt = null,
                localExitCode = null,
                errorCode = null,
            )
        }
    }

    @Test
    fun `本地取消后收到远端终态会补齐完成时间`() = runBlocking {
        val execution = execution(
            status = ComputerExecutionStatus.CANCELLED.name,
            completionMode = "WAIT_FOR_RESULT",
        ).copy(
            remoteStatus = ComputerRemoteStatus.RUNNING.name,
            errorCode = ComputerErrorCodes.EXECUTION_CANCEL_REQUESTED,
            finishedAt = null,
        )
        coEvery { dao.getExecutionById(executionId) } returns execution
        coEvery {
            dao.updateRemoteExecutionObservation(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns Unit

        reconciler(ComputerRemoteExecutionQuery.State(statePayload("CANCELLED", exitCode = 143)))
            .reconcile(execution)

        coVerify {
            dao.updateRemoteExecutionObservation(
                executionId = executionId,
                target = "CONTAINER",
                remoteProcessId = processId,
                remoteStatus = "CANCELLED",
                remoteExitCode = 143,
                observedAt = 10_000L,
                localStatus = null,
                finishedAt = 10_000L,
                localExitCode = 143,
                errorCode = null,
            )
        }
    }

    @Test
    fun `非exec记录不会进入远端对账`() {
        val execution = execution(status = ComputerExecutionStatus.RUNNING.name, completionMode = "WAIT_FOR_RESULT").copy(
            toolName = ComputerToolNames.READ_FILE,
            remoteStatus = ComputerRemoteStatus.RUNNING.name,
        )

        assertTrue(!execution.shouldReconcileRemote())
    }

    private fun reconciler(query: ComputerRemoteExecutionQuery) = ComputerExecutionReconciler(
        dao = dao,
        gateway = ComputerRemoteExecutionGateway { query },
        nowMillis = { 10_000L },
    )

    private fun execution(status: String, completionMode: String) = ComputerExecutionEntity(
        id = executionId,
        toolCallId = "tool-call",
        computerId = "computer-1",
        workspaceId = "workspace-1",
        toolName = ComputerToolNames.EXEC,
        requestHash = requestHash,
        status = status,
        startedAt = 1_000L,
        finishedAt = null,
        exitCode = null,
        errorCode = null,
        safeSummary = null,
        target = ComputerExecTarget.CONTAINER.name,
        completionMode = completionMode,
        remoteProcessId = processId,
        remoteStatePath = "/workspace/.everytalk/executions/$executionId/state",
        remoteStatus = status,
    )

    private fun statePayload(
        status: String,
        exitCode: Int? = null,
        target: ComputerExecTarget = ComputerExecTarget.CONTAINER,
    ): String = buildString {
        appendLine("protocol=2")
        appendLine("execution_id=$executionId")
        appendLine("process_id=$processId")
        appendLine("request_hash=$requestHash")
        appendLine("target=${target.name}")
        appendLine("pid=123")
        appendLine("start_ticks=456")
        appendLine("status=$status")
        appendLine("exit_code=${exitCode ?: ""}")
        appendLine("started_at=1")
        appendLine("updated_at=2")
        appendLine("stdout_bytes=0")
        appendLine("stderr_bytes=0")
    }
}

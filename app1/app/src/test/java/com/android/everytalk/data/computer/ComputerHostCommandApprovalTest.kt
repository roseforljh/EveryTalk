package com.android.everytalk.data.computer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ComputerHostCommandApprovalTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `用户决定前主机命令不会开始执行`() = runTest {
        val decision = CompletableDeferred<Boolean>()
        val request = ComputerExecRequest(
            command = "systemctl restart nginx",
            cwd = "~",
            target = ComputerExecTarget.HOST,
        )
        var executedRequest: ComputerExecRequest? = null

        val result = async {
            executeHostCommandWithConfirmation(
                request = request,
                confirmationRequest = { assessment -> confirmation(assessment, request) },
                confirmer = { decision.await() },
                execute = { approved -> executedRequest = approved; approved },
            )
        }
        runCurrent()

        assertFalse(result.isCompleted)
        assertSame(null, executedRequest)
        decision.complete(true)
        assertSame(request, result.await())
        assertSame(request, executedRequest)
    }

    @Test
    fun `用户拒绝后主机命令不执行`() = runTest {
        val request = ComputerExecRequest(
            command = "systemctl restart nginx",
            cwd = "~",
            target = ComputerExecTarget.HOST,
        )
        var executed = false

        val error = runCatching {
            executeHostCommandWithConfirmation(
                request = request,
                confirmationRequest = { assessment -> confirmation(assessment, request) },
                confirmer = { false },
                execute = { executed = true },
            )
        }.exceptionOrNull() as ComputerException

        assertFalse(executed)
        org.junit.Assert.assertEquals(ComputerErrorCodes.HOST_COMMAND_REJECTED, error.code)
    }

    @Test
    fun `无效主机参数不会先弹确认卡`() = runTest {
        var confirmationRequested = false
        var executed = false

        val error = runCatching {
            executeHostCommandWithConfirmation(
                request = ComputerExecRequest(
                    command = "systemctl restart nginx",
                    cwd = "relative",
                    target = ComputerExecTarget.HOST,
                ),
                confirmationRequest = { assessment ->
                    confirmationRequested = true
                    confirmation(assessment, ComputerExecRequest(command = "x"))
                },
                confirmer = { false },
                execute = { executed = true },
            )
        }.exceptionOrNull()

        assertFalse(confirmationRequested)
        assertFalse(executed)
        org.junit.Assert.assertTrue(error is ComputerException)
    }

    @Test
    fun `智能批准按AI决定是否请求用户`() = runTest {
        val request = ComputerExecRequest(
            command = "systemctl restart nginx",
            cwd = "~",
            target = ComputerExecTarget.HOST,
        )
        var requested = false
        var executed = false

        executeHostCommandWithConfirmation(
            request = request,
            permissionMode = ComputerPermissionMode.SMART,
            askUserApproval = false,
            confirmationRequest = { assessment -> confirmation(assessment, request) },
            confirmer = { requested = true; true },
            execute = { executed = true },
        )

        assertFalse(requested)
        org.junit.Assert.assertTrue(executed)
    }

    @Test
    fun `完全批准跳过所有确认`() = runTest {
        val request = ComputerExecRequest(
            command = "rm -rf /tmp/everytalk-test",
            cwd = "~",
            target = ComputerExecTarget.HOST,
        )
        var requested = false
        var executed = false

        executeHostCommandWithConfirmation(
            request = request,
            permissionMode = ComputerPermissionMode.FULL,
            confirmationRequest = { assessment -> confirmation(assessment, request) },
            confirmer = { requested = true; false },
            execute = { executed = true },
        )

        assertFalse(requested)
        org.junit.Assert.assertTrue(executed)
    }

    private fun confirmation(
        assessment: ComputerHostCommandAssessment,
        request: ComputerExecRequest,
    ) = ComputerHostCommandConfirmationRequest(
        requestId = "execution_1",
        context = ComputerRequestContext("conversation_1", "computer_1", "workspace_1"),
        computerName = "VPS",
        command = request.command,
        cwd = request.cwd,
        requestsPrivilege = request.asRoot || ComputerHostCommandRisk.PRIVILEGE_ESCALATION in assessment.risks,
        reason = assessment.reason.orEmpty(),
        risks = assessment.risks,
    )
}

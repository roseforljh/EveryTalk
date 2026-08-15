package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerConnectionRetryPolicyTest {
    @Test
    fun `容器待升级时禁止新任务但允许恢复旧任务`() {
        assertTrue(ComputerStatus.READY.canUseSshTools())
        assertFalse(ComputerStatus.CONFIGURATION_REQUIRED.canUseSshTools())
        assertTrue(ComputerStatus.CONFIGURATION_REQUIRED.canAttemptExecutionRecovery())
        assertFalse(ComputerStatus.HOST_KEY_CHANGED.canUseSshTools())
        assertFalse(ComputerStatus.ACTION_REQUIRED.canUseSshTools())
    }

    @Test
    fun `v8 Container 使用轻量升级且 Direct 不受影响`() {
        val container = computer(
            runMode = ComputerRunMode.CONTAINER,
            bootstrapVersion = "8",
            dockerAvailable = true,
        )

        assertTrue(container.needsContainerRuntimeUpgrade())
        assertTrue(container.canUseRuntimeOnlyUpgrade())
        assertFalse(container.copy(bootstrapVersion = null).needsContainerRuntimeUpgrade())
        assertFalse(container.copy(capabilities = ComputerCapabilities(dockerAvailable = false)).canUseRuntimeOnlyUpgrade())
        assertFalse(container.copy(runMode = ComputerRunMode.DIRECT).needsContainerRuntimeUpgrade())
        assertFalse(container.copy(runMode = ComputerRunMode.DIRECT).canUseRuntimeOnlyUpgrade())
        assertEquals(false, container.copy(bootstrapVersion = COMPUTER_BOOTSTRAP_VERSION).needsContainerRuntimeUpgrade())
    }

    @Test
    fun `只有活动Container任务会阻止升级`() {
        val container = execution(ComputerExecTarget.CONTAINER.name)
        val legacyContainer = execution(null)
        val host = execution(ComputerExecTarget.HOST.name)

        assertTrue(hasActiveContainerExecution(listOf(container)))
        assertTrue(hasActiveContainerExecution(listOf(legacyContainer)))
        assertFalse(hasActiveContainerExecution(listOf(host)))
        assertFalse(hasActiveContainerExecution(emptyList()))
    }

    @Test
    fun `SSH 通道包装异常也必须视为坏连接`() {
        assertTrue(isComputerConnectionFailure(ComputerSshChannelOpenException(java.io.IOException("channel closed"))))
    }

    @Test
    fun `普通业务异常不应被连接池当成坏连接`() {
        assertFalse(isComputerConnectionFailure(IllegalStateException("业务状态无效")))
    }

    @Test
    fun `只有Transport已断开且没有退出信息才判定为异常关闭`() {
        assertTrue(
            isUnexpectedSshChannelClosure(
                timedOut = false,
                exitCode = null,
                transportConnected = false,
            ),
        )
        assertFalse(
            isUnexpectedSshChannelClosure(
                timedOut = false,
                exitCode = null,
                transportConnected = true,
            ),
        )
        assertFalse(
            isUnexpectedSshChannelClosure(
                timedOut = false,
                exitCode = null,
                exitSignalPresent = true,
                transportConnected = false,
            ),
        )
        assertFalse(isUnexpectedSshChannelClosure(timedOut = true, exitCode = null))
        assertFalse(isUnexpectedSshChannelClosure(timedOut = false, exitCode = 0))
    }

    @Test
    fun `首个 Channel 建立前失败允许重连一次`() {
        assertTrue(shouldRetryComputerChannelOpen(startedBefore = 7, startedAfter = 7))
    }

    @Test
    fun `已有 Channel 启动后禁止自动重放`() {
        assertFalse(shouldRetryComputerChannelOpen(startedBefore = 7, startedAfter = 8))
    }

    private fun computer(
        runMode: ComputerRunMode,
        bootstrapVersion: String?,
        dockerAvailable: Boolean,
    ) = Computer(
        id = "computer-1",
        displayName = "VPS",
        host = "127.0.0.1",
        port = 22,
        username = "root",
        authKind = ComputerAuthKind.PASSWORD,
        runMode = runMode,
        status = ComputerStatus.CONFIGURATION_REQUIRED,
        capabilities = ComputerCapabilities(dockerAvailable = dockerAvailable),
        bootstrapVersion = bootstrapVersion,
    )

    private fun execution(target: String?) = ComputerExecutionEntity(
        id = "execution-${target ?: "legacy"}",
        toolCallId = "tool-${target ?: "legacy"}",
        computerId = "computer-1",
        workspaceId = "workspace-1",
        toolName = ComputerToolNames.EXEC,
        requestHash = "hash",
        status = ComputerExecutionStatus.RUNNING.name,
        startedAt = 1L,
        finishedAt = null,
        exitCode = null,
        errorCode = null,
        safeSummary = null,
        target = target,
        remoteStatus = ComputerRemoteStatus.RUNNING.name,
    )
}

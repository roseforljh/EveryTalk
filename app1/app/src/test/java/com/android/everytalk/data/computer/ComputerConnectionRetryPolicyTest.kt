package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerConnectionRetryPolicyTest {
    @Test
    fun `容器待修复时仍允许使用基础SSH能力`() {
        assertTrue(ComputerStatus.READY.canUseSshTools())
        assertTrue(ComputerStatus.CONFIGURATION_REQUIRED.canUseSshTools())
        assertTrue(ComputerStatus.CONFIGURATION_REQUIRED.canAttemptExecutionRecovery())
        assertFalse(ComputerStatus.HOST_KEY_CHANGED.canUseSshTools())
        assertFalse(ComputerStatus.ACTION_REQUIRED.canUseSshTools())
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
}

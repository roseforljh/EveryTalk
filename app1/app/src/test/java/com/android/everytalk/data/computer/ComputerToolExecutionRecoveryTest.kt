package com.android.everytalk.data.computer

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ComputerToolExecutionRecoveryTest {
    @Test
    fun `只读命令遇到可重试执行异常允许恢复`() {
        assertTrue(
            shouldRetryReadOnlyExecution(
                readOnlyRequest = true,
                error = ComputerException(
                    code = ComputerErrorCodes.EXECUTION_UNKNOWN,
                    message = "SSH Channel 断开",
                    retryable = true,
                ),
            ),
        )
        assertTrue(shouldRetryReadOnlyExecution(true, IOException("channel closed")))
    }

    @Test
    fun `写命令和取消异常不允许自动重放`() {
        val unknown = ComputerException(
            code = ComputerErrorCodes.EXECUTION_UNKNOWN,
            message = "状态未知",
            retryable = true,
        )

        assertFalse(shouldRetryReadOnlyExecution(false, unknown))
        assertFalse(shouldRetryReadOnlyExecution(true, CancellationException("用户停止")))
    }

    @Test
    fun `不可重试的协议错误不自动恢复`() {
        assertFalse(
            shouldRetryReadOnlyExecution(
                readOnlyRequest = true,
                error = ComputerException(
                    code = ComputerErrorCodes.EXECUTION_PROTOCOL_MISMATCH,
                    message = "远端身份不匹配",
                    retryable = false,
                ),
            ),
        )
    }
}

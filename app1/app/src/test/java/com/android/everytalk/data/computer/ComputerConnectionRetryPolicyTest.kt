package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerConnectionRetryPolicyTest {
    @Test
    fun `首个 Channel 建立前失败允许重连一次`() {
        assertTrue(shouldRetryComputerChannelOpen(startedBefore = 7, startedAfter = 7))
    }

    @Test
    fun `已有 Channel 启动后禁止自动重放`() {
        assertFalse(shouldRetryComputerChannelOpen(startedBefore = 7, startedAfter = 8))
    }
}

package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHistoryExecutionStateTest {
    private val base = Message(id = "message-1", text = "结果", sender = Sender.AI)

    @Test
    fun `历史判等必须识别停止状态和结束时间变化`() {
        assertTrue(base.hasSamePersistedExecutionState(base.copy()))
        assertFalse(
            base.hasSamePersistedExecutionState(
                base.copy(executionStatus = "正在取消远端任务", executionFinishedAt = 1234L)
            )
        )
        assertFalse(
            base.copy(executionStatus = "正在取消远端任务", executionFinishedAt = 1234L)
                .hasSamePersistedExecutionState(
                    base.copy(executionStatus = "远端任务已取消", executionFinishedAt = 1234L)
                )
        )
    }
}

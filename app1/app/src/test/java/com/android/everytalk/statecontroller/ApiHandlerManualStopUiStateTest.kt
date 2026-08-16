package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerManualStopUiStateTest {
    @Test
    fun `手动停止同时保存取消提示和执行结束时间`() {
        val message = Message(id = "message-1", text = "", sender = Sender.AI)
        val messages = mutableListOf(message)

        assertTrue(
            finishPreparedMessageExecution(
                messageList = messages,
                messageId = message.id,
                status = "正在取消远端任务",
                finishedAt = 1234L,
            )
        )
        assertEquals("正在取消远端任务", messages.single().executionStatus)
        assertEquals(1234L, messages.single().executionFinishedAt)
    }
}

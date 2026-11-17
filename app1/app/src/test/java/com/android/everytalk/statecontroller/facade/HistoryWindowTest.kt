package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryWindowTest {
    private fun conversation(rounds: Int) = listOf(Message(id = "system", text = "提示", sender = Sender.System)) +
        (1..rounds).flatMap { listOf(Message(id = "u$it", text = "问$it", sender = Sender.User), Message(id = "a$it", text = "答$it", sender = Sender.AI)) }

    @Test
    fun `只渲染最近若干轮，前面只剩系统提示时不显示加载更多`() {
        val messages = conversation(15)
        assertEquals("u6", messages[historyWindowStart(messages, 10)].id)
        assertEquals(0, historyWindowStart(conversation(10), 10))
        assertEquals(0, historyWindowStart(conversation(3), 10))
    }

    @Test
    fun `搜索或引用跳转的目标在窗口外时从它所在一轮开始`() {
        val messages = conversation(15)
        assertEquals("u2", messages[historyWindowStart(messages, 10, pinnedMessageId = "a2")].id)
        assertEquals("u6", messages[historyWindowStart(messages, 10, pinnedMessageId = "a9")].id)
    }
}

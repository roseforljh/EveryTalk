package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionFailureItemTest {
    @Test
    fun `压缩错误保留执行入口并传递具体原因`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val failure = "上下文压缩失败：请求超时"
        controller.stateHolder.messages.add(
            Message(
                id = "compression-failed",
                text = failure,
                sender = Sender.AI,
                isError = true,
                executionStatus = failure,
            )
        )

        val items = controller.chatListItemsForTest()
        val reasoning = items.filterIsInstance<ChatListItem.AiMessageReasoning>().single()

        assertEquals(failure, reasoning.activityStatusText)
        assertTrue(items.any { it is ChatListItem.ErrorMessage })
    }
}

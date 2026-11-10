package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextCompressionFailureItemTest {
    // computeBubbleState 会无条件调用 Log.isLoggable，单元测试里需要先替身。
    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.isLoggable(any(), any()) } returns false
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        MessageItemsControllerTestAccess.closeAll()
        unmockkAll()
    }
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

    @Test
    fun `正文开始前停止仍显示已有思考过程`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "stopped",
                text = "",
                sender = Sender.AI,
                reasoning = "先分析用户的问题",
                executionStatus = "已停止",
                isIncomplete = true,
            )
        )

        val reasoning = controller.chatListItemsForTest().filterIsInstance<ChatListItem.AiMessageReasoning>().single()
        assertEquals("先分析用户的问题", reasoning.message.reasoning)
    }
}

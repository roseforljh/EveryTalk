package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageItemsControllerStatusTest {

    @Test
    fun `normalize status text keeps remote control progress text`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val result = controller.normalizeStatusTextForTest(
            Message(
                id = "ai-1",
                text = "plain",
                sender = Sender.AI,
                currentWebSearchStage = "远程控制中 · 正在修改 /workspace/app/main.kt"
            )
        )

        assertEquals("远程控制中 · 正在修改 /workspace/app/main.kt", result)
    }

    @Test
    fun `normalize status text converts tool result summary into receipt style`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val result = controller.normalizeStatusTextForTest(
            Message(
                id = "ai-2",
                text = "[工具结果] fs.write: 已修改 /workspace/app/main.kt",
                sender = Sender.AI,
                currentWebSearchStage = "done"
            )
        )

        assertEquals("工具结果 · fs.write: 已修改 /workspace/app/main.kt", result)
    }

    @Test
    fun `system message is rendered into system chat list item`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "system-1",
                text = "slash 输出",
                sender = Sender.System
            )
        )

        val items = controller.chatListItemsForTest()

        assertEquals(1, items.size)
        assertTrue(items.first() is ChatListItem.SystemMessage)
        val item = items.first() as ChatListItem.SystemMessage
        assertEquals("system-1", item.messageId)
        assertEquals("slash 输出", item.text)
    }
}

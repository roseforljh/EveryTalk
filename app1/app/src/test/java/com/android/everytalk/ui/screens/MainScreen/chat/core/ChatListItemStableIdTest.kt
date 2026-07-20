package com.android.everytalk.ui.screens.MainScreen.chat.core

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListItemStableIdTest {
    @Test
    fun `AI正文类型切换时保持同一个LazyColumn身份`() {
        val messageId = "assistant-message"
        val message = Message(
            id = messageId,
            text = "正文",
            sender = Sender.AI,
        )

        val stableIds = listOf(
            ChatListItem.AiMessage(
                message = message,
                messageId = messageId,
                text = message.text,
                hasReasoning = false,
            ).stableId,
            ChatListItem.AiMessageCode(
                message = message,
                messageId = messageId,
                text = message.text,
                hasReasoning = false,
            ).stableId,
            ChatListItem.AiMessageStreaming(
                messageId = messageId,
                hasReasoning = false,
            ).stableId,
            ChatListItem.AiMessageCodeStreaming(
                messageId = messageId,
                hasReasoning = false,
            ).stableId,
        )

        assertEquals(listOf(messageId, messageId, messageId, messageId), stableIds)
    }
}

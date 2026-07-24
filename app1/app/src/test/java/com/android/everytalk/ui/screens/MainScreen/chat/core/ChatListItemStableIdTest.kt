package com.android.everytalk.ui.screens.MainScreen.chat.core

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `完成态历史AI正文展开为稳定的Markdown分块项`() {
        val message = Message(
            id = "history-assistant",
            text = "# 标题\n\n第一段。\n\n第二段。",
            sender = Sender.AI,
        )
        val preparedMessage = PreparedMessage(
            markdown = message.text,
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val state = parseMarkdown(preparedMessage.markdown) as State.Success
        val document = PreparedMarkdownDocument(
            state = state,
            nodes = state.node.children,
        )
        val item = ChatListItem.AiMessage(
            message = message,
            messageId = message.id,
            text = message.text,
            hasReasoning = false,
            preparedMessage = preparedMessage,
            preparedMarkdownDocument = document,
        )

        val expanded = expandStaticAiMessageItem(item)
        val nodes = expanded.filterIsInstance<ChatListItem.AiMarkdownNode>()

        assertTrue(document.nodes.size > nodes.size)
        assertFalse(expanded.any { it is ChatListItem.AiMessage })
        assertEquals(document.nodes, nodes.flatMap { it.nodes })
        assertTrue(nodes.map { it.stableId }.distinct().size == nodes.size)
    }
}

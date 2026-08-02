package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.hasReviewableExecutionProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerMessageInsertionTest {

    @Test
    fun `ai message is inserted immediately after target user message`() {
        val messages = mutableListOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
        )
        val newAiMessage = Message(id = "ai-new", text = "", sender = Sender.AI)

        val insertedIndex = addAiMessageAfterUserMessage(
            messageList = messages,
            newAiMessage = newAiMessage,
            afterUserMessageId = "user-1",
        )

        assertEquals(1, insertedIndex)
        assertEquals(listOf("user-1", "ai-new", "user-2", "ai-2"), messages.map { it.id })
    }

    @Test
    fun `ai message is appended when target user message is missing`() {
        val messages = mutableListOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
        )
        val newAiMessage = Message(id = "ai-new", text = "", sender = Sender.AI)

        val insertedIndex = addAiMessageAfterUserMessage(
            messageList = messages,
            newAiMessage = newAiMessage,
            afterUserMessageId = "missing",
        )

        assertEquals(1, insertedIndex)
        assertEquals(listOf("user-1", "ai-new"), messages.map { it.id })
    }

    @Test
    fun `预创建消息在正式请求前同步最终上下文快照`() {
        val messages = mutableListOf(
            Message(id = "ai-new", text = "", sender = Sender.AI),
        )
        val snapshot = ContextUsageSnapshot(
            messageId = "",
            configId = "config-1",
            systemPromptTokens = 10,
            conversationTextTokens = 80,
            mediaTokens = 0,
            toolSchemaTokens = 10,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 50,
            contextWindowTokens = 1_000,
        )

        val updated = updateMessageContextUsageSnapshot(
            messageList = messages,
            messageId = "ai-new",
            snapshot = snapshot,
        )

        assertTrue(updated)
        assertEquals("ai-new", messages.single().contextUsageSnapshot?.messageId)
        assertEquals(105L, messages.single().contextUsageSnapshot?.estimatedInputTokens)
    }

    @Test
    fun `压缩失败会将原占位消息转为可回看错误`() {
        val messages = mutableListOf(
            Message(
                id = "ai-compression",
                text = "",
                sender = Sender.AI,
                executionStatus = CONTEXT_COMPRESSION_RUNNING_STATUS,
            )
        )
        val failure = "${CONTEXT_COMPRESSION_FAILURE_PREFIX}API 返回 429"

        val updated = markPreparedMessageFailed(messages, "ai-compression", failure)

        assertTrue(updated)
        assertTrue(messages.single().isError)
        assertEquals(failure, messages.single().text)
        assertEquals(failure, messages.single().executionStatus)
        assertTrue(messages.single().hasReviewableExecutionProcess())
    }
}

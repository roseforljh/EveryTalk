package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
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
}

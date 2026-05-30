package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSenderRegenerationInsertTest {

    @Test
    fun `regenerated user message is appended to the bottom`() {
        val messages = mutableListOf(
            Message(id = "user-1", text = "old question", sender = Sender.User),
            Message(id = "user-2", text = "second question", sender = Sender.User),
        )
        val regenerated = Message(id = "user-new", text = "old question", sender = Sender.User)

        val writtenIndex = addOrReplaceRegeneratedUserMessage(
            messageList = messages,
            newUserMessage = regenerated,
            isFromRegeneration = true,
            manualMessageId = "user-1",
        )

        assertEquals(2, writtenIndex)
        assertEquals(listOf("user-1", "user-2", "user-new"), messages.map { it.id })
        assertEquals(regenerated, messages[2])
    }

    @Test
    fun `normal user message is appended`() {
        val messages = mutableListOf(
            Message(id = "user-1", text = "old question", sender = Sender.User),
        )
        val next = Message(id = "user-2", text = "second question", sender = Sender.User)

        val writtenIndex = addOrReplaceRegeneratedUserMessage(
            messageList = messages,
            newUserMessage = next,
            isFromRegeneration = false,
            manualMessageId = null,
        )

        assertEquals(1, writtenIndex)
        assertEquals(listOf("user-1", "user-2"), messages.map { it.id })
    }
}

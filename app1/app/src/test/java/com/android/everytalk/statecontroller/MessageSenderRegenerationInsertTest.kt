package com.android.everytalk.statecontroller

import androidx.compose.runtime.mutableStateListOf
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderRegenerationInsertTest {

    @Test
    fun `regenerated user message moves to bottom without duplicating its key`() {
        val messages = mutableStateListOf(
            Message(id = "user-1", text = "old question", sender = Sender.User),
            Message(id = "user-2", text = "second question", sender = Sender.User),
        )
        val regenerated = Message(id = "user-1", text = "old question", sender = Sender.User)

        val writtenIndex = addOrReplaceRegeneratedUserMessage(
            messageList = messages,
            newUserMessage = regenerated,
            isFromRegeneration = true,
            manualMessageId = "user-1",
        )

        assertEquals(1, writtenIndex)
        assertEquals(listOf("user-2", "user-1"), messages.map { it.id })
        assertEquals(regenerated, messages[1])
    }

    @Test
    fun `latest regenerated user message is replaced in place`() {
        val messages = mutableListOf(
            Message(id = "user-1", text = "old question", sender = Sender.User),
        )
        val regenerated = Message(id = "user-1", text = "updated question", sender = Sender.User)

        val writtenIndex = addOrReplaceRegeneratedUserMessage(
            messageList = messages,
            newUserMessage = regenerated,
            isFromRegeneration = true,
            manualMessageId = "user-1",
        )

        assertEquals(0, writtenIndex)
        assertEquals(listOf("user-1"), messages.map { it.id })
        assertEquals(regenerated, messages.single())
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

    @Test
    fun `api config log summary does not expose sensitive or user editable fields`() {
        val summary = safeApiConfigSummary(
            ApiConfig(
                id = "config-1",
                name = "name has sk-very-secret-key-123456789",
                provider = "provider.secret.example.com",
                model = "model-secret",
                address = "https://secret.example.com/v1/chat/completions",
                key = "sk-very-secret-key-123456789",
            )
        )

        assertTrue(summary.contains("config-1"))
        assertTrue(summary.contains("providerChars="))
        assertTrue(summary.contains("modelChars="))
        assertTrue(summary.contains("channelChars="))
        assertFalse(summary.contains("providerHash="))
        assertFalse(summary.contains("modelHash="))
        assertFalse(summary.contains("channelHash="))
        assertFalse(summary.contains("sk-very-secret-key-123456789"))
        assertFalse(summary.contains("secret.example.com"))
        assertFalse(summary.contains("model-secret"))
    }
}

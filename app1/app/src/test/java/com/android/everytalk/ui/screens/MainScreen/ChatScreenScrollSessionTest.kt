package com.android.everytalk.ui.screens.MainScreen

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenScrollSessionTest {

    @Test
    fun `preserves scroll session when conversation id migrates from temporary id to first user message id`() {
        val stableConversationId = "user_message_1"
        val messages = listOf(
            Message(
                id = stableConversationId,
                text = "hello",
                sender = Sender.User
            )
        )

        val result = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = "user_temp_123",
            newConversationId = stableConversationId,
            messages = messages
        )

        assertTrue(result)
    }

    @Test
    fun `does not preserve scroll session for unrelated conversation switches`() {
        val messages = listOf(
            Message(
                id = "user_message_1",
                text = "hello",
                sender = Sender.User
            )
        )

        val result = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = "conversation_old",
            newConversationId = "conversation_new",
            messages = messages
        )

        assertFalse(result)
    }
}

package com.android.everytalk.ui.screens.MainScreen

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.viewmodel.resolveHistoryExpectedStableConversationId
import org.junit.Assert.assertEquals
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

    @Test
    fun `image regeneration keeps loaded conversation stable id instead of new first message id`() {
        val stableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = true,
            loadedHistoryIndex = 2,
            currentConversationId = "original_user_id",
            stableIdFromMessages = "new_regenerated_user_id"
        )

        assertEquals("original_user_id", stableId)
    }

    @Test
    fun `new image conversation still migrates to first message stable id`() {
        val stableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = true,
            loadedHistoryIndex = null,
            currentConversationId = "new_image_generation_123",
            stableIdFromMessages = "first_user_id"
        )

        assertEquals("first_user_id", stableId)
    }
}

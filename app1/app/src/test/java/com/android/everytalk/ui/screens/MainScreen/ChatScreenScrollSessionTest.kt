package com.android.everytalk.ui.screens.MainScreen

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldClearTransientBottomReserveOnStreamChange
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldEnableUserScrollForPinnedUserBubble
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldResetTransientBottomReserve
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
    fun `resets transient bottom reserve when visible conversation changes after streaming`() {
        val result = shouldResetTransientBottomReserve(
            previousConversationId = "conversation_a",
            currentConversationId = "conversation_b",
            isApiCalling = false
        )

        assertTrue(result)
    }

    @Test
    fun `keeps transient bottom reserve while active stream still owns pinned bubble`() {
        val result = shouldResetTransientBottomReserve(
            previousConversationId = "conversation_a",
            currentConversationId = "conversation_b",
            isApiCalling = true
        )

        assertFalse(result)
    }

    @Test
    fun `does not clear transient bottom reserve when stream stops in same conversation`() {
        val result = shouldClearTransientBottomReserveOnStreamChange(isApiCalling = false)

        assertFalse(result)
    }

    @Test
    fun `keeps user scroll enabled while user bubble is pinned over dynamic reserve`() {
        val result = shouldEnableUserScrollForPinnedUserBubble(
            grokScrollCompleted = true,
            isApiCalling = true,
            hasPinnedUserMessage = true,
            hasDynamicBottomReserve = true
        )

        assertTrue(result)
    }

    @Test
    fun `enables user scroll when no pinned dynamic reserve exists`() {
        val result = shouldEnableUserScrollForPinnedUserBubble(
            grokScrollCompleted = true,
            isApiCalling = false,
            hasPinnedUserMessage = false,
            hasDynamicBottomReserve = false
        )

        assertTrue(result)
    }
}

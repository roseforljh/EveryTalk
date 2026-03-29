package com.android.everytalk.ui.screens.viewmodel

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HistoryManagerCustomPromptPersistenceTest {

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `save current chat removes legacy prompt system messages and does not prepend active prompt`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        stateHolder.setCurrentConversationId("conv-1")
        stateHolder.systemPrompts["conv-1"] = "你是翻译助手"
        stateHolder.messages.add(
            Message(
                id = "legacy-history-prompt",
                text = "你是翻译助手",
                sender = Sender.System
            )
        )
        stateHolder.messages.add(
            Message(
                id = "system_conv-1",
                text = "你是翻译助手",
                sender = Sender.System,
                contentStarted = true
            )
        )
        stateHolder.messages.add(
            Message(
                id = "user-1",
                text = "hello",
                sender = Sender.User
            )
        )

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            val savedConversation = stateHolder._historicalConversations.value.single()
            assertEquals(1, savedConversation.size)
            assertEquals(Sender.User, savedConversation.first().sender)
            assertEquals("hello", savedConversation.first().text)
            assertTrue(savedConversation.none { it.sender == Sender.System })
        } finally {
            scope.cancel()
        }
    }
}

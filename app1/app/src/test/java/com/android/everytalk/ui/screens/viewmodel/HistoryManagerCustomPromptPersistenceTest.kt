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
import kotlinx.coroutines.withTimeout
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

    @Test
    fun `queued force save keeps original conversation snapshot when user switches history immediately`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val originalConversation = listOf(
            Message(id = "user-a", text = "问题 a", sender = Sender.User),
            Message(id = "ai-a-old", text = "旧回答", sender = Sender.AI),
        )
        val updatedConversation = originalConversation + Message(
            id = "ai-a-new",
            text = "回答 b",
            sender = Sender.AI,
            contentStarted = true,
        )
        val otherConversation = listOf(
            Message(id = "user-other", text = "另一个会话", sender = Sender.User),
            Message(id = "ai-other", text = "另一个回答", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(originalConversation, otherConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-a")
        stateHolder.messages.addAll(updatedConversation)

        try {
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)

            stateHolder.messages.clear()
            stateHolder.messages.addAll(otherConversation)
            stateHolder._loadedHistoryIndex.value = 1
            stateHolder.setCurrentConversationId("user-other")

            withTimeout(1_000) {
                while (stateHolder._historicalConversations.value[0].none { it.text == "回答 b" }) {
                    kotlinx.coroutines.delay(10)
                }
            }

            assertEquals("回答 b", stateHolder._historicalConversations.value[0].last().text)
            assertEquals("另一个回答", stateHolder._historicalConversations.value[1].last().text)
            assertEquals(1, stateHolder._loadedHistoryIndex.value)
            assertEquals("user-other", stateHolder._currentConversationId.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `force save updates conversation by stable id when loaded index drifted`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val otherConversation = listOf(
            Message(id = "user-other", text = "另一个会话", sender = Sender.User),
            Message(id = "ai-other", text = "另一个回答", sender = Sender.AI),
        )
        val originalConversation = listOf(
            Message(id = "user-a", text = "问题 a", sender = Sender.User),
            Message(id = "ai-a-old", text = "旧回答", sender = Sender.AI),
        )
        val updatedConversation = listOf(
            Message(id = "user-a", text = "问题 a", sender = Sender.User),
            Message(id = "ai-a-new", text = "回答 b", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(otherConversation, originalConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-a")
        stateHolder.messages.addAll(updatedConversation)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals("另一个回答", stateHolder._historicalConversations.value[0].last().text)
            assertEquals("回答 b", stateHolder._historicalConversations.value[1].last().text)
            assertEquals(1, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `force save merges ai reply into loaded user only conversation when stable id changes`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val userOnlyConversation = listOf(
            Message(id = "temp-conversation-id", text = "你好", sender = Sender.User),
        )
        val completedConversation = listOf(
            Message(id = "actual-user-message-id", text = "你好", sender = Sender.User),
            Message(id = "ai-reply", text = "你好！请问有什么我可以帮你的吗？", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(userOnlyConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("temp-conversation-id")
        stateHolder.messages.addAll(completedConversation)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(1, stateHolder._historicalConversations.value.size)
            assertEquals("你好！请问有什么我可以帮你的吗？", stateHolder._historicalConversations.value[0].last().text)
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.cancel()
        }
    }
}

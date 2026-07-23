package com.android.everytalk.ui.screens.viewmodel

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ConversationFunctionToggleState
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.ViewModelStateHolder
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `queued force saves persist text and image conversations independently`() = runTest {
        val stateHolder = ViewModelStateHolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(SupervisorJob() + dispatcher)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        stateHolder.setCurrentConversationId("text-user")
        stateHolder._currentImageGenerationConversationId.value = "image-user"
        stateHolder.messages.add(Message(id = "text-user", text = "文本问题", sender = Sender.User))
        stateHolder.messages.add(Message(id = "text-ai", text = "文本回答", sender = Sender.AI))
        stateHolder.imageGenerationMessages.add(Message(id = "image-user", text = "画一张图", sender = Sender.User))
        stateHolder.imageGenerationMessages.add(Message(id = "image-ai", text = "图片完成", sender = Sender.AI))
        stateHolder.isTextConversationDirty.value = true
        stateHolder.isImageConversationDirty.value = true

        try {
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = false)
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)

            scope.advanceUntilIdle()

            assertEquals("文本回答", stateHolder._historicalConversations.value.single().last().text)
            assertEquals("图片完成", stateHolder._imageGenerationHistoricalConversations.value.single().last().text)
        } finally {
            scope.coroutineContext[Job]?.cancel()
            testScheduler.advanceUntilIdle()
        }
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
            scope.coroutineContext[Job]?.cancelAndJoin()
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
            scope.coroutineContext[Job]?.cancelAndJoin()
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

            assertEquals("回答 b", stateHolder._historicalConversations.value[0].last().text)
            assertEquals("另一个回答", stateHolder._historicalConversations.value[1].last().text)
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save moves updated existing conversation to top immediately`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val newerConversation = listOf(
            Message(id = "user-newer", text = "newer question", sender = Sender.User),
            Message(id = "ai-newer", text = "newer answer", sender = Sender.AI),
        )
        val oldConversation = listOf(
            Message(id = "user-old", text = "old question", sender = Sender.User),
            Message(id = "ai-old", text = "old answer", sender = Sender.AI),
        )
        val updatedOldConversation = listOf(
            Message(id = "user-old", text = "old question", sender = Sender.User),
            Message(id = "ai-old-updated", text = "updated old answer", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(newerConversation, oldConversation)
        stateHolder._loadedHistoryIndex.value = 1
        stateHolder.setCurrentConversationId("user-old")
        stateHolder.messages.addAll(updatedOldConversation)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals("updated old answer", stateHolder._historicalConversations.value[0].last().text)
            assertEquals("newer answer", stateHolder._historicalConversations.value[1].last().text)
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save keeps unchanged existing conversation at its original position`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var historyModifiedCount = 0
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = { historyModifiedCount++ },
            scope = scope
        )

        val newerConversation = listOf(
            Message(id = "user-newer", text = "newer question", sender = Sender.User),
            Message(id = "ai-newer", text = "newer answer", sender = Sender.AI),
        )
        val unchangedOlderConversation = listOf(
            Message(id = "user-older", text = "older question", sender = Sender.User),
            Message(id = "ai-older", text = "older answer", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value =
            listOf(newerConversation, unchangedOlderConversation)
        stateHolder._loadedHistoryIndex.value = 1
        stateHolder.setCurrentConversationId("user-older")
        stateHolder.messages.addAll(unchangedOlderConversation)
        stateHolder.isTextConversationDirty.value = false

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(newerConversation, stateHolder._historicalConversations.value[0])
            assertEquals(unchangedOlderConversation, stateHolder._historicalConversations.value[1])
            assertEquals(1, stateHolder._loadedHistoryIndex.value)
            assertEquals(0, historyModifiedCount)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
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
            assertEquals("actual-user-message-id", stateHolder._currentConversationId.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save keeps loaded conversation when regenerated first turn moves to bottom`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val originalConversation = listOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1", text = "answer one", sender = Sender.AI),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
        )
        val regeneratedConversation = listOf(
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1-new", text = "new answer one", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(originalConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-1")
        stateHolder.messages.addAll(regeneratedConversation)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(1, stateHolder._historicalConversations.value.size)
            assertEquals(regeneratedConversation, stateHolder._historicalConversations.value[0])
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
            assertEquals("user-1", stateHolder._currentConversationId.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save updates existing history by stable id when content fingerprint changed`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val savedConversation = listOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1", text = "answer one", sender = Sender.AI),
        )
        val updatedConversation = listOf(
            Message(id = "user-1", text = "question one edited order", sender = Sender.User),
            Message(id = "ai-1-new", text = "new answer", sender = Sender.AI),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(savedConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("stale-current-id")
        stateHolder.messages.addAll(updatedConversation)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(1, stateHolder._historicalConversations.value.size)
            assertEquals(updatedConversation, stateHolder._historicalConversations.value[0])
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save removes duplicate history entries with same stable id`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val firstVersion = listOf(
            Message(id = "user-1", text = "question one new", sender = Sender.User),
            Message(id = "ai-1-new", text = "answer one new", sender = Sender.AI),
        )
        val duplicateVersion = listOf(
            Message(id = "user-1", text = "question one old", sender = Sender.User),
            Message(id = "ai-1-old", text = "answer one old", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(firstVersion, duplicateVersion)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-1")
        stateHolder.messages.addAll(firstVersion)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(1, stateHolder._historicalConversations.value.size)
            assertEquals(firstVersion, stateHolder._historicalConversations.value[0])
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save keeps updated duplicate when older entry with same stable id appears first`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val olderDuplicate = listOf(
            Message(id = "user-1", text = "old question", sender = Sender.User),
            Message(id = "ai-old", text = "old answer", sender = Sender.AI),
        )
        val conversationBeingUpdated = listOf(
            Message(id = "user-1", text = "new question", sender = Sender.User),
            Message(id = "ai-new", text = "new answer", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(olderDuplicate, conversationBeingUpdated)
        stateHolder._loadedHistoryIndex.value = 1
        stateHolder.setCurrentConversationId("user-1")
        stateHolder.messages.addAll(conversationBeingUpdated)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(1, stateHolder._historicalConversations.value.size)
            assertEquals(conversationBeingUpdated, stateHolder._historicalConversations.value[0])
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save updates loaded history instead of inserting when stable id changed but loaded draft matches`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val loadedDraft = listOf(
            Message(id = "session-temp-id", text = "same question", sender = Sender.User),
        )
        val completedConversation = listOf(
            Message(id = "actual-user-id", text = "same question", sender = Sender.User),
            Message(id = "ai-reply", text = "new answer", sender = Sender.AI),
        )
        val olderConversation = listOf(
            Message(id = "older-user", text = "older question", sender = Sender.User),
            Message(id = "older-ai", text = "older answer", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(loadedDraft, olderConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("stale-live-id")
        stateHolder.messages.addAll(completedConversation)

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(2, stateHolder._historicalConversations.value.size)
            assertEquals(completedConversation, stateHolder._historicalConversations.value[0])
            assertEquals(olderConversation, stateHolder._historicalConversations.value[1])
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `queued force save migrates config binding from request conversation after live switch`() = runBlocking {
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
            Message(id = "temp-conversation-id", text = "question", sender = Sender.User),
        )
        val completedConversation = listOf(
            Message(id = "actual-user-id", text = "question", sender = Sender.User),
            Message(id = "ai-reply", text = "answer", sender = Sender.AI),
        )
        val otherConversation = listOf(
            Message(id = "user-other", text = "other", sender = Sender.User),
            Message(id = "ai-other", text = "other answer", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(originalConversation, otherConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("temp-conversation-id")
        stateHolder.messages.addAll(completedConversation)
        stateHolder.conversationApiConfigIds.value = mapOf(
            "temp-conversation-id" to "config-a",
            "user-other" to "config-b",
        )

        try {
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)

            stateHolder.messages.clear()
            stateHolder.messages.addAll(otherConversation)
            stateHolder._loadedHistoryIndex.value = 1
            stateHolder.setCurrentConversationId("user-other")

            withTimeout(1_000) {
                while (
                    stateHolder._historicalConversations.value[0].last().text != "answer" ||
                    stateHolder.conversationApiConfigIds.value["actual-user-id"] != "config-a"
                ) {
                    kotlinx.coroutines.delay(10)
                }
            }

            assertEquals("config-a", stateHolder.conversationApiConfigIds.value["actual-user-id"])
            assertEquals("config-b", stateHolder.conversationApiConfigIds.value["user-other"])
            assertFalse(stateHolder.conversationApiConfigIds.value.containsKey("temp-conversation-id"))
            assertEquals("user-other", stateHolder._currentConversationId.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `force save prunes orphan conversation state mappings`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val conversation = listOf(
            Message(id = "user-keep", text = "question", sender = Sender.User),
            Message(id = "ai-keep", text = "answer", sender = Sender.AI),
        )
        stateHolder._historicalConversations.value = listOf(conversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-keep")
        stateHolder.messages.addAll(conversation)
        stateHolder.conversationApiConfigIds.value = mapOf(
            "user-keep" to "config-keep",
            "orphan-api" to "config-old",
        )
        stateHolder.conversationFunctionToggleStates.value = mapOf(
            "user-keep" to ConversationFunctionToggleState(webSearchEnabled = true),
            "orphan-toggle" to ConversationFunctionToggleState(codeExecutionEnabled = true),
        )
        stateHolder.systemPrompts["user-keep"] = "keep"
        stateHolder.systemPrompts["orphan-system"] = "old"
        stateHolder.conversationScrollStates["user-keep"] = ConversationScrollState(firstVisibleItemIndex = 1)
        stateHolder.conversationScrollStates["orphan-scroll"] = ConversationScrollState(firstVisibleItemIndex = 9)
        stateHolder.markTextHistoryReadyForParameterCleanup()
        stateHolder.markImageHistoryReadyForStateCleanup()

        try {
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(setOf("user-keep"), stateHolder.conversationApiConfigIds.value.keys)
            assertEquals(setOf("user-keep"), stateHolder.conversationFunctionToggleStates.value.keys)
            assertEquals(setOf("user-keep"), stateHolder.systemPrompts.keys)
            assertEquals(setOf("user-keep"), stateHolder.conversationScrollStates.keys)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `successive regenerations keep one history entry and append regenerated turns`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = mockk(relaxed = true),
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope
        )

        val originalConversation = listOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1", text = "answer one", sender = Sender.AI),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
            Message(id = "user-3", text = "question three", sender = Sender.User),
            Message(id = "ai-3", text = "answer three", sender = Sender.AI),
        )
        val afterFirstRegeneration = listOf(
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
            Message(id = "user-3", text = "question three", sender = Sender.User),
            Message(id = "ai-3", text = "answer three", sender = Sender.AI),
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1-new", text = "new answer one", sender = Sender.AI),
        )
        val afterSecondRegenerationAndNewQuestion = listOf(
            Message(id = "user-3", text = "question three", sender = Sender.User),
            Message(id = "ai-3", text = "answer three", sender = Sender.AI),
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1-new", text = "new answer one", sender = Sender.AI),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2-new", text = "new answer two", sender = Sender.AI),
            Message(id = "user-4", text = "question four", sender = Sender.User),
            Message(id = "ai-4", text = "answer four", sender = Sender.AI),
        )

        stateHolder._historicalConversations.value = listOf(originalConversation)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-1")

        try {
            stateHolder.messages.addAll(afterFirstRegeneration)
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            stateHolder.messages.clear()
            stateHolder.messages.addAll(afterSecondRegenerationAndNewQuestion)
            historyManager.saveCurrentChatToHistoryNow(forceSave = true)

            assertEquals(1, stateHolder._historicalConversations.value.size)
            assertEquals(afterSecondRegenerationAndNewQuestion, stateHolder._historicalConversations.value[0])
            assertEquals(0, stateHolder._loadedHistoryIndex.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `排队保存后删除同一会话不会被旧快照重新写回`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope,
        )
        val original = listOf(
            Message(id = "user-delete", text = "问题", sender = Sender.User),
            Message(id = "ai-old", text = "旧回答", sender = Sender.AI),
        )
        val updated = original + Message(id = "ai-new", text = "新回答", sender = Sender.AI)
        val retained = listOf(
            Message(id = "user-retain", text = "保留", sender = Sender.User),
        )
        stateHolder._historicalConversations.value = listOf(original, retained)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-delete")
        stateHolder.messages.addAll(updated)
        stateHolder.isTextConversationDirty.value = true

        try {
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            historyManager.deleteConversation(indexToDelete = 0)

            assertEquals(listOf(retained), stateHolder._historicalConversations.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `同步保存和重命名持久化按提交顺序串行执行`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        coEvery { persistenceManager.saveHistorySession(any(), any(), any()) } coAnswers {
            calls += "dirty-save"
        }
        coEvery { persistenceManager.saveChatHistory(any(), any()) } coAnswers {
            calls += "direct-sync"
        }
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope,
        )
        val original = listOf(Message(id = "user-1", text = "问题", sender = Sender.User))
        val updated = original + Message(id = "ai-1", text = "回答", sender = Sender.AI)
        stateHolder._historicalConversations.value = listOf(original)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-1")
        stateHolder.messages.addAll(updated)
        stateHolder.isTextConversationDirty.value = true

        try {
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            historyManager.persistHistoryListDirectly()

            assertEquals(listOf("dirty-save", "direct-sync"), calls)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `持久化失败后未变化的脏会话仍会重试`() = runBlocking {
        val stateHolder = ViewModelStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        var attempts = 0
        coEvery { persistenceManager.saveHistorySession(any(), any(), any()) } coAnswers {
            attempts++
            if (attempts == 1) error("模拟首次写入失败")
        }
        val historyManager = HistoryManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            compareMessageLists = { left, right -> left == right },
            onHistoryModified = {},
            scope = scope,
        )
        val original = listOf(Message(id = "user-retry", text = "问题", sender = Sender.User))
        val updated = original + Message(id = "ai-retry", text = "回答", sender = Sender.AI)
        stateHolder._historicalConversations.value = listOf(original)
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.setCurrentConversationId("user-retry")
        stateHolder.messages.addAll(updated)
        stateHolder.isTextConversationDirty.value = true

        try {
            runCatching { historyManager.saveCurrentChatToHistoryNow() }
            assertTrue(stateHolder.isTextConversationDirty.value)

            historyManager.saveCurrentChatToHistoryNow()

            coVerify(exactly = 2) { persistenceManager.saveHistorySession("user-retry", updated, false) }
            assertFalse(stateHolder.isTextConversationDirty.value)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}

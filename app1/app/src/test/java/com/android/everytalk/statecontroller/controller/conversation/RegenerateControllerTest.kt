package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class RegenerateControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = TestScope(dispatcher)
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
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `text regenerate reuses original user id and keeps conversation identity`() = scope.runTest {
        val stateHolder = stateHolderWithTextConfig()
        stateHolder.setCurrentConversationId("user-1")
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.messages.addAll(
            listOf(
                Message(id = "user-1", text = "问题", sender = Sender.User),
                Message(id = "ai-1", text = "旧回答", sender = Sender.AI),
            )
        )

        var sentManualId: String? = null
        val sentLatch = CountDownLatch(1)
        val controller = createController(stateHolder) { text, _, _, isImageGeneration, manualMessageId ->
            sentManualId = manualMessageId
            val target = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val existingIndex = target.indexOfFirst { it.id == manualMessageId }
            val userMessage = Message(id = manualMessageId ?: "missing", text = text, sender = Sender.User)
            if (existingIndex >= 0) {
                target[existingIndex] = userMessage
            } else {
                target.add(userMessage)
            }
            sentLatch.countDown()
        }

        controller.regenerateFrom(stateHolder.messages[1], isImageGeneration = false)
        advanceUntilIdle()
        assertTrue(sentLatch.await(1, TimeUnit.SECONDS))
        advanceUntilIdle()

        assertEquals("user-1", sentManualId)
        assertEquals("user-1", stateHolder._currentConversationId.value)
        assertEquals(listOf("user-1"), stateHolder.messages.map { it.id })
    }

    @Test
    fun `text regenerate preserves later turns when regenerating earlier answer`() = scope.runTest {
        val stateHolder = stateHolderWithTextConfig()
        stateHolder.setCurrentConversationId("user-1")
        stateHolder._loadedHistoryIndex.value = 0
        stateHolder.messages.addAll(
            listOf(
                Message(id = "user-1", text = "第一问", sender = Sender.User),
                Message(id = "ai-1", text = "第一答", sender = Sender.AI),
                Message(id = "user-2", text = "第二问", sender = Sender.User),
                Message(id = "ai-2", text = "第二答", sender = Sender.AI),
            )
        )

        val sentLatch = CountDownLatch(1)
        val controller = createController(stateHolder) { text, _, _, isImageGeneration, manualMessageId ->
            val target = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val existingIndex = target.indexOfFirst { it.id == manualMessageId }
            val userMessage = Message(id = manualMessageId ?: "missing", text = text, sender = Sender.User)
            if (existingIndex >= 0) {
                target[existingIndex] = userMessage
                target.add(existingIndex + 1, Message(id = "new-ai-1", text = "new answer one", sender = Sender.AI))
            } else {
                target.add(userMessage)
            }
            sentLatch.countDown()
        }

        controller.regenerateFrom(stateHolder.messages[1], isImageGeneration = false)
        advanceUntilIdle()
        assertTrue(sentLatch.await(1, TimeUnit.SECONDS))
        advanceUntilIdle()

        assertEquals(listOf("user-1", "new-ai-1", "user-2", "ai-2"), stateHolder.messages.map { it.id })
    }

    @Test
    fun `collect regeneration branch removes only answers before next user message`() {
        val messages = listOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1", text = "answer one", sender = Sender.AI),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
        )

        val branch = collectRegenerationBranch(messages, userMessageIndex = 0)

        assertEquals(listOf("ai-1"), branch.map { it.id })
    }

    @Test
    fun `text regenerate latest answer keeps previous turns`() {
        val messages = listOf(
            Message(id = "user-1", text = "question one", sender = Sender.User),
            Message(id = "ai-1", text = "answer one", sender = Sender.AI),
            Message(id = "user-2", text = "question two", sender = Sender.User),
            Message(id = "ai-2", text = "answer two", sender = Sender.AI),
        )

        val branch = collectRegenerationBranch(messages, userMessageIndex = 2)

        assertEquals(listOf("ai-2"), branch.map { it.id })
    }

    @Test
    fun `media cleanup for regenerate excludes reused base user message`() {
        val messagesToRemove = listOf(
            Message(id = "user-1", text = "带附件的问题", sender = Sender.User),
            Message(id = "ai-1", text = "旧回答", sender = Sender.AI),
        )

        val cleanupIds = filterRegenerationMediaCleanupMessages(
            messagesToRemove = messagesToRemove,
            baseUserMessageId = "user-1"
        ).map { it.id }

        assertFalse(cleanupIds.contains("user-1"))
        assertTrue(cleanupIds.contains("ai-1"))
    }

    @Test
    fun `image regenerate from error reuses original user id and keeps conversation identity`() = scope.runTest {
        val stateHolder = stateHolderWithImageConfig()
        stateHolder._currentImageGenerationConversationId.value = "image-user-1"
        stateHolder.imageGenerationMessages.addAll(
            listOf(
                Message(id = "image-user-1", text = "画一只猫", sender = Sender.User),
                Message(id = "image-ai-error", text = "生成失败", sender = Sender.AI, isError = true),
            )
        )

        var sentManualId: String? = null
        val sentLatch = CountDownLatch(1)
        val controller = createController(stateHolder) { text, _, _, isImageGeneration, manualMessageId ->
            sentManualId = manualMessageId
            val target = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val existingIndex = target.indexOfFirst { it.id == manualMessageId }
            val userMessage = Message(id = manualMessageId ?: "missing", text = text, sender = Sender.User)
            if (existingIndex >= 0) {
                target[existingIndex] = userMessage
            } else {
                target.add(userMessage)
            }
            sentLatch.countDown()
        }

        controller.regenerateFrom(stateHolder.imageGenerationMessages[1], isImageGeneration = true)
        advanceUntilIdle()
        assertTrue(sentLatch.await(1, TimeUnit.SECONDS))
        advanceUntilIdle()

        assertEquals("image-user-1", sentManualId)
        assertEquals("image-user-1", stateHolder._currentImageGenerationConversationId.value)
        assertEquals(listOf("image-user-1"), stateHolder.imageGenerationMessages.map { it.id })
    }

    private fun stateHolderWithTextConfig(): ViewModelStateHolder {
        return ViewModelStateHolder().apply {
            _selectedApiConfig.value = ApiConfig(
                address = "https://example.test/v1",
                key = "test-key",
                model = "test-model",
                provider = "OpenAI",
                name = "test-config"
            )
        }
    }

    private fun stateHolderWithImageConfig(): ViewModelStateHolder {
        return ViewModelStateHolder().apply {
            _selectedImageGenApiConfig.value = ApiConfig(
                address = "https://example.test/v1",
                key = "test-key",
                model = "test-image-model",
                provider = "OpenAI",
                name = "test-image-config"
            )
        }
    }

    private fun createController(
        stateHolder: ViewModelStateHolder,
        onDeleteMediaFor: suspend (List<List<Message>>) -> Unit = {},
        onSendMessage: (
            messageText: String,
            isFromRegeneration: Boolean,
            attachments: List<SelectedMediaItem>,
            isImageGeneration: Boolean,
            manualMessageId: String?
        ) -> Unit,
    ): RegenerateController {
        return RegenerateController(
            stateHolder = stateHolder,
            apiHandler = mockk<ApiHandler>(relaxed = true),
            scope = scope,
            messagesMutex = kotlinx.coroutines.sync.Mutex(),
            persistenceDeleteMediaFor = onDeleteMediaFor,
            showSnackbar = {},
            shouldAutoScroll = { false },
            triggerScrollToBottom = {},
            sendMessage = onSendMessage
        )
    }
}

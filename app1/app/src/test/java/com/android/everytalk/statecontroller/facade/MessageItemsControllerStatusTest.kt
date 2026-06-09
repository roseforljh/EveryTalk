package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import androidx.compose.runtime.snapshots.Snapshot
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageItemsControllerStatusTest {

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
        every { android.util.Log.isLoggable(any(), any()) } returns false
    }

    @After
    fun tearDown() {
        MessageItemsControllerTestAccess.closeAll()
        unmockkAll()
    }

    @Test
    fun `normalize status text keeps remote control progress text`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val result = controller.normalizeStatusTextForTest(
            Message(
                id = "ai-1",
                text = "plain",
                sender = Sender.AI,
                currentWebSearchStage = "远程控制中 · 正在修改 /workspace/app/main.kt"
            )
        )

        assertEquals("远程控制中 · 正在修改 /workspace/app/main.kt", result)
    }

    @Test
    fun `normalize status text converts tool result summary into receipt style`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val result = controller.normalizeStatusTextForTest(
            Message(
                id = "ai-2",
                text = "[工具结果] fs.write: 已修改 /workspace/app/main.kt",
                sender = Sender.AI,
                currentWebSearchStage = "done"
            )
        )

        assertEquals("工具结果 · fs.write: 已修改 /workspace/app/main.kt", result)
    }

    @Test
    fun `system message is rendered into system chat list item`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "user-1",
                text = "hello",
                sender = Sender.User
            )
        )
        controller.stateHolder.messages.add(
            Message(
                id = "system-1",
                text = "slash 输出",
                sender = Sender.System
            )
        )

        val items = controller.chatListItemsForTest()

        assertEquals(2, items.size)
        assertTrue(items[1] is ChatListItem.SystemMessage)
        val item = items[1] as ChatListItem.SystemMessage
        assertEquals("system-1", item.messageId)
        assertEquals("slash 输出", item.text)
    }

    @Test
    fun `leading legacy system prompt messages are filtered from chat list items`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "system_history_prompt",
                text = "你是翻译助手",
                sender = Sender.System
            )
        )
        controller.stateHolder.messages.add(
            Message(
                id = "system_runtime_prompt",
                text = "你是翻译助手",
                sender = Sender.System,
                contentStarted = true
            )
        )
        controller.stateHolder.messages.add(
            Message(
                id = "user-1",
                text = "hello",
                sender = Sender.User
            )
        )

        val items = controller.chatListItemsForTest()

        assertEquals(1, items.size)
        assertTrue(items.first() is ChatListItem.UserMessage)
    }

    @Test
    fun `connecting stage text uses factual runtime fallback until backend reports progress`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "test",
                text = "",
                sender = Sender.AI,
                providerName = "OpenAI",
                modelName = "gpt-4o"
            ),
            6000L
        )

        assertEquals("等待首个响应 · OpenAI · gpt-4o · 6s", text)
    }

    @Test
    fun `legacy webfetch and search stage codes fall back to factual runtime text`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val webfetchText = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-webfetch",
                text = "",
                sender = Sender.AI,
                currentWebSearchStage = "webfetch_reading"
            ),
            0L
        )
        val searchText = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-search",
                text = "",
                sender = Sender.AI,
                currentWebSearchStage = "searching_web"
            ),
            0L
        )

        assertEquals("等待首个响应 · 0s", webfetchText)
        assertEquals("等待首个响应 · 0s", searchText)
    }

    @Test
    fun `internal protocol status codes fall back to factual runtime text`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val statuses = listOf(
            "chat_run:run-123",
            "agent_run:run-456",
            "history_loaded:2",
            "pairing_pending:device-1",
            "health:ok",
            "CHAT_RUN:run-789",
            "connected",
            "subscribed",
            "done"
        )

        statuses.forEach { status ->
            val text = controller.resolveStreamingStageTextForTest(
                Message(
                    id = "ai-$status",
                    text = "",
                    sender = Sender.AI,
                    currentWebSearchStage = status
                ),
                0L
            )
            assertEquals("status=$status", "等待首个响应 · 0s", text)
        }
    }

    @Test
    fun `reasoning loading stage uses factual reasoning state`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-reasoning",
                text = "",
                sender = Sender.AI,
                reasoning = "先分析问题",
                providerName = "Gemini"
            ),
            2500L
        )

        assertEquals("正在接收思考 · Gemini · 2s", text)
    }

    @Test
    fun `streaming stage text uses backend progress verbatim`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-real-progress",
                text = "",
                sender = Sender.AI,
                currentWebSearchStage = "搜索网页 2/5：正在读取 example.com"
            ),
            0L
        )

        assertEquals("搜索网页 2/5：正在读取 example.com", text)
    }

    @Test
    fun `streaming stage text disappears after content starts`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-streaming",
                text = "已经开始输出",
                sender = Sender.AI,
                contentStarted = true,
                currentWebSearchStage = "searching_web",
                executionStatus = "我先上网查一下…"
            ),
            2500L
        )

        assertNull(text)
    }

    @Test
    fun `chat list removes loading and status indicators after content starts`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-content",
                text = "已经开始输出",
                sender = Sender.AI,
                contentStarted = true,
                currentWebSearchStage = "searching_web",
                executionStatus = "我先上网查一下…"
            )
        )

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessage })
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
        assertFalse(items.any { it is ChatListItem.StatusIndicator })
    }

    @Test
    fun `chat list hides status indicator while streaming content is already visible`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-streaming-status",
                text = "已经开始输出",
                sender = Sender.AI,
                contentStarted = true,
                executionStatus = "我先调用一下工具看看…"
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-streaming-status"

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessage })
        assertFalse(items.any { it is ChatListItem.StatusIndicator })
    }

    @Test
    fun `bubble state does not return to connecting once visible content has arrived during api call`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.seedStreamingRenderContent("ai-streaming", "已经输出一部分内容")

        val state = controller.computeBubbleStateForTest(
            message = Message(
                id = "ai-streaming",
                text = "",
                sender = Sender.AI,
                contentStarted = false
            ),
            isApiCalling = true,
            currentStreamingAiMessageId = "ai-streaming"
        )

        assertTrue(state is com.android.everytalk.ui.state.AiBubbleState.Streaming)
    }

    @Test
    fun `stage text disappears when streaming render state already has content`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.seedStreamingRenderContent("ai-stage-hidden", "已经输出正文")

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-stage-hidden",
                text = "",
                sender = Sender.AI,
                contentStarted = true
            ),
            4000L
        )

        assertNull(text)
    }

    @Test
    fun `stage text falls back to runtime state when no backend progress remains`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-terminal-writing",
                text = "",
                sender = Sender.AI,
                contentStarted = false,
                executionStatus = null,
                currentWebSearchStage = null,
                reasoning = null
            ),
            6000L
        )

        assertEquals("等待首个响应 · 6s", text)
    }

    @Test
    fun `text loading indicator includes factual runtime text before first content`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-loading-text",
                text = "",
                sender = Sender.AI,
                providerName = "OpenAI",
                modelName = "gpt-4o"
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-loading-text"

        val items = controller.chatListItemsForTest()
        val loading = items.filterIsInstance<ChatListItem.LoadingIndicator>().single()

        assertTrue(loading.text.orEmpty().startsWith("等待首个响应 · OpenAI · gpt-4o ·"))
    }

    @Test
    fun `image loading indicator includes factual runtime text before first content`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.imageGenerationMessages.add(
            Message(
                id = "ai-loading-image",
                text = "",
                sender = Sender.AI,
                providerName = "Gemini",
                modelName = "imagen-3"
            )
        )
        controller.stateHolder._isImageApiCalling.value = true
        controller.stateHolder._currentImageStreamingAiMessageId.value = "ai-loading-image"

        val items = runBlocking {
            controller.imageGenerationChatListItems.first { chatItems ->
                chatItems.any { it is ChatListItem.LoadingIndicator }
            }
        }
        val loading = items.filterIsInstance<ChatListItem.LoadingIndicator>().single()

        assertTrue(loading.text.orEmpty().startsWith("等待首个响应 · Gemini · imagen-3 ·"))
    }

    @Test
    fun `image loading indicator runtime text advances while waiting for first content`() = runBlocking {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.imageGenerationMessages.add(
            Message(
                id = "ai-loading-image-timer",
                text = "",
                sender = Sender.AI,
                providerName = "Gemini",
                modelName = "imagen-3"
            )
        )
        controller.stateHolder._isImageApiCalling.value = true
        controller.stateHolder._currentImageStreamingAiMessageId.value = "ai-loading-image-timer"

        val firstItems = withTimeout(2_000) {
            controller.imageGenerationChatListItems.first { chatItems ->
                chatItems.any { it is ChatListItem.LoadingIndicator }
            }
        }
        val firstText = firstItems.filterIsInstance<ChatListItem.LoadingIndicator>().single().text
        delay(1_100)

        val secondItems = withTimeout(2_000) {
            controller.imageGenerationChatListItems.first { chatItems ->
                chatItems.filterIsInstance<ChatListItem.LoadingIndicator>()
                    .singleOrNull()
                    ?.text != firstText
            }
        }
        val secondText = secondItems.filterIsInstance<ChatListItem.LoadingIndicator>().single().text

        assertTrue(secondText.orEmpty().startsWith("等待首个响应 · Gemini · imagen-3 ·"))
        assertTrue(secondText.orEmpty() != firstText.orEmpty())
    }

    @Test
    fun `image generation flow should not keep loading when render state already has content`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.seedStreamingRenderContent("img-streaming", "图片描述已经开始输出")
        controller.stateHolder.imageGenerationMessages.add(
            Message(
                id = "img-streaming",
                text = "",
                sender = Sender.AI,
                contentStarted = false
            )
        )
        controller.stateHolder._isImageApiCalling.value = true
        controller.stateHolder._currentImageStreamingAiMessageId.value = "img-streaming"

        val items = runBlocking { controller.imageGenerationChatListItems.first { it.isNotEmpty() } }

        assertTrue(items.any { it is ChatListItem.AiMessage })
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `chat list rebuilds ai item when final text arrives after streaming cache`() = runBlocking {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.seedStreamingRenderContent("ai-final", "你好！请问有什么我可以帮你的吗？")
        controller.stateHolder.messages.add(
            Message(
                id = "ai-final",
                text = "",
                sender = Sender.AI,
                contentStarted = true
            )
        )

        controller.chatListItems.first { it.any { item -> item is ChatListItem.AiMessage } }
        val updatedItems = async {
            withTimeout(1_000) {
                controller.chatListItems.first { chatItems ->
                    chatItems.filterIsInstance<ChatListItem.AiMessage>().firstOrNull()?.text ==
                        "你好！请问有什么我可以帮你的吗？"
                }
            }
        }
        controller.stateHolder.messages.clear()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-final",
                text = "你好！请问有什么我可以帮你的吗？",
                sender = Sender.AI,
                contentStarted = true
            )
        )
        Snapshot.sendApplyNotifications()

        val items = updatedItems.await()

        assertEquals("你好！请问有什么我可以帮你的吗？", items.filterIsInstance<ChatListItem.AiMessage>().first().text)
    }

}

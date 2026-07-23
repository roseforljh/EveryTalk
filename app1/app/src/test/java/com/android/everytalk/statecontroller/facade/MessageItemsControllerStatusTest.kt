package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.markdown.footnoteDefinitionUri
import com.android.everytalk.ui.components.markdown.footnoteReferenceUri
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

        assertTrue(result.startsWith("远程控制中 · 正在修改"))
        assertTrue(result.endsWith("..."))
        assertTrue(result.length <= 28)
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

        assertTrue(result.startsWith("工具结果 · fs.write: 已修改"))
        assertTrue(result.endsWith("..."))
        assertTrue(result.length <= 28)
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

        assertEquals("等待首个响应", text)
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

        assertEquals("读取网页", webfetchText)
        assertEquals("搜索网页", searchText)
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
            assertEquals("status=$status", "等待首个响应", text)
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

        assertEquals("正在接收思考", text)
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
    fun `long web search stage is compacted for one line status`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-long-progress",
                text = "",
                sender = Sender.AI,
                currentWebSearchStage = "搜索网页 2/5：正在读取 https://example.com/some/really/long/path/for/status"
            ),
            0L
        )

        assertTrue(text.orEmpty().startsWith("搜索网页 2/5：正在读取"))
        assertTrue(text.orEmpty().endsWith("..."))
        assertTrue(text.orEmpty().length <= 28)
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

        assertTrue(items.any { it is ChatListItem.AiMarkdownNode })
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
        assertFalse(items.any { it is ChatListItem.StatusIndicator })
    }

    @Test
    fun `completed ai item carries prepared markdown and extracted sources`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-prepared",
                text = "# 标题\n\n正文\n\nSources:\n[示例](https://example.com)",
                sender = Sender.AI,
                contentStarted = true,
            )
        )

        val items = controller.chatListItemsForTest()
        val sources = items.filterIsInstance<ChatListItem.AiMessageSources>().single()
        val firstNode = items.filterIsInstance<ChatListItem.AiMarkdownNode>().first()

        assertEquals("https://example.com", sources.pageSources.single().href)
        assertEquals("# 标题\n\n正文", firstNode.preparedMessage.markdown)
        assertEquals(firstNode.preparedMessage.markdown, firstNode.preparedMarkdownDocument.state.content)
    }

    @Test
    fun `completed ai item exposes lazy markdown nodes from the same prepared content`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-lazy-document",
                text = "# 标题\n\n第一段\n\n- 项目一\n- 项目二",
                sender = Sender.AI,
                contentStarted = true,
            )
        )

        val nodes = controller.chatListItemsForTest()
            .filterIsInstance<ChatListItem.AiMarkdownNode>()
        val document = nodes.first().preparedMarkdownDocument

        assertEquals(nodes.first().preparedMessage.markdown, document.state.content)
        assertTrue(document.nodes.size > 1)
        assertEquals(document.nodes.size, nodes.size)
    }

    @Test
    fun `lazy markdown document indexes footnote targets across nodes`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-lazy-footnote",
                text = "正文[^note]。\n\n中间段落。\n\n[^note]: 脚注内容",
                sender = Sender.AI,
                contentStarted = true,
            )
        )

        val document = controller.chatListItemsForTest()
            .filterIsInstance<ChatListItem.AiMarkdownNode>()
            .first()
            .preparedMarkdownDocument

        assertTrue(document.targetNodeIndexByUri.containsKey(footnoteDefinitionUri(1)))
        assertTrue(document.targetNodeIndexByUri.containsKey(footnoteReferenceUri(1, 1)))
    }

    @Test
    fun `completed code item also carries background prepared render`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-code-prepared",
                text = "```kotlin\nprintln(\"hi\")\n```",
                sender = Sender.AI,
                outputType = "code",
                contentStarted = true,
            )
        )

        val item = controller.chatListItemsForTest()
            .filterIsInstance<ChatListItem.AiMarkdownNode>()
            .single()

        assertEquals("```kotlin\nprintln(\"hi\")\n```", item.preparedMessage.markdown)
        assertEquals(item.preparedMessage.markdown, item.preparedMarkdownDocument.state.content)
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
    fun `暂停状态不会把已完成消息降级为空闲状态`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder._isStreamingPaused.value = true

        val state = controller.computeBubbleStateForTest(
            message = Message(
                id = "ai-paused-complete",
                text = "完整回复",
                sender = Sender.AI,
                contentStarted = true,
            ),
            isApiCalling = false,
            currentStreamingAiMessageId = null,
        )

        assertTrue(state is com.android.everytalk.ui.state.AiBubbleState.Complete)
    }

    @Test
    fun `暂停期间完成流式响应会保留最后一帧并在恢复时追平终态`() = runBlocking {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-paused-finish",
                text = "部分回复",
                sender = Sender.AI,
                contentStarted = true,
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-paused-finish"

        val initialItems = withTimeout(1_000) {
            controller.chatListItems.first { items ->
                items.filterIsInstance<ChatListItem.AiMessage>().firstOrNull()?.text == "部分回复"
            }
        }

        controller.stateHolder._isStreamingPaused.value = true
        delay(20)
        controller.stateHolder.messages[0] = Message(
            id = "ai-paused-finish",
            text = "完整回复",
            sender = Sender.AI,
            contentStarted = true,
        )
        controller.stateHolder._isTextApiCalling.value = false
        controller.stateHolder._currentTextStreamingAiMessageId.value = null
        Snapshot.sendApplyNotifications()
        delay(50)

        assertEquals(initialItems, controller.chatListItems.value)

        controller.stateHolder._isStreamingPaused.value = false
        val resumedItems = withTimeout(1_000) {
            controller.chatListItems.first { items ->
                items.filterIsInstance<ChatListItem.AiMessage>().firstOrNull()?.text == "完整回复"
            }
        }

        assertEquals(
            "完整回复",
            resumedItems.filterIsInstance<ChatListItem.AiMessage>().first().text,
        )
        assertFalse(resumedItems.any { it is ChatListItem.AiMarkdownNode })
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

        assertEquals("等待首个响应", text)
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

        assertEquals("等待首个响应", loading.text)
        assertFalse(loading.text.orEmpty().contains("OpenAI"))
        assertFalse(loading.text.orEmpty().contains("gpt-4o"))
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

        assertEquals("等待首个响应", loading.text)
        assertFalse(loading.text.orEmpty().contains("Gemini"))
        assertFalse(loading.text.orEmpty().contains("imagen-3"))
    }

    @Test
    fun `image loading indicator remains stable while waiting for first content`() = runBlocking {
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

        val secondItems = controller.imageGenerationChatListItems.value
        val secondText = secondItems.filterIsInstance<ChatListItem.LoadingIndicator>().single().text

        assertEquals("等待首个响应", secondText)
        assertFalse(secondText.orEmpty().contains("Gemini"))
        assertFalse(secondText.orEmpty().contains("imagen-3"))
        assertEquals(firstText, secondText)
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

        assertEquals(
            "你好！请问有什么我可以帮你的吗？",
            items.filterIsInstance<ChatListItem.AiMessage>().first().text,
        )
        assertFalse(items.any { it is ChatListItem.AiMarkdownNode })
    }

}

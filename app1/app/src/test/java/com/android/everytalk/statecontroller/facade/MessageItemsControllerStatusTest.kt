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
    fun `connecting stage text changes based on elapsed time`() {
        val controller = MessageItemsControllerTestAccess.newController()
        
        // Test different elapsed times
        val text1 = controller.resolveStreamingStageTextForTest(Message(id = "test", text = "", sender = Sender.AI), 500L)
        val text2 = controller.resolveStreamingStageTextForTest(Message(id = "test", text = "", sender = Sender.AI), 1500L)
        val text3 = controller.resolveStreamingStageTextForTest(Message(id = "test", text = "", sender = Sender.AI), 3000L)
        val text4 = controller.resolveStreamingStageTextForTest(Message(id = "test", text = "", sender = Sender.AI), 4500L)
        val text5 = controller.resolveStreamingStageTextForTest(Message(id = "test", text = "", sender = Sender.AI), 6000L)
        
        assertEquals("我先想想怎么回答…", text1)
        assertEquals("我在整理思路…", text2)
        assertEquals("我在整理思路…", text3)
        assertEquals("我来组织一下答案…", text4)
        assertEquals("我正在把结果写出来…", text5)
    }

    @Test
    fun `webfetch and search stages use natural copy`() {
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

        assertEquals("我正在读网页里的内容…", webfetchText)
        assertEquals("我先上网查一下…", searchText)
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
    fun `chat list keeps status indicator while streaming content is already visible`() {
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
        assertTrue(items.any { it is ChatListItem.StatusIndicator })
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
    fun `terminal writing stage text is hidden when no tool or search stage remains`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-terminal-writing",
                text = "",
                sender = Sender.AI,
                contentStarted = false,
                executionStatus = "我正在把结果写出来…",
                currentWebSearchStage = null,
                reasoning = null
            ),
            6000L
        )

        assertNull(text)
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

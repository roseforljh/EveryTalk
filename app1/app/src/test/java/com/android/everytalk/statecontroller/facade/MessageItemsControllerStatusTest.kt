package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.network.TOOL_CALL_WRITING_STATUS

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
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
import org.intellij.markdown.MarkdownTokenTypes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageItemsControllerStatusTest {

    @Test
    fun `统一聊天列表显示没有正文的图片回复`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val image = Message(id = "image-only", text = "", sender = Sender.AI, contentStarted = true, imageUrls = listOf("/local/image.png"))
        controller.stateHolder.messages.add(image)
        Snapshot.sendApplyNotifications()
        val item = controller.chatListItemsForTest().filterIsInstance<ChatListItem.AiMessage>().single()
        assertEquals(image.id, item.messageId)
        assertEquals(image.imageUrls, item.message.imageUrls)
    }

    @Test
    fun `历史有序长正文后台分块并保留调整边界和共享链接索引`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val firstText = (1..80).joinToString("\n\n") { "第 $it 段 [官网](https://example.com)" }
        val secondText = "调整后的最终回答"
        val ai = Message(
            id = "history-ordered", text = firstText + secondText, sender = Sender.AI, contentStarted = true,
            executionTrace = listOf(
                ExecutionTraceEvent.Content(firstText),
                ExecutionTraceEvent.UserMessageBoundary("steering"),
                ExecutionTraceEvent.Content(secondText),
            ),
        )
        controller.stateHolder.messages.addAll(listOf(ai, Message(id = "steering", text = "调整", sender = Sender.User)))
        Snapshot.sendApplyNotifications()
        val items = controller.chatListItemsForTest()
        val segments = items.filterIsInstance<ChatListItem.AiMessageContentSegment>()
        val firstBlocks = segments.filter { it.segmentIndex == 0 }.map { requireNotNull(it.staticBlock) }
        assertTrue(firstBlocks.size > 1)
        assertEquals(firstBlocks.first().preparedMarkdownDocument.nodes, firstBlocks.flatMap { it.nodes })
        assertTrue(firstBlocks.all { it.preparedMarkdownDocument === firstBlocks.first().preparedMarkdownDocument })
        assertEquals("example.com", firstBlocks.first().preparedMarkdownDocument.linkLogoIndex?.requests?.single()?.host)
        val userIndex = items.indexOfFirst { it is ChatListItem.UserMessage }
        assertTrue(items.take(userIndex).all { it is ChatListItem.AiMessageContentSegment && it.segmentIndex == 0 })
        assertEquals(secondText, (items[userIndex + 1] as ChatListItem.AiMessageContentSegment).text)
        assertEquals(items.size, items.map { it.stableId }.distinct().size)
        assertTrue(segments.all { it.sourceMessageId == ai.id })
    }

    @Test
    fun `调整后的回答位于用户气泡下面且仍使用原AI消息`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val ai = Message(
            id = "same-ai", text = "旧回答新回答", sender = Sender.AI, contentStarted = true,
            executionTrace = listOf(
                ExecutionTraceEvent.Content("旧回答"),
                ExecutionTraceEvent.UserMessageBoundary("steer-1"),
                ExecutionTraceEvent.Content("新回答"),
            ),
        )
        controller.stateHolder.messages.addAll(listOf(ai, Message(id = "steer-1", text = "调整方向", sender = Sender.User)))
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = ai.id
        Snapshot.sendApplyNotifications()
        val items = controller.chatListItemsForTest()

        assertEquals(listOf("旧回答", "调整方向", "新回答"), timelineTexts(items))
        val contents = items.filterIsInstance<ChatListItem.AiMessageContentSegment>()
        assertEquals(listOf(ai.id, ai.id), contents.map { it.sourceMessageId })
        assertEquals(listOf(false, true), contents.map { it.isStreaming })
        assertEquals(2, controller.stateHolder.messages.size)
    }

    @Test
    fun `多次调整和末尾边界均按接收顺序插入且不重复用户气泡`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.addAll(listOf(
            Message(id = "ai", text = "甲乙", sender = Sender.AI, contentStarted = true,
                executionTrace = listOf(
                    ExecutionTraceEvent.Content("甲"),
                    ExecutionTraceEvent.UserMessageBoundary("u1"),
                    ExecutionTraceEvent.Content("乙"),
                    ExecutionTraceEvent.UserMessageBoundary("u2"),
                )),
            Message(id = "u1", text = "调整一", sender = Sender.User),
            Message(id = "u2", text = "调整二", sender = Sender.User),
        ))
        Snapshot.sendApplyNotifications()
        val items = controller.chatListItemsForTest()
        assertEquals(listOf("甲", "调整一", "乙", "调整二"), timelineTexts(items))
        assertTrue(items.last() is ChatListItem.AiMessageFooter)
        assertEquals(items.size, items.map { it.stableId }.distinct().size)
    }

    @Test
    fun `尚未消费的调整消息保持原位置且无边界消息不会被隐藏`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.addAll(listOf(
            Message(id = "ai", text = "回答", sender = Sender.AI, contentStarted = true,
                executionTrace = listOf(
                    ExecutionTraceEvent.Content("回答"),
                    ExecutionTraceEvent.UserMessageBoundary("not-loaded"),
                )),
            Message(id = "queued", text = "尚未消费", sender = Sender.User),
        ))
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("回答", "尚未消费"), timelineTexts(controller.chatListItemsForTest()))
    }

    @Test
    fun `只有思考工具的回复也在调整边界分段且详情索引不计用户消息`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.addAll(listOf(
            Message(id = "ai", text = "", sender = Sender.AI,
                executionTrace = listOf(
                    ExecutionTraceEvent.Tool(completedToolStep()),
                    ExecutionTraceEvent.UserMessageBoundary("u1"),
                    ExecutionTraceEvent.Reasoning("调整后的思考"),
                )),
            Message(id = "u1", text = "调整方向", sender = Sender.User),
        ))
        Snapshot.sendApplyNotifications()
        val items = controller.chatListItemsForTest()
        assertTrue(items[0] is ChatListItem.AiMessageProcessSegment)
        assertTrue(items[1] is ChatListItem.UserMessage)
        assertTrue(items[2] is ChatListItem.AiMessageProcessSegment)
        assertEquals(listOf(0, 1), items.filterIsInstance<ChatListItem.AiMessageProcessSegment>().map { it.detailStartIndex })
    }

    private fun timelineTexts(items: List<ChatListItem>): List<String> = items.mapNotNull {
        when (it) {
            is ChatListItem.UserMessage -> it.text
            is ChatListItem.AiMessageContentSegment -> it.text
            else -> null
        }
    }

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
    fun `conversation title metadata is never rendered as a system bubble`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "title-1",
                text = "会话名称",
                sender = Sender.System,
                isPlaceholderName = true,
            ),
        )
        controller.stateHolder.messages.add(
            Message(id = "user-1", text = "hello", sender = Sender.User),
        )

        val items = controller.chatListItemsForTest()

        assertEquals(1, items.size)
        assertTrue(items.single() is ChatListItem.UserMessage)
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
    fun `completed ai item exposes bounded markdown blocks from the same prepared content`() {
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
        assertTrue(document.nodes.size > nodes.size)
        assertEquals(document.nodes, nodes.flatMap { it.nodes })
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

        val blocks = controller.chatListItemsForTest()
            .filterIsInstance<ChatListItem.AiMarkdownNode>()
        val document = blocks.first().preparedMarkdownDocument

        assertTrue(document.targetNodeIndexByUri.containsKey(footnoteDefinitionUri(1)))
        assertTrue(document.targetNodeIndexByUri.containsKey(footnoteReferenceUri(1, 1)))
        document.targetNodeIndexByUri.forEach { (uri, targetNodeIndex) ->
            val targetBlock = blocks.single { block ->
                targetNodeIndex in block.firstNodeIndex..block.lastNodeIndex
            }
            assertEquals(targetBlock.blockIndex, blocks.first().targetBlockIndexByUri[uri])
            assertEquals(
                1,
                targetBlock.nodes.count { node ->
                    node.type != MarkdownTokenTypes.EOL &&
                        node.type != MarkdownTokenTypes.WHITE_SPACE
                },
            )
        }
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
    fun `前导正文出现后 Agent 执行状态仍进入思考区`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-agent-running",
                text = "我来实际检查一下这台机器",
                sender = Sender.AI,
                contentStarted = true,
                currentWebSearchStage = "运行 Agent · exec",
                executionSteps = listOf(
                    ExecutionStep(
                        id = "call-1",
                        type = ExecutionStepType.Agent,
                        title = "运行 Agent",
                        labels = listOf("exec"),
                    )
                ),
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-agent-running"

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessage })
        assertEquals(
            "运行 Agent · exec",
            items.filterIsInstance<ChatListItem.AiMessageReasoning>().single().activityStatusText,
        )
    }

    @Test
    fun `前导正文后的下一轮思考仍显示运行状态`() {
        val controller = MessageItemsControllerTestAccess.newController()

        val text = controller.resolveStreamingStageTextForTest(
            Message(
                id = "ai-agent-reasoning-again",
                text = "我已经检查了第一批信息",
                sender = Sender.AI,
                reasoning = "第一轮思考，第二轮继续分析",
                contentStarted = true,
                executionSteps = listOf(completedToolStep()),
            ),
            0L,
        )

        assertEquals("正在接收思考", text)
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
    fun `text connecting stage routes runtime status into execution drawer item`() {
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
        val execution = items.filterIsInstance<ChatListItem.AiMessageReasoning>().single()

        assertEquals("等待首个响应", execution.activityStatusText)
        assertFalse(execution.activityStatusText.orEmpty().contains("OpenAI"))
        assertFalse(execution.activityStatusText.orEmpty().contains("gpt-4o"))
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `gemini connecting stage exposes reasoning item before first reasoning token`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder._selectedApiConfig.value = geminiConfig()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-gemini-connecting",
                text = "",
                sender = Sender.AI,
                providerName = "Gemini",
                modelName = "gemini-2.5-pro",
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-gemini-connecting"

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessageReasoning })
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `reasoning stage does not render a second loading status below thinking`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-reasoning-only",
                text = "",
                sender = Sender.AI,
                reasoning = "正在分析问题",
                providerName = "Gemini",
                modelName = "gemini-2.5-pro",
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-reasoning-only"

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessageReasoning })
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `explicitly disabled reasoning still uses generic execution drawer`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder._selectedApiConfig.value = geminiConfig(
            modelParameters = ModelParameters(reasoningMode = ReasoningMode.DISABLED)
        )
        controller.stateHolder.messages.add(
            Message(
                id = "ai-gemini-no-reasoning",
                text = "",
                sender = Sender.AI,
                providerName = "Gemini",
                modelName = "gemini-2.5-pro",
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-gemini-no-reasoning"

        val items = controller.chatListItemsForTest()

        val execution = items.filterIsInstance<ChatListItem.AiMessageReasoning>().single()
        assertEquals("等待首个响应", execution.activityStatusText)
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    private fun geminiConfig(modelParameters: ModelParameters = ModelParameters()): ApiConfig = ApiConfig(
        address = "https://generativelanguage.googleapis.com",
        key = "test-key",
        model = "gemini-2.5-pro",
        provider = "Google",
        name = "gemini-2.5-pro",
        channel = "Gemini",
        modelParameters = modelParameters,
    )

    @Test
    fun `ordinary model connecting stage uses generic execution drawer`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-openai-connecting",
                text = "",
                sender = Sender.AI,
                providerName = "OpenAI",
                modelName = "gpt-4o",
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-openai-connecting"

        val items = controller.chatListItemsForTest()

        val execution = items.filterIsInstance<ChatListItem.AiMessageReasoning>().single()
        assertEquals("等待首个响应", execution.activityStatusText)
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `tool call status is routed into execution drawer item`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-tool-call",
                text = "",
                sender = Sender.AI,
                reasoning = "正在选择工具",
                currentWebSearchStage = "调用工具 · search_docs",
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-tool-call"

        val items = controller.chatListItemsForTest()
        val execution = items.filterIsInstance<ChatListItem.AiMessageReasoning>().single()

        assertEquals("调用工具 · search_docs", execution.activityStatusText)
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `image connecting stage routes runtime status into execution drawer item`() {
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
                chatItems.any { it is ChatListItem.AiMessageReasoning }
            }
        }
        val execution = items.filterIsInstance<ChatListItem.AiMessageReasoning>().single()

        assertEquals("等待首个响应", execution.activityStatusText)
        assertFalse(execution.activityStatusText.orEmpty().contains("Gemini"))
        assertFalse(execution.activityStatusText.orEmpty().contains("imagen-3"))
        assertFalse(items.any { it is ChatListItem.LoadingIndicator })
    }

    @Test
    fun `image execution drawer status remains stable while waiting for first content`() = runBlocking {
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
                chatItems.any { it is ChatListItem.AiMessageReasoning }
            }
        }
        val firstText = firstItems.filterIsInstance<ChatListItem.AiMessageReasoning>().single().activityStatusText
        delay(1_100)

        val secondItems = controller.imageGenerationChatListItems.value
        val secondText = secondItems.filterIsInstance<ChatListItem.AiMessageReasoning>().single().activityStatusText

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

    @Test
    fun `completed tool-only message keeps execution review item`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-completed-tool-only",
                text = "工具执行完成",
                sender = Sender.AI,
                contentStarted = true,
                executionSteps = listOf(completedToolStep()),
            )
        )

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessageReasoning })
    }

    @Test
    fun `streaming content keeps execution review item without reasoning text`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-streaming-tool-only",
                text = "正在输出正文",
                sender = Sender.AI,
                contentStarted = true,
                executionSteps = listOf(completedToolStep()),
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = "ai-streaming-tool-only"

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessageReasoning })
    }

    @Test
    fun `completed web-only message keeps execution review item`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-completed-web-only",
                text = "网页资料整理完成",
                sender = Sender.AI,
                contentStarted = true,
                webSearchResults = listOf(
                    WebSearchResult(
                        index = 1,
                        title = "Android Developers",
                        href = "https://developer.android.com/compose",
                        snippet = "Compose documentation",
                    )
                ),
            )
        )

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessageReasoning })
    }

    @Test
    fun `failed execution keeps review item when durable process exists`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-failed-tool",
                text = "工具执行失败",
                sender = Sender.AI,
                contentStarted = true,
                isError = true,
                executionSteps = listOf(completedToolStep()),
            )
        )

        val items = controller.chatListItemsForTest()

        assertTrue(items.any { it is ChatListItem.AiMessageReasoning })
        assertTrue(items.any { it is ChatListItem.ErrorMessage })
    }

    @Test
    fun `completed ordinary answer does not create empty execution review item`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ai-completed-without-process",
                text = "普通回答",
                sender = Sender.AI,
                contentStarted = true,
            )
        )

        val items = controller.chatListItemsForTest()

        assertFalse(items.any { it is ChatListItem.AiMessageReasoning })
    }

    @Test
    fun `tool execution review item keeps stable id when stream completes`() = runBlocking {
        val controller = MessageItemsControllerTestAccess.newController()
        val messageId = "ai-tool-stream-to-complete"
        controller.stateHolder.messages.add(
            Message(
                id = messageId,
                text = "",
                sender = Sender.AI,
                executionSteps = listOf(completedToolStep().copy(completed = false)),
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = messageId

        val streamingItems = controller.chatListItems.first { items ->
            items.any { it is ChatListItem.AiMessageReasoning }
        }
        val streamingId = streamingItems.filterIsInstance<ChatListItem.AiMessageReasoning>()
            .single().stableId
        val completedItemsDeferred = async {
            withTimeout(1_000) {
                controller.chatListItems.first { items ->
                    items.filterIsInstance<ChatListItem.AiMessage>()
                        .firstOrNull()?.text == "工具执行完成" &&
                        items.any { it is ChatListItem.AiMessageReasoning }
                }
            }
        }

        controller.stateHolder.messages.clear()
        controller.stateHolder.messages.add(
            Message(
                id = messageId,
                text = "工具执行完成",
                sender = Sender.AI,
                contentStarted = true,
                executionSteps = listOf(completedToolStep()),
            )
        )
        controller.stateHolder._isTextApiCalling.value = false
        controller.stateHolder._currentTextStreamingAiMessageId.value = null
        Snapshot.sendApplyNotifications()

        val completedItems = completedItemsDeferred.await()
        val completedId = completedItems.filterIsInstance<ChatListItem.AiMessageReasoning>()
            .single().stableId
        assertEquals(streamingId, completedId)
        assertEquals("${messageId}_reasoning", completedId)
    }

    @Test
    fun `新消息列表保持正文与多个过程的实际顺序`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val tool = completedToolStep()
        controller.stateHolder.messages.add(
            Message(
                id = "ordered-output",
                text = "正文 1正文 2正文 3",
                sender = Sender.AI,
                contentStarted = true,
                executionTrace = listOf(
                    ExecutionTraceEvent.Content("旁白 1"),
                    ExecutionTraceEvent.Reasoning("思考 1"),
                    ExecutionTraceEvent.Tool(tool),
                    ExecutionTraceEvent.Content("旁白 2"),
                    ExecutionTraceEvent.Reasoning("思考 2"),
                    ExecutionTraceEvent.Tool(tool.copy(id = "tool-2")),
                    ExecutionTraceEvent.Content("正文 3"),
                ),
            )
        )
        Snapshot.sendApplyNotifications()

        val items = controller.chatListItemsForTest()

        assertEquals(
            listOf("content", "process", "content", "process", "content", "footer"),
            items.map { item ->
                when (item) {
                    is ChatListItem.AiMessageContentSegment -> "content"
                    is ChatListItem.AiMessageProcessSegment -> "process"
                    is ChatListItem.AiMessageFooter -> "footer"
                    else -> item::class.java.simpleName
                }
            },
        )
        assertEquals(
            listOf("旁白 1", "旁白 2", "正文 3"),
            items.filterIsInstance<ChatListItem.AiMessageContentSegment>().map { it.text },
        )
        assertTrue(
            items.filterIsInstance<ChatListItem.AiMessageContentSegment>().all {
                it.sourceMessageId == "ordered-output" &&
                    it.renderState.content == it.text &&
                    it.renderState.blocks.isNotEmpty()
            }
        )
        assertTrue(
            items.filterIsInstance<ChatListItem.AiMessageProcessSegment>().all {
                it.messageId == "ordered-output"
            }
        )
        assertEquals(
            listOf("思考 1", "调用工具", "思考 2", "调用工具"),
            items.filterIsInstance<ChatListItem.AiMessageProcessSegment>()
                .flatMap { it.events }
                .map { event ->
                    when (event) {
                        is ExecutionTraceEvent.Content -> event.text
                        is ExecutionTraceEvent.Reasoning -> event.text
                        is ExecutionTraceEvent.Tool -> event.step.title
                        else -> error("执行区不应包含正式正文或用户边界事件")
                    }
                },
        )
        assertTrue(items.filterIsInstance<ChatListItem.AiMessageProcessSegment>().all { !it.replyIsStreaming })
    }

    @Test
    fun `首段正文前保留请求到首字耗时组件`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ordered-output-initial-duration",
                text = "第一段第二段",
                sender = Sender.AI,
                contentStarted = true,
                timestamp = 100L,
                executionFinishedAt = 900L,
                executionTrace = listOf(
                    ExecutionTraceEvent.Content("第一段", startedAtMillis = 500L),
                    ExecutionTraceEvent.Reasoning("检查配置", startedAtMillis = 600L),
                    ExecutionTraceEvent.Tool(
                        step = completedToolStep(),
                        startedAtMillis = 700L,
                        finishedAtMillis = 800L,
                    ),
                    ExecutionTraceEvent.Content("第二段", startedAtMillis = 900L),
                ),
            )
        )
        Snapshot.sendApplyNotifications()

        val items = controller.chatListItemsForTest()
        val processItems = items.filterIsInstance<ChatListItem.AiMessageProcessSegment>()

        assertEquals(
            listOf("process", "content", "process", "content", "footer"),
            items.map { item ->
                when (item) {
                    is ChatListItem.AiMessageProcessSegment -> "process"
                    is ChatListItem.AiMessageContentSegment -> "content"
                    is ChatListItem.AiMessageFooter -> "footer"
                    else -> item::class.java.simpleName
                }
            },
        )
        assertTrue(processItems.first().events.isEmpty())
        assertEquals(100L, processItems.first().executionStartedAtMillis)
        assertEquals(500L, processItems.first().executionFinishedAtMillis)
        assertEquals(2, processItems[1].events.size)
        assertEquals(600L, processItems[1].executionStartedAtMillis)
        assertEquals(900L, processItems[1].executionFinishedAtMillis)
    }

    @Test
    fun `两轮工具编写执行及用户调整不替换已显示正文和临时过程key`() = runBlocking {
        val controller = MessageItemsControllerTestAccess.newController()
        val messageId = "stable-tool-transition"
        val firstContent = ExecutionTraceEvent.Content("**先检查环境**。")
        val secondContent = ExecutionTraceEvent.Content("根据结果继续检查。")
        val firstTool = ExecutionTraceEvent.Tool(completedToolStep())
        val secondTool = ExecutionTraceEvent.Tool(completedToolStep().copy(id = "tool-2"))
        val initial = Message(
            id = messageId,
            text = firstContent.text,
            sender = Sender.AI,
            contentStarted = true,
            executionTrace = listOf(firstContent),
        )
        controller.stateHolder.messages.add(initial)
        controller.stateHolder.messages.add(Message(id = "steer", text = "调整方向", sender = Sender.User))
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = messageId
        Snapshot.sendApplyNotifications()
        var items = controller.chatListItemsForTest()

        // 逐帧更新真实投影：正文 → 编写 → 工具 → 续写 → 再次编写 → 第二个工具 → 用户调整。
        // 每次等待新列表，避免 StateFlow 的上一帧让稳定性断言误通过。
        val phases = listOf(
            initial.copy(executionStatus = TOOL_CALL_WRITING_STATUS),
            initial.copy(executionTrace = listOf(firstContent, firstTool)),
            initial.copy(text = firstContent.text + secondContent.text,
                executionTrace = listOf(firstContent, firstTool, secondContent)),
            initial.copy(text = firstContent.text + secondContent.text, executionStatus = TOOL_CALL_WRITING_STATUS,
                executionTrace = listOf(firstContent, firstTool, secondContent)),
            initial.copy(text = firstContent.text + secondContent.text,
                executionTrace = listOf(firstContent, firstTool, secondContent, secondTool)),
            initial.copy(text = firstContent.text + secondContent.text + "调整后的回复。",
                executionTrace = listOf(firstContent, firstTool, secondContent, secondTool,
                    ExecutionTraceEvent.UserMessageBoundary("steer"), ExecutionTraceEvent.Content("调整后的回复。"))),
        )
        val expectedKeys = listOf(
            listOf("content_0", "process_1"),
            listOf("content_0", "process_1"),
            listOf("content_0", "process_1", "content_2", "loading"),
            listOf("content_0", "process_1", "content_2", "process_3"),
            listOf("content_0", "process_1", "content_2", "process_3"),
            listOf("content_0", "process_1", "content_2", "process_3", "steer", "content_5", "loading"),
        )
        phases.forEachIndexed { phaseIndex, message ->
            val previous = items
            controller.stateHolder.messages[0] = message
            Snapshot.sendApplyNotifications()
            items = withTimeout(5_000) { controller.chatListItems.first { it != previous } }
            assertEquals(expectedKeys[phaseIndex], items
                .filterNot { it is ChatListItem.UserMessage && phaseIndex < phases.lastIndex }
                .map { it.stableId.removePrefix("${messageId}_") })
            previous.filterIsInstance<ChatListItem.AiMessageContentSegment>().forEach { oldContent ->
                val current = items.single { it.stableId == oldContent.stableId } as ChatListItem.AiMessageContentSegment
                assertEquals(oldContent.message.id, current.message.id)
                assertEquals(oldContent.text, current.text)
                assertEquals(oldContent.renderState.preparedMessage, current.renderState.preparedMessage)
                assertNull(current.staticBlock)
            }
            assertEquals(items.size, items.map { it.stableId }.distinct().size)
        }

        controller.stateHolder._isTextApiCalling.value = false
        controller.stateHolder._currentTextStreamingAiMessageId.value = null
        Snapshot.sendApplyNotifications()
        val completed = withTimeout(5_000) {
            controller.chatListItems.first { list -> list.any { it is ChatListItem.AiMessageFooter } }
        }
        assertEquals(items.filterIsInstance<ChatListItem.AiMessageContentSegment>().map { it.stableId },
            completed.filterIsInstance<ChatListItem.AiMessageContentSegment>().map { it.stableId })
        assertTrue(completed.filterIsInstance<ChatListItem.AiMessageContentSegment>().all { it.staticBlock == null })
        assertEquals(listOf("**先检查环境**。", "根据结果继续检查。", "调整方向", "调整后的回复。"), timelineTexts(completed))
    }

    @Test
    fun `正文后编写工具时显示临时进度且停止后移除`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val messageId = "writing-tool"
        controller.stateHolder.messages.add(Message(
            id = messageId,
            text = "先检查环境。",
            sender = Sender.AI,
            contentStarted = true,
            executionStatus = TOOL_CALL_WRITING_STATUS,
            executionTrace = listOf(ExecutionTraceEvent.Content("先检查环境。")),
        ))
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = messageId
        Snapshot.sendApplyNotifications()

        val writingItems = controller.chatListItemsForTest()
        assertTrue(writingItems.first() is ChatListItem.AiMessageContentSegment)
        val writing = writingItems.filterIsInstance<ChatListItem.AiMessageProcessSegment>().single()
        assertEquals(TOOL_CALL_WRITING_STATUS, writing.activityStatusText)
        assertTrue(writing.processIsActive)
        // 编写状态只在正文后追加临时过程；停止时原正文和 key 都必须保留。
        assertTrue(writing.events.isEmpty())
        val content = writingItems.filterIsInstance<ChatListItem.AiMessageContentSegment>().single()
        assertEquals("先检查环境。", content.text)
        assertEquals("${messageId}_content_0", content.stableId)

        controller.stateHolder._isTextApiCalling.value = false
        controller.stateHolder._currentTextStreamingAiMessageId.value = null
        Snapshot.sendApplyNotifications()
        // StateFlow 会先返回上一帧，等待结束态 Footer 到达后再检查临时状态是否清除。
        val stoppedItems = runBlocking {
            withTimeout(5_000) {
                controller.chatListItems.first { items -> items.any { it is ChatListItem.AiMessageFooter } }
            }
        }
        assertTrue(stoppedItems.none { it is ChatListItem.AiMessageProcessSegment })
        assertEquals(content.stableId, stoppedItems.filterIsInstance<ChatListItem.AiMessageContentSegment>().single().stableId)
    }

    @Test
    fun `流式正文开始输出时执行区结束强制展开`() {
        val controller = MessageItemsControllerTestAccess.newController()
        val messageId = "ordered-output-streaming"
        controller.stateHolder.messages.add(
            Message(
                id = messageId,
                text = "正文 1正文 2正文 3",
                sender = Sender.AI,
                contentStarted = true,
                executionTrace = listOf(
                    ExecutionTraceEvent.Content("正文 1"),
                    ExecutionTraceEvent.Reasoning("思考 1"),
                    ExecutionTraceEvent.Tool(completedToolStep()),
                    ExecutionTraceEvent.Content("正文 2"),
                    ExecutionTraceEvent.Reasoning("思考 2"),
                    ExecutionTraceEvent.Tool(completedToolStep().copy(id = "tool-2")),
                    ExecutionTraceEvent.Content("正文 3"),
                ),
            )
        )
        controller.stateHolder._isTextApiCalling.value = true
        controller.stateHolder._currentTextStreamingAiMessageId.value = messageId
        Snapshot.sendApplyNotifications()

        val processItems = controller.chatListItemsForTest()
            .filterIsInstance<ChatListItem.AiMessageProcessSegment>()

        // 正文仍在流式输出，前面的过程也应立即收起。
        assertEquals(listOf(false, false), processItems.map { it.replyIsStreaming })
        assertEquals(listOf(false, false), processItems.map { it.processIsActive })
    }

    @Test
    fun `多个过程段分别保留首字等待和各段耗时`() {
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(
            Message(
                id = "ordered-output-duration",
                text = "正文 1正文 2",
                sender = Sender.AI,
                contentStarted = true,
                timestamp = 50L,
                executionFinishedAt = 900L,
                executionTrace = listOf(
                    ExecutionTraceEvent.Content("正文 1", startedAtMillis = 100L),
                    ExecutionTraceEvent.Reasoning("思考 1", startedAtMillis = 200L),
                    ExecutionTraceEvent.Tool(
                        step = completedToolStep(),
                        startedAtMillis = 300L,
                        finishedAtMillis = 450L,
                    ),
                    ExecutionTraceEvent.Content("正文 2", startedAtMillis = 500L),
                    ExecutionTraceEvent.Reasoning("思考 2", startedAtMillis = 700L),
                    ExecutionTraceEvent.Tool(
                        step = completedToolStep().copy(id = "tool-2"),
                        startedAtMillis = 800L,
                        finishedAtMillis = 850L,
                    ),
                ),
            )
        )
        Snapshot.sendApplyNotifications()

        val processItems = controller.chatListItemsForTest()
            .filterIsInstance<ChatListItem.AiMessageProcessSegment>()

        assertEquals(3, processItems.size)
        assertEquals(listOf(50L, 200L, 700L), processItems.map { it.executionStartedAtMillis })
        assertEquals(listOf(100L, 500L, 900L), processItems.map { it.executionFinishedAtMillis })
        assertEquals(listOf(0, 2, 2), processItems.map { it.events.size })
    }

    private fun completedToolStep() = ExecutionStep(
        id = "tool-1",
        type = ExecutionStepType.Tool,
        title = "调用工具",
        labels = listOf("local_clock"),
        completed = true,
    )
}

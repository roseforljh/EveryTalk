package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.expandStaticAiMessageItem
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatMessagesListRenderRouteTest {

    @Test
    fun `历史长回复将普通Markdown节点拆为单节点懒列表项`() {
        val message = Message(
            id = "history-node-blocks",
            text = (1..17).joinToString("\n\n") { "第 ${it} 段。" },
            sender = Sender.AI,
        )
        val preparedMessage = PreparedMessage(
            markdown = message.text,
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val state = parseMarkdown(preparedMessage.markdown) as State.Success

        val blocks = expandStaticAiMessageItem(
            ChatListItem.AiMessage(
                message = message,
                messageId = message.id,
                text = message.text,
                hasReasoning = false,
                preparedMessage = preparedMessage,
                preparedMarkdownDocument = PreparedMarkdownDocument(state, state.node.children),
            )
        ).filterIsInstance<ChatListItem.AiMarkdownNode>()

        assertEquals(17, blocks.size)
        assertEquals(state.node.children, blocks.flatMap { it.nodes })
        assertTrue(
            blocks.all { block ->
                block.renderableTopLevelNodeCount() <= 1
            }
        )
        assertTrue(blocks.first().isFirstNode)
        assertTrue(blocks.last().isLastNode)
    }

    @Test
    fun `跨分块脚注定位换算为目标懒列表项`() {
        val message = Message(
            id = "history-footnote-blocks",
            text = (1..17).joinToString("\n\n") { "第 ${it} 段。" },
            sender = Sender.AI,
        )
        val preparedMessage = PreparedMessage(
            markdown = message.text,
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val state = parseMarkdown(preparedMessage.markdown) as State.Success
        val blocks = expandStaticAiMessageItem(
            ChatListItem.AiMessage(
                message = message,
                messageId = message.id,
                text = message.text,
                hasReasoning = false,
                preparedMessage = preparedMessage,
                preparedMarkdownDocument = PreparedMarkdownDocument(
                    state = state,
                    nodes = state.node.children,
                    targetNodeIndexByUri = mapOf(
                        "first" to 0,
                        "last" to state.node.children.lastIndex,
                    ),
                ),
            )
        ).filterIsInstance<ChatListItem.AiMarkdownNode>()
        val currentBlock = blocks[1]
        val baseListIndex = 40 - currentBlock.blockIndex

        assertEquals(
            baseListIndex + blocks.first().blockIndex,
            resolveStaticMarkdownTargetListIndex(
                currentListIndex = 40,
                item = currentBlock,
                uri = "first",
            ),
        )
        assertEquals(
            baseListIndex + blocks.last().blockIndex,
            resolveStaticMarkdownTargetListIndex(
                currentListIndex = 40,
                item = currentBlock,
                uri = "last",
            ),
        )
        assertEquals(1, blocks.first().renderableTopLevelNodeCount())
        assertEquals(1, blocks.last().renderableTopLevelNodeCount())
    }

    @Test
    fun `历史代码块保持独立懒列表项`() {
        val message = Message(
            id = "history-heavy-block",
            text = "前文。\n\n```kotlin\nprintln(\"hi\")\n```\n\n后文。",
            sender = Sender.AI,
        )
        val preparedMessage = PreparedMessage(
            markdown = message.text,
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val state = parseMarkdown(preparedMessage.markdown) as State.Success
        val blocks = expandStaticAiMessageItem(
            ChatListItem.AiMessage(
                message = message,
                messageId = message.id,
                text = message.text,
                hasReasoning = false,
                preparedMessage = preparedMessage,
                preparedMarkdownDocument = PreparedMarkdownDocument(state, state.node.children),
            )
        ).filterIsInstance<ChatListItem.AiMarkdownNode>()
        val codeBlock = blocks.single { block ->
            block.nodes.any { it.type == MarkdownElementTypes.CODE_FENCE }
        }

        assertEquals(1, codeBlock.renderableTopLevelNodeCount())
        assertTrue(codeBlock.blockIndex > 0)
        assertTrue(codeBlock.blockIndex < blocks.lastIndex)
    }

    @Test
    fun `历史Markdown节点之间不插入会话级间距`() {
        val message = Message(
            id = "history-spacing",
            text = (1..9).joinToString("\n\n") { "第 ${it} 段。" },
            sender = Sender.AI,
        )
        val preparedMessage = PreparedMessage(
            markdown = message.text,
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val state = parseMarkdown(preparedMessage.markdown) as State.Success
        val expanded = expandStaticAiMessageItem(
            ChatListItem.AiMessage(
                message = message,
                messageId = message.id,
                text = message.text,
                hasReasoning = false,
                pageSources = listOf(
                    WebSearchResult(
                        index = 1,
                        title = "示例",
                        href = "https://example.com",
                        snippet = "",
                    )
                ),
                preparedMessage = preparedMessage,
                preparedMarkdownDocument = PreparedMarkdownDocument(state, state.node.children),
            )
        )
        val sources = expanded.first()
        val nodes = expanded.filterIsInstance<ChatListItem.AiMarkdownNode>()

        assertFalse(shouldAddConversationGapAfter(sources))
        assertFalse(shouldAddConversationGapAfter(nodes.first()))
        assertTrue(shouldAddConversationGapAfter(nodes.last()))
    }

    @Test
    fun `completed matching ai content uses background prepared render`() {
        assertTrue(
            shouldUsePreparedStaticAiRender(
                shouldPreferStreamingContent = false,
                hasPreparedMessage = true,
                itemText = "完整回复",
                effectiveContent = "完整回复",
            )
        )
    }

    @Test
    fun `streaming ai content keeps incremental render path`() {
        assertFalse(
            shouldUsePreparedStaticAiRender(
                shouldPreferStreamingContent = true,
                hasPreparedMessage = true,
                itemText = "旧完整回复",
                effectiveContent = "正在追加的新回复",
            )
        )
    }

    @Test
    fun `completion race with newer render text keeps fallback path`() {
        assertFalse(
            shouldUsePreparedStaticAiRender(
                shouldPreferStreamingContent = false,
                hasPreparedMessage = true,
                itemText = "较短的数据库文本",
                effectiveContent = "流式状态中的完整回复内容",
            )
        )
    }

    @Test
    fun `chat messages list should not call enhanced markdown text directly`() {
        val source = chatMessagesListSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
    }

    @Test
    fun `selected render state should bypass fallback message preparation`() {
        val source = chatMessagesListSource()

        assertTrue(source.contains("usePreparedStaticRender -> requireNotNull(staticPreparedMessage)"))
        assertTrue(source.contains("selectedRenderState != null -> selectedRenderState.preparedMessage"))
        assertFalse(source.contains("val preparedFromBlocks = remember("))
    }

    @Test
    fun `text chat top anchor always uses configured top inset`() {
        val source = chatMessagesListSource()

        assertFalse(source.contains("firstBubbleScreenY"))
        assertTrue(source.contains("targetAnchorY = topPaddingPx"))
    }

    @Test
    fun `streaming text and reasoning should respect pause aware collection`() {
        val source = chatMessagesListSource()

        assertTrue(source.contains("streamingRenderStateSource.freezeWhileStreamingPaused"))
        assertTrue(source.contains("streamingReasoningSource.freezeWhileStreamingPaused"))
        assertTrue(source.contains("currentTextStreamingAiMessageId.freezeWhileStreamingPaused"))
    }

    @Test
    fun `natural stream completion retains exact anchor reserve`() {
        val source = chatMessagesListSource()

        assertTrue(source.contains("keepReserveAfterRunEnd = true"))
        assertTrue(source.contains("hasResponseTarget = engineResponseTargetId != null"))
        assertTrue(source.contains("topAnchorEngine.attachResponseTarget"))
        assertFalse(source.contains("pinToRealBottomAfterAutomaticRun()"))
        assertTrue(source.contains("reserveInsideTrailingItem = true"))
        assertTrue(source.contains("appendTopAnchorReserve("))
        assertTrue(source.contains("trailingRealItemIndex = chatItems.lastIndex"))
        assertFalse(source.contains("dynamic_padding_spacer"))
        assertFalse(source.contains("topAnchorEngine.reservePx.toDp()"))
        assertFalse(source.contains("translationY = -retainedVisualOffsetPx.toFloat()"))
        assertTrue(source.contains("LaunchedEffect(scrollSessionKey)"))
        assertTrue(source.contains("topAnchorEngine.clearRuntime()"))
    }

    @Test
    fun `ai footer regenerate locks competing auto scroll before starting`() {
        val source = chatMessagesListSource()
        val footerStart = source.indexOf("fun AiMessageFooterItem(")
        val footerEnd = source.indexOf("private fun AiMessagePopupMenu(", startIndex = footerStart)
        require(footerStart >= 0 && footerEnd > footerStart) { "找不到 AI 消息底部操作区" }
        val footerSource = source.substring(footerStart, footerEnd)

        assertTrue(footerSource.contains("scrollStateManager: ChatScrollStateManager"))
        assertTrue(
            Regex("""scrollStateManager\.lockAutoScroll\(\)\s+viewModel\.regenerateAiResponse\(""")
                .containsMatchIn(footerSource)
        )
        assertTrue(
            Regex("""scrollStateManager\.lockAutoScroll\(\)\s+viewModel\.regenerateAiResponseWithConfig\(""")
                .containsMatchIn(footerSource)
        )
    }

    @Test
    fun `streaming height guard stays in layout phase without compose state writes`() {
        val source = chatMessagesListSource()

        assertTrue(retainedStreamingHeightPx(120, 0, isStreaming = true) == 120)
        assertTrue(retainedStreamingHeightPx(80, 120, isStreaming = true) == 120)
        assertTrue(retainedStreamingHeightPx(80, 120, isStreaming = false) == 80)
        assertTrue(source.contains("retainGrowingHeightWhileStreaming("))
        assertFalse(source.contains("lastMeasuredHeightPx"))
        assertFalse(source.contains(".onSizeChanged { size ->"))
    }

    @Test
    fun `source stripped ai answer should rebuild stream blocks for display content`() {
        val effectiveContent = """
            正文第一段。

            ## Sources
            - [Example](https://example.com)
        """.trimIndent()
        val displayContent = "正文第一段。"

        assertTrue(
            shouldBuildSourceStrippedRenderBlocks(
                messageOutputType = "",
                extractedSourceCount = 1,
                effectiveContent = effectiveContent,
                displayContent = displayContent,
            )
        )
    }

    @Test
    fun `code output should not rebuild source stripped stream blocks`() {
        assertFalse(
            shouldBuildSourceStrippedRenderBlocks(
                messageOutputType = "code",
                extractedSourceCount = 1,
                effectiveContent = "正文\n\n## Sources\n- [Example](https://example.com)",
                displayContent = "正文",
            )
        )
    }

    @Test
    fun `plain ai answer without upstream blocks should build local stream blocks`() {
        assertTrue(
            shouldBuildLocalRenderBlocks(
                messageOutputType = "",
                displayContent = "普通 AI 回复",
                hasUpstreamBlocks = false,
                hasStreamingBlocks = false,
                hasSourceStrippedBlocks = false,
                hasExtractedSources = false,
            )
        )
    }

    @Test
    fun `code output should build local stream blocks`() {
        assertTrue(
            shouldBuildLocalRenderBlocks(
                messageOutputType = "code",
                displayContent = "println(\"hi\")",
                hasUpstreamBlocks = false,
                hasStreamingBlocks = false,
                hasSourceStrippedBlocks = false,
                hasExtractedSources = false,
            )
        )
    }

    @Test
    fun `source extracted answer should not build duplicate local stream blocks`() {
        assertFalse(
            shouldBuildLocalRenderBlocks(
                messageOutputType = "",
                displayContent = "正文",
                hasUpstreamBlocks = false,
                hasStreamingBlocks = false,
                hasSourceStrippedBlocks = true,
                hasExtractedSources = true,
            )
        )
    }

    private fun chatMessagesListSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ChatMessagesList.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}

private fun ChatListItem.AiMarkdownNode.renderableTopLevelNodeCount(): Int = nodes.count { node ->
    node.type != MarkdownTokenTypes.EOL && node.type != MarkdownTokenTypes.WHITE_SPACE
}

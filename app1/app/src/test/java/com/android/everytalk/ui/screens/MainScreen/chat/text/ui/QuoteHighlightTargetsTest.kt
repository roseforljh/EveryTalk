package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.streaming.StreamingRenderState
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteHighlightTargetsTest {
    private val ai = Message(id = "a1", text = "", sender = Sender.AI)
    private fun segment(index: Int, text: String) = ChatListItem.AiMessageContentSegment(
        sourceMessageId = "a1",
        message = ai,
        segmentIndex = index,
        text = text,
        isStreaming = false,
        renderState = StreamingRenderState(messageId = "a1"),
    )
    private val items = listOf(
        ChatListItem.UserMessage("u1", "问题", emptyList()),
        segment(0, "## 第一节\n\n**Kotlin** 协程用于异步编程。"),
        segment(1, "第二段讲 Flow：冷流在收集时才开始。"),
        segment(2, "第三段讲 Channel。"),
    )

    @Test fun `引用按字母数字匹配并忽略 Markdown 符号`() {
        assertEquals(listOf(1), quoteHighlightTargets(items, "a1", "Kotlin 协程用于异步编程"))
    }

    @Test fun `跨段引用高亮从起点到终点的所有段`() {
        assertEquals(listOf(1, 2), quoteHighlightTargets(items, "a1", "协程用于异步编程。\n第二段讲 Flow：冷流在收集时才开始"))
    }

    @Test fun `对不上原文时退回整条消息且不碰其他消息`() {
        assertEquals(listOf(1, 2, 3), quoteHighlightTargets(items, "a1", "完全不存在的内容"))
        assertEquals(emptyList<Int>(), quoteHighlightTargets(items, "missing", "Kotlin"))
    }

    @Test fun `搜索跳转按第几处关键词只高亮所在段`() {
        assertEquals(listOf(2), quoteHighlightTargets(items, "a1", "讲", occurrence = 0))
        assertEquals(listOf(3), quoteHighlightTargets(items, "a1", "讲", occurrence = 1))
        // 超出次数时停在最后一处
        assertEquals(listOf(3), quoteHighlightTargets(items, "a1", "讲", occurrence = 5))
        val snippets = com.android.everytalk.ui.screens.MainScreen.search.buildSearchSnippets("讲A，讲B", "讲", maxSnippetChars = 1)
        assertEquals(listOf(0, 1), snippets.map { it.firstOccurrence })
    }
}

package com.android.everytalk.ui.screens.MainScreen.search

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchContentTest {
    @Test
    fun `全文搜索能够命中第三条之后的消息`() {
        val messages = listOf(
            Message(text = "第一条", sender = Sender.User),
            Message(text = "第二条", sender = Sender.AI),
            Message(text = "第三条", sender = Sender.User),
            Message(text = "第四条包含目标关键词", sender = Sender.AI),
        )

        val results = buildConversationSearchResults(
            sources = listOf(
                ConversationSearchSource(
                    originalIndex = 7,
                    stableId = "conversation-7",
                    title = "测试会话",
                    messages = messages,
                ),
            ),
            query = "目标关键词",
        )

        assertEquals(1, results.size)
        assertEquals(7, results.single().originalIndex)
        assertEquals(1, results.single().totalOccurrences)
        assertEquals(ConversationSearchSourceType.AI, results.single().snippets.single().source)
    }

    @Test
    fun `相距较远的多处命中会生成多个受限文本段`() {
        val text = "关键词" + "甲".repeat(240) + "关键词"

        val snippets = buildSearchSnippets(
            text = text,
            query = "关键词",
            maxSnippetChars = 80,
        )

        assertEquals(2, snippets.size)
        assertEquals(2, snippets.sumOf(TextSearchSnippet::occurrenceCount))
        assertTrue(snippets.all { it.text.length <= 82 })
        assertTrue(snippets.all { it.text.contains("关键词") })
    }

    @Test
    fun `会话标题和内容命中会合并到同一展开项`() {
        val result = buildConversationSearchResults(
            sources = listOf(
                ConversationSearchSource(
                    originalIndex = 0,
                    stableId = "conversation-0",
                    title = "Kotlin 学习",
                    messages = listOf(
                        Message(text = "继续学习 Kotlin 协程", sender = Sender.User),
                    ),
                ),
            ),
            query = "kotlin",
        ).single()

        assertEquals(2, result.totalOccurrences)
        assertEquals(
            listOf(ConversationSearchSourceType.TITLE, ConversationSearchSourceType.USER),
            result.snippets.map { it.source },
        )
    }

    @Test
    fun `搜索会统一连续空白以匹配换行内容`() {
        val results = buildConversationSearchResults(
            sources = listOf(
                ConversationSearchSource(
                    originalIndex = 0,
                    stableId = "conversation-0",
                    title = "测试会话",
                    messages = listOf(
                        Message(text = "目标\n\n关键词", sender = Sender.User),
                    ),
                ),
            ),
            query = "目标   关键词",
        )

        assertEquals(1, results.size)
        assertTrue(results.single().snippets.single().text.contains("目标 关键词"))
    }
}

package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.ui.components.MarkdownPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerStreamCompletionMergeTest {

    @Test
    fun `merge streaming completion message should keep synced text and finalized structure`() {
        val syncedMessage = Message(
            id = "msg-1",
            text = "```kotlin\nprintln(\"done\")\n```",
            sender = Sender.AI,
            contentStarted = true,
        )
        val finalizedMessage = syncedMessage.copy(
            text = "旧的中间态文本",
            reasoning = "收尾推理",
            parts = listOf(
                MarkdownPart.CodeBlock(
                    id = "code_0",
                    language = "kotlin",
                    content = "println(\"done\")",
                )
            ),
        )

        val merged = mergeStreamingCompletionMessage(
            syncedMessage = syncedMessage,
            finalizedMessage = finalizedMessage,
        )

        assertEquals(syncedMessage.text, merged.text)
        assertEquals("收尾推理", merged.reasoning)
        assertTrue(merged.contentStarted)
        assertTrue(merged.parts.single() is MarkdownPart.CodeBlock)
    }

    @Test
    fun `merge streaming completion message should replace synced text when think block was extracted`() {
        val syncedMessage = Message(
            id = "msg-1",
            text = """
                <think>
                hidden reasoning
                </think>
                visible answer
            """.trimIndent(),
            sender = Sender.AI,
            contentStarted = true,
        )
        val finalizedMessage = syncedMessage.copy(
            text = "visible answer",
            reasoning = "hidden reasoning",
            parts = listOf(MarkdownPart.Text(id = "text_0", content = "visible answer")),
        )

        val merged = mergeStreamingCompletionMessage(
            syncedMessage = syncedMessage,
            finalizedMessage = finalizedMessage,
        )

        assertEquals("visible answer", merged.text)
        assertEquals("hidden reasoning", merged.reasoning)
        assertTrue(merged.contentStarted)
    }

    @Test
    fun `merge preserves web search results from finalized message`() {
        val source = WebSearchResult(
            index = 1,
            title = "Result",
            href = "https://example.com/result",
            snippet = "snippet"
        )
        val synced = Message(
            id = "msg-1",
            text = "final text",
            sender = Sender.AI,
        )
        val finalized = Message(
            id = "msg-1",
            text = "final text",
            sender = Sender.AI,
            webSearchResults = listOf(source)
        )

        val merged = mergeStreamingCompletionMessage(
            syncedMessage = synced,
            finalizedMessage = finalized
        )

        assertEquals(listOf(source), merged.webSearchResults)
    }

    @Test
    fun `merge preserves web search results from synced message when finalized has empty results`() {
        val source = WebSearchResult(
            index = 1,
            title = "Result",
            href = "https://example.com/result",
            snippet = "snippet"
        )
        val synced = Message(
            id = "msg-1",
            text = "final text",
            sender = Sender.AI,
            webSearchResults = listOf(source)
        )
        val finalized = Message(
            id = "msg-1",
            text = "final text",
            sender = Sender.AI,
            webSearchResults = emptyList()
        )

        val merged = mergeStreamingCompletionMessage(
            syncedMessage = synced,
            finalizedMessage = finalized
        )

        assertEquals(listOf(source), merged.webSearchResults)
    }

    @Test
    fun `merge web search results appends deduplicates and reindexes`() {
        val first = WebSearchResult(1, "A", "https://example.com/a", "")
        val duplicate = WebSearchResult(1, "A again", "https://example.com/a", "new")
        val second = WebSearchResult(9, "B", "https://example.com/b", "")

        val merged = mergeWebSearchResults(
            existing = listOf(first),
            incoming = listOf(duplicate, second)
        )

        assertEquals(listOf("https://example.com/a", "https://example.com/b"), merged.map { it.href })
        assertEquals(listOf(1, 2), merged.map { it.index })
        assertEquals("A", merged[0].title)
    }

    @Test
    fun `merge web search results reindexes existing when incoming is empty`() {
        val source = WebSearchResult(7, "A", "https://example.com/a", "")

        val merged = mergeWebSearchResults(
            existing = listOf(source),
            incoming = emptyList()
        )

        assertEquals(listOf("https://example.com/a"), merged.map { it.href })
        assertEquals(listOf(1), merged.map { it.index })
    }
}

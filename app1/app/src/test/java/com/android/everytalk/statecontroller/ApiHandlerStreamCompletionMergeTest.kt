package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
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
}

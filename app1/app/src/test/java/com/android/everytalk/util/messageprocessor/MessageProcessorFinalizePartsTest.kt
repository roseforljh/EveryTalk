package com.android.everytalk.util.messageprocessor

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.ui.components.MarkdownPart
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessageProcessorFinalizePartsTest {

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0
    }

    @Test
    fun `finalize message processing should persist complete raw markdown as one text part`() {
        val text = """
            # 渲染测试

            | 名称 | 公式 |
            |:---|:---:|
            | 质能方程 | ${'$'}E = mc^2${'$'} |

            ```kotlin
            val answer = 42
            ```

            ${'$'}${'$'}\int_0^1 x^2 dx${'$'}${'$'}
        """.trimIndent()
        val message = Message(
            id = "raw-markdown-message",
            text = text,
            sender = Sender.AI,
            contentStarted = true,
        )

        val processor = MessageProcessor().apply { initialize("session", message.id) }
        val finalized = processor.finalizeMessageProcessing(message)

        assertEquals(listOf(MarkdownPart.Text(id = "text_0", content = text)), finalized.parts)
    }

    @Test
    fun `finalize message processing should move think block from text to reasoning`() {
        val text = """
            <think>
            I've just received information about Bill Gates.
            </think>
            在这张截图的语境中，CTO 是 Community Takeover。
        """.trimIndent()

        val message = Message(
            id = "msg",
            text = text,
            sender = Sender.AI,
            contentStarted = true
        )

        val processor = MessageProcessor().apply { initialize("session", "msg") }
        val finalized = processor.finalizeMessageProcessing(message)

        assertEquals("在这张截图的语境中，CTO 是 Community Takeover。", finalized.text)
        assertEquals("I've just received information about Bill Gates.", finalized.reasoning)
    }

    @Test
    fun `stream processing removes unicode replacement characters from text and reasoning`() = runBlocking {
        val processor = MessageProcessor().apply { initialize("session", "msg") }

        processor.processStreamEvent(AppStreamEvent.Content("为什么���今天"), "msg")
        processor.processStreamEvent(AppStreamEvent.Reasoning("推理���内容"), "msg")
        val finalized = processor.finalizeMessageProcessing(
            Message(
                id = "msg",
                text = "",
                sender = Sender.AI,
                contentStarted = true
            )
        )

        assertEquals("为什么今天", finalized.text)
        assertEquals("推理内容", finalized.reasoning)
    }

    @Test
    fun `finalize reasoning does not append the seeded first chunk twice`() = runBlocking {
        val processor = MessageProcessor().apply { initialize("session", "reasoning-seed") }
        processor.processStreamEvent(AppStreamEvent.Reasoning("第一段"), "reasoning-seed")
        processor.processStreamEvent(AppStreamEvent.Reasoning("第二段"), "reasoning-seed")

        val finalized = processor.finalizeMessageProcessing(
            Message(
                id = "reasoning-seed",
                text = "",
                sender = Sender.AI,
                reasoning = "第一段",
            )
        )

        assertEquals("第一段第二段", finalized.reasoning)
    }
}

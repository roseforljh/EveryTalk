package com.android.everytalk.util.messageprocessor

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.ui.components.MarkdownPart
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `finalize message processing should persist code blocks into message parts`() {
        val text = """
            ### 2. Windows 系统安装
            Windows 用户通常需要通过 **PowerShell**（管理员权限）运行安装脚本。目前社区推荐的一键安装方式如下：
            *   **方式 A（官方/通用）：**
                打开 PowerShell，输入：
                ```powershell
                irm https://openclaw.ai/install.ps1 | iex
                ```
            *   **方式 B（社区简化版/Qclaw）：**
                部分博主（如秋芝2046）提供的简化脚本，通常在 PowerShell 中运行：
                ```powershell
                iex (irm https://qclaw.io/install.ps1)
                ```
        """.trimIndent()

        val message = Message(
            id = "msg",
            text = text,
            sender = Sender.AI,
            contentStarted = true
        )

        val processor = MessageProcessor().apply { initialize("session", "msg") }
        val finalized = processor.finalizeMessageProcessing(message)
        val codeBlocks = finalized.parts.filterIsInstance<MarkdownPart.CodeBlock>()

        assertEquals(2, codeBlocks.size)
        assertTrue(codeBlocks.any { it.language == "powershell" && it.content.contains("openclaw.ai/install.ps1 | iex") })
        assertTrue(codeBlocks.any { it.language == "powershell" && it.content.contains("qclaw.io/install.ps1") })
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
}

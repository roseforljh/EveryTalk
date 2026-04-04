package com.android.everytalk.ui.components.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBlockParserPendingFenceTest {

    @Test
    fun `unfinished fenced code should still produce code block in streaming parse`() {
        val input = """
            在终端中输入以下命令：
            ```bash
            curl -fsSL https://openclaw.ai/install.sh | bash
        """.trimIndent()

        val result = StreamBlockParser.parse(input, "msg")
        val codeBlocks = result.blocks.filterIsInstance<StreamBlock.CodeBlock>()

        assertEquals(1, codeBlocks.size)
        assertTrue(codeBlocks.first().text.contains("curl -fsSL https://openclaw.ai/install.sh | bash"))
    }
}

package com.android.everytalk.ui.components.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBlockParserTildeFenceTest {

    @Test
    fun `supports closed tilde fenced code blocks during streaming`() {
        val input = """
            安装步骤：
            ~~~bash
            curl -fsSL https://openclaw.ai/install.sh | bash
            ~~~
        """.trimIndent()

        val result = StreamBlockParser.parse(input, "msg")
        val codeBlocks = result.blocks.filterIsInstance<StreamBlock.CodeBlock>()

        assertEquals(1, codeBlocks.size)
        assertTrue(codeBlocks.first().text.contains("install.sh | bash"))
    }

    @Test
    fun `supports unfinished tilde fenced code blocks during streaming`() {
        val input = """
            安装步骤：
            ~~~powershell
            irm https://openclaw.ai/install.ps1 | iex
        """.trimIndent()

        val result = StreamBlockParser.parse(input, "msg")
        val codeBlocks = result.blocks.filterIsInstance<StreamBlock.CodeBlock>()

        assertEquals(1, codeBlocks.size)
        assertTrue(codeBlocks.first().text.contains("install.ps1 | iex"))
    }
}

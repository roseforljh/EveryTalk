package com.android.everytalk.ui.components.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBlockParserNestedFenceTest {

    @Test
    fun `list indented fenced code should stay as code blocks during streaming`() {
        val input = """
            ### 3. 社区优化版/中文一键脚本 (适合国内环境)
            如果官方链接下载较慢，可以使用社区维护的快速安装脚本：
            *   **macOS:**
                ```bash
                curl -fsSL https://raw.githubusercontent.com/736773174/openclaw-setup-cn/main/install.sh | bash
                ```
            *   **Windows (PowerShell):**
                ```powershell
                irm https://raw.githubusercontent.com/736773174/openclaw-setup-cn/main/install.ps1 | iex
                ```
        """.trimIndent()

        val result = StreamBlockParser.parse(input, "msg")
        val codeBlocks = result.blocks.filterIsInstance<StreamBlock.CodeBlock>()

        assertEquals(2, codeBlocks.size)
        assertTrue(codeBlocks.any { it.text.contains("install.sh | bash") })
        assertTrue(codeBlocks.any { it.text.contains("install.ps1 | iex") })
    }
}

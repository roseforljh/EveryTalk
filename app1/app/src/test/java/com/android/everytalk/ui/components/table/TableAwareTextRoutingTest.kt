package com.android.everytalk.ui.components.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableAwareTextRoutingTest {

    @Test
    fun `fenced code text should never use native markdown shortcut`() {
        assertFalse(
            shouldRenderTrailingStreamingTextWithMarkdown(
                """
                ```powershell
                irm https://openclaw.ai/install.ps1 | iex
                ```
                """.trimIndent()
            )
        )

        assertFalse(
            shouldRenderTrailingStreamingTextWithMarkdown(
                """
                ~~~bash
                docker compose up -d
                ~~~
                """.trimIndent()
            )
        )
    }

    @Test
    fun `fenced code detector should cover both backtick and tilde fences`() {
        assertTrue(containsFencedCodeSyntax("```bash\ncurl -fsSL https://get.docker.com | bash\n```"))
        assertTrue(containsFencedCodeSyntax("~~~powershell\nirm https://openclaw.ai/install.ps1 | iex\n~~~"))
        assertFalse(containsFencedCodeSyntax("普通文本，没有代码围栏"))
    }

    @Test
    fun `fenced code text should leave stable fallback after stream completes`() {
        assertFalse(
            shouldPreferStableMarkdownFallback(
                content = "```powershell\nwmic diskdrive get model,caption,size\n```",
                isStreaming = false,
                isTrailingStreamingText = false,
            )
        )
    }

    @Test
    fun `fenced code text should keep stable fallback while streaming`() {
        assertTrue(
            shouldPreferStableMarkdownFallback(
                content = "```powershell\nwmic diskdrive get model,caption,size\n```",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `parse request should be equal when text is the same`() {
        val text = "```kotlin\nprintln(\"hi\")\n```"

        assertEquals(
            TableAwareParseRequest(text = text),
            TableAwareParseRequest(text = text),
        )
    }
}

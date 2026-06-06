package com.android.everytalk.ui.components.table

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    fun `closed fenced code text should not keep stable fallback while streaming`() {
        assertFalse(
            shouldPreferStableMarkdownFallback(
                content = "```powershell\nwmic diskdrive get model,caption,size\n```",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `table text should not keep stable fallback while streaming`() {
        assertFalse(
            shouldPreferStableMarkdownFallback(
                content = "| A | B |\n|---|---|\n| 1 | 2 |",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `complete table text should reroute through table aware parser while streaming`() {
        assertTrue(
            shouldRerouteTextPartThroughTableAwareParser(
                content = "前言\n\n| A | B |\n|---|---|\n| 1 | 2 |",
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `partial table text should not reroute through table aware parser`() {
        assertFalse(
            shouldRerouteTextPartThroughTableAwareParser(
                content = "前言\n\n| A | B |",
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `table text reroute should respect recursion guard`() {
        assertFalse(
            shouldRerouteTextPartThroughTableAwareParser(
                content = "| A | B |\n|---|---|\n| 1 | 2 |",
                recursionDepth = 3,
            )
        )
    }

    @Test
    fun `table cells use compact line height`() {
        val style = compactTableCellTextStyle(
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 26.sp,
            )
        )

        assertEquals(18.sp, style.lineHeight)
    }

    @Test
    fun `adjacent tables get stronger outer spacing`() {
        assertEquals(8.dp, tableBlockVerticalPaddingDp(previousIsTable = false, nextIsTable = false))
        assertEquals(14.dp, tableBlockVerticalPaddingDp(previousIsTable = true, nextIsTable = false))
        assertEquals(14.dp, tableBlockVerticalPaddingDp(previousIsTable = false, nextIsTable = true))
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

package com.android.everytalk.ui.components.table

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `table aware text should not call markdown renderer directly`() {
        val source = tableAwareTextSource()

        assertFalse(source.contains("MarkdownRenderer("))
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
    fun `plain text part should use native text renderer`() {
        assertTrue(
            shouldRenderTextPartNatively(
                content = "普通文本",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `bare url text part should use native text renderer`() {
        assertTrue(
            shouldRenderTextPartNatively(
                content = "Open https://example.com/docs now",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `trailing streaming plain text part should use native text renderer`() {
        assertTrue(
            shouldRenderTextPartNatively(
                content = "正在生成的普通文本",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `trailing streaming bare url text part should use native text renderer`() {
        assertTrue(
            shouldRenderTextPartNatively(
                content = "Open https://example.com/docs now",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `trailing lightweight inline markdown should not request markdown renderer shortcut`() {
        assertFalse(
            shouldRenderTrailingStreamingTextWithMarkdown(
                content = "这是 **重点** 和 `code`"
            )
        )
    }

    @Test
    fun `unclosed streaming math text part should not use native text renderer`() {
        assertFalse(
            shouldRenderTextPartNatively(
                content = "公式 ${'$'}${'$'}x+1",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `unclosed streaming block math text part should use native raw math renderer`() {
        assertTrue(
            shouldRenderTextPartAsNativeRawMath(
                content = "公式 ${'$'}${'$'}x+1",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `unclosed streaming bracket math text part should use native raw math renderer`() {
        assertTrue(
            shouldRenderTextPartAsNativeRawMath(
                content = """公式 \[x+1""",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `unclosed streaming inline dollar math text part should use native raw math renderer`() {
        assertTrue(
            shouldRenderTextPartAsNativeRawMath(
                content = "公式 ${'$'}x+1",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `unclosed streaming escaped inline math text part should use native raw math renderer`() {
        assertTrue(
            shouldRenderTextPartAsNativeRawMath(
                content = """公式 \(x+1""",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `complete block math text part should not use native raw math renderer`() {
        assertFalse(
            shouldRenderTextPartAsNativeRawMath(
                content = "${'$'}${'$'}x+1${'$'}${'$'}",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `trailing streaming inline dollar math text part should use native inline parts renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeInlineParts(
                content = "公式 ${'$'}x+1${'$'} 成立",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `trailing streaming escaped inline math text part should use native inline parts renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeInlineParts(
                content = """公式 \(x+1\) 成立""",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `completed inline dollar math text part should use native inline parts renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeInlineParts(
                content = "公式 ${'$'}x+1${'$'} 成立",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `completed escaped inline math text part should use native inline parts renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeInlineParts(
                content = """公式 \(x+1\) 成立""",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `unclosed streaming inline dollar math text part should not use native inline parts renderer`() {
        assertFalse(
            shouldRenderTextPartWithNativeInlineParts(
                content = "公式 ${'$'}x+1",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `plain text part should not use native inline parts renderer`() {
        assertFalse(
            shouldRenderTextPartWithNativeInlineParts(
                content = "普通文本",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `plain streaming text part should not use native raw math renderer`() {
        assertFalse(
            shouldRenderTextPartAsNativeRawMath(
                content = "普通文本",
                isStreaming = true,
                isTrailingStreamingText = true,
            )
        )
    }

    @Test
    fun `complete table text part should not use native text renderer`() {
        assertFalse(
            shouldRenderTextPartNatively(
                content = "前言\n\n| A | B |\n|---|---|\n| 1 | 2 |",
                isStreaming = true,
                isTrailingStreamingText = true,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `heading text part should use native block renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeBlocks(
                content = "# 标题",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `list text part should use native block renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeBlocks(
                content = "- 第一项\n- 第二项",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `blockquote text part should use native block renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeBlocks(
                content = "> 引用内容",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `unsupported html in heading text part should not use native block renderer`() {
        assertFalse(
            shouldRenderTextPartWithNativeBlocks(
                content = "# <custom>标题</custom>",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `paragraph with embedded dollar block math should use native block renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeBlocks(
                content = "公式 ${'$'}${'$'}x+1${'$'}${'$'} 成立",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `paragraph with embedded escaped block math should use native block renderer`() {
        assertTrue(
            shouldRenderTextPartWithNativeBlocks(
                content = """公式 \[x+1\] 成立""",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
            )
        )
    }

    @Test
    fun `fenced code text part should not use native block renderer`() {
        assertFalse(
            shouldRenderTextPartWithNativeBlocks(
                content = "```kotlin\nprintln(\"hi\")\n```",
                isStreaming = false,
                isTrailingStreamingText = false,
                recursionDepth = 0,
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

    private fun tableAwareTextSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/components/table/TableAwareText.kt"),
            File("app/src/main/java/com/android/everytalk/ui/components/table/TableAwareText.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/components/table/TableAwareText.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 TableAwareText.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}

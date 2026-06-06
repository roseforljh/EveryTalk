package com.android.everytalk.ui.components.streaming

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBlocksRendererRoutingTest {

    @Test
    fun `ai stream markdown does not consume long press so text selection can open`() {
        var called = false

        val handler = streamMarkdownLongPressHandler(Sender.AI) { called = true }

        assertNull(handler)
        assertFalse(called)
    }

    @Test
    fun `non ai stream markdown keeps parent long press handler`() {
        var called = false

        val handler = streamMarkdownLongPressHandler(Sender.User) { called = true }

        assertNotNull(handler)
        handler?.invoke(Offset.Zero)
        assertTrue(called)
    }

    @Test
    fun `plain text uses compose text path`() {
        val path = resolveStreamingMarkdownRenderPath("plain text without markdown")

        assertEquals(StreamingMarkdownRenderPath.PlainText, path)
    }

    @Test
    fun `simple bold uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("This is **important** content")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `heading falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("# Heading")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `horizontal rule falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("before\n---\nafter")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `list falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("- first\n- second")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `quote falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("> quote")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `table falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("| A | B |\n|---|---|\n| 1 | 2 |")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `stream renderer keeps promoted complex math embedded in table row`() {
        val input = """
            | Metric | LaTeX | Edge |
            | :--- | :---: | :--- |
            | **Set A** | ${'$'}\sum_{i=1}^{n} x_i^2 \ge \frac{(\sum x_i)^2}{n}${'$'} | `\|` pipe escape |
        """.trimIndent()

        val result = StreamBlockParser.parse(input, "msg-table")
        val mathBlockIndex = result.blocks.indexOfFirst { it is StreamBlock.MathBlock }

        assertTrue(mathBlockIndex >= 0)
        assertEquals(
            "${'$'}${'$'}\\sum_{i=1}^{n} x_i^2 \\ge \\frac{(\\sum x_i)^2}{n}${'$'}${'$'}",
            result.blocks[mathBlockIndex].text
        )
        assertTrue(isTableCellEmbeddedMathBlock(result.blocks, mathBlockIndex))
    }

    @Test
    fun `setext heading falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Heading\n===")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `ordered list with parenthesis falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("1) first")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `html falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("<span>content</span>")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `html entity falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("A &amp; B")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `reference link falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("[docs][doc]\n[doc]: https://example.com")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `autolink falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("<https://example.com>")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `fenced code falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("```kotlin\nval x = 1\n```")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `native parser handles common non heading block markdown`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "---\n\n- first\n- **second**\n\n> quote",
            segmentId = "msg-1",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.HorizontalRule,
                NativeStreamingMarkdownBlockType.ListBlock,
                NativeStreamingMarkdownBlockType.BlockQuote,
            ),
            blocks.map { it.type },
        )
        assertEquals(listOf("first", "**second**"), blocks[1].items)
        assertEquals(0, blocks.first().start)
        assertTrue(blocks.first().endExclusive > blocks.first().start)
    }

    @Test
    fun `native parser keeps list continuations and nested bullets`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "- **first**\n    continuation\n- **second**\n    - child one\n    - child two",
            segmentId = "msg-list",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "**first** continuation"),
                NativeStreamingListItem(text = "**second**"),
                NativeStreamingListItem(text = "child one", level = 1),
                NativeStreamingListItem(text = "child two", level = 1),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `native parser splits paragraphs by blank lines`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "first paragraph\n\nsecond paragraph\n\nthird paragraph",
            segmentId = "msg-paragraphs",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
    }

    @Test
    fun `native block spacing follows chatgpt like rhythm`() {
        val paragraph = NativeStreamingMarkdownBlock(
            stableId = "p",
            type = NativeStreamingMarkdownBlockType.Paragraph,
            text = "paragraph",
        )
        val heading = NativeStreamingMarkdownBlock(
            stableId = "h",
            type = NativeStreamingMarkdownBlockType.Heading,
            text = "heading",
            level = 1,
        )
        val rule = NativeStreamingMarkdownBlock(
            stableId = "hr",
            type = NativeStreamingMarkdownBlockType.HorizontalRule,
        )

        assertEquals(12.dp, nativeMarkdownBlockSpacingAfter(paragraph, paragraph))
        assertEquals(16.dp, nativeMarkdownBlockSpacingAfter(paragraph, heading))
        assertEquals(4.dp, nativeMarkdownBlockSpacingAfter(heading, paragraph))
        assertEquals(12.dp, nativeMarkdownBlockSpacingAfter(rule, paragraph))
        assertEquals(0.5f, ChatMarkdownTextStyle.HORIZONTAL_RULE_THICKNESS_DP, 0.001f)
    }

    @Test
    fun `native heading text spec follows chatgpt title tokens`() {
        val h1 = chatGptHeadingTextSpecForLevel(1)
        val h2 = chatGptHeadingTextSpecForLevel(2)
        val h3 = chatGptHeadingTextSpecForLevel(3)
        val h4 = chatGptHeadingTextSpecForLevel(4)
        val h6 = chatGptHeadingTextSpecForLevel(6)

        assertEquals(20.sp, h1.fontSize)
        assertEquals(28.sp, h1.lineHeight)
        assertEquals(FontWeight.SemiBold, h1.fontWeight)
        assertEquals(18.sp, h2.fontSize)
        assertEquals(26.sp, h2.lineHeight)
        assertEquals(FontWeight.SemiBold, h2.fontWeight)
        assertEquals(16.sp, h3.fontSize)
        assertEquals(24.sp, h3.lineHeight)
        assertEquals(FontWeight.SemiBold, h3.fontWeight)
        assertEquals(14.sp, h4.fontSize)
        assertEquals(22.sp, h4.lineHeight)
        assertEquals(FontWeight.Normal, h4.fontWeight)
        assertEquals(14.sp, h6.fontSize)
        assertEquals(22.sp, h6.lineHeight)
        assertEquals(FontWeight.Normal, h6.fontWeight)
    }

    @Test
    fun `native streaming body text uses simple non hyphenating line break strategy`() {
        val textStyle = compactBodyTextStyle(
            style = TextStyle(fontSize = 16.sp),
            color = Color.Black,
        )

        assertEquals(LineBreak.Simple, textStyle.lineBreak)
        assertEquals(Hyphens.None, textStyle.hyphens)
    }

    @Test
    fun `inline code uses gray bold compact style without background in streaming renderer`() {
        val surfaceVariant = Color(0xFF888888)

        assertEquals(
            ChatMarkdownTextStyle.INLINE_CODE_BACKGROUND_LIGHT_ALPHA,
            chatInlineCodeBackgroundColor(surfaceVariant, isDark = false).alpha,
            0.001f,
        )
        assertEquals(
            ChatMarkdownTextStyle.INLINE_CODE_BACKGROUND_DARK_ALPHA,
            chatInlineCodeBackgroundColor(surfaceVariant, isDark = true).alpha,
            0.001f,
        )
        assertEquals(Color(0xFF4F5661), chatInlineCodeTextColor(isDark = false))
        assertEquals(Color(0xFFD1D5DB), chatInlineCodeTextColor(isDark = true))
        assertEquals(14.72.sp, chatInlineCodeFontSize(16.sp))
    }

    @Test
    fun `nested list uses hollow marker deeper indent and clearer spacing`() {
        assertEquals(16f, ChatMarkdownTextStyle.LIST_MARKER_WIDTH_DP, 0.001f)
        assertEquals(5f, ChatMarkdownTextStyle.listBulletSizeDp(level = 0), 0.001f)
        assertEquals(4f, ChatMarkdownTextStyle.listBulletSizeDp(level = 1), 0.001f)
        assertEquals(24f, ChatMarkdownTextStyle.LIST_NESTED_INDENT_DP, 0.001f)
        assertEquals(12f, ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP, 0.001f)
        assertEquals(6f, ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP, 0.001f)
        assertEquals(22f, ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP, 0.001f)
        assertTrue(ChatMarkdownTextStyle.listBulletFilled(level = 0))
        assertFalse(ChatMarkdownTextStyle.listBulletFilled(level = 1))
    }

    @Test
    fun `native list spacing applies between items by level only`() {
        val rows = listOf(
            NativeStreamingListItem(text = "first wraps naturally"),
            NativeStreamingListItem(text = "second"),
            NativeStreamingListItem(text = "child one", level = 1),
            NativeStreamingListItem(text = "child two", level = 1),
        )

        assertEquals(0.dp, nativeListItemTopSpacing(rows, 0))
        assertEquals(12.dp, nativeListItemTopSpacing(rows, 1))
        assertEquals(6.dp, nativeListItemTopSpacing(rows, 2))
        assertEquals(12.dp, nativeListItemTopSpacing(rows, 3))
    }

    @Test
    fun `native heading falls back to textview markdown path`() {
        val markdown = "# Heading with `pmatrix` and `\\partial`"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "heading-inline-code",
        )

        assertEquals(null, blocks)
        assertEquals(
            StreamingMarkdownRenderPath.FullMarkdown,
            resolveStreamingMarkdownRenderPath(markdown),
        )
    }

    @Test
    fun `native setext heading falls back to textview markdown path`() {
        val markdown = "Heading with `cases`\n---"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "setext-heading-inline-code",
        )

        assertEquals(null, blocks)
        assertEquals(
            StreamingMarkdownRenderPath.FullMarkdown,
            resolveStreamingMarkdownRenderPath(markdown),
        )
    }

    @Test
    fun `native parser rejects complex markdown`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "| A | B |\n|---|---|\n| 1 | 2 |",
            segmentId = "msg-2",
        )

        assertEquals(null, blocks)
    }

    @Test
    fun `native parser keeps stable ids for unchanged prefix blocks`() {
        val first = parseNativeStreamingMarkdownBlocks(
            text = "- first",
            segmentId = "msg-3",
        )
        val second = parseNativeStreamingMarkdownBlocks(
            text = "- first\n- second",
            segmentId = "msg-3",
        )

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(first[0].stableId, second[0].stableId)
    }

    @Test
    fun `fenced code extraction strips outer fence only`() {
        val codeBlock = extractFencedCodeBlockContent(
            "````markdown\n```kotlin\nval x = 1\n```\n````"
        )

        assertEquals("markdown", codeBlock.language)
        assertEquals("```kotlin\nval x = 1\n```", codeBlock.code)
    }
}

package com.android.everytalk.ui.components.streaming

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Sender
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
        val path = resolveStreamingMarkdownRenderPath("普通文本 without markdown")

        assertEquals(StreamingMarkdownRenderPath.PlainText, path)
    }

    @Test
    fun `simple bold uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("这是 **重点** 内容")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `heading falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("# 一级标题")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `horizontal rule falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("前文\n---\n后文")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `list falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("- 第一项\n- 第二项")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `quote falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("> 引用内容")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `table falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("| A | B |\n|---|---|\n| 1 | 2 |")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `setext heading falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("标题\n===")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `ordered list with parenthesis falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("1) 第一项")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `html falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("<span>内容</span>")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `html entity falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("A &amp; B")

        assertEquals(StreamingMarkdownRenderPath.FullMarkdown, path)
    }

    @Test
    fun `reference link falls back to full markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("[文档][doc]\n[doc]: https://example.com")

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
    fun `native parser handles common block markdown`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "# 标题\n\n---\n\n- 第一项\n- **第二项**\n\n> 引用",
            segmentId = "msg-1",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Heading,
                NativeStreamingMarkdownBlockType.HorizontalRule,
                NativeStreamingMarkdownBlockType.ListBlock,
                NativeStreamingMarkdownBlockType.BlockQuote,
            ),
            blocks.map { it.type },
        )
        assertEquals(listOf("第一项", "**第二项**"), blocks[2].items)
        assertEquals(0, blocks.first().start)
        assertTrue(blocks.first().endExclusive > blocks.first().start)
    }

    @Test
    fun `native parser splits paragraphs by blank lines`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "第一段\n\n第二段\n\n# 标题\n\n第三段",
            segmentId = "msg-paragraphs",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Heading,
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
            text = "段落",
        )
        val heading = NativeStreamingMarkdownBlock(
            stableId = "h",
            type = NativeStreamingMarkdownBlockType.Heading,
            text = "标题",
            level = 1,
        )
        val rule = NativeStreamingMarkdownBlock(
            stableId = "hr",
            type = NativeStreamingMarkdownBlockType.HorizontalRule,
        )

        assertEquals(8.dp, nativeMarkdownBlockSpacingAfter(paragraph, paragraph))
        assertEquals(12.dp, nativeMarkdownBlockSpacingAfter(paragraph, heading))
        assertEquals(12.dp, nativeMarkdownBlockSpacingAfter(rule, paragraph))
    }

    @Test
    fun `native heading text spec follows chatgpt title tokens`() {
        val h1 = chatGptHeadingTextSpecForLevel(1)
        val h2 = chatGptHeadingTextSpecForLevel(2)
        val h3 = chatGptHeadingTextSpecForLevel(3)
        val h6 = chatGptHeadingTextSpecForLevel(6)

        assertEquals(20.sp, h1.fontSize)
        assertEquals(28.sp, h1.lineHeight)
        assertEquals(FontWeight.SemiBold, h1.fontWeight)
        assertEquals(18.sp, h2.fontSize)
        assertEquals(24.sp, h2.lineHeight)
        assertEquals(FontWeight.SemiBold, h2.fontWeight)
        assertEquals(17.sp, h3.fontSize)
        assertEquals(24.sp, h3.lineHeight)
        assertEquals(FontWeight.SemiBold, h3.fontWeight)
        assertEquals(16.sp, h6.fontSize)
        assertEquals(22.sp, h6.lineHeight)
        assertEquals(FontWeight.Medium, h6.fontWeight)
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
            text = "# 标题\n\n- 第一项",
            segmentId = "msg-3",
        )
        val second = parseNativeStreamingMarkdownBlocks(
            text = "# 标题\n\n- 第一项\n- 第二项",
            segmentId = "msg-3",
        )

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(first[0].stableId, second[0].stableId)
        assertEquals(first[1].stableId, second[1].stableId)
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

package com.android.everytalk.ui.components.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBlockParserTest {

    @Test
    fun `supports escaped inline math delimiters`() {
        val result = StreamBlockParser.parse("前缀 \\(x+1\\) 后缀", "msg-1")

        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals(MathBlockState.RENDERED, (result.blocks[1] as StreamBlock.MathInline).state)
        assertEquals("\\(x+1\\)", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `supports escaped block math delimiters`() {
        val result = StreamBlockParser.parse("\\[x^2 + y^2\\]", "msg-2")

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.MathBlock)
        assertEquals(MathBlockState.RENDERED, (result.blocks.first() as StreamBlock.MathBlock).state)
        assertEquals("\\[x^2 + y^2\\]", result.blocks.first().text)
    }

    @Test
    fun `keeps unclosed escaped inline math as raw pending block`() {
        val result = StreamBlockParser.parse("结果是 \\(x+1", "msg-3")

        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals(MathBlockState.RAW, (result.blocks[1] as StreamBlock.MathInline).state)
        assertTrue(result.hasPendingMath)
    }

    @Test
    fun `stable ids remain unchanged for unchanged prefix blocks`() {
        val first = StreamBlockParser.parse("前缀 \\(x+1\\)", "msg-4")
        val second = StreamBlockParser.parse("前缀 \\(x+1\\) 后缀继续", "msg-4")

        assertEquals(first.blocks[0].stableId, second.blocks[0].stableId)
        assertEquals(first.blocks[1].stableId, second.blocks[1].stableId)
        assertEquals(first.blocks[1].text, second.blocks[1].text)
    }

    @Test
    fun `streaming render state keeps only tail mutable while streaming`() {
        val state = buildStreamingRenderState(
            messageId = "msg-5",
            content = "第一段\n```kotlin\nval x = 1\n```\n正在增长的尾段",
            isStreaming = true,
            isComplete = false,
        )

        assertTrue(state.blocks.size > 1)
        assertEquals(state.blocks.dropLast(1), state.committedBlocks)
        assertEquals(state.blocks.takeLast(1), state.tailBlocks)
    }

    @Test
    fun `complete render state commits all blocks and has no mutable tail`() {
        val state = buildStreamingRenderState(
            messageId = "msg-6",
            content = "第一段\n```kotlin\nval x = 1\n```\n最终尾段",
            isStreaming = false,
            isComplete = true,
        )

        assertEquals(state.blocks, state.committedBlocks)
        assertTrue(state.tailBlocks.isEmpty())
    }

    @Test
    fun `streaming render state splits native markdown blocks`() {
        val state = buildStreamingRenderState(
            messageId = "msg-7",
            content = "# 标题\n\n- 第一项\n- 第二项\n\n尾段",
            isStreaming = true,
            isComplete = false,
        )

        assertEquals(3, state.nativeMarkdownBlocks.size)
        assertEquals(state.nativeMarkdownBlocks.dropLast(1), state.committedNativeMarkdownBlocks)
        assertEquals(state.nativeMarkdownBlocks.takeLast(1), state.tailNativeMarkdownBlocks)
        assertTrue(state.committedNativeMarkdownBlocksHash != "empty")
        assertTrue(state.tailNativeMarkdownBlocksHash != "empty")
    }

    @Test
    fun `complete render state commits native markdown blocks`() {
        val state = buildStreamingRenderState(
            messageId = "msg-8",
            content = "# 标题\n\n---\n\n> 引用",
            isStreaming = false,
            isComplete = true,
        )

        assertEquals(state.nativeMarkdownBlocks, state.committedNativeMarkdownBlocks)
        assertTrue(state.tailNativeMarkdownBlocks.isEmpty())
    }

    @Test
    fun `code block with longer outer fence keeps inner triple fences inside code`() {
        val result = StreamBlockParser.parse(
            "````markdown\n```kotlin\nval x = 1\n```\n````",
            "msg-9",
        )

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.CodeBlock)
        assertEquals(
            "```kotlin\nval x = 1\n```",
            extractFencedCodeBlockContent(result.blocks.first().text).code,
        )
    }

    @Test
    fun `top level code block closes with up to three spaces indentation`() {
        val result = StreamBlockParser.parse(
            "```bash\nopencode web\n   ```",
            "msg-10",
        )

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.CodeBlock)
        assertEquals("opencode web", extractFencedCodeBlockContent(result.blocks.first().text).code.trim())
    }

    @Test
    fun `closing fence can be longer than opening fence`() {
        val result = StreamBlockParser.parse(
            "```bash\nopencode web\n``````",
            "msg-11",
        )

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.CodeBlock)
        assertEquals("opencode web", extractFencedCodeBlockContent(result.blocks.first().text).code.trim())
    }
}

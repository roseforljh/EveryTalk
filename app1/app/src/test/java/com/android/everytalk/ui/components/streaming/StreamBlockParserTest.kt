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
}

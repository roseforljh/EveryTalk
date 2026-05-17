package com.android.everytalk.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkTagStreamRouterTest {

    @Test
    fun `extracts leading multiline think block`() {
        val result = extractThinkTagContent(
            """
            <think>
            分析问题
            </think>
            正文回答
            """.trimIndent()
        )

        assertTrue(result.changed)
        assertEquals("分析问题", result.reasoning)
        assertEquals("正文回答", result.content)
    }

    @Test
    fun `does not extract inline normal think tag text`() {
        val result = extractThinkTagContent("请解释 `<think>foo</think>` 这个标签。")

        assertFalse(result.changed)
        assertEquals("请解释 `<think>foo</think>` 这个标签。", result.content)
        assertEquals("", result.reasoning)
    }

    @Test
    fun `does not extract leading inline think example`() {
        val result = extractThinkTagContent("<think>foo</think> 是一个普通标签示例。")

        assertFalse(result.changed)
        assertEquals("<think>foo</think> 是一个普通标签示例。", result.content)
        assertEquals("", result.reasoning)
    }

    @Test
    fun `routes only leading multiline think block as reasoning`() {
        val router = ThinkTagStreamRouter()
        val chunks = buildList {
            addAll(router.feed("<think>"))
            addAll(router.feed("\n分析"))
            addAll(router.feed("\n</think>\n正文"))
            addAll(router.flush())
        }

        assertEquals(
            listOf(
                ThinkTagStreamRouter.Chunk("分析\n", true),
                ThinkTagStreamRouter.Chunk("正文", false),
            ),
            chunks,
        )
    }

    @Test
    fun `routes leading inline think tag as content`() {
        val router = ThinkTagStreamRouter()
        val chunks = buildList {
            addAll(router.feed("<think>foo</think> 是标签示例"))
            addAll(router.flush())
        }

        assertEquals(
            listOf(ThinkTagStreamRouter.Chunk("<think>foo</think> 是标签示例", false)),
            chunks,
        )
    }

    @Test
    fun `extracts leading multiline think block even when final content is empty`() {
        val text = """
            <think>
            I've just received information about Bill Gates.
            </think>
        """.trimIndent()

        val result = extractThinkTagContent(text)

        assertTrue(result.changed)
        assertEquals("I've just received information about Bill Gates.", result.reasoning)
        assertEquals("", result.content)
    }

    @Test
    fun `does not extract untagged text even if it looks like reasoning`() {
        val text = """
            I've just received information about a topic.

            在这里，这是正文回答。
        """.trimIndent()

        val result = extractThinkTagContent(text)

        assertFalse(result.changed)
        assertEquals(text, result.content)
        assertEquals("", result.reasoning)
    }
}

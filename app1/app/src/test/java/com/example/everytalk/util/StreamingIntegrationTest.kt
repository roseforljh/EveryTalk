package com.example.everytalk.util

import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.util.messageprocessor.MarkdownBlockManager
import com.example.everytalk.util.messageprocessor.MessageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamingIntegrationTest {

    @Test
    fun testMessageProcessor_CumulativeContent_NoDuplication() = runBlocking {
        // 准备：MessageProcessor 绑定消息ID，模拟后端“累计全文”推送
        val mp = MessageProcessor()
        val messageId = "msg-1"
        mp.initialize(sessionId = "s-1", messageId = messageId)

        // 逐步累计：每次事件的 text 是从头到当前的“全文”
        val chunks = listOf(
            "Hello",
            "Hello, wor",
            "Hello, world",
            "Hello, world!\n\nThis is a list:\n1. A\n2. B\n",
        )

        var lastResult: com.example.everytalk.util.messageprocessor.ProcessedEventResult? = null
        for (t in chunks) {
            lastResult = mp.processStreamEvent(AppStreamEvent.Content(text = t, output_type = "general", block_type = "text"), messageId)
            assertTrue(
                "每步都应返回 ContentUpdated 以便 UI 刷新",
                lastResult is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated
            )
            // MessageProcessor 内部会对文本进行轻量格式清理，这里只断言“至少包含”当前全文
            val contentNow = (lastResult as com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated).content
            assertTrue("当前内容应包含累计全文片段", contentNow.contains(t))
        }

        // 终态断言：不重复、不回退
        val finalText = mp.getCurrentText()
        assertTrue(finalText.contains("Hello, world!"))
        assertTrue(finalText.contains("1. A"))
        assertTrue(finalText.contains("2. B"))

        // 再次喂同样的“最终全文”，应不产生重复（幂等）
        val resultRepeat = mp.processStreamEvent(AppStreamEvent.Content(text = chunks.last(), output_type = "general", block_type = "text"), messageId)
        assertTrue(resultRepeat is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated)
        val afterRepeat = (resultRepeat as com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated).content
        assertEquals("重复输入最终全文不应导致内容变化（幂等）", finalText, afterRepeat)
    }

    @Test
    fun testMarkdownBlockManager_Overwrite_OnSameBlockType() {
        // 准备：BlockManager，模拟前端对同一 block_type 的覆盖式更新
        val bm = MarkdownBlockManager()

        val first = AppStreamEvent.Content(
            text = "```python\nprint('hi')\n```",
            output_type = "code",
            block_type = "code_block"
        )
        val second = AppStreamEvent.Content(
            text = "```python\nprint('hi')\nprint('there')\n```",
            output_type = "code",
            block_type = "code_block"
        )

        // 首次事件：应新增一个块
        bm.processEvent(first)
        assertEquals(1, bm.blocks.size)

        // 第二次同 block_type：应覆盖同一块而不是追加（保持 size==1）
        bm.processEvent(second)
        assertEquals(1, bm.blocks.size)

        val block = bm.blocks.first()
        assertTrue("块类型应为 CodeBlock", block is com.example.everytalk.ui.components.MarkdownPart.CodeBlock)
        val code = (block as com.example.everytalk.ui.components.MarkdownPart.CodeBlock).content
        assertTrue(code.contains("print('there')"))
        assertFalse("不应出现重复拼接", code.contains("print('there')\nprint('there')"))
    }

    @Test
    fun testMarkdownBlockManager_TextOverwrite_NoDuplication() {
        val bm = MarkdownBlockManager()

        val t1 = AppStreamEvent.Content(
            text = "段落A",
            output_type = "general",
            block_type = "text"
        )
        val t2 = AppStreamEvent.Content(
            text = "段落A\n段落B",
            output_type = "general",
            block_type = "text"
        )

        bm.processEvent(t1)
        assertEquals(1, bm.blocks.size)
        bm.processEvent(t2)
        assertEquals(1, bm.blocks.size)

        val txt = (bm.blocks.first() as com.example.everytalk.ui.components.MarkdownPart.Text).content
        // 覆盖不是追加：内容应等于“最新全文”
        assertEquals("段落A\n段落B", txt)
    }
}
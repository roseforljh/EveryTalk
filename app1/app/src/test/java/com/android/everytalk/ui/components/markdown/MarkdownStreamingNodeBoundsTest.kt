package com.android.everytalk.ui.components.markdown

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStreamingNodeBoundsTest {

    @Test
    fun `重基一帧内旧AST的偏移会超出新正文长度`() {
        val markdown = "第一行。\n\n- 项目一\n- 项目二\n- 项目三\n"
        val nodes = (parseMarkdown(markdown) as State.Success).node.children
        val listNode = nodes.first { it.type == MarkdownElementTypes.UNORDERED_LIST }
        val shortenedContent = markdown.take(listNode.startOffset)

        assertTrue(listNode.endOffset > shortenedContent.length)
        assertThrows(StringIndexOutOfBoundsException::class.java) {
            shortenedContent.subSequence(listNode.startOffset, listNode.endOffset)
        }

        val filtered = markdownNodesWithinContent(nodes, shortenedContent.length)
        assertTrue(filtered.none { it.endOffset > shortenedContent.length })
        assertTrue(filtered.none { it === listNode })
    }

    @Test
    fun `正文完整时节点全部保留`() {
        val markdown = "第一行。\n\n- 项目一\n- 项目二\n- 项目三\n"
        val nodes = (parseMarkdown(markdown) as State.Success).node.children

        assertEquals(nodes, markdownNodesWithinContent(nodes, markdown.length))
    }
}

package com.android.everytalk.ui.screens.appinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryOverviewParserTest {
    @Test fun `总览按段落拆分并去掉会话指针、行内标记和空段`() {
        val sections = parseMemoryOverview("""
            ## User profile
            - Works on **proxy** nodes with `sing-box`.
            ## User preferences
            None
            ## What's in memory
            ### 代理
            - [conv:chat_1] 2026-11-11 — 节点迁移到 sing-box
            - [conv:chat_2] 没有日期的条目
        """.trimIndent())
        assertEquals(listOf("User profile", "What's in memory"), sections.map { it.title })
        assertEquals(MemoryOverviewItem.Bullet("Works on proxy nodes with sing-box."), sections[0].items.single())
        assertEquals(listOf(
            MemoryOverviewItem.Heading("代理"),
            MemoryOverviewItem.Bullet("节点迁移到 sing-box", "2026-11-11", "chat_1"),
            MemoryOverviewItem.Bullet("没有日期的条目", null, "chat_2"),
        ), sections[1].items)
    }

    @Test fun `会话摘要按标题、列表和段落渲染`() {
        assertEquals(listOf(MemoryBlock.Heading("任务", 2), MemoryBlock.Bullet("改 sing-box"), MemoryBlock.Paragraph("状态：完成")),
            parseMemoryMarkdown("## 任务\n\n- 改 **sing-box**\n状态：完成"))
    }

    @Test fun `token 数量缩写`() {
        assertEquals(listOf("999", "12.3K", "1.2M"), listOf(999L, 12_345L, 1_234_567L).map(::formatTokenCount))
    }
}

package com.android.everytalk.statecontroller.mcp.dispatch

import com.android.everytalk.data.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCandidateSelectorTest {

    @Test
    fun `docs lookup keeps docs tools first`() {
        val selected = selectMcpCandidates(
            intent = QueryIntent.DOCS_LOOKUP,
            candidates = listOf(
                toMcpToolCandidate("Context7", McpTool("query-docs", "Query docs")),
                toMcpToolCandidate("Exa", McpTool("web_search_exa", "Search web")),
                toMcpToolCandidate("Firecrawl", McpTool("crawl_page", "Read page"))
            )
        )

        assertEquals(
            listOf(McpToolCategory.DOCS, McpToolCategory.SEARCH, McpToolCategory.BROWSER),
            selected.map { it.category }
        )
    }

    @Test
    fun `local reasoning keeps enabled mcp tools available`() {
        val selected = selectMcpCandidates(
            intent = QueryIntent.LOCAL_REASONING,
            candidates = listOf(
                toMcpToolCandidate("Context7", McpTool("query-docs", "Query docs")),
                toMcpToolCandidate("Internal", McpTool("create_ticket", "Create ticket"))
            )
        )

        assertEquals(2, selected.size)
    }

    @Test
    fun `disabled tools are never exposed`() {
        val selected = selectMcpCandidates(
            intent = QueryIntent.DOCS_LOOKUP,
            candidates = listOf(
                toMcpToolCandidate(
                    "Context7",
                    McpTool("query-docs", "Query docs", enable = false)
                )
            )
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `duplicate tool names are exposed once`() {
        val selected = selectMcpCandidates(
            intent = QueryIntent.LOCAL_REASONING,
            candidates = listOf(
                toMcpToolCandidate("First", McpTool("lookup", "First lookup")),
                toMcpToolCandidate("Second", McpTool("lookup", "Second lookup"))
            )
        )

        assertEquals(1, selected.size)
        assertEquals("First lookup", selected.single().description)
    }

    @Test
    fun `intent preference does not remove tools needed by combined tasks`() {
        val selected = selectMcpCandidates(
            intent = QueryIntent.REALTIME_INFO,
            candidates = listOf(
                toMcpToolCandidate("Internal", McpTool("create_ticket", "Create ticket")),
                toMcpToolCandidate("Exa", McpTool("web_search_exa", "Search web"))
            )
        )

        assertEquals(listOf("web_search_exa", "create_ticket"), selected.map { it.toolName })
    }
}

package com.android.everytalk.statecontroller.mcp.dispatch

import com.android.everytalk.data.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCandidateSelectorTest {

    @Test
    fun `docs lookup keeps docs tools first`() {
        val plan = selectMcpCandidates(
            intent = McpDispatchIntent(
                primaryIntent = QueryIntent.DOCS_LOOKUP,
                shouldPreferMcp = true,
                candidateCategories = setOf(McpToolCategory.DOCS, McpToolCategory.SEARCH)
            ),
            candidates = listOf(
                toMcpToolCandidate("docs", "Context7", McpTool("query-docs", "Query docs")),
                toMcpToolCandidate("search", "Exa", McpTool("web_search_exa", "Search web")),
                toMcpToolCandidate("browser", "Firecrawl", McpTool("crawl_page", "Read page"))
            ),
            strategy = McpDispatchStrategy.modelLedDefault()
        )

        assertEquals(McpToolCategory.DOCS, plan.exposedTools.first().category)
    }

    @Test
    fun `local reasoning hides external mcp tools`() {
        val plan = selectMcpCandidates(
            intent = McpDispatchIntent(
                primaryIntent = QueryIntent.LOCAL_REASONING,
                shouldPreferMcp = false,
                candidateCategories = emptySet()
            ),
            candidates = listOf(
                toMcpToolCandidate("docs", "Context7", McpTool("query-docs", "Query docs")),
                toMcpToolCandidate("search", "Exa", McpTool("web_search_exa", "Search web"))
            ),
            strategy = McpDispatchStrategy.modelLedDefault()
        )

        assertTrue(plan.exposedTools.isEmpty())
    }
}

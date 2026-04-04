package com.android.everytalk.statecontroller.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolDescriptionEnhancerTest {

    @Test
    fun `search style tool gets news and search scenario enhancement`() {
        val description = buildEnhancedMcpToolDescription(
            toolName = "web_search_exa",
            originalDescription = "Search the web"
        ).orEmpty()

        assertTrue(description.contains("最新新闻"))
        assertTrue(description.contains("热点事件"))
        assertTrue(description.contains("网页信息检索"))
    }

    @Test
    fun `browser style tool gets page reading scenario enhancement`() {
        val description = buildEnhancedMcpToolDescription(
            toolName = "browser_fetch_page",
            originalDescription = "Fetch and parse page content"
        ).orEmpty()

        assertTrue(description.contains("打开网页"))
        assertTrue(description.contains("提取网页正文"))
    }

    @Test
    fun `finance style tool gets market scenario enhancement`() {
        val description = buildEnhancedMcpToolDescription(
            toolName = "stock_market_lookup",
            originalDescription = "Lookup stock price"
        ).orEmpty()

        assertTrue(description.contains("股价"))
        assertTrue(description.contains("市场数据"))
    }
}

package com.android.everytalk.data.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolDiscoveryTest {
    private fun tool(server: String, name: String, description: String, vararg params: String) = mapOf<String, Any>(
        "type" to "function",
        "function" to mapOf(
            "name" to "mcp_${"0".repeat(32)}_$name",
            "description" to McpToolDiscovery.tagServer(server, description),
            "parameters" to mapOf("type" to "object", "properties" to params.associateWith { mapOf("type" to "string") }),
        ),
    )

    private val github = listOf(
        tool("GitHub", "create_issue", "Create a new issue in a GitHub repository.", "owner", "repo", "title"),
        tool("GitHub", "list_pull_requests", "List pull requests in a GitHub repository.", "owner", "repo"),
        tool("GitHub", "search_code", "Search for code across GitHub repositories.", "query"),
        tool("GitHub", "get_file_contents", "Get the contents of a file or directory from a repository.", "owner", "repo", "path"),
    ) + (1..30).map { tool("GitHub", "misc_$it", "Miscellaneous GitHub operation number $it.") }
    private val notion = listOf(
        tool("Notion", "notion_search", "Search pages and databases in the Notion workspace.", "query"),
        tool("Notion", "create_page", "Create a new page in Notion.", "parent", "title"),
    ) + (1..20).map { tool("Notion", "misc_$it", "Notion helper $it.") }
    private val search = listOf(
        tool("Parallel", "web_search", "Search the web and return dense excerpts.", "objective"),
        tool("Parallel", "web_fetch", "Fetch URLs and return markdown.", "urls"),
        tool("Exa", "web_search_exa", "Search the web with Exa.", "query"),
    )
    private val all = github + notion + search

    private fun names(tools: List<Map<String, Any>>?) = tools.orEmpty().map { McpToolDiscovery.name(it).substringAfterLast("0_") }

    private fun search(query: String, limit: Int = 3): List<String> {
        val result = McpToolDiscovery.search(AgentContentBlock.ToolCall("c", McpToolDiscovery.NAME,
            buildJsonObject { put("query", query); put("limit", limit) }), all)
        return result.content.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content.substringAfterLast("0_") }
    }

    @Test
    fun `小服务器直接声明，大服务器延迟并只在系统提示列一行`() {
        val projected = names(McpToolDiscovery.project(all, emptyList()))
        // 3 个搜索工具 + Notion 22 个超出 24，Notion 与 GitHub 都延迟；搜索服务器保持可见。
        assertEquals(listOf("web_search", "web_fetch", "web_search_exa", McpToolDiscovery.NAME), projected.map { it.removePrefix("mcp_") })
        val catalog = McpToolDiscovery.serverCatalog(all, mapOf("GitHub" to "GitHub's official MCP server.\nMore."))!!
        assertTrue("- GitHub (34 tools): GitHub's official MCP server." in catalog)
        assertTrue("- Notion (22 tools): notion_search, create_page" in catalog)
        assertTrue("Parallel" !in catalog)
        assertNull(McpToolDiscovery.serverCatalog(search))
        assertEquals(search, McpToolDiscovery.project(search, emptyList()))
    }

    @Test
    fun `BM25 按动作和对象命中正确工具，只搜延迟工具`() {
        assertEquals("create_issue", search("create github issue").first())
        assertEquals("list_pull_requests", search("list pull requests").first())
        assertEquals("notion_search", search("search notion pages").first())
        assertEquals("get_file_contents", search("read file contents in repo").first())
        assertEquals("create_page", search("create_page").first())
        // 直接可见的搜索工具不会被重复加载。
        assertTrue(search("web search exa", 10).none { it.startsWith("web_") })
    }

    @Test
    fun `中文按单字和双字切分`() {
        assertTrue("创建" in Bm25.tokenize("创建页面"))
        assertTrue("页" in Bm25.tokenize("创建页面"))
        assertEquals(listOf("issue", "list", "pull", "request"), Bm25.tokenize("issues list pullRequests"))
    }

    @Test
    fun `加载的工具在后续请求保留且有上限`() {
        val loaded = github.map(McpToolDiscovery::name)
        val projected = names(McpToolDiscovery.project(all, loaded))
        assertEquals(McpToolDiscovery.MAX_LOADED, projected.count { it.startsWith("misc_") || it in setOf("create_issue", "list_pull_requests", "search_code", "get_file_contents") })
        // 保留的是最近加载的那批。
        assertTrue("misc_30" in projected && "create_issue" !in projected)
    }
}

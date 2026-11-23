package com.android.everytalk.ui.screens.mcp

import com.android.everytalk.data.mcp.McpKeyProvider
import com.android.everytalk.data.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerFormTest {
    @Test
    fun `通用表单自动命名并按地址选择传输方式`() {
        val http = buildMcpServerConfig("https://mcp.context7.com/mcp", "", " k1 ")!!
        assertTrue(http is McpServerConfig.StreamableHTTPServer)
        assertEquals("Context7", http.name)
        assertEquals(listOf("Authorization" to "Bearer k1"), http.commonOptions.headers)
        assertEquals("k1", mcpApiKeyOf(http.commonOptions.headers))

        assertTrue(buildMcpServerConfig("https://example.com/sse/", "Demo", "") is McpServerConfig.SseTransportServer)
    }

    @Test
    fun `非法地址不生成配置，编辑保留原ID、传输方式与非认证请求头`() {
        assertNull(buildMcpServerConfig("ftp://example.com", "", ""))
        assertNull(buildMcpServerConfig("example.com/mcp", "", ""))

        val old = McpServerConfig.SseTransportServer(
            id = "keep",
            url = "https://example.com/events",
            commonOptions = com.android.everytalk.data.mcp.McpCommonOptions(
                headers = listOf("Authorization" to "Bearer old-k", "X-Workspace" to "team-a"),
            ),
        )
        assertEquals("old-k", mcpApiKeyOf(old.commonOptions.headers))
        val edited = buildMcpServerConfig("https://example.com/events", "New", "new-k", old)!!
        assertEquals("keep", edited.id)
        assertTrue(edited is McpServerConfig.SseTransportServer)
        assertEquals(
            listOf("Authorization" to "Bearer new-k", "X-Workspace" to "team-a"),
            edited.commonOptions.headers,
        )
    }

    @Test
    fun `内置服务把Key拼进地址或请求头，并识别旧随机ID记录`() {
        val fc = McpKeyProvider.FIRECRAWL.config(" fc-1 ")
        assertEquals("https://mcp.firecrawl.dev/fc-1/v2/mcp", fc.url)
        assertEquals("mcp-firecrawl", fc.id)
        val c7 = McpKeyProvider.CONTEXT7.config("k")
        assertEquals("https://mcp.context7.com/mcp", c7.url)
        assertEquals(listOf("CONTEXT7_API_KEY" to "k"), c7.commonOptions.headers)
        assertTrue(McpKeyProvider.CONTEXT7.config("").commonOptions.headers.isEmpty())
        assertTrue(McpKeyProvider.CONTEXT7.matches(McpServerConfig.StreamableHTTPServer(id = "random", url = "https://mcp.context7.com/mcp")))
        assertTrue(!McpKeyProvider.EXA.matches(c7))

        val old = McpServerConfig.StreamableHTTPServer(id = "old", url = "https://mcp.exa.ai/mcp?exaApiKey=a",
            commonOptions = com.android.everytalk.data.mcp.McpCommonOptions(enable = false, name = "我的 Exa"))
        val renewed = McpKeyProvider.EXA.withKey(old, "b")
        assertEquals("old", renewed.id)
        assertEquals("我的 Exa", renewed.name)
        assertTrue(!renewed.enabled)
        assertEquals("https://mcp.exa.ai/mcp?exaApiKey=b", renewed.url)

        assertEquals("https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-1", McpKeyProvider.TAVILY.config("tvly-1").url)
        assertEquals(listOf("Authorization" to "Bearer jina-1"), McpKeyProvider.JINA.config("jina-1").commonOptions.headers)
        assertTrue(McpKeyProvider.JINA.config("").commonOptions.headers.isEmpty())
    }

    @Test
    fun `匿名端点不带空凭据并保留完整搜索抓取工具`() {
        assertEquals("https://mcp.parallel.ai/mcp", McpKeyProvider.PARALLEL.config("").url)
        assertTrue(McpKeyProvider.PARALLEL.config("").headers.isEmpty())
        assertEquals(listOf("Authorization" to "Bearer parallel-key"), McpKeyProvider.PARALLEL.config(" parallel-key ").commonOptions.headers)
        assertEquals("parallel-key", McpKeyProvider.PARALLEL.keyOf(McpKeyProvider.PARALLEL.config("parallel-key")))
        assertEquals("fc-1", McpKeyProvider.FIRECRAWL.keyOf(McpKeyProvider.FIRECRAWL.config("fc-1")))
        assertEquals("", McpKeyProvider.FIRECRAWL.keyOf(McpKeyProvider.FIRECRAWL.config("")))
        assertEquals("b", McpKeyProvider.EXA.keyOf(McpKeyProvider.EXA.config("b")))
        assertEquals("https://mcp.firecrawl.dev/v2/mcp", McpKeyProvider.FIRECRAWL.config("").url)
        assertEquals("https://mcp.exa.ai/mcp", McpKeyProvider.EXA.config("").url)
        assertEquals("https://mcp.brightdata.com/mcp?token=token-1", McpKeyProvider.BRIGHT_DATA.config(" token-1 ").url)
        listOf(McpKeyProvider.PARALLEL, McpKeyProvider.FIRECRAWL, McpKeyProvider.EXA).forEach { provider ->
            assertTrue(!provider.keyRequired)
            val legacy = provider.config("").clone(id = "legacy")
            assertTrue(provider.matches(legacy))
        }
    }
}

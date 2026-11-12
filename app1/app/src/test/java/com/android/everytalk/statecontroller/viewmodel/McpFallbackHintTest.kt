package com.android.everytalk.statecontroller.viewmodel

import com.android.everytalk.data.mcp.McpTool
import com.android.everytalk.data.network.AppToolExecutionResult
import com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFallbackHintTest {
    private val candidates = listOf(
        toMcpToolCandidate("Parallel", McpTool("web_search", "Search the web."), "p_search"),
        toMcpToolCandidate("Exa", McpTool("web_search_exa", "Search the web with Exa."), "e_search"),
        toMcpToolCandidate("GitHub", McpTool("create_branch", "Create a branch."), "g_branch"),
    )
    private val failure = AppToolExecutionResult(buildJsonObject { put("ok", false); put("error", "quota") }, isError = true)

    @Test
    fun `失败的搜索工具附上其他家的同类工具`() {
        val hint = withMcpFallbackHint(failure, "p_search", candidates).content.jsonObject["alternatives"].toString()
        assertTrue("e_search ([Exa] Search the web with Exa.)" in hint)
        assertTrue("p_search" !in hint && "g_branch" !in hint)
    }

    @Test
    fun `成功或没有同类工具时原样返回`() {
        val ok = AppToolExecutionResult(JsonPrimitive("ok"))
        assertEquals(ok, withMcpFallbackHint(ok, "p_search", candidates))
        assertEquals(failure, withMcpFallbackHint(failure, "g_branch", candidates))
    }
}

package com.android.everytalk.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 只投影当前模型声明，不改变已授权工具全集；加载 ID 由成功 ToolResult 保存。 */
internal object McpToolDiscovery {
    const val NAME = "tool_search"
    const val INLINE_MCP_LIMIT = 24

    fun name(definition: Map<String, Any>): String =
        ((definition["function"] as? Map<*, *>)?.get("name") ?: definition["name"])?.toString().orEmpty()

    private fun isMcp(definition: Map<String, Any>) = name(definition).startsWith("mcp_")

    fun project(all: List<Map<String, Any>>?, loaded: List<String>): List<Map<String, Any>>? {
        if (all == null || all.count(::isMcp) <= INLINE_MCP_LIMIT) return all
        val byName = all.associateBy(::name)
        // 非 MCP 工具保持原样；已加载 MCP 按持久化顺序追加，撤权后自动从声明中消失。
        return all.filterNot(::isMcp) + loaded.distinct().mapNotNull { byName[it]?.takeIf(::isMcp) } + definition
    }

    fun search(call: AgentContentBlock.ToolCall, all: List<Map<String, Any>>?): AgentContentBlock.ToolResult {
        val query = (call.arguments["query"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().lowercase()
        val limit = ((call.arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 8).coerceIn(1, 20)
        val terms = query.split(Regex("[^a-z0-9一-鿿]+")).filter(String::isNotBlank)
        val found = CodemodeTool.callableTools(all).filter { it.name.startsWith("mcp_") }
            .map { tool ->
                val hay = "${tool.name} ${tool.description} ${tool.parameters}".lowercase()
                tool to (if (tool.name.lowercase() == query) 10_000 else terms.sumOf { term ->
                    (if (term in tool.name.lowercase()) 3 else 0) + (hay.split(term).size - 1)
                })
            }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
        return AgentContentBlock.ToolResult(
            call.id, NAME,
            buildJsonObject {
                put("message", "Matched tools will be declared on your NEXT request. Do not call an undeclared tool in this response.")
                put("tools", buildJsonArray { found.forEach { tool ->
                    add(buildJsonObject { put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters) })
                } })
            },
            loadedMcpToolIds = found.map { it.name },
        )
    }

    private val definition: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to NAME,
            "description" to "Find authorized MCP tools by name, task or parameter description. Matching tools become available on the NEXT request. Use this when the needed MCP tool is not declared; codemode can already search and call the full authorized catalog.",
            "parameters" to mapOf(
                "type" to "object", "properties" to mapOf(
                    "query" to mapOf("type" to "string"),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20),
                ), "required" to listOf("query"),
            ),
        ),
        "executionMode" to "sequential",
    )
}

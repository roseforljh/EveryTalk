package com.android.everytalk.statecontroller.mcp.dispatch

import com.android.everytalk.data.mcp.McpInputSchema
import com.android.everytalk.data.mcp.McpTool

private val DOCS_TOOL_KEYWORDS = listOf(
    "doc", "docs", "documentation", "library", "framework", "sdk", "api", "context7", "query-docs", "resolve-library-id"
)
private val SEARCH_TOOL_KEYWORDS = listOf(
    "search", "exa", "query", "news", "latest", "web_search", "fetch search"
)
private val BROWSER_TOOL_KEYWORDS = listOf(
    "browser", "crawl", "scrape", "page", "web", "url", "read page", "fetch page"
)
data class McpToolCandidate(
    val toolName: String,
    val description: String,
    val category: McpToolCategory,
    val enabled: Boolean,
    val schema: McpInputSchema?,
)

internal fun toMcpToolCandidate(
    serverName: String,
    tool: McpTool,
    exposedToolName: String = tool.name,
): McpToolCandidate {
    val normalized = "$serverName ${tool.name} ${tool.description.orEmpty()}".lowercase()
    val category = when {
        DOCS_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.DOCS
        SEARCH_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.SEARCH
        BROWSER_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.BROWSER
        else -> McpToolCategory.OTHER
    }

    return McpToolCandidate(
        toolName = exposedToolName,
        description = tool.description?.takeIf { it.isNotBlank() } ?: tool.name,
        category = category,
        enabled = tool.enable,
        schema = tool.inputSchema,
    )
}

internal fun McpToolCandidate.toToolDefinition(): Map<String, Any> {
    val functionDef = mutableMapOf<String, Any>(
        "name" to toolName,
        "description" to description,
    )

    schema?.let { schemaValue ->
        when (schemaValue) {
            is McpInputSchema.Obj -> {
                val parameters = mutableMapOf<String, Any>(
                    "type" to "object",
                    "properties" to schemaValue.properties.toMap(),
                )
                schemaValue.required?.let { parameters["required"] = it }
                functionDef["parameters"] = parameters
            }
        }
    } ?: run {
        functionDef["parameters"] = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
        )
    }

    return mapOf(
        "type" to "function",
        "function" to functionDef,
    )
}

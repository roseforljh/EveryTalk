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
private val DATABASE_TOOL_KEYWORDS = listOf(
    "database", "sql", "query table", "record", "row", "collection"
)
private val SERVICE_TOOL_KEYWORDS = listOf(
    "service", "internal", "crm", "ticket", "workflow", "business"
)

enum class McpCapability {
    LOOKUP_DOCS,
    FETCH_LATEST_INFO,
    READ_WEBPAGE,
    QUERY_STRUCTURED_DATA,
    ACCESS_INTERNAL_SERVICE,
}

enum class ToolCostLevel {
    LOW,
    MEDIUM,
    HIGH,
}

enum class ToolLatencyLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class McpToolCandidate(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val originalDescription: String?,
    val enhancedDescription: String,
    val category: McpToolCategory,
    val capabilities: Set<McpCapability>,
    val costLevel: ToolCostLevel,
    val latencyLevel: ToolLatencyLevel,
    val requiresNetwork: Boolean,
    val reliabilityScore: Int,
    val enabled: Boolean,
    val schema: McpInputSchema?,
)

internal fun toMcpToolCandidate(
    serverId: String,
    serverName: String,
    tool: McpTool,
): McpToolCandidate {
    val normalized = "$serverName ${tool.name} ${tool.description.orEmpty()}".lowercase()
    val category = when {
        DOCS_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.DOCS
        BROWSER_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.BROWSER
        SEARCH_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.SEARCH
        DATABASE_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.DATABASE
        SERVICE_TOOL_KEYWORDS.any { it in normalized } -> McpToolCategory.SERVICE
        else -> McpToolCategory.GENERAL
    }
    val capabilities = when (category) {
        McpToolCategory.DOCS -> setOf(McpCapability.LOOKUP_DOCS)
        McpToolCategory.SEARCH -> setOf(McpCapability.FETCH_LATEST_INFO)
        McpToolCategory.BROWSER -> setOf(McpCapability.READ_WEBPAGE)
        McpToolCategory.DATABASE -> setOf(McpCapability.QUERY_STRUCTURED_DATA)
        McpToolCategory.SERVICE -> setOf(McpCapability.ACCESS_INTERNAL_SERVICE)
        McpToolCategory.GENERAL -> emptySet()
    }

    return McpToolCandidate(
        serverId = serverId,
        serverName = serverName,
        toolName = tool.name,
        originalDescription = tool.description,
        enhancedDescription = tool.description?.takeIf { it.isNotBlank() } ?: tool.name,
        category = category,
        capabilities = capabilities,
        costLevel = ToolCostLevel.MEDIUM,
        latencyLevel = ToolLatencyLevel.MEDIUM,
        requiresNetwork = true,
        reliabilityScore = 100,
        enabled = tool.enable,
        schema = tool.inputSchema,
    )
}

internal fun McpToolCandidate.toToolDefinition(): Map<String, Any> {
    val functionDef = mutableMapOf<String, Any>(
        "name" to toolName,
        "description" to enhancedDescription,
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

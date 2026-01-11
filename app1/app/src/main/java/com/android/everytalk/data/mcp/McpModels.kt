package com.android.everytalk.data.mcp

import kotlinx.serialization.Serializable

/**
 * MCP 服务器传输类型
 */
enum class McpTransportType {
    SSE,        // Server-Sent Events (HTTP)
    WEBSOCKET   // WebSocket
}

/**
 * MCP 服务器配置
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val transportType: McpTransportType = McpTransportType.SSE,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap()
)

/**
 * MCP 工具定义
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: McpToolInputSchema?
)

/**
 * MCP 工具输入模式
 */
@Serializable
data class McpToolInputSchema(
    val type: String = "object",
    val properties: Map<String, McpPropertySchema> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * MCP 属性模式
 */
@Serializable
data class McpPropertySchema(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
)

/**
 * MCP 工具调用请求
 */
@Serializable
data class McpToolCall(
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)

/**
 * MCP 工具调用结果
 */
@Serializable
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false
)

/**
 * MCP 内容类型
 */
@Serializable
sealed class McpContent {
    @Serializable
    data class Text(val text: String) : McpContent()

    @Serializable
    data class Image(val data: String, val mimeType: String) : McpContent()

    @Serializable
    data class Resource(val uri: String, val mimeType: String?, val text: String?) : McpContent()
}

/**
 * MCP 连接状态
 */
enum class McpConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * MCP 服务器状态
 */
data class McpServerState(
    val config: McpServerConfig,
    val connectionState: McpConnectionState = McpConnectionState.DISCONNECTED,
    val tools: List<McpTool> = emptyList(),
    val errorMessage: String? = null
)

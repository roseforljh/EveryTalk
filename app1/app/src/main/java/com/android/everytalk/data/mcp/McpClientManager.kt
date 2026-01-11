package com.android.everytalk.data.mcp

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 客户端管理器
 * 负责管理多个 MCP 服务器连接和工具调用
 */
class McpClientManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@McpClientManager.json)
        }
        install(SSE)
    }

    private val _serverStates = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val serverStates: StateFlow<Map<String, McpServerState>> = _serverStates.asStateFlow()

    private val connections = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 添加并连接 MCP 服务器
     */
    suspend fun addServer(config: McpServerConfig) {
        updateServerState(config.id) {
            McpServerState(config = config, connectionState = McpConnectionState.CONNECTING)
        }

        try {
            val tools = fetchTools(config)
            updateServerState(config.id) {
                McpServerState(
                    config = config,
                    connectionState = McpConnectionState.CONNECTED,
                    tools = tools
                )
            }
        } catch (e: Exception) {
            updateServerState(config.id) {
                McpServerState(
                    config = config,
                    connectionState = McpConnectionState.ERROR,
                    errorMessage = e.message ?: "连接失败"
                )
            }
        }
    }

    /**
     * 移除服务器连接
     */
    fun removeServer(serverId: String) {
        connections[serverId]?.cancel()
        connections.remove(serverId)
        _serverStates.update { it - serverId }
    }

    /**
     * 获取所有可用工具
     */
    fun getAllTools(): List<Pair<McpServerConfig, McpTool>> {
        return _serverStates.value.values
            .filter { it.connectionState == McpConnectionState.CONNECTED && it.config.enabled }
            .flatMap { state -> state.tools.map { tool -> state.config to tool } }
    }

    /**
     * 调用 MCP 工具
     */
    suspend fun callTool(
        serverId: String,
        toolName: String,
        arguments: Map<String, JsonElement>
    ): Result<McpToolResult> {
        val serverState = _serverStates.value[serverId]
            ?: return Result.failure(Exception("服务器不存在: $serverId"))

        if (serverState.connectionState != McpConnectionState.CONNECTED) {
            return Result.failure(Exception("服务器未连接: ${serverState.config.name}"))
        }

        return try {
            val result = executeToolCall(serverState.config, toolName, arguments)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从服务器获取工具列表
     */
    private suspend fun fetchTools(config: McpServerConfig): List<McpTool> {
        val requestId = UUID.randomUUID().toString()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "tools/list")
        }

        val response = httpClient.post(config.url) {
            contentType(ContentType.Application.Json)
            config.headers.forEach { (key, value) ->
                header(key, value)
            }
            setBody(request.toString())
        }

        val responseBody = response.bodyAsText()
        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

        val result = jsonResponse["result"]?.jsonObject
            ?: throw Exception("无效的响应格式")

        val toolsArray = result["tools"]?.jsonArray
            ?: return emptyList()

        return toolsArray.map { toolElement ->
            val toolObj = toolElement.jsonObject
            McpTool(
                name = toolObj["name"]?.jsonPrimitive?.content ?: "",
                description = toolObj["description"]?.jsonPrimitive?.contentOrNull,
                inputSchema = toolObj["inputSchema"]?.let { parseInputSchema(it) }
            )
        }
    }

    /**
     * 执行工具调用
     */
    private suspend fun executeToolCall(
        config: McpServerConfig,
        toolName: String,
        arguments: Map<String, JsonElement>
    ): McpToolResult {
        val requestId = UUID.randomUUID().toString()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", JsonObject(arguments))
            })
        }

        val response = httpClient.post(config.url) {
            contentType(ContentType.Application.Json)
            config.headers.forEach { (key, value) ->
                header(key, value)
            }
            setBody(request.toString())
        }

        val responseBody = response.bodyAsText()
        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

        // 检查错误
        jsonResponse["error"]?.let { error ->
            val errorObj = error.jsonObject
            val message = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            return McpToolResult(
                content = listOf(McpContent.Text(message)),
                isError = true
            )
        }

        val result = jsonResponse["result"]?.jsonObject
            ?: throw Exception("无效的响应格式")

        val contentArray = result["content"]?.jsonArray ?: return McpToolResult(emptyList())
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false

        val contents = contentArray.mapNotNull { contentElement ->
            val contentObj = contentElement.jsonObject
            when (contentObj["type"]?.jsonPrimitive?.content) {
                "text" -> McpContent.Text(
                    text = contentObj["text"]?.jsonPrimitive?.content ?: ""
                )
                "image" -> McpContent.Image(
                    data = contentObj["data"]?.jsonPrimitive?.content ?: "",
                    mimeType = contentObj["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                )
                "resource" -> McpContent.Resource(
                    uri = contentObj["uri"]?.jsonPrimitive?.content ?: "",
                    mimeType = contentObj["mimeType"]?.jsonPrimitive?.contentOrNull,
                    text = contentObj["text"]?.jsonPrimitive?.contentOrNull
                )
                else -> null
            }
        }

        return McpToolResult(content = contents, isError = isError)
    }

    /**
     * 解析工具输入模式
     */
    private fun parseInputSchema(element: JsonElement): McpToolInputSchema {
        val obj = element.jsonObject
        val properties = obj["properties"]?.jsonObject?.mapValues { (_, propElement) ->
            val propObj = propElement.jsonObject
            McpPropertySchema(
                type = propObj["type"]?.jsonPrimitive?.content ?: "string",
                description = propObj["description"]?.jsonPrimitive?.contentOrNull,
                enum = propObj["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            )
        } ?: emptyMap()

        val required = obj["required"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()

        return McpToolInputSchema(
            type = obj["type"]?.jsonPrimitive?.content ?: "object",
            properties = properties,
            required = required
        )
    }

    private fun updateServerState(serverId: String, update: () -> McpServerState) {
        _serverStates.update { current ->
            current + (serverId to update())
        }
    }

    /**
     * 关闭管理器，释放资源
     */
    fun close() {
        scope.cancel()
        connections.values.forEach { it.cancel() }
        connections.clear()
        httpClient.close()
    }
}

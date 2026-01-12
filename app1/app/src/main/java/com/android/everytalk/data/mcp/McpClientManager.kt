package com.android.everytalk.data.mcp

import android.util.Log
import com.android.everytalk.data.mcp.transport.SseClientTransport
import com.android.everytalk.data.mcp.transport.StreamableHttpClientTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val TAG = "McpClientManager"

class McpClientManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val clients = ConcurrentHashMap<String, Client>()
    private val configs = ConcurrentHashMap<String, McpServerConfig>()

    private val _serverStates = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val serverStates: StateFlow<Map<String, McpServerState>> = _serverStates.asStateFlow()

    private val _syncingStatus = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val syncingStatus: StateFlow<Map<String, McpStatus>> = _syncingStatus.asStateFlow()

    fun getClient(config: McpServerConfig): Client? {
        return clients[config.id]
    }

    fun getAllAvailableTools(): List<McpTool> {
        return _serverStates.value.values
            .filter { it.status is McpStatus.Connected && it.config.enabled }
            .flatMap { state -> state.tools.filter { it.enable } }
    }

    fun getToolWithServer(toolName: String): Pair<McpServerConfig, McpTool>? {
        for ((_, state) in _serverStates.value) {
            if (state.status !is McpStatus.Connected || !state.config.enabled) continue
            val tool = state.tools.find { it.name == toolName && it.enable }
            if (tool != null) {
                return state.config to tool
            }
        }
        return null
    }

    suspend fun callTool(toolName: String, args: JsonObject): JsonElement {
        val (config, _) = getToolWithServer(toolName)
            ?: return JsonPrimitive("Failed to execute tool: no such tool '$toolName'")

        val client = clients[config.id]
            ?: return JsonPrimitive("Failed to execute tool: no client for server '${config.name}'")

        Log.i(TAG, "callTool: $toolName / $args")
        Log.d(TAG, "callTool: transport=${client.transport?.javaClass?.simpleName}, connected=${client.transport != null}")

        return try {
            if (client.transport == null) {
                Log.d(TAG, "callTool: reconnecting...")
                client.connect(getTransport(config))
            }

            Log.d(TAG, "callTool: sending request...")
            val result = client.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(
                        name = toolName,
                        arguments = args,
                    ),
                ),
                options = RequestOptions(timeout = 60.seconds),
            )
            Log.d(TAG, "callTool: got result with ${result.content.size} content items")
            McpJson.encodeToJsonElement(result.content)
        } catch (e: Exception) {
            Log.e(TAG, "callTool error: ${e.javaClass.simpleName}: ${e.message}", e)
            JsonPrimitive("Error executing tool '$toolName': ${e.message}")
        }
    }

    private fun getTransport(config: McpServerConfig): AbstractTransport = when (config) {
        is McpServerConfig.SseTransportServer -> {
            SseClientTransport(
                urlString = config.url,
                client = httpClient,
                requestBuilder = {
                    headers.appendAll(StringValues.build {
                        config.commonOptions.headers.forEach {
                            append(it.first, it.second)
                        }
                    })
                },
            )
        }

        is McpServerConfig.StreamableHTTPServer -> {
            StreamableHttpClientTransport(
                url = config.url,
                client = httpClient,
                requestBuilder = {
                    headers.appendAll(StringValues.build {
                        config.commonOptions.headers.forEach {
                            append(it.first, it.second)
                        }
                    })
                }
            )
        }
    }

    suspend fun addServer(config: McpServerConfig) = withContext(Dispatchers.IO) {
        removeServer(config.id)

        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.name,
                version = "1.0",
            )
        )

        clients[config.id] = client
        configs[config.id] = config

        runCatching {
            setStatus(config.id, McpStatus.Connecting)
            updateServerState(config.id) {
                McpServerState(config = config, status = McpStatus.Connecting)
            }

            client.connect(transport)
            syncTools(config)

            setStatus(config.id, McpStatus.Connected)
            Log.i(TAG, "addServer: connected ${config.name}")
        }.onFailure { e ->
            e.printStackTrace()
            setStatus(config.id, McpStatus.Error(e.message ?: e.javaClass.name))
            updateServerState(config.id) {
                McpServerState(
                    config = config,
                    status = McpStatus.Error(e.message ?: "Connection failed"),
                    errorMessage = e.message
                )
            }
        }
    }

    private suspend fun syncTools(config: McpServerConfig) {
        val client = clients[config.id] ?: return

        setStatus(config.id, McpStatus.Connecting)

        if (client.transport == null) {
            client.connect(getTransport(config))
        }

        val serverTools = client.listTools()?.tools ?: emptyList()
        Log.i(TAG, "syncTools: ${serverTools.size} tools from ${config.name}")

        val tools = serverTools.map { serverTool ->
            McpTool(
                name = serverTool.name,
                description = serverTool.description,
                enable = true,
                inputSchema = serverTool.inputSchema.toMcpInputSchema()
            )
        }

        val updatedConfig = config.clone(
            commonOptions = config.commonOptions.copy(tools = tools)
        )
        configs[config.id] = updatedConfig

        updateServerState(config.id) {
            McpServerState(
                config = updatedConfig,
                status = McpStatus.Connected,
                tools = tools
            )
        }

        setStatus(config.id, McpStatus.Connected)
    }

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        configs.values.toList().forEach { config ->
            runCatching {
                syncTools(config)
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    suspend fun removeServer(serverId: String) = withContext(Dispatchers.IO) {
        val client = clients.remove(serverId)
        configs.remove(serverId)

        client?.let {
            runCatching { it.close() }.onFailure { e -> e.printStackTrace() }
        }

        _serverStates.value = _serverStates.value - serverId
        _syncingStatus.value = _syncingStatus.value - serverId

        Log.i(TAG, "removeServer: $serverId")
    }

    suspend fun disconnectServer(serverId: String) = withContext(Dispatchers.IO) {
        val client = clients.remove(serverId)
        val config = configs[serverId]

        client?.let {
            runCatching { it.close() }.onFailure { e -> e.printStackTrace() }
        }

        if (config != null) {
            val disabledConfig = config.clone(
                commonOptions = config.commonOptions.copy(enable = false)
            )
            configs[serverId] = disabledConfig
            updateServerState(serverId) {
                McpServerState(
                    config = disabledConfig,
                    status = McpStatus.Idle,
                    tools = _serverStates.value[serverId]?.tools ?: emptyList()
                )
            }
            setStatus(serverId, McpStatus.Idle)
        }

        Log.i(TAG, "disconnectServer: $serverId")
    }

    private suspend fun setStatus(serverId: String, status: McpStatus) {
        _syncingStatus.value = _syncingStatus.value + (serverId to status)
    }

    private fun updateServerState(serverId: String, update: () -> McpServerState) {
        _serverStates.value = _serverStates.value + (serverId to update())
    }

    fun getStatus(serverId: String): McpStatus {
        return _syncingStatus.value[serverId] ?: McpStatus.Idle
    }

    fun close() {
        scope.launch {
            clients.values.forEach { client ->
                runCatching { client.close() }.onFailure { it.printStackTrace() }
            }
            clients.clear()
            configs.clear()
            httpClient.close()
        }
    }
}

internal val McpJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}

private fun ToolSchema.toMcpInputSchema(): McpInputSchema {
    return McpInputSchema.Obj(
        properties = this.properties ?: JsonObject(emptyMap()),
        required = this.required
    )
}

package com.android.everytalk.data.mcp

import android.util.Log
import com.android.everytalk.data.mcp.transport.SseClientTransport
import com.android.everytalk.data.mcp.transport.StreamableHttpClientTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private const val TAG = "McpClientManager"
private const val MCP_TOOL_NAME_MAX_LENGTH = 64

internal fun buildMcpToolAlias(serverId: String, toolName: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest("$serverId\u0000$toolName".toByteArray(StandardCharsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    val safeToolName = toolName.map { char ->
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-') char else '_'
    }.joinToString("").trim('_').ifBlank { "tool" }
    val prefix = "mcp_${hash}_"
    return prefix + safeToolName.take(MCP_TOOL_NAME_MAX_LENGTH - prefix.length)
}

internal fun resolveMcpToolWithServer(
    states: Collection<McpServerState>,
    exposedToolName: String,
): Pair<McpServerConfig, McpTool>? {
    val routes = states
        .filter { it.status is McpStatus.Connected && it.config.enabled }
        .flatMap { state -> state.tools.filter { it.enable }.map { tool -> state.config to tool } }
    return routes.firstOrNull { (config, tool) ->
        buildMcpToolAlias(config.id, tool.name) == exposedToolName
    }
}

internal fun hasSameMcpConnectionSettings(
    first: McpServerConfig,
    second: McpServerConfig,
): Boolean = first::class == second::class &&
    first.id == second.id &&
    first.url == second.url &&
    first.commonOptions.copy(tools = emptyList()) == second.commonOptions.copy(tools = emptyList())

private enum class McpFailureType {
    NETWORK_ERROR,
    TIMEOUT,
    AUTH_ERROR,
    INVALID_ARGUMENT,
    SERVER_ERROR,
    UNKNOWN,
}

private fun classifyFailureType(error: Throwable): McpFailureType {
    val message = error.message.orEmpty().lowercase()
    return when {
        "401" in message || "403" in message || "unauthorized" in message || "forbidden" in message -> McpFailureType.AUTH_ERROR
        "timeout" in message || "timed out" in message -> McpFailureType.TIMEOUT
        "invalid" in message || "argument" in message -> McpFailureType.INVALID_ARGUMENT
        "network" in message || "unreachable" in message || "connection" in message -> McpFailureType.NETWORK_ERROR
        "500" in message || "server" in message -> McpFailureType.SERVER_ERROR
        else -> McpFailureType.UNKNOWN
    }
}

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
    }

    private val clients = ConcurrentHashMap<String, Client>()
    private val configs = ConcurrentHashMap<String, McpServerConfig>()
    // ponytail: 服务器数量很小，按 ID 保留轻量锁比引用计数锁池更稳；关闭时统一释放。
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    private val closed = AtomicBoolean(false)

    private val _serverStates = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val serverStates: StateFlow<Map<String, McpServerState>> = _serverStates.asStateFlow()

    suspend fun callTool(toolName: String, args: JsonObject): JsonElement {
        val initialRoute = resolveMcpToolWithServer(_serverStates.value.values, toolName)
            ?: return JsonPrimitive("Failed to execute tool: no such tool '$toolName'")

        return try {
            withServerLock(initialRoute.first.id) {
                val (config, tool) = resolveMcpToolWithServer(_serverStates.value.values, toolName)
                    ?: return@withServerLock JsonPrimitive("Failed to execute tool: no such tool '$toolName'")
                val client = clients[config.id]
                    ?: return@withServerLock JsonPrimitive("Failed to execute tool: no client for server '${config.name}'")

                Log.i(TAG, "callTool: $toolName -> ${config.name}/${tool.name} / argumentKeys=${args.keys}")
                Log.d(TAG, "callTool: transport=${client.transport?.javaClass?.simpleName}, connected=${client.transport != null}")

                try {
                    if (client.transport == null) {
                        Log.d(TAG, "callTool: reconnecting...")
                        client.connect(getTransport(config))
                    }

                    Log.d(TAG, "callTool: sending request...")
                    val result = client.callTool(
                        request = CallToolRequest(
                            params = CallToolRequestParams(
                                name = tool.name,
                                arguments = args,
                            ),
                        ),
                        options = RequestOptions(timeout = 60.seconds),
                    )
                    Log.d(TAG, "callTool: got result with ${result.content.size} content items")
                    McpJson.encodeToJsonElement(result.content)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "callTool error: ${e.javaClass.simpleName}: ${e.message}", e)
                    JsonPrimitive("Error executing tool '$toolName': ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JsonPrimitive("Failed to execute tool '$toolName': ${e.message}")
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
        withServerLock(config.id) {
            addServerLocked(config)
        }
    }

    private suspend fun addServerLocked(config: McpServerConfig) {
        val currentState = _serverStates.value[config.id]
        if (
            currentState?.status is McpStatus.Connected &&
            clients[config.id]?.transport != null &&
            hasSameMcpConnectionSettings(currentState.config, config)
        ) {
            return
        }

        removeServerLocked(config.id)

        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.name,
                version = "1.0",
            )
        )

        clients[config.id] = client
        configs[config.id] = config

        try {
            updateServerState(config.id) {
                McpServerState(config = config, status = McpStatus.Connecting)
            }

            client.connect(transport)
            check(!closed.get()) { "McpClientManager is closed" }
            syncToolsLocked(config)

            Log.i(TAG, "addServer: connected ${config.name}")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                try {
                    client.close()
                } catch (closeError: Exception) {
                    Log.w(TAG, "addServer cancellation cleanup failed: ${config.name}", closeError)
                }
            }
            clients.remove(config.id, client)
            configs.remove(config.id, config)
            _serverStates.update { it - config.id }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "addServer failed: ${config.name}", e)
            withContext(NonCancellable) {
                runCatching { client.close() }
                    .onFailure { closeError -> Log.w(TAG, "addServer failure cleanup failed: ${config.name}", closeError) }
            }
            clients.remove(config.id, client)
            if (closed.get()) {
                configs.remove(config.id, config)
                _serverStates.update { it - config.id }
                return
            }
            val failureType = classifyFailureType(e)
            updateServerState(config.id) {
                McpServerState(
                    config = config,
                    status = McpStatus.Error(e.message ?: "Connection failed", failureType.name),
                    errorMessage = e.message
                )
            }
        }
    }

    private suspend fun syncToolsLocked(config: McpServerConfig) {
        val client = clients[config.id] ?: return

        if (client.transport == null) {
            client.connect(getTransport(config))
        }

        val serverTools = client.listTools().tools
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

    }

    suspend fun removeServer(serverId: String) = withContext(Dispatchers.IO) {
        withServerLock(serverId) {
            removeServerLocked(serverId)
        }
    }

    private suspend fun removeServerLocked(serverId: String) {
        val client = clients.remove(serverId)
        configs.remove(serverId)
        var closeCancellation: CancellationException? = null

        try {
            client?.close()
        } catch (e: CancellationException) {
            closeCancellation = e
        } catch (e: Exception) {
            Log.w(TAG, "removeServer close failed: $serverId", e)
        }

        _serverStates.update { it - serverId }

        Log.i(TAG, "removeServer: $serverId")
        closeCancellation?.let { throw it }
    }

    suspend fun disconnectServer(serverId: String) = withContext(Dispatchers.IO) {
        withServerLock(serverId) {
            disconnectServerLocked(serverId)
        }
    }

    private suspend fun disconnectServerLocked(serverId: String) {
        val client = clients.remove(serverId)
        val config = configs[serverId]
        var closeCancellation: CancellationException? = null

        try {
            client?.close()
        } catch (e: CancellationException) {
            closeCancellation = e
        } catch (e: Exception) {
            Log.w(TAG, "disconnectServer close failed: $serverId", e)
        }

        if (config != null) {
            val disabledConfig = config.clone(
                commonOptions = config.commonOptions.copy(enable = false)
            )
            configs[serverId] = disabledConfig
            updateServerState(serverId) { currentState ->
                McpServerState(
                    config = disabledConfig,
                    status = McpStatus.Idle,
                    tools = currentState?.tools.orEmpty()
                )
            }
        }

        Log.i(TAG, "disconnectServer: $serverId")
        closeCancellation?.let { throw it }
    }

    private fun updateServerState(
        serverId: String,
        update: (McpServerState?) -> McpServerState,
    ) {
        _serverStates.update { states ->
            if (closed.get()) states else states + (serverId to update(states[serverId]))
        }
    }

    private suspend fun <T> withServerLock(serverId: String, block: suspend () -> T): T {
        return operationLocks.computeIfAbsent(serverId) { Mutex() }.withLock {
            check(!closed.get()) { "McpClientManager is closed" }
            block()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            withContext(NonCancellable) {
                try {
                    clients.values.toList().forEach { client ->
                        runCatching { client.close() }
                            .onFailure { Log.w(TAG, "MCP client close failed", it) }
                    }
                    clients.clear()
                    configs.clear()
                    _serverStates.value = emptyMap()
                    operationLocks.clear()
                    httpClient.close()
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
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

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private const val TAG = "McpClientManager"
private const val MCP_TOOL_NAME_MAX_LENGTH = 64
private const val MCP_CONNECT_TIMEOUT_MS = 15_000L
private const val MCP_CLOSE_TIMEOUT_MS = 2_000L

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
        .filter { (it.status is McpStatus.Connected || it.status is McpStatus.Error) && it.config.enabled }
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
    if (generateSequence(error) { it.cause }.any { it is McpAuthorizationRequiredException }) return McpFailureType.AUTH_ERROR
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val accessToken: suspend (McpServerConfig) -> String? = { null },
    private val connectTimeoutMillis: Long = MCP_CONNECT_TIMEOUT_MS,
    private val toolTimeoutMillis: Long = 60_000L,
    private val connectClient: suspend (Client, AbstractTransport) -> Unit = { client, transport ->
        client.connect(transport)
    },
) {
    private data class ActiveConnection(
        val generation: Long,
        val job: Job,
    )

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .followRedirects(false)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        followRedirects = false
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

    private data class ToolTarget(val config: McpServerConfig, val tool: McpTool, val client: Client)
    private val stoppingServers = ConcurrentHashMap.newKeySet<String>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val configs = ConcurrentHashMap<String, McpServerConfig>()
    // ponytail: 服务器数量很小，按 ID 保留轻量锁比引用计数锁池更稳；关闭时统一释放。
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    private val operationGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val activeConnections = ConcurrentHashMap<String, ActiveConnection>()
    private val closed = AtomicBoolean(false)

    private val _serverStates = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val serverStates: StateFlow<Map<String, McpServerState>> = _serverStates.asStateFlow()

    suspend fun callTool(toolName: String, args: JsonObject): JsonElement = callToolResult(toolName, args).content

    /** 保留协议错误和结构化数据，避免正文中的同名字段影响 App 控制状态。 */
    suspend fun callToolResult(toolName: String, args: JsonObject): com.android.everytalk.data.network.AppToolExecutionResult {
        val initialRoute = resolveMcpToolWithServer(_serverStates.value.values, toolName)
            ?: return mcpToolFailure("Failed to execute tool: no such tool '$toolName'")

        return try {
            // 连接身份在锁内捕获，网络等待在锁外；同服务器工具不再互相阻塞。
            val target = withServerLock(initialRoute.first.id) {
                check(initialRoute.first.id !in stoppingServers) { "MCP server is stopping" }
                val config = resolveMcpToolWithServer(_serverStates.value.values, toolName)?.first
                    ?: error("no such tool '$toolName'")
                if (_serverStates.value[config.id]?.status !is McpStatus.Connected || clients[config.id] == null) {
                    val active = ActiveConnection(operationGenerations[config.id]?.get() ?: 0L, currentCoroutineContext().job)
                    activeConnections[config.id] = active
                    try { addServerLocked(config) } finally { activeConnections.remove(config.id, active) }
                }
                val (latestConfig, latestTool) = resolveMcpToolWithServer(_serverStates.value.values, toolName)
                    ?: error("tool '$toolName' is no longer available")
                check(_serverStates.value[config.id]?.status is McpStatus.Connected && config.id !in stoppingServers) { "MCP server is not connected" }
                ToolTarget(latestConfig, latestTool, checkNotNull(clients[config.id]))
            }
            check(!closed.get() && target.config.id !in stoppingServers && clients[target.config.id] === target.client &&
                resolveMcpToolWithServer(_serverStates.value.values, toolName) != null) { "MCP tool is no longer available" }
            try {
                // SDK 0.14 的超时只包 send，这里覆盖发送和完整响应等待。
                val result = withTimeoutOrNull(toolTimeoutMillis) {
                    target.client.callTool(
                        request = CallToolRequest(params = CallToolRequestParams(name = target.tool.name, arguments = args)),
                        options = RequestOptions(timeout = 60.seconds),
                    )
                } ?: error("MCP tool timeout after ${toolTimeoutMillis}ms")
                val content = McpJson.encodeToJsonElement(result.content)
                val modelContent = result.structuredContent?.let { structured ->
                    buildJsonObject { put("content", content); put("structuredContent", structured) }
                } ?: content
                com.android.everytalk.data.network.AppToolExecutionResult(
                    content = if (result.isError == true) buildJsonObject {
                        put("ok", false); put("error", content); put("data", modelContent)
                    } else modelContent,
                    scriptContent = result.structuredContent ?: content,
                    isError = result.isError == true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withServerLock(target.config.id) {
                    // 旧请求迟到失败不能移除新连接，或把已关闭服务器改回 Error。
                    if (target.config.id !in stoppingServers && clients[target.config.id] === target.client &&
                        clients.remove(target.config.id, target.client)) {
                        updateServerState(target.config.id) { current ->
                            McpServerState(config = current?.config ?: target.config,
                                status = McpStatus.Error(error.message ?: "Tool call failed", classifyFailureType(error).name),
                                tools = current?.tools.orEmpty(), errorMessage = error.message)
                        }
                        scope.launch { closeClient(target.client) }
                    }
                }
                // 原调用绝不自动重放，下一次新调用才允许自愈。
                mcpToolFailure("Error executing tool '$toolName': ${error.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mcpToolFailure("Failed to execute tool '$toolName': ${e.message}")
        }
    }

    /** MCP 协议失败必须保留 isError 语义，不能仅靠正文里的英文单词让模型猜。 */
    private fun mcpToolFailure(message: String) = com.android.everytalk.data.network.AppToolExecutionResult(
        content = buildJsonObject { put("ok", false); put("error", message) },
        isError = true,
    )

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
                accessToken = { accessToken(config) },
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

    suspend fun addServer(config: McpServerConfig) {
        val generation = nextServerOperation(config.id)
        withContext(Dispatchers.IO) {
            withServerLock(config.id) {
                val activeConnection = ActiveConnection(generation, currentCoroutineContext().job)
                activeConnections[config.id] = activeConnection
                try {
                    // 数据库监听和显式开关可能同时触发连接。只允许最后一次操作真正执行。
                    if (isCurrentServerOperation(config.id, generation)) {
                        addServerLocked(config)
                    }
                } finally {
                    activeConnections.remove(config.id, activeConnection)
                }
            }
        }
    }

    private suspend fun addServerLocked(config: McpServerConfig) {
        // 先登记 activeConnections 再复核停止标志：关闭要么取消已登记握手，要么在这里阻止它启动。
        check(config.id !in stoppingServers) { "MCP server is stopping" }
        val currentState = _serverStates.value[config.id]
        if (
            currentState?.status is McpStatus.Connected &&
            clients.containsKey(config.id) &&
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
                McpServerState(config = config, status = McpStatus.Connecting, tools = currentState?.tools.orEmpty())
            }

            // MCP 服务端可能卡在 DNS、TLS 或初始化请求。连接必须有独立上限，
            // 否则用户批准后 Agent 会一直保持“正在发送”，无法回到正常对话状态。
            val connected = withTimeoutOrNull(connectTimeoutMillis) {
                connectClient(client, transport)
                check(!closed.get()) { "McpClientManager is closed" }
                syncToolsLocked(config)
                true
            }
            // 仅把本次连接的超时转成服务器错误；用户取消、父任务超时仍向上传播。
            check(connected == true) { "MCP 连接超时 (timeout)" }

            Log.i(TAG, "addServer: connected ${config.name}")
        } catch (e: CancellationException) {
            closeClient(client)
            clients.remove(config.id, client)
            configs.remove(config.id, config)
            _serverStates.update { it - config.id }
            throw e
        } catch (e: Exception) {
            handleConnectionFailure(config, client, e)
        }
    }

    /** 连接失败只影响当前服务器，不能让其他 MCP 或原 Agent 恢复流程一起卡住。 */
    private suspend fun handleConnectionFailure(
        config: McpServerConfig,
        client: Client,
        error: Throwable,
    ) {
        Log.e(TAG, "addServer failed: ${config.name}", error)
        closeClient(client)
        clients.remove(config.id, client)
        if (closed.get()) {
            configs.remove(config.id, config)
            _serverStates.update { it - config.id }
            return
        }
        val failureType = classifyFailureType(error)
        val message = generateSequence(error) { it.cause }
            .filterIsInstance<McpAuthorizationRequiredException>().firstOrNull()?.message ?: error.message
        updateServerState(config.id) { currentState ->
            McpServerState(
                config = config,
                status = McpStatus.Error(message ?: "Connection failed", failureType.name),
                tools = currentState?.tools.orEmpty(),
                errorMessage = message
            )
        }
    }

    /** 关闭可能发送远端 DELETE；即使断网，也必须限时释放服务器锁。 */
    private suspend fun closeClient(client: Client) {
        withContext(NonCancellable) {
            withTimeoutOrNull(MCP_CLOSE_TIMEOUT_MS) {
                runCatching { client.close() }
                    .onFailure { closeError -> Log.w(TAG, "MCP client cleanup failed", closeError) }
            }
        }
    }

    private suspend fun syncToolsLocked(config: McpServerConfig) {
        val client = clients[config.id] ?: return

        // SDK listTools 只返回一页；收齐分页再发布，避免 GitHub 工具被静默截断。
        val serverTools = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Tool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = client.listTools(io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest(
                params = io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams(cursor = cursor),
            ))
            serverTools.addAll(page.tools)
            cursor = page.nextCursor
            check(cursor == null || seenCursors.add(cursor)) { "MCP 工具分页游标重复" }
            check(seenCursors.size <= 1_000) { "MCP 工具分页超出限制" }
        } while (cursor != null)
        Log.i(TAG, "syncTools: ${serverTools.size} tools from ${config.name}")

        currentCoroutineContext().ensureActive()
        check(config.id !in stoppingServers) { "MCP server is stopping" }
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
                tools = tools,
                instructions = client.serverInstructions?.trim()?.takeIf(String::isNotBlank),
            )
        }

    }

    suspend fun removeServer(serverId: String) {
        val generation = nextServerOperation(serverId, stopping = true)
        cancelActiveConnection(serverId)
        withContext(Dispatchers.IO) {
            withServerLock(serverId) {
                if (isCurrentServerOperation(serverId, generation)) {
                    removeServerLocked(serverId)
                }
            }
        }
    }

    private suspend fun removeServerLocked(serverId: String) {
        val client = clients.remove(serverId)
        configs.remove(serverId)
        var closeCancellation: CancellationException? = null

        try {
            client?.let { closeClient(it) }
        } catch (e: CancellationException) {
            closeCancellation = e
        } catch (e: Exception) {
            Log.w(TAG, "removeServer close failed: $serverId", e)
        }

        _serverStates.update { it - serverId }

        Log.i(TAG, "removeServer: $serverId")
        closeCancellation?.let { throw it }
    }

    suspend fun disconnectServer(serverId: String) {
        val generation = nextServerOperation(serverId, stopping = true)
        val fallbackConfig = configs[serverId] ?: _serverStates.value[serverId]?.config
        cancelActiveConnection(serverId)
        withContext(Dispatchers.IO) {
            withServerLock(serverId) {
                if (isCurrentServerOperation(serverId, generation)) {
                    disconnectServerLocked(serverId, fallbackConfig)
                }
            }
        }
    }

    private suspend fun disconnectServerLocked(
        serverId: String,
        fallbackConfig: McpServerConfig? = null,
    ) {
        val client = clients.remove(serverId)
        val config = configs[serverId] ?: fallbackConfig
        var closeCancellation: CancellationException? = null

        try {
            client?.let { closeClient(it) }
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
            updateServerState(serverId, allowStopping = true) { currentState ->
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
        allowStopping: Boolean = false,
        update: (McpServerState?) -> McpServerState,
    ) {
        // 与停止标志共用短临界区，禁止关闭生效后握手又发布 Connected/Error。
        synchronized(operationGenerations) {
            _serverStates.update { states ->
                if (closed.get() || (!allowStopping && serverId in stoppingServers)) states
                else states + (serverId to update(states[serverId]))
            }
        }
    }

    /** 操作代次和停止标志一起发布，防止较旧 addServer 清掉刚生效的关闭请求。 */
    private fun nextServerOperation(serverId: String, stopping: Boolean = false): Long = synchronized(operationGenerations) {
        if (stopping) stoppingServers.add(serverId) else stoppingServers.remove(serverId)
        operationGenerations.computeIfAbsent(serverId) { AtomicLong() }.incrementAndGet()
    }

    private fun isCurrentServerOperation(serverId: String, generation: Long): Boolean {
        return operationGenerations[serverId]?.get() == generation
    }

    /**
     * 关闭开关时先取消仍在握手或同步工具的连接协程。
     * 否则关闭操作会在同一把服务器锁后等待完整连接超时，界面看起来像点击无效。
     */
    private suspend fun cancelActiveConnection(serverId: String) {
        activeConnections[serverId]?.job?.cancelAndJoin()
    }

    private suspend fun <T> withServerLock(serverId: String, block: suspend () -> T): T {
        return operationLocks.computeIfAbsent(serverId) { Mutex() }.withLock {
            check(!closed.get()) { "McpClientManager is closed" }
            block()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnections.values.forEach { it.job.cancel() }
        scope.launch {
            withContext(NonCancellable) {
                try {
                    clients.values.toList().forEach { client ->
                        runCatching { closeClient(client) }
                            .onFailure { Log.w(TAG, "MCP client close failed", it) }
                    }
                    clients.clear()
                    configs.clear()
                    _serverStates.value = emptyMap()
                    activeConnections.clear()
                    operationGenerations.clear()
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

internal fun ToolSchema.toMcpInputSchema(): McpInputSchema {
    return McpInputSchema.Obj(
        properties = this.properties ?: JsonObject(emptyMap()),
        required = this.required,
        defs = this.defs,
    )
}

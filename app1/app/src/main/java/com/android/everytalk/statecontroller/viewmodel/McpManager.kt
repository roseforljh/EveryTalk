package com.android.everytalk.statecontroller.viewmodel

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.android.everytalk.data.sync.SyncSettingsProjector
import com.android.everytalk.data.sync.syncStableSettingKey
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.McpServerConfigEntity
import com.android.everytalk.data.mcp.*
import com.android.everytalk.statecontroller.mcp.dispatch.McpToolCandidate
import com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val TAG = "McpManager"

class McpManager(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val mcpDao = database.mcpConfigDao()
    private val oauthHttp = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        engine { config { followRedirects(false); followSslRedirects(false) } }
    }
    private val oauth = McpOAuthManager(EncryptedMcpOAuthSecrets(context), oauthHttp)
    private val mail = McpMailManager(EncryptedMcpOAuthSecrets(context))
    private val clientManager = McpClientManager(accessToken = { config ->
        mail.accessTokenFor(config) ?: oauth.accessTokenFor(config)
    })
    private val _oauthMessage = MutableStateFlow<String?>(null)
    val oauthMessage = _oauthMessage.asStateFlow()
    private val _oauthBusy = MutableStateFlow(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val serverStates: StateFlow<Map<String, McpServerState>> = clientManager.serverStates

    init {
        scope.launch { removeDuplicateServers() }
        scope.launch { serverStates.collect { McpToolLabels.update(it.values) } }
        scope.launch {
            McpOAuthCallbackBus.flow.filterNotNull().collect { uri ->
                McpOAuthCallbackBus.consume(uri)
                _oauthBusy.value = true
                try {
                    val provider = oauth.consume(uri.toString())
                    if (provider == McpOAuthProvider.PARALLEL) {
                        // 登录换到的是用户自己的 Key：存进 Parallel 连接后删掉授权草稿，额度计入用户账号。
                        val key = oauth.accessTokenFor(provider.defaultConfig()).orEmpty()
                        oauth.logout(provider)
                        addServer(McpKeyProvider.PARALLEL.config(key))
                    } else {
                        // 重新登录必须替换旧连接；配置中只保存地址，不保存 Authorization。
                        clientManager.removeServer(provider.serverId)
                        addServer(provider.defaultConfig())
                    }
                    _oauthMessage.value = "${provider.displayName} 授权成功"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { _oauthMessage.value = error.message ?: "MCP 登录失败" }
                finally { _oauthBusy.value = false }
            }
        }
        scope.launch {
            var observed = emptyMap<String, McpServerConfig>()
            var observedCredentials = emptyMap<String, String?>()
            // 同时监听停用和删除，才能把另一台设备的修改落实到当前连接。
            // 按配置变化连接，不随普通数据库通知反复重试认证失败的服务。
            combine(mcpDao.getAllConfigs(), com.android.everytalk.data.sync.SyncLocalChanges.revision) { entities, _ -> entities }.collect { entities ->
                val current = entities.associate { it.id to it.toModel() }
                val credentials = current.keys.associateWith { id ->
                    val row = database.settingsDao().getUserSetting("mcp_servers", syncStableSettingKey("mcp_servers", id))
                    row?.let { database.settingsDao().getValue("sync_private_applied:${it.uuid}") }
                }
                coroutineScope {
                    (observed.keys - current.keys).forEach { id ->
                        launch { clientManager.removeServer(id) }
                    }
                    current.values.forEach { config ->
                        val previous = observed[config.id]
                        val authorizationChanged = previous != null && credentials[config.id] != observedCredentials[config.id]
                        if (previous != null && !authorizationChanged && hasSameMcpConnectionSettings(previous, config)) return@forEach
                        launch {
                            try {
                                // OAuth/邮箱授权在配置之后恢复；凭据变化时丢掉旧连接，不能沿用认证失败或旧用户的连接。
                                if (authorizationChanged) clientManager.removeServer(config.id)
                                if (config.enabled) clientManager.addServer(config)
                                else clientManager.disconnectServer(config.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "MCP 配置生效失败: ${config.id}", e)
                            }
                        }
                    }
                }
                observed = current
                observedCredentials = credentials
            }
        }
    }

    /** 浏览器打开后即可释放按钮；返回时由回调通道完成登录，不依赖设置页还在前台。 */
    fun login(provider: McpOAuthProvider) {
        if (_oauthBusy.value) return
        _oauthBusy.value = true
        scope.launch {
            try {
                val url = oauth.start(provider)
                withContext(Dispatchers.Main) {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _oauthMessage.value = error.message ?: "MCP 登录失败" }
            finally { _oauthBusy.value = false }
        }
    }

    fun dismissOAuthMessage() { _oauthMessage.value = null }

    /** 保存 QQ/网易授权码后立即创建对应 MCP 连接；授权码本身只进入 Keystore。 */
    fun configureMail(provider: McpMailProvider, input: McpMailInput) {
        if (_oauthBusy.value) return
        _oauthBusy.value = true
        scope.launch {
            try {
                val config = mail.save(provider, input)
                addServer(config)
                _oauthMessage.value = "${provider.displayName} 已配置"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _oauthMessage.value = error.message ?: "邮箱配置失败" }
            finally { _oauthBusy.value = false }
        }
    }

    fun getAllConfigs(): Flow<List<McpServerConfig>> {
        return mcpDao.getAllConfigs().map { entities ->
            entities.map { it.toModel() }
        }
    }

    /** 同一地址只保留一条：重复添加时更新原记录，不再生成第二张卡片。 */
    suspend fun addServer(newConfig: McpServerConfig) {
        val config = if (isAuthorizedServer(newConfig.id)) newConfig
        else mcpDao.getAllSnapshot().firstOrNull { it.id != newConfig.id && it.url == newConfig.url }
            ?.let { newConfig.clone(id = it.id) } ?: newConfig
        database.withTransaction {
            mcpDao.insertConfig(McpServerConfigEntity.fromModel(config))
            com.android.everytalk.data.sync.captureSyncSettings(context, database)
        }
        clientManager.addServer(config)
    }

    suspend fun updateServer(config: McpServerConfig) {
        database.withTransaction {
            mcpDao.updateConfig(McpServerConfigEntity.fromModel(config))
            com.android.everytalk.data.sync.captureSyncSettings(context, database)
        }
        if (config.enabled) {
            clientManager.addServer(config)
        } else {
            clientManager.disconnectServer(config.id)
        }
    }

    private fun isAuthorizedServer(id: String) =
        McpOAuthProvider.entries.any { it.serverId == id } || McpMailProvider.entries.any { it.serverId == id }

    /** 清理旧版本留下的空地址记录和同地址重复记录，优先保留带授权的内置连接。 */
    private suspend fun removeDuplicateServers() {
        val rows = mcpDao.getAllSnapshot()
        val keep = rows.filter { it.url.isNotBlank() }
            .sortedByDescending { isAuthorizedServer(it.id) }
            .distinctBy { it.url }
            .mapTo(HashSet()) { it.id }
        rows.filter { it.id !in keep }.forEach { removeServer(it.id) }
    }

    suspend fun removeServer(serverId: String) {
        database.withTransaction {
            mcpDao.deleteConfigById(serverId)
            SyncSettingsProjector(database).markDeleted("mcp_servers", syncStableSettingKey("mcp_servers", serverId))
        }
        clientManager.removeServer(serverId)
        McpOAuthProvider.entries.firstOrNull { it.serverId == serverId }?.let { oauth.logout(it) }
        McpMailProvider.entries.firstOrNull { it.serverId == serverId }?.let { mail.remove(it) }
    }

    suspend fun toggleServer(serverId: String, enabled: Boolean) {
        database.withTransaction {
            mcpDao.setEnabled(serverId, enabled)
            com.android.everytalk.data.sync.captureSyncSettings(context, database)
        }
        if (enabled) {
            val entity = mcpDao.getConfigById(serverId)
            entity?.let {
                clientManager.addServer(it.toModel())
            }
        } else {
            clientManager.disconnectServer(serverId)
        }
    }

    fun getDispatchCandidates(): List<McpToolCandidate> {
        return serverStates.value.values
            .filter { it.status is McpStatus.Connected && it.config.enabled }
            .flatMap { state ->
                state.tools.filter { it.enable }.map { tool ->
                    toMcpToolCandidate(
                        serverName = state.config.name,
                        tool = tool,
                        exposedToolName = buildMcpToolAlias(state.config.id, tool.name),
                        serverInstructions = state.instructions,
                    )
                }
            }
    }

    /**
     * 只封存本轮实际声明的工具别名，不从哈希猜服务器，也不把未启用服务的凭据带走。
     * OAuth 先走原有刷新与固定来源校验；结果只用于加密云任务输入，不进入模型工具 schema。
     */
    internal suspend fun cloudBindings(toolNames: Set<String>): Map<String, JsonObject> {
        val bindings = mutableMapOf<String, JsonObject>()
        for (state in serverStates.value.values) {
            val config = state.config
            if (!config.enabled || state.status !is McpStatus.Connected) continue
            val tools = state.tools.filter { it.enable && buildMcpToolAlias(config.id, it.name) in toolNames }
            if (tools.isEmpty()) continue
            val headers = config.headers.toMutableMap()
            (mail.accessTokenFor(config) ?: oauth.accessTokenFor(config))?.let { token ->
                headers.keys.filter { it.equals("Authorization", true) }.forEach(headers::remove)
                headers["Authorization"] = "Bearer $token"
            }
            for (tool in tools) bindings[buildMcpToolAlias(config.id, tool.name)] = kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("mcp"))
                put("url", kotlinx.serialization.json.JsonPrimitive(config.url))
                put("transport", kotlinx.serialization.json.JsonPrimitive(if (config is McpServerConfig.SseTransportServer) "sse" else "streamable_http"))
                put("headers", JsonObject(headers.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                put("remoteName", kotlinx.serialization.json.JsonPrimitive(tool.name))
            }
        }
        check(toolNames.all { it in bindings }) { "本轮 MCP 工具连接已变化，请重新发送" }
        return bindings
    }

    /**
     * 为已经获得用户批准的当前请求准备 MCP 工具。
     *
     * 输入框的会话开关与设置页的服务器开关是两层状态：只连接用户在设置中启用的服务，
     * 不改写已停用的服务器。并行准备，避免多个失败服务器把等待时间叠加。
     * 直接调用客户端，不重复写配置触发 Room 监听再次连接同一个服务器。
     */
    suspend fun enableConfiguredServersForCurrentRequest(): List<McpToolCandidate> {
        supervisorScope {
            mcpDao.getEnabledConfigs().first().map { entity ->
                async(Dispatchers.IO) {
                    val config = entity.toModel()
                    if (serverStates.value[config.id]?.status !is McpStatus.Connected) {
                        clientManager.addServer(config)
                    }
                }
            }.awaitAll()
        }
        return getDispatchCandidates()
    }

    suspend fun callTool(toolName: String, arguments: JsonObject): JsonElement {
        Log.i(TAG, "Calling MCP tool: $toolName with argument keys: ${arguments.keys}")
        return clientManager.callTool(toolName, arguments)
    }

    suspend fun callToolResult(toolName: String, arguments: JsonObject): com.android.everytalk.data.network.AppToolExecutionResult {
        // 调用前取候选：连接级失败会把服务器移出已连接列表，之后就查不到失败工具的类别。
        val candidates = getDispatchCandidates()
        return withMcpFallbackHint(clientManager.callToolResult(toolName, arguments), toolName, candidates)
    }

    fun close() {
        scope.cancel()
        clientManager.close()
        oauthHttp.close()
    }
}

/**
 * 失败时告诉模型其他服务的同类工具（搜索、读网页、文档），由模型决定是否换一家重试，
 * 不在代码里自动重放：参数格式各家不同，且可能有副作用。
 */
internal fun withMcpFallbackHint(
    result: com.android.everytalk.data.network.AppToolExecutionResult,
    toolName: String,
    candidates: List<McpToolCandidate>,
): com.android.everytalk.data.network.AppToolExecutionResult {
    val content = result.content as? JsonObject
    val failed = candidates.firstOrNull { it.toolName == toolName }
    if (result.isError != true || content == null || failed == null ||
        failed.category == com.android.everytalk.statecontroller.mcp.dispatch.McpToolCategory.OTHER) return result
    val alternatives = candidates.filter { it.category == failed.category && it.server != failed.server }.take(4)
    if (alternatives.isEmpty()) return result
    return result.copy(content = JsonObject(content + ("alternatives" to kotlinx.serialization.json.JsonPrimitive(
        "This service failed. Tools with the same capability from other services: " +
            alternatives.joinToString("; ") { "${it.toolName} (${com.android.everytalk.data.agent.McpToolDiscovery.tagServer(it.server, it.description.lineSequence().first().take(80))})" } +
            ". Retry with one of them if it fits; if it is not declared yet, load it with tool_search first.",
    ))))
}

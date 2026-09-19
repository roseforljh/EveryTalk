package com.android.everytalk.statecontroller.viewmodel

import android.content.Context
import android.util.Log
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
    private val clientManager = McpClientManager(accessToken = oauth::accessTokenFor)
    private val _oauthMessage = MutableStateFlow<String?>(null)
    val oauthMessage = _oauthMessage.asStateFlow()
    private val _oauthBusy = MutableStateFlow(false)
    val oauthBusy = _oauthBusy.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val serverStates: StateFlow<Map<String, McpServerState>> = clientManager.serverStates

    init {
        scope.launch {
            McpOAuthCallbackBus.flow.filterNotNull().collect { uri ->
                McpOAuthCallbackBus.consume(uri)
                _oauthBusy.value = true
                try {
                    val provider = oauth.consume(uri.toString())
                    // 重新登录必须替换旧连接；配置中只保存地址，不保存 Authorization。
                    clientManager.removeServer(provider.serverId)
                    addServer(provider.defaultConfig())
                    _oauthMessage.value = "${provider.displayName} 授权成功"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { _oauthMessage.value = error.message ?: "MCP 登录失败" }
                finally { _oauthBusy.value = false }
            }
        }
        scope.launch {
            mcpDao.getEnabledConfigs().collect { entities ->
                entities.forEach { entity ->
                    val config = entity.toModel()
                    if (!serverStates.value.containsKey(config.id)) {
                        launch {
                            try {
                                clientManager.addServer(config)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to add server ${config.name}", e)
                            }
                        }
                    }
                }
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

    fun getAllConfigs(): Flow<List<McpServerConfig>> {
        return mcpDao.getAllConfigs().map { entities ->
            entities.map { it.toModel() }
        }
    }

    suspend fun addServer(config: McpServerConfig) {
        mcpDao.insertConfig(McpServerConfigEntity.fromModel(config))
        clientManager.addServer(config)
    }

    suspend fun updateServer(config: McpServerConfig) {
        mcpDao.updateConfig(McpServerConfigEntity.fromModel(config))
        if (config.enabled) {
            clientManager.addServer(config)
        } else {
            clientManager.disconnectServer(config.id)
        }
    }

    suspend fun removeServer(serverId: String) {
        mcpDao.deleteConfigById(serverId)
        clientManager.removeServer(serverId)
        McpOAuthProvider.entries.firstOrNull { it.serverId == serverId }?.let { oauth.logout(it) }
    }

    suspend fun toggleServer(serverId: String, enabled: Boolean) {
        mcpDao.setEnabled(serverId, enabled)
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
                    )
                }
            }
    }

    suspend fun callTool(toolName: String, arguments: JsonObject): JsonElement {
        Log.i(TAG, "Calling MCP tool: $toolName with argument keys: ${arguments.keys}")
        return clientManager.callTool(toolName, arguments)
    }

    fun close() {
        scope.cancel()
        clientManager.close()
        oauthHttp.close()
    }
}

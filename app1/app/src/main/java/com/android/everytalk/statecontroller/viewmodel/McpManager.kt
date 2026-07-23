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

class McpManager(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val mcpDao = database.mcpConfigDao()
    private val clientManager = McpClientManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val serverStates: StateFlow<Map<String, McpServerState>> = clientManager.serverStates

    init {
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
        Log.i(TAG, "Calling MCP tool: $toolName with args: $arguments")
        return clientManager.callTool(toolName, arguments)
    }

    fun close() {
        scope.cancel()
        clientManager.close()
    }
}

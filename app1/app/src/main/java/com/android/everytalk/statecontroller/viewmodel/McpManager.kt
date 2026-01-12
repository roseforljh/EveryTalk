package com.android.everytalk.statecontroller.viewmodel

import android.content.Context
import android.util.Log
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.McpServerConfigEntity
import com.android.everytalk.data.mcp.*
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
    val syncingStatus: StateFlow<Map<String, McpStatus>> = clientManager.syncingStatus

    private val _selectedTools = MutableStateFlow<Set<String>>(emptySet())
    val selectedTools: StateFlow<Set<String>> = _selectedTools.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _mcpToolNames = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            mcpDao.getEnabledConfigs().collect { entities ->
                entities.forEach { entity ->
                    val config = entity.toModel()
                    if (!serverStates.value.containsKey(config.id)) {
                        launch {
                            runCatching { clientManager.addServer(config) }
                                .onFailure { Log.e(TAG, "Failed to add server ${config.name}", it) }
                        }
                    }
                }
            }
        }
        scope.launch {
            serverStates.collect { states ->
                val toolNames = states.values
                    .filter { it.status is McpStatus.Connected && it.config.enabled }
                    .flatMap { it.tools.map { tool -> tool.name } }
                    .toSet()
                _mcpToolNames.value = toolNames
                Log.d(TAG, "Updated MCP tool names: $toolNames")
            }
        }
    }

    fun getAllConfigs(): Flow<List<McpServerConfig>> {
        return mcpDao.getAllConfigs().map { entities ->
            entities.map { it.toModel() }
        }
    }

    suspend fun addServer(config: McpServerConfig) {
        _isLoading.value = true
        try {
            mcpDao.insertConfig(McpServerConfigEntity.fromModel(config))
            clientManager.addServer(config)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun removeServer(serverId: String) {
        mcpDao.deleteConfigById(serverId)
        clientManager.removeServer(serverId)
        _selectedTools.update { tools ->
            tools.filterNot { it.startsWith("$serverId:") }.toSet()
        }
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
            _selectedTools.update { tools ->
                tools.filterNot { it.startsWith("$serverId:") }.toSet()
            }
        }
    }

    fun getAllAvailableTools(): List<McpTool> {
        return clientManager.getAllAvailableTools()
    }

    fun getToolsForChatRequest(): List<Map<String, Any>> {
        val states = serverStates.value
        Log.d(TAG, "getToolsForChatRequest: serverStates count=${states.size}")
        states.forEach { (id, state) ->
            Log.d(TAG, "  Server[$id]: status=${state.status::class.simpleName}, enabled=${state.config.enabled}, tools=${state.tools.size}")
        }
        val tools = getAllAvailableTools()
        Log.d(TAG, "getToolsForChatRequest: ${tools.size} tools available after filter")
        tools.forEach { tool ->
            Log.d(TAG, "  Tool: ${tool.name}, enable=${tool.enable}")
        }
        return tools.map { tool ->
            buildToolDefinition(tool)
        }
    }

    private fun buildToolDefinition(tool: McpTool): Map<String, Any> {
        val functionDef = mutableMapOf<String, Any>(
            "name" to tool.name,
            "description" to (tool.description ?: "MCP tool: ${tool.name}")
        )

        tool.inputSchema?.let { schema ->
            when (schema) {
                is McpInputSchema.Obj -> {
                    val parameters = mutableMapOf<String, Any>(
                        "type" to "object",
                        "properties" to schema.properties.toMap()
                    )
                    schema.required?.let { parameters["required"] = it }
                    functionDef["parameters"] = parameters
                }
            }
        } ?: run {
            functionDef["parameters"] = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        }

        return mapOf(
            "type" to "function",
            "function" to functionDef
        )
    }

    fun isMcpTool(toolName: String): Boolean {
        return _mcpToolNames.value.contains(toolName)
    }

    suspend fun callTool(toolName: String, arguments: JsonObject): JsonElement {
        Log.i(TAG, "Calling MCP tool: $toolName with args: $arguments")
        return clientManager.callTool(toolName, arguments)
    }

    fun toggleToolSelection(toolId: String) {
        _selectedTools.update { current ->
            if (current.contains(toolId)) {
                current - toolId
            } else {
                current + toolId
            }
        }
    }

    fun clearToolSelection() {
        _selectedTools.value = emptySet()
    }

    fun close() {
        scope.cancel()
        clientManager.close()
    }
}

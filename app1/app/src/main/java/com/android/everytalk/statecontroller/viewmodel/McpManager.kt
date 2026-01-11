package com.android.everytalk.statecontroller.viewmodel

import android.content.Context
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.McpServerConfigEntity
import com.android.everytalk.data.mcp.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * MCP 管理器
 * 负责管理 MCP 服务器配置和工具调用
 */
class McpManager(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val mcpDao = database.mcpConfigDao()
    private val clientManager = McpClientManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val serverStates: StateFlow<Map<String, McpServerState>> = clientManager.serverStates

    private val _selectedTools = MutableStateFlow<Set<String>>(emptySet())
    val selectedTools: StateFlow<Set<String>> = _selectedTools.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // 加载已保存的服务器配置并连接
        scope.launch {
            mcpDao.getEnabledConfigs().collect { entities ->
                entities.forEach { entity ->
                    val config = entity.toModel()
                    if (!serverStates.value.containsKey(config.id)) {
                        launch { clientManager.addServer(config) }
                    }
                }
            }
        }
    }

    /**
     * 获取所有已配置的服务器
     */
    fun getAllConfigs(): Flow<List<McpServerConfig>> {
        return mcpDao.getAllConfigs().map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 添加服务器
     */
    suspend fun addServer(config: McpServerConfig) {
        _isLoading.value = true
        try {
            mcpDao.insertConfig(McpServerConfigEntity.fromModel(config))
            clientManager.addServer(config)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 移除服务器
     */
    suspend fun removeServer(serverId: String) {
        mcpDao.deleteConfigById(serverId)
        clientManager.removeServer(serverId)
        // 移除该服务器相关的已选工具
        _selectedTools.update { tools ->
            tools.filterNot { it.startsWith("$serverId:") }.toSet()
        }
    }

    /**
     * 切换服务器启用状态
     */
    suspend fun toggleServer(serverId: String, enabled: Boolean) {
        mcpDao.setEnabled(serverId, enabled)
        if (enabled) {
            val entity = mcpDao.getConfigById(serverId)
            entity?.let {
                clientManager.addServer(it.toModel())
            }
        } else {
            clientManager.removeServer(serverId)
            // 移除该服务器相关的已选工具
            _selectedTools.update { tools ->
                tools.filterNot { it.startsWith("$serverId:") }.toSet()
            }
        }
    }

    /**
     * 获取所有可用工具
     */
    fun getAllAvailableTools(): List<Pair<McpServerConfig, McpTool>> {
        return clientManager.getAllTools()
    }

    /**
     * 切换工具选择状态
     */
    fun toggleToolSelection(toolId: String) {
        _selectedTools.update { current ->
            if (current.contains(toolId)) {
                current - toolId
            } else {
                current + toolId
            }
        }
    }

    /**
     * 清除工具选择
     */
    fun clearToolSelection() {
        _selectedTools.value = emptySet()
    }

    /**
     * 调用选中的工具
     */
    suspend fun callSelectedTools(
        arguments: Map<String, kotlinx.serialization.json.JsonElement>
    ): List<Pair<String, Result<McpToolResult>>> {
        val results = mutableListOf<Pair<String, Result<McpToolResult>>>()

        selectedTools.value.forEach { toolId ->
            val parts = toolId.split(":", limit = 2)
            if (parts.size == 2) {
                val serverId = parts[0]
                val toolName = parts[1]
                val result = clientManager.callTool(serverId, toolName, arguments)
                results.add(toolId to result)
            }
        }

        return results
    }

    /**
     * 调用指定工具
     */
    suspend fun callTool(
        serverId: String,
        toolName: String,
        arguments: Map<String, kotlinx.serialization.json.JsonElement>
    ): Result<McpToolResult> {
        return clientManager.callTool(serverId, toolName, arguments)
    }

    /**
     * 关闭管理器
     */
    fun close() {
        scope.cancel()
        clientManager.close()
    }
}

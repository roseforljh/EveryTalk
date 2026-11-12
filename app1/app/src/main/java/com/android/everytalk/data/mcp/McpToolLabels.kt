package com.android.everytalk.data.mcp

/**
 * 工具别名（mcp_<哈希>_<工具>）到“服务器 · 工具”的显示名。
 * 只用于界面展示，由 McpManager 随连接状态刷新；查不到时原样返回。
 */
object McpToolLabels {
    @Volatile private var labels: Map<String, String> = emptyMap()

    fun update(states: Collection<McpServerState>) {
        labels = states.flatMap { state ->
            state.tools.map { buildMcpToolAlias(state.config.id, it.name) to "${state.config.name} · ${it.name}" }
        }.toMap()
    }

    fun display(name: String): String = labels[name] ?: name
}

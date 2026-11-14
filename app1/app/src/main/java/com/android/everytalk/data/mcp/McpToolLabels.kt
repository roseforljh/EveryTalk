package com.android.everytalk.data.mcp

/**
 * 工具别名（mcp_<哈希>_<工具>）到真实工具显示名。
 * 只用于界面展示，由 McpManager 随连接状态刷新；离线历史也不能泄露哈希别名。
 */
object McpToolLabels {
    @Volatile private var labels: Map<String, String> = emptyMap()

    fun update(states: Collection<McpServerState>) {
        labels = states.flatMap { state ->
            state.tools.map { buildMcpToolAlias(state.config.id, it.name) to "${state.config.name} · ${it.name}" }
        }.toMap()
    }

    private val aliasPrefix = Regex("^mcp_[0-9a-f]{32}_")

    /** MCP 后只显示真实工具名；完整别名仍留在调用和账本里用于精确路由。 */
    fun display(name: String): String = if (aliasPrefix.containsMatchIn(name)) name.replaceFirst(aliasPrefix, "") else labels[name] ?: name
}

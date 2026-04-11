package com.android.everytalk.statecontroller.mcp.dispatch

enum class DispatchMode {
    MODEL_LED,
    CLIENT_GUIDED,
    CLIENT_ENFORCED,
}

data class McpToolBudget(
    val maxToolCalls: Int,
    val maxRoundTrips: Int,
    val maxSameCategoryCalls: Int,
    val maxSameToolRetries: Int,
)

data class McpDispatchStrategy(
    val mode: DispatchMode,
    val budget: McpToolBudget,
    val showUserVisibleStatus: Boolean,
    val enableCandidateFiltering: Boolean,
    val enableFailureFailover: Boolean,
    val enableHealthSuppression: Boolean,
) {
    companion object {
        fun modelLedDefault(): McpDispatchStrategy {
            return McpDispatchStrategy(
                mode = DispatchMode.MODEL_LED,
                budget = McpToolBudget(
                    maxToolCalls = 2,
                    maxRoundTrips = 3,
                    maxSameCategoryCalls = 2,
                    maxSameToolRetries = 1,
                ),
                showUserVisibleStatus = true,
                enableCandidateFiltering = true,
                enableFailureFailover = true,
                enableHealthSuppression = true,
            )
        }
    }
}

enum class McpToolCategory {
    DOCS,
    SEARCH,
    BROWSER,
    DATABASE,
    SERVICE,
    GENERAL,
}

enum class QueryIntent {
    GENERAL_CHAT,
    DOCS_LOOKUP,
    REALTIME_INFO,
    WEB_CONTENT_READ,
    STRUCTURED_DATA_QUERY,
    INTERNAL_SERVICE_QUERY,
    LOCAL_REASONING,
}

data class McpDispatchIntent(
    val primaryIntent: QueryIntent,
    val shouldPreferMcp: Boolean,
    val candidateCategories: Set<McpToolCategory>,
)

enum class McpUiStage(val userVisibleText: String) {
    ROUTING("正在选择工具…"),
    LOOKING_UP_DOCS("正在查官方文档…"),
    SEARCHING_WEB("正在搜索最新信息…"),
    READING_PAGE("正在读取网页内容…"),
    RETRYING("正在切换备用服务…"),
    FALLBACK_ANSWERING("正在基于现有信息回答…"),
    COMPLETED(""),
}

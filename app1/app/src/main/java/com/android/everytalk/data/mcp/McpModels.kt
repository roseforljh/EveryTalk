package com.android.everytalk.data.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class McpTransportType {
    SSE,
    HTTP
}

/** 内置的 API Key 类服务：固定 ID 防止重复，Key 拼进官方地址或放进请求头。 */
enum class McpKeyProvider(
    val serverId: String,
    val displayName: String,
    private val urlTemplate: String,
    val keyRequired: Boolean,
    val keyPageUrl: String,
    private val keyHeader: String? = null,
    private val keyPrefix: String = "",
    /** 与联网搜索共用同一个 Key 的搜索服务 ID。 */
    val webSearchProviderId: String? = null,
) {
    FIRECRAWL("mcp-firecrawl", "Firecrawl", "https://mcp.firecrawl.dev/{API_KEY}/v2/mcp", true, "https://www.firecrawl.dev/app/api-keys"),
    EXA("mcp-exa", "Exa", "https://mcp.exa.ai/mcp?exaApiKey={API_KEY}&tools=web_search_exa,get_code_context_exa", true, "https://dashboard.exa.ai/api-keys", webSearchProviderId = "exa"),
    TAVILY("mcp-tavily", "Tavily", "https://mcp.tavily.com/mcp/?tavilyApiKey={API_KEY}", true, "https://app.tavily.com/home", webSearchProviderId = "tavily"),
    JINA("mcp-jina", "Jina Reader", "https://mcp.jina.ai/v1", false, "https://jina.ai/api-dashboard/", "Authorization", keyPrefix = "Bearer "),
    CONTEXT7("mcp-context7", "Context7", "https://mcp.context7.com/mcp", false, "https://context7.com/dashboard", "CONTEXT7_API_KEY");

    /** 旧版本用随机 ID 添加过的同一服务也算已接入，不再显示内置行。 */
    fun matches(config: McpServerConfig): Boolean =
        config.id == serverId || config.url.startsWith(urlTemplate.substringBefore("{API_KEY}"))

    fun config(apiKey: String): McpServerConfig {
        val key = apiKey.trim()
        val url = if (keyHeader == null) urlTemplate.replace("{API_KEY}", key) else urlTemplate
        val headers = if (keyHeader != null && key.isNotEmpty()) listOf(keyHeader to keyPrefix + key) else emptyList()
        return McpServerConfig.StreamableHTTPServer(serverId, McpCommonOptions(name = displayName, headers = headers), url)
    }

    /** 换 Key 时保留原记录的 ID、名称和开关，只替换地址与认证头。 */
    fun withKey(existing: McpServerConfig, apiKey: String): McpServerConfig {
        val next = config(apiKey)
        return McpServerConfig.StreamableHTTPServer(existing.id, existing.commonOptions.copy(headers = next.commonOptions.headers), next.url)
    }
}

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList()
)

@Serializable
sealed class McpServerConfig {
    abstract val id: String
    abstract val commonOptions: McpCommonOptions
    abstract val url: String

    abstract fun clone(
        id: String = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    val name: String get() = commonOptions.name
    val enabled: Boolean get() = commonOptions.enable
    val headers: Map<String, String> get() = commonOptions.headers.toMap()

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: String = java.util.UUID.randomUUID().toString(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        override val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: String, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: String = java.util.UUID.randomUUID().toString(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        override val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: String, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val enable: Boolean = true,
    val inputSchema: McpInputSchema? = null
)

@Serializable
sealed class McpInputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject = JsonObject(emptyMap()),
        val required: List<String>? = null,
        /** SDK 的根 $defs 必须随属性引用一起保存，避免下游收到悬空引用。 */
        val defs: JsonObject? = null,
    ) : McpInputSchema()

    fun toJsonObject(): JsonObject {
        return when (this) {
            is Obj -> properties
        }
    }
}

sealed class McpStatus {
    object Idle : McpStatus()
    object Connecting : McpStatus()
    object Connected : McpStatus()
    class Error(
        val message: String,
        val failureType: String? = null,
    ) : McpStatus()
}

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus = McpStatus.Idle,
    val tools: List<McpTool> = emptyList(),
    val errorMessage: String? = null
)

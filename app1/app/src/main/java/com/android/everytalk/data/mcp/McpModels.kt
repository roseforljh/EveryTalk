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
    private val anonymousUrl: String? = null,
) {
    // Bearer 同时接受 API Key 和账号登录换到的令牌。
    PARALLEL("mcp-parallel", "Parallel", "https://mcp.parallel.ai/mcp", false, "https://platform.parallel.ai", "Authorization", keyPrefix = "Bearer "),
    FIRECRAWL("mcp-firecrawl", "Firecrawl", "https://mcp.firecrawl.dev/{API_KEY}/v2/mcp", false, "https://www.firecrawl.dev/app/api-keys", anonymousUrl = "https://mcp.firecrawl.dev/v2/mcp"),
    EXA("mcp-exa", "Exa", "https://mcp.exa.ai/mcp?exaApiKey={API_KEY}", false, "https://dashboard.exa.ai/api-keys", webSearchProviderId = "exa", anonymousUrl = "https://mcp.exa.ai/mcp"),
    BRIGHT_DATA("mcp-brightdata", "Bright Data", "https://mcp.brightdata.com/mcp?token={API_KEY}", true, "https://brightdata.com/cp/setting/users"),
    TAVILY("mcp-tavily", "Tavily", "https://mcp.tavily.com/mcp/?tavilyApiKey={API_KEY}", true, "https://app.tavily.com/home", webSearchProviderId = "tavily"),
    JINA("mcp-jina", "Jina Reader", "https://mcp.jina.ai/v1", false, "https://jina.ai/api-dashboard/", "Authorization", keyPrefix = "Bearer "),
    CONTEXT7("mcp-context7", "Context7", "https://mcp.context7.com/mcp", false, "https://context7.com/dashboard", "CONTEXT7_API_KEY");

    /** 旧版本用随机 ID 添加过的同一服务也算已接入，不再显示内置行。 */
    fun matches(config: McpServerConfig): Boolean =
        config.id == serverId || config.url == anonymousUrl ||
            config.url.startsWith(urlTemplate.substringBefore("{API_KEY}"))

    /** 从已保存的配置取回当前 Key，编辑时回填；匿名连接返回空。 */
    fun keyOf(config: McpServerConfig): String = when {
        keyHeader != null -> config.commonOptions.headers.firstOrNull { it.first == keyHeader }?.second?.removePrefix(keyPrefix).orEmpty()
        config.url == anonymousUrl -> ""
        else -> config.url.removePrefix(urlTemplate.substringBefore("{API_KEY}")).removeSuffix(urlTemplate.substringAfter("{API_KEY}"))
    }

    fun config(apiKey: String): McpServerConfig {
        val key = apiKey.trim()
        // 匿名端点不带空 Key：Firecrawl 的路径和 Exa 的查询参数都不能留空占位。
        val url = if (key.isEmpty() && anonymousUrl != null) anonymousUrl
        else if (keyHeader == null) urlTemplate.replace("{API_KEY}", key) else urlTemplate
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
    val errorMessage: String? = null,
    /** 服务器 initialize 返回的说明，用于向模型介绍延迟加载的服务。 */
    val instructions: String? = null,
)

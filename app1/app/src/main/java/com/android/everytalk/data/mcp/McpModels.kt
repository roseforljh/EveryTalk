package com.android.everytalk.data.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class McpTransportType {
    SSE,
    HTTP
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

    companion object {
        fun createDefault(
            name: String,
            url: String,
            transportType: McpTransportType = McpTransportType.SSE,
            headers: Map<String, String> = emptyMap()
        ): McpServerConfig {
            val commonOptions = McpCommonOptions(
                enable = true,
                name = name,
                headers = headers.toList()
            )
            return when (transportType) {
                McpTransportType.SSE -> SseTransportServer(
                    commonOptions = commonOptions,
                    url = url
                )
                McpTransportType.HTTP -> StreamableHTTPServer(
                    commonOptions = commonOptions,
                    url = url
                )
            }
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
        val required: List<String>? = null
    ) : McpInputSchema()

    fun toJsonObject(): JsonObject {
        return when (this) {
            is Obj -> properties
        }
    }
}

@Serializable
data class McpToolCall(
    val name: String,
    val arguments: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false
)

@Serializable
sealed class McpContent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : McpContent()

    @Serializable
    @SerialName("image")
    data class Image(val data: String, val mimeType: String) : McpContent()

    @Serializable
    @SerialName("resource")
    data class Resource(val uri: String, val mimeType: String?, val text: String?) : McpContent()
}

sealed class McpStatus {
    object Idle : McpStatus()
    object Connecting : McpStatus()
    object Connected : McpStatus()
    class Error(val message: String) : McpStatus()
}

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus = McpStatus.Idle,
    val tools: List<McpTool> = emptyList(),
    val errorMessage: String? = null
)

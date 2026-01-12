package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.android.everytalk.data.mcp.McpCommonOptions
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.mcp.McpTransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Entity(tableName = "mcp_server_configs")
data class McpServerConfigEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val url: String,
    val transportType: String = "SSE",
    val enabled: Boolean = true,
    val headers: String = "[]"
) {
    fun toModel(): McpServerConfig {
        val headerList: List<Pair<String, String>> = try {
            Json.decodeFromString<List<List<String>>>(headers).map { it[0] to it[1] }
        } catch (e: Exception) {
            try {
                val headerMap = Json.decodeFromString<Map<String, String>>(headers)
                headerMap.toList()
            } catch (e2: Exception) {
                emptyList()
            }
        }

        val commonOptions = McpCommonOptions(
            enable = enabled,
            name = name,
            headers = headerList
        )

        return when (McpTransportType.valueOf(transportType)) {
            McpTransportType.SSE -> McpServerConfig.SseTransportServer(
                id = id,
                commonOptions = commonOptions,
                url = url
            )
            McpTransportType.HTTP -> McpServerConfig.StreamableHTTPServer(
                id = id,
                commonOptions = commonOptions,
                url = url
            )
        }
    }

    companion object {
        fun fromModel(config: McpServerConfig): McpServerConfigEntity {
            val headersList = config.commonOptions.headers.map { listOf(it.first, it.second) }
            val headersJson = Json.encodeToString(headersList)

            val transportType = when (config) {
                is McpServerConfig.SseTransportServer -> McpTransportType.SSE
                is McpServerConfig.StreamableHTTPServer -> McpTransportType.HTTP
            }

            return McpServerConfigEntity(
                id = config.id,
                name = config.name,
                url = config.url,
                transportType = transportType.name,
                enabled = config.enabled,
                headers = headersJson
            )
        }
    }
}

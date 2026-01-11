package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.mcp.McpTransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * MCP 服务器配置数据库实体
 */
@Entity(tableName = "mcp_server_configs")
data class McpServerConfigEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val url: String,
    val transportType: String = "SSE",
    val enabled: Boolean = true,
    val headers: String = "{}" // JSON 格式存储
) {
    fun toModel(): McpServerConfig {
        val headerMap = try {
            Json.decodeFromString<Map<String, String>>(headers)
        } catch (e: Exception) {
            emptyMap()
        }
        return McpServerConfig(
            id = id,
            name = name,
            url = url,
            transportType = McpTransportType.valueOf(transportType),
            enabled = enabled,
            headers = headerMap
        )
    }

    companion object {
        fun fromModel(config: McpServerConfig): McpServerConfigEntity {
            val headersJson = Json.encodeToString(config.headers)
            return McpServerConfigEntity(
                id = config.id,
                name = config.name,
                url = config.url,
                transportType = config.transportType.name,
                enabled = config.enabled,
                headers = headersJson
            )
        }
    }
}

package com.android.everytalk.data.database.daos

import androidx.room.*
import com.android.everytalk.data.database.entities.McpServerConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * MCP 服务器配置 DAO
 */
@Dao
interface McpConfigDao {
    @Query("SELECT * FROM mcp_server_configs ORDER BY name ASC")
    fun getAllConfigs(): Flow<List<McpServerConfigEntity>>

    @Query("SELECT * FROM mcp_server_configs WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledConfigs(): Flow<List<McpServerConfigEntity>>

    @Query("SELECT * FROM mcp_server_configs WHERE id = :id")
    suspend fun getConfigById(id: String): McpServerConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: McpServerConfigEntity)

    @Update
    suspend fun updateConfig(config: McpServerConfigEntity)

    @Delete
    suspend fun deleteConfig(config: McpServerConfigEntity)

    @Query("DELETE FROM mcp_server_configs WHERE id = :id")
    suspend fun deleteConfigById(id: String)

    @Query("UPDATE mcp_server_configs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

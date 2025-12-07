package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ConversationParamsEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity

@Dao
interface SettingsDao {
    // System Settings (key-value)
    @Query("SELECT value FROM system_settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SystemSettingEntity)

    @Query("DELETE FROM system_settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    // Pinned Items
    @Query("SELECT id FROM pinned_items WHERE isImageGeneration = :isImageGen")
    suspend fun getPinnedIds(isImageGen: Boolean): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPinnedItems(items: List<PinnedItemEntity>)

    @Query("DELETE FROM pinned_items WHERE isImageGeneration = :isImageGen")
    suspend fun clearPinnedItems(isImageGen: Boolean)

    // Conversation Groups
    @Query("SELECT * FROM conversation_groups")
    suspend fun getConversationGroups(): List<ConversationGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationGroups(groups: List<ConversationGroupEntity>)

    @Query("DELETE FROM conversation_groups")
    suspend fun clearConversationGroups()

    // Expanded Groups
    @Query("SELECT groupKey FROM expanded_groups")
    suspend fun getExpandedGroupKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpandedGroups(groups: List<ExpandedGroupEntity>)

    @Query("DELETE FROM expanded_groups")
    suspend fun clearExpandedGroups()

    // Conversation Parameters
    @Query("SELECT * FROM conversation_params")
    suspend fun getAllConversationParams(): List<ConversationParamsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationParams(params: List<ConversationParamsEntity>)
}
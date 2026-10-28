package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.UserSettingEntity

@Dao
interface SettingsDao {
    /** 页面只需要三个状态键和失败提示，不能加载数千条附件缓存及内部同步清单。 */
    @Query("SELECT * FROM system_settings WHERE `key` IN ('sync_epoch_decision_required', 'sync_last_success_at', 'sync_paused_after_cloud_reset') OR `key` GLOB 'sync_rejected:*'")
    fun observeSyncControl(): kotlinx.coroutines.flow.Flow<List<SystemSettingEntity>>

    @Query("SELECT COUNT(*) FROM system_settings WHERE `key` LIKE 'sync_rejected:%'")
    suspend fun syncRejectedCount(): Int

    @Query("DELETE FROM system_settings WHERE `key` LIKE 'sync_rejected:%'")
    suspend fun clearSyncRejections()

    @Query("SELECT * FROM user_settings")
    suspend fun getAllUserSettings(): List<UserSettingEntity>

    @Query("UPDATE user_settings SET sync_status = 'LOCAL_ONLY'")
    suspend fun keepAllUserSettingsLocal()

    @Query("DELETE FROM user_settings")
    suspend fun clearUserSettingsForSync()

    /** 保留只属于生图的分组；混合分组仅去掉文字引用。 */
    @Transaction
    suspend fun clearTextGroupsForSync() {
        val images = getImageSessionIds().toSet()
        val groups = getConversationGroups().mapNotNull { group ->
            val ids = group.conversationIds.filter(images::contains)
            if (ids.isEmpty()) null else group.copy(conversationIds = ids)
        }
        replaceConversationGroups(groups)
    }

    @Query("SELECT id FROM chat_sessions WHERE isImageGeneration = 1 AND deleted = 0")
    suspend fun getImageSessionIds(): List<String>
    @Query("SELECT * FROM user_settings WHERE deleted = 0")
    suspend fun getSyncableUserSettings(): List<UserSettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserSettings(settings: List<UserSettingEntity>)

    @Query("SELECT * FROM user_settings WHERE sync_status = 'PENDING' AND kind != 'conversation_groups' AND NOT (kind = 'pinned_items' AND `key` = 'text') LIMIT :limit")
    suspend fun getPendingUserSettings(limit: Int = Int.MAX_VALUE): List<UserSettingEntity>

    @Query("UPDATE user_settings SET sync_status = 'SYNCED' WHERE uuid IN (:uuids) AND deleted = 0")
    suspend fun markUserSettingsSynced(uuids: List<String>)

    @Query("SELECT * FROM user_settings WHERE kind = :kind AND `key` = :key LIMIT 1")
    suspend fun getUserSetting(kind: String, key: String): UserSettingEntity?

    @Query("SELECT * FROM user_settings WHERE uuid = :uuid LIMIT 1")
    suspend fun getUserSettingByUuid(uuid: String): UserSettingEntity?

    @Query("DELETE FROM user_settings WHERE kind = :kind AND `key` = :key")
    suspend fun deleteUserSetting(kind: String, key: String)

    @Query("UPDATE user_settings SET sync_status = 'SYNCED' WHERE uuid = :uuid AND updated_at = :version AND sync_status = 'PENDING'")
    suspend fun markUserSettingSyncedIfVersion(uuid: String, version: Long): Int

    @Query("UPDATE user_settings SET sync_status = 'LOCAL_ONLY' WHERE uuid = :uuid AND updated_at = :version")
    suspend fun markUserSettingRejectedIfVersion(uuid: String, version: Long): Int

    /** 只恢复同步系统判定为可重试的旧失败设置。 */
    @Query("UPDATE user_settings SET sync_status = 'PENDING' WHERE uuid = :uuid")
    suspend fun markUserSettingPendingByUuid(uuid: String): Int

    @Query("UPDATE user_settings SET sync_status = 'PENDING'")
    suspend fun markAllUserSettingsPending()

    /** 后台去重键不进入页面状态。 */
    @Query("SELECT * FROM system_settings WHERE `key` IN ('memory_enabled', 'memory_auto_capture_enabled', 'memory_task_version')")
    fun observeMemorySettings(): kotlinx.coroutines.flow.Flow<List<SystemSettingEntity>>

    // System Settings (key-value)
    @Query("SELECT value FROM system_settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SystemSettingEntity)

    @Query("DELETE FROM system_settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    /** 按主键分批读取内部清单，避免 Room 的单次结果超过设备 CursorWindow 容量。 */
    @Query("SELECT * FROM system_settings WHERE substr(`key`, 1, length(:prefix)) = :prefix AND (:afterKey IS NULL OR `key` > :afterKey) ORDER BY `key` LIMIT 128")
    suspend fun getPrefixPage(prefix: String, afterKey: String?): List<SystemSettingEntity>

    /** 前缀按字面匹配，下划线和百分号不能被当成 SQL 通配符。 */
    suspend fun getByPrefix(prefix: String): List<SystemSettingEntity> {
        val rows = mutableListOf<SystemSettingEntity>()
        var afterKey: String? = null
        do {
            val page = getPrefixPage(prefix, afterKey)
            rows.addAll(page)
            afterKey = page.lastOrNull()?.key
        } while (page.size == 128)
        return rows
    }

    // Pinned Items
    @Query("SELECT id FROM pinned_items WHERE isImageGeneration = :isImageGen")
    suspend fun getPinnedIds(isImageGen: Boolean): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPinnedItems(items: List<PinnedItemEntity>)

    @Query("DELETE FROM pinned_items WHERE isImageGeneration = :isImageGen")
    suspend fun clearPinnedItems(isImageGen: Boolean)

    @Transaction
    suspend fun replacePinnedItems(isImageGen: Boolean, items: List<PinnedItemEntity>) {
        clearPinnedItems(isImageGen)
        if (items.isNotEmpty()) insertPinnedItems(items)
    }

    // Conversation Groups
    @Query("SELECT * FROM conversation_groups")
    suspend fun getConversationGroups(): List<ConversationGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationGroups(groups: List<ConversationGroupEntity>)

    @Query("DELETE FROM conversation_groups")
    suspend fun clearConversationGroups()

    @Transaction
    suspend fun replaceConversationGroups(groups: List<ConversationGroupEntity>) {
        clearConversationGroups()
        if (groups.isNotEmpty()) insertConversationGroups(groups)
    }

    @Query("DELETE FROM conversation_groups WHERE groupName = :groupName")
    suspend fun deleteConversationGroup(groupName: String)

    // Expanded Groups
    @Query("SELECT groupKey FROM expanded_groups")
    suspend fun getExpandedGroupKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpandedGroups(groups: List<ExpandedGroupEntity>)

    @Query("DELETE FROM expanded_groups")
    suspend fun clearExpandedGroups()

    @Transaction
    suspend fun replaceExpandedGroups(groups: List<ExpandedGroupEntity>) {
        clearExpandedGroups()
        if (groups.isNotEmpty()) insertExpandedGroups(groups)
    }

}

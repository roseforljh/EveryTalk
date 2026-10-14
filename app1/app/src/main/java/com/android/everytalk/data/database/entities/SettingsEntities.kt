package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

// Generic key-value store for single items like selected_config_id
@Entity(tableName = "system_settings")
data class SystemSettingEntity(
    @PrimaryKey
    val key: String,
    val value: String
)

@Entity(tableName = "pinned_items")
data class PinnedItemEntity(
    @PrimaryKey
    val id: String,
    val isImageGeneration: Boolean
)

@Entity(tableName = "conversation_groups")
data class ConversationGroupEntity(
    @PrimaryKey
    val groupName: String,
    val conversationIds: List<String> // Stored as JSON string via TypeConverter
)

@Entity(tableName = "expanded_groups")
data class ExpandedGroupEntity(
    @PrimaryKey
    val groupKey: String
)

/**
 * 只保存白名单内的账号设置。kind + key 是业务身份，value 内会话编号必须是 UUID。
 * 旧设置表继续承载图片和设备偏好，不得把 system_settings 或 API 配置整体拿去同步。
 */
@Entity(tableName = "user_settings", primaryKeys = ["kind", "key"], indices = [Index(value = ["uuid"], unique = true)])
data class UserSettingEntity(
    val kind: String,
    val key: String,
    val value: String,
    val uuid: String = localSyncUuid("user_settings", "$kind:$key"),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "sync_status", defaultValue = "'PENDING'") val syncStatus: String = SyncStatus.PENDING,
) {
    init {
        require(kind == UserSettingKind.CONVERSATION_GROUPS || kind == UserSettingKind.PINNED_ITEMS)
    }

    @get:androidx.room.Ignore
    val clientModifiedAt: Long get() = updatedAt
}

/**
 * 从设备设置生成文字同步投影。原表仍保留本地 ID 和生图设置；同步值只包含实际存在的文字会话 UUID。
 * 空分组可以同步，只包含生图/无效引用的非空分组不上传。迁移和日常保存共用此白名单。
 */
fun syncUserSettingSnapshots(
    groups: List<ConversationGroupEntity>,
    pinnedIds: List<String>,
    sessions: List<SyncSessionReference>,
    modifiedAt: Long? = null,
): List<UserSettingEntity> {
    val byId = sessions.associateBy { it.id }
    fun snapshot(kind: String, key: String, ids: List<String>): UserSettingEntity {
        val references = ids.mapNotNull(byId::get).distinctBy { it.uuid }
        return UserSettingEntity(
            kind, key, org.json.JSONArray(references.map { it.uuid }).toString(),
            updatedAt = modifiedAt ?: (references.maxOfOrNull { it.updatedAt } ?: 0L),
        )
    }
    return buildList {
        groups.filter { it.conversationIds.isEmpty() || it.conversationIds.any(byId::containsKey) }.forEach {
            add(snapshot(UserSettingKind.CONVERSATION_GROUPS, it.groupName, it.conversationIds))
        }
        if (pinnedIds.any(byId::containsKey)) add(snapshot(UserSettingKind.PINNED_ITEMS, UserSettingKind.PINNED_KEY, pinnedIds))
    }
}

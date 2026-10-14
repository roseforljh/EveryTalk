package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/** 独立长期资料；来源仅用于显示，无级联外键，删除原会话不会误删记忆。 */
@Entity(tableName = "memories", indices = [Index(value = ["uuid"], unique = true)])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val category: String,
    val content: String,
    val origin: String,
    val sourceConversationId: String?,
    val sourceMessageId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "''") val uuid: String = localSyncUuid("memories", id),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val syncUpdatedAt: Long = updatedAt,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "sync_status", defaultValue = "'LOCAL_ONLY'") val syncStatus: String = SyncStatus.PENDING,
)

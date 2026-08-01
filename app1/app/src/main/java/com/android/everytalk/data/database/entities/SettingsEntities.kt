package com.android.everytalk.data.database.entities

import androidx.room.Entity
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

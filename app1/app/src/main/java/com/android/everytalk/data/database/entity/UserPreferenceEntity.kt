package com.android.everytalk.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户偏好设置实体类 - 用于存储各种用户偏好设置
 * 
 * 使用键值对形式存储，支持展开状态、UI偏好等
 */
@Entity(tableName = "user_preferences")
data class UserPreferenceEntity(
    @PrimaryKey
    val key: String,
    
    /**
     * 偏好值（JSON序列化存储复杂类型）
     */
    val value: String,
    
    /**
     * 最后更新时间
     */
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 用户偏好键常量
 */
object UserPreferenceKeys {
    const val EXPANDED_GROUP_KEYS = "expanded_group_keys"
    const val LAST_SELECTED_TAB = "last_selected_tab"
    const val THEME_MODE = "theme_mode"
}
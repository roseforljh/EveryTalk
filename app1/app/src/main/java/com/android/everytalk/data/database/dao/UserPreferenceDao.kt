package com.android.everytalk.data.database.dao

import androidx.room.*
import com.android.everytalk.data.database.entity.UserPreferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 用户偏好设置数据访问对象
 */
@Dao
interface UserPreferenceDao {
    
    /**
     * 插入或更新偏好设置
     */
    @Upsert
    suspend fun upsertPreference(preference: UserPreferenceEntity)
    
    /**
     * 批量插入或更新偏好设置
     */
    @Upsert
    suspend fun upsertPreferences(preferences: List<UserPreferenceEntity>)
    
    /**
     * 获取所有偏好设置
     */
    @Query("SELECT * FROM user_preferences")
    fun getAllPreferences(): Flow<List<UserPreferenceEntity>>
    
    /**
     * 获取所有偏好设置（一次性查询）
     */
    @Query("SELECT * FROM user_preferences")
    suspend fun getAllPreferencesOnce(): List<UserPreferenceEntity>
    
    /**
     * 根据键获取偏好设置
     */
    @Query("SELECT * FROM user_preferences WHERE `key` = :key")
    suspend fun getPreferenceByKey(key: String): UserPreferenceEntity?
    
    /**
     * 根据键获取偏好值
     */
    @Query("SELECT value FROM user_preferences WHERE `key` = :key")
    suspend fun getPreferenceValue(key: String): String?
    
    /**
     * 删除偏好设置
     */
    @Delete
    suspend fun deletePreference(preference: UserPreferenceEntity)
    
    /**
     * 根据键删除偏好设置
     */
    @Query("DELETE FROM user_preferences WHERE `key` = :key")
    suspend fun deletePreferenceByKey(key: String)
    
    /**
     * 删除所有偏好设置
     */
    @Query("DELETE FROM user_preferences")
    suspend fun deleteAllPreferences()
    
    /**
     * 检查偏好设置是否存在
     */
    @Query("SELECT EXISTS(SELECT 1 FROM user_preferences WHERE `key` = :key)")
    suspend fun preferenceExists(key: String): Boolean
    
    /**
     * 更新偏好值
     */
    @Query("UPDATE user_preferences SET value = :value, updatedAt = :updatedAt WHERE `key` = :key")
    suspend fun updatePreferenceValue(key: String, value: String, updatedAt: Long = System.currentTimeMillis())
}
package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.android.everytalk.data.database.entities.ApiConfigEntity
import com.android.everytalk.data.database.entities.VoiceBackendConfigEntity

@Dao
interface ApiConfigDao {
    @Query("SELECT * FROM api_configs WHERE isImageGenConfig = :isImageGen")
    suspend fun getConfigs(isImageGen: Boolean): List<ApiConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfigs(configs: List<ApiConfigEntity>)

    @Query("DELETE FROM api_configs WHERE isImageGenConfig = :isImageGen")
    suspend fun clearConfigs(isImageGen: Boolean)
}

@Dao
interface VoiceConfigDao {
    @Query("SELECT * FROM voice_backend_configs")
    suspend fun getAll(): List<VoiceBackendConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<VoiceBackendConfigEntity>)

    @Query("DELETE FROM voice_backend_configs")
    suspend fun clearAll()
}
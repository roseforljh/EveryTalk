package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Transaction
    suspend fun replaceConfigs(isImageGen: Boolean, configs: List<ApiConfigEntity>) {
        clearConfigs(isImageGen)
        if (configs.isNotEmpty()) insertConfigs(configs)
    }
}

@Dao
interface VoiceConfigDao {
    @Query("SELECT * FROM voice_backend_configs")
    suspend fun getAll(): List<VoiceBackendConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<VoiceBackendConfigEntity>)

    @Query("DELETE FROM voice_backend_configs")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(configs: List<VoiceBackendConfigEntity>) {
        clearAll()
        if (configs.isNotEmpty()) insertAll(configs)
    }
}

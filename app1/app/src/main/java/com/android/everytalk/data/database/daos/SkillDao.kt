package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.database.entities.SkillVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {
    @Query("SELECT * FROM skill_installations ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SkillInstallationEntity>>

    @Query("SELECT * FROM skill_installations ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<SkillInstallationEntity>

    @Query("SELECT * FROM skill_installations WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getEnabled(): List<SkillInstallationEntity>

    @Query("SELECT * FROM skill_installations WHERE skillId = :skillId LIMIT 1")
    suspend fun getInstallation(skillId: String): SkillInstallationEntity?

    @Query("SELECT * FROM skill_versions WHERE skillId = :skillId AND contentHash = :contentHash LIMIT 1")
    suspend fun getVersion(skillId: String, contentHash: String): SkillVersionEntity?

    @Upsert
    suspend fun upsertInstallation(entity: SkillInstallationEntity)

    @Upsert
    suspend fun upsertVersion(entity: SkillVersionEntity)

    @Query("UPDATE skill_installations SET enabled = :enabled, updatedAt = :updatedAt WHERE skillId = :skillId")
    suspend fun setEnabled(skillId: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE skill_installations SET updateHash = :updateHash WHERE skillId = :skillId")
    suspend fun setUpdateHash(skillId: String, updateHash: String?)

    @Query("UPDATE skill_installations SET lastUsedAt = :usedAt, useCount = useCount + 1 WHERE skillId = :skillId")
    suspend fun recordUse(skillId: String, usedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM skill_installations WHERE skillId = :skillId")
    suspend fun delete(skillId: String)

    /** 安装记录必须先存在，版本表的外键才允许写入。 */
    @Transaction
    suspend fun saveVersion(
        installation: SkillInstallationEntity,
        version: SkillVersionEntity,
    ) {
        upsertInstallation(installation)
        upsertVersion(version)
    }
}

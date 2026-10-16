package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.database.entities.SkillVersionEntity
import com.android.everytalk.data.database.entities.UserSettingEntity
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.database.entities.localSyncUuid
import com.android.everytalk.data.sync.syncSkillValue
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query("SELECT * FROM skill_installations WHERE packageId = :packageId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getPackageChildren(packageId: String): List<SkillInstallationEntity>

    @Query("SELECT * FROM skill_versions WHERE skillId = :skillId AND contentHash = :contentHash LIMIT 1")
    suspend fun getVersion(skillId: String, contentHash: String): SkillVersionEntity?

    @Upsert
    suspend fun upsertInstallation(entity: SkillInstallationEntity)

    @Upsert
    suspend fun upsertVersion(entity: SkillVersionEntity)

    @Upsert
    suspend fun upsertInstallations(entities: List<SkillInstallationEntity>)

    @Upsert
    suspend fun upsertVersions(entities: List<SkillVersionEntity>)

    @Query("UPDATE skill_installations SET enabled = :enabled, updatedAt = :updatedAt WHERE skillId = :skillId")
    suspend fun setEnabledRow(skillId: String, enabled: Boolean, updatedAt: Long)

    /** 用户修改和同步快照在同一个事务中保存，不能等后台拉取时才补记时间。 */
    @Transaction
    suspend fun setEnabled(skillId: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis()) {
        setEnabledRow(skillId, enabled, updatedAt)
        captureSyncSetting(skillId)
    }

    @Query("UPDATE skill_installations SET enabled = :enabled, updatedAt = :updatedAt WHERE packageId = :packageId")
    suspend fun setPackageEnabledRows(packageId: String, enabled: Boolean, updatedAt: Long)

    @Transaction
    suspend fun setPackageEnabled(packageId: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis()) {
        setPackageEnabledRows(packageId, enabled, updatedAt)
        getPackageChildren(packageId).forEach { captureSyncSetting(it.skillId) }
    }

    @Query("SELECT * FROM user_settings WHERE kind = 'skills' AND `key` = :skillId LIMIT 1")
    suspend fun getSyncSetting(skillId: String): UserSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeSyncSetting(setting: UserSettingEntity)

    /** 普通拉取仍用原始 upsert/delete，不经过本地修改入口，避免同步回环。 */
    @Transaction
    suspend fun captureSyncSetting(skillId: String, deleted: Boolean = false) {
        val old = getSyncSetting(skillId)
        val skill = getInstallation(skillId)
        if (!deleted && skill == null) return
        val fields = if (deleted) emptyMap() else syncSkillValue(requireNotNull(skill)).toMutableMap().apply {
            val previous = old?.value?.let { runCatching { com.android.everytalk.data.sync.syncJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            if (previous?.get("contentHash") == get("contentHash")) listOf("fileRefs", "fileManifest", "manifestRef").forEach { name -> previous?.get(name)?.let { put(name, it) } }
        }
        val value = kotlinx.serialization.json.JsonObject(fields).toString()
        if (old?.deleted == deleted && old.value == value) return
        writeSyncSetting(UserSettingEntity(
            kind = "skills", key = skillId, value = value,
            uuid = old?.uuid ?: localSyncUuid("user_settings", "skills:$skillId"),
            updatedAt = maxOf(if (deleted) System.currentTimeMillis() else requireNotNull(skill).updatedAt, (old?.updatedAt ?: 0L) + 1),
            deleted = deleted, syncStatus = SyncStatus.PENDING,
        ))
    }

    @Transaction
    suspend fun deleteLocally(skillId: String) {
        captureSyncSetting(skillId, deleted = true)
        delete(skillId)
    }

    @Transaction
    suspend fun deletePackageLocally(packageId: String) {
        getPackageChildren(packageId).forEach { captureSyncSetting(it.skillId, deleted = true) }
        deletePackage(packageId)
    }

    @Query("UPDATE skill_installations SET updateHash = :updateHash WHERE skillId = :skillId")
    suspend fun setUpdateHash(skillId: String, updateHash: String?)

    @Query("UPDATE skill_installations SET updateHash = :updateHash WHERE packageId = :packageId")
    suspend fun setPackageUpdateHash(packageId: String, updateHash: String?)

    @Query("UPDATE skill_installations SET lastUsedAt = :usedAt, useCount = useCount + 1 WHERE skillId = :skillId")
    suspend fun recordUse(skillId: String, usedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM skill_installations WHERE skillId = :skillId")
    suspend fun delete(skillId: String)

    @Query("DELETE FROM skill_installations WHERE packageId = :packageId")
    suspend fun deletePackage(packageId: String)

    @Query("DELETE FROM skill_installations WHERE skillId IN (:skillIds)")
    suspend fun deleteSkills(skillIds: List<String>)

    /** 安装记录必须先存在，版本表的外键才允许写入。 */
    @Transaction
    suspend fun saveVersion(
        installation: SkillInstallationEntity,
        version: SkillVersionEntity,
    ) {
        upsertInstallation(installation)
        upsertVersion(version)
        captureSyncSetting(installation.skillId)
    }

    /** 所有子 Skill 在同一个 Room 事务中切换，禁止出现半包新旧混用。 */
    @Transaction
    suspend fun replacePackage(
        packageId: String,
        installations: List<SkillInstallationEntity>,
        versions: List<SkillVersionEntity>,
    ) {
        val removed = getPackageChildren(packageId).map(SkillInstallationEntity::skillId) -
            installations.map(SkillInstallationEntity::skillId).toSet()
        upsertInstallations(installations)
        upsertVersions(versions)
        installations.forEach { captureSyncSetting(it.skillId) }
        removed.forEach { captureSyncSetting(it, deleted = true) }
        if (removed.isNotEmpty()) deleteSkills(removed)
    }
}

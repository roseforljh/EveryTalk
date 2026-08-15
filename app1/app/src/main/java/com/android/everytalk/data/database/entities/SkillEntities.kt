package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "skill_installations",
    indices = [Index("enabled"), Index("currentHash")],
    primaryKeys = ["skillId"],
)
data class SkillInstallationEntity(
    val skillId: String,
    val name: String,
    val description: String,
    val sourceType: String,
    val sourceRepository: String?,
    val sourcePath: String?,
    val currentHash: String,
    val enabled: Boolean,
    val invocationMode: String,
    val auditStatus: String,
    val updateHash: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val useCount: Long,
)

@Entity(
    tableName = "skill_versions",
    primaryKeys = ["skillId", "contentHash"],
    foreignKeys = [
        ForeignKey(
            entity = SkillInstallationEntity::class,
            parentColumns = ["skillId"],
            childColumns = ["skillId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("skillId")],
)
data class SkillVersionEntity(
    val skillId: String,
    val contentHash: String,
    val versionLabel: String?,
    val rootPath: String,
    val manifestJson: String,
    val frontmatterJson: String,
    val auditJson: String?,
    val installedAt: Long,
)

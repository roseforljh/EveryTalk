package com.android.everytalk.data.skill

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object SkillToolNames {
    const val LOAD_SKILL = "load_skill"
    const val READ_SKILL_FILE = "read_skill_file"

    val all: Set<String> = setOf(LOAD_SKILL, READ_SKILL_FILE)
}

@Serializable
enum class SkillSourceType {
    REMOTE,
    LOCAL_IMPORT,
    USER_CREATED,
}

@Serializable
enum class SkillInvocationMode {
    AUTO,
    MANUAL_ONLY,
}

@Serializable
enum class SkillAuditStatus {
    PASS,
    WARN,
    FAIL,
    UNVERIFIED,
}

/**
 * 单个 Skill 版本的文件清单项。
 * 路径始终是相对 Skill 根目录的正斜杠路径，禁止保存绝对路径。
 */
@Serializable
data class SkillFileManifestEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val text: Boolean,
)

/** skills.sh 公开目录中的轻量条目。完整规则只在用户确认安装后从来源仓库读取。 */
@Serializable
data class RemoteSkillCatalogItem(
    val source: String,
    val skillId: String,
    val name: String,
    val installs: Long = 0,
    val weeklyInstalls: List<Long> = emptyList(),
    val isOfficial: Boolean = false,
    val change: Long? = null,
) {
    val githubRepository: String?
        get() = source.takeIf(::isSafeGithubRepository)?.let { "https://github.com/$it" }
}

private fun isSafeGithubRepository(value: String): Boolean {
    val parts = value.split('/')
    return parts.size == 2 && parts.all { part ->
        part.isNotBlank() && part != "." && part != ".." &&
            part.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}

@Serializable
internal data class RemoteSkillCatalogResponse(
    val skills: List<RemoteSkillCatalogItem> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
)

/** 转发服务返回的稳定详情，文件哈希与 Android 本地安装校验使用同一算法。 */
@Serializable
data class RemoteSkillDetail(
    val id: String,
    val source: String,
    val skillId: String,
    val name: String,
    val description: String,
    val sourceRepository: String,
    val sourcePath: String,
    val contentHash: String,
    val files: List<SkillFileManifestEntry> = emptyList(),
    val auditStatus: SkillAuditStatus = SkillAuditStatus.UNVERIFIED,
    val audit: JsonElement? = null,
    val updatedAt: String? = null,
)

@Serializable
data class RemoteSkillHash(
    val id: String,
    val contentHash: String,
    val updatedAt: String? = null,
    val auditStatus: SkillAuditStatus = SkillAuditStatus.UNVERIFIED,
)

data class SkillFileDiff(
    val added: List<String>,
    val modified: List<String>,
    val removed: List<String>,
)

/** 每次发送消息时冻结的 Skill 目录项。 */
@Serializable
data class SkillSnapshotEntry(
    val skillId: String,
    val name: String,
    val description: String,
    val sourceType: SkillSourceType,
    val sourceRepository: String? = null,
    val sourcePath: String? = null,
    val contentHash: String,
    val invocationMode: SkillInvocationMode,
)

/** 用户消息中一次性的 Skill 引用。 */
@Serializable
data class MessageSkillReference(
    val skillId: String,
    val displayName: String,
    val sourceType: SkillSourceType,
    val sourceRepository: String? = null,
    val sourcePath: String? = null,
    val contentHash: String,
)

/**
 * Run 恢复时必须沿用这份快照，禁止重新读取当前启停状态。
 */
@Serializable
data class SkillRequestSnapshot(
    val automaticCatalog: List<SkillSnapshotEntry> = emptyList(),
    val manualReferences: List<MessageSkillReference> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun allowedVersion(skillId: String, contentHash: String): SkillSnapshotEntry? {
        automaticCatalog.firstOrNull {
            it.skillId == skillId && it.contentHash == contentHash
        }?.let { return it }
        return manualReferences.firstOrNull {
            it.skillId == skillId && it.contentHash == contentHash
        }?.let { reference ->
            SkillSnapshotEntry(
                skillId = reference.skillId,
                name = reference.displayName,
                description = "用户手动指定的 Skill",
                sourceType = reference.sourceType,
                sourceRepository = reference.sourceRepository,
                sourcePath = reference.sourcePath,
                contentHash = reference.contentHash,
                invocationMode = SkillInvocationMode.MANUAL_ONLY,
            )
        }
    }

    fun renderCatalog(): String {
        if (automaticCatalog.isEmpty() && manualReferences.isEmpty()) {
            return "<skill_catalog empty=\"true\" />"
        }
        val automatic = automaticCatalog.joinToString("\n") { entry ->
            """  <skill id="${entry.skillId.xmlEscape()}" name="${entry.name.xmlEscape()}" source="${(entry.sourceRepository ?: entry.sourceType.name).xmlEscape()}" content_hash="${entry.contentHash}" invocation_mode="auto">${entry.description.xmlEscape()}</skill>"""
        }
        val manual = manualReferences.joinToString("\n") { entry ->
            """  <required_skill id="${entry.skillId.xmlEscape()}" name="${entry.displayName.xmlEscape()}" content_hash="${entry.contentHash}" />"""
        }
        return buildString {
            appendLine("<skill_catalog>")
            listOf(automatic, manual).filter(String::isNotBlank).forEach(::appendLine)
            append("</skill_catalog>")
        }
    }
}

private fun String.xmlEscape(): String = buildString(length) {
    this@xmlEscape.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char
            },
        )
    }
}

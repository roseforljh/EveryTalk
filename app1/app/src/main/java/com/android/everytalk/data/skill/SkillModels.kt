package com.android.everytalk.data.skill

import kotlinx.serialization.Serializable

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

/** 云目录按来源仓库合并后的下载单位。 */
data class RemoteSkillPackageCatalogItem(
    val source: String,
    val name: String,
    val matchedSkills: List<RemoteSkillCatalogItem>,
) {
    val installs: Long = matchedSkills.maxOfOrNull(RemoteSkillCatalogItem::installs) ?: 0
    val isOfficial: Boolean = matchedSkills.any(RemoteSkillCatalogItem::isOfficial)
    val matchedSkillNames: List<String> = matchedSkills.map(RemoteSkillCatalogItem::name).distinct()
    val packageId: String = "remote:$source"
    val githubRepository: String? = source.takeIf(::isSafeGithubRepository)?.let { "https://github.com/$it" }
}

/** skills.sh 榜单的一页。页面层根据 hasMore 在滚动到底部时继续读取。 */
data class RemoteSkillCatalogPage(
    val skills: List<RemoteSkillCatalogItem>,
    val page: Int,
    val total: Int,
    val pageSize: Int,
    val hasMore: Boolean,
)

/** 返回当前页前后需要静默缓存的页码，不重复请求当前正在显示的页面。 */
internal fun catalogPrefetchPages(currentPage: Int, maxPage: Int, radius: Int = 3): List<Int> {
    if (currentPage <= 0 || maxPage <= 0 || radius <= 0) return emptyList()
    return (maxOf(1, currentPage - radius)..minOf(maxPage, currentPage + radius))
        .filter { it != currentPage }
}

/** 云目录每个独立 Skill 都显示，但下载仍按来源仓库整包安装。 */
fun RemoteSkillCatalogItem.toRemotePackageCatalogItem(): RemoteSkillPackageCatalogItem =
    RemoteSkillPackageCatalogItem(
        source = source,
        name = source.substringAfterLast('/').replaceFirstChar(Char::uppercaseChar),
        matchedSkills = listOf(this),
    )

enum class RemoteSkillInstallStage {
    DOWNLOADING,
    INSTALLING,
}

/** 下载阶段的字节进度和安装阶段的文件进度共用同一份轻量状态。 */
data class RemoteSkillInstallProgress(
    val stage: RemoteSkillInstallStage,
    val completed: Long,
    val total: Long,
)

/** GitHub Tree 中一个待下载文件，repositoryPath 是仓库内路径。 */
data class RemoteSkillPackageFile(
    val path: String,
    val repositoryPath: String,
    val size: Long,
)

/** 一个仓库包内可独立调用的子 Skill。 */
data class RemoteSkillPackageChild(
    val name: String,
    val description: String,
    val sourcePath: String,
    val invocationMode: SkillInvocationMode,
    val files: List<RemoteSkillPackageFile>,
)

/** GitHub 默认分支在一次 Tree 快照下生成的完整 Skill 包。 */
data class RemoteSkillPackageDetail(
    val packageId: String,
    val name: String,
    val source: String,
    val sourceRepository: String,
    val branch: String,
    val contentHash: String,
    val skills: List<RemoteSkillPackageChild>,
)

/** 设置页使用的包视图，数据库仍按子 Skill 保存不可变版本。 */
data class InstalledSkillPackage(
    val packageId: String,
    val name: String,
    val sourceType: SkillSourceType,
    val sourceRepository: String?,
    val enabled: Boolean,
    val updateHash: String?,
    val children: List<com.android.everytalk.data.database.entities.SkillInstallationEntity>,
)

fun List<com.android.everytalk.data.database.entities.SkillInstallationEntity>.toInstalledSkillPackages(): List<InstalledSkillPackage> =
    groupBy { it.effectivePackageId() }
        .map { (packageId, children) ->
            val first = children.first()
            InstalledSkillPackage(
                packageId = packageId,
                name = first.effectivePackageName(),
                sourceType = runCatching { SkillSourceType.valueOf(first.sourceType) }.getOrDefault(SkillSourceType.LOCAL_IMPORT),
                sourceRepository = first.sourceRepository,
                enabled = children.all { it.enabled },
                updateHash = children.firstNotNullOfOrNull { it.updateHash },
                children = children.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.name.lowercase() }

fun com.android.everytalk.data.database.entities.SkillInstallationEntity.effectivePackageId(): String =
    packageId.ifBlank {
        sourceRepository?.removePrefix("https://github.com/")?.let { "remote:$it" } ?: skillId
    }

fun com.android.everytalk.data.database.entities.SkillInstallationEntity.effectivePackageName(): String =
    packageName.ifBlank { sourceRepository?.trimEnd('/')?.substringAfterLast('/') ?: name }.let { stored ->
        if (sourceType == SkillSourceType.REMOTE.name) stored.substringAfterLast('/') else stored
    }

fun groupRemoteSkillPackages(items: List<RemoteSkillCatalogItem>): List<RemoteSkillPackageCatalogItem> =
    items.groupBy(RemoteSkillCatalogItem::source)
        .map { (source, children) ->
            RemoteSkillPackageCatalogItem(
                source = source,
                name = source.substringAfterLast('/').replaceFirstChar(Char::uppercaseChar),
                matchedSkills = children.sortedBy { it.name.lowercase() },
            )
        }
        .sortedByDescending(RemoteSkillPackageCatalogItem::installs)

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
    val packageName: String = "",
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
                packageName = "用户手动指定",
            )
        }
    }

    fun renderCatalog(): String {
        if (automaticCatalog.isEmpty() && manualReferences.isEmpty()) {
            return "<skill_catalog empty=\"true\" />"
        }
        val automatic = automaticCatalog
            .groupBy { it.packageName.ifBlank { it.sourceRepository ?: "我的 Skill" } }
            .entries
            .joinToString("\n") { (packageName, entries) ->
                buildString {
                    appendLine("  <skill_package name=\"${packageName.xmlEscape()}\">")
                    entries.forEach { entry ->
                        appendLine("""    <skill id="${entry.skillId.xmlEscape()}" name="${entry.name.xmlEscape()}" source="${(entry.sourceRepository ?: entry.sourceType.name).xmlEscape()}" content_hash="${entry.contentHash}" invocation_mode="auto">${entry.description.xmlEscape()}</skill>""")
                    }
                    append("  </skill_package>")
                }
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

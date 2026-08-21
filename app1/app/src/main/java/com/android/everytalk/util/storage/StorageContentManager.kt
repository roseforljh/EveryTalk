package com.android.everytalk.util.storage

import android.content.Context
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.skill.SkillRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 明细页中一行数据的来源。 */
enum class StorageEntryKind {
    CONVERSATION,
    DATABASE_FILE,
    ATTACHMENT_FILE,
    SKILL,
    TOOL_ARCHIVE,
    TEMPORARY_FILE,
    OTHER_FILE,
    SYSTEM_REMAINDER,
}

/** 明细页分区。会话内容是数据库内部来源，不与数据库物理文件重复相加。 */
enum class StorageEntrySection {
    CONTENT_SOURCES,
    DISK_FILES,
}

/**
 * 一条真实空间明细。
 *
 * [bytes] 对文件表示真实文件大小，对会话表示 SQLite 已保存字段的内容字节估算。
 * [count] 对会话表示消息数，对工具归档表示文件数。
 */
data class StorageContentEntry(
    val id: String,
    val type: StorageDetailType,
    val kind: StorageEntryKind,
    val section: StorageEntrySection,
    val title: String,
    val bytes: Long,
    val updatedAt: Long? = null,
    val count: Int = 0,
    val selectable: Boolean = false,
    val isImageConversation: Boolean = false,
    internal val absolutePath: String? = null,
)

data class StorageCategoryContent(
    val type: StorageDetailType,
    val totalBytes: Long,
    val entries: List<StorageContentEntry>,
)

/**
 * 扫描分类内部的真实文件和内容来源。
 *
 * 所有扫描都限制在 App 私有目录。逐项删除会再次校验目标路径，防止错误路径越界。
 */
class StorageContentManager(context: Context) {
    private val appContext = context.applicationContext
    private val dataRoot = appContext.dataDir

    suspend fun scan(detail: StorageDetail): StorageCategoryContent = withContext(Dispatchers.IO) {
        val entries = when (detail.type) {
            StorageDetailType.CONVERSATIONS -> scanConversations(detail.bytes)
            StorageDetailType.ATTACHMENTS -> scanFiles(
                type = detail.type,
                roots = attachmentRoots(),
                kind = StorageEntryKind.ATTACHMENT_FILE,
            )
            StorageDetailType.SKILLS -> scanSkills(detail.bytes)
            StorageDetailType.TOOL_RESULTS -> scanToolArchives()
            StorageDetailType.TEMPORARY_FILES -> scanFiles(
                type = detail.type,
                roots = temporaryRoots(),
                kind = StorageEntryKind.TEMPORARY_FILE,
            )
            StorageDetailType.OTHER_DATA -> scanOtherData(detail.bytes)
        }
        StorageCategoryContent(detail.type, detail.bytes, entries)
    }

    /** 删除用户勾选的文件明细，并返回实际释放的字节数。 */
    suspend fun deleteFiles(type: StorageDetailType, entries: List<StorageContentEntry>): Long =
        withContext(Dispatchers.IO) {
            val allowedRoots = when (type) {
                StorageDetailType.ATTACHMENTS -> attachmentRoots()
                StorageDetailType.TOOL_RESULTS -> toolResultRoots()
                StorageDetailType.TEMPORARY_FILES -> temporaryRoots()
                else -> emptyList()
            }.mapNotNull { root -> runCatching { root.canonicalFile }.getOrNull() }

            val targets = entries
                .asSequence()
                .filter { it.type == type && it.selectable }
                .mapNotNull(StorageContentEntry::absolutePath)
                .map(::File)
                .mapNotNull { target -> safeChildOf(target, allowedRoots) }
                .distinctBy(File::getPath)
                .toList()

            val before = targets.sumOf(::directorySize)
            targets.forEach { target -> runCatching { target.deleteRecursively() } }
            (before - targets.sumOf(::directorySize)).coerceAtLeast(0L)
        }

    private fun scanConversations(totalBytes: Long): List<StorageContentEntry> {
        val databaseDirectory = File(dataRoot, "databases")
        val physicalFiles = databaseDirectory.listFiles().orEmpty()
            .filter(File::isFile)
            .map { file ->
                StorageContentEntry(
                    id = "database:${file.name}",
                    type = StorageDetailType.CONVERSATIONS,
                    kind = StorageEntryKind.DATABASE_FILE,
                    section = StorageEntrySection.DISK_FILES,
                    title = file.name,
                    bytes = file.length(),
                    updatedAt = file.lastModified().takeIf { it > 0L },
                )
            }
            .sortedByDescending(StorageContentEntry::bytes)

        val conversations = queryConversationContent()
        return conversations + reconcilePhysicalTotal(
            type = StorageDetailType.CONVERSATIONS,
            totalBytes = totalBytes,
            entries = physicalFiles,
        )
    }

    /**
     * 直接让 SQLite 计算每个会话已保存文本字段的字节数。
     * 这里不加载大型 JSON，避免打开数据管理页时制造第二份大对象。
     */
    private fun queryConversationContent(): List<StorageContentEntry> = runCatching {
        val database = AppDatabase.getDatabase(appContext).openHelper.readableDatabase
        val storedColumns = listOf(
            "text", "contentParts", "reasoning", "webSearchResults", "currentWebSearchStage",
            "imageUrls", "attachments", "parts", "executionStatus", "executionSteps",
            "executionTrace", "enabledToolIds", "computerIdSnapshot", "workspaceIdSnapshot",
            "modelName", "providerName", "tokenUsage", "contextUsageSnapshot", "contextCompressionState",
        )
        val byteExpression = storedColumns.joinToString(" + ") { column ->
            "COALESCE(LENGTH(CAST(m.$column AS BLOB)), 0)"
        }
        val sql = """
            SELECT s.id, COALESCE(s.title, ''), s.lastModifiedTimestamp, s.isImageGeneration,
                   COUNT(m.id), COALESCE(SUM($byteExpression + 96), 0)
            FROM chat_sessions s
            LEFT JOIN messages m ON m.sessionId = s.id
            GROUP BY s.id, s.title, s.lastModifiedTimestamp, s.isImageGeneration
        """.trimIndent()

        database.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StorageContentEntry(
                            id = cursor.getString(0),
                            type = StorageDetailType.CONVERSATIONS,
                            kind = StorageEntryKind.CONVERSATION,
                            section = StorageEntrySection.CONTENT_SOURCES,
                            title = cursor.getString(1),
                            updatedAt = cursor.getLong(2).takeIf { it > 0L },
                            isImageConversation = cursor.getInt(3) != 0,
                            count = cursor.getInt(4),
                            bytes = cursor.getLong(5).coerceAtLeast(0L),
                            selectable = true,
                        ),
                    )
                }
            }.sortedByDescending(StorageContentEntry::bytes)
        }
    }.getOrDefault(emptyList())

    private suspend fun scanSkills(totalBytes: Long): List<StorageContentEntry> {
        val root = File(appContext.filesDir, "skills")
        val installations = SkillRepository(appContext).getAll()
        val registeredDirectories = mutableSetOf<String>()
        val registered = installations.map { skill ->
            val directory = File(root, skill.skillId.directoryKey())
            registeredDirectories += directory.canonicalOrAbsolutePath()
            StorageContentEntry(
                id = skill.skillId,
                type = StorageDetailType.SKILLS,
                kind = StorageEntryKind.SKILL,
                section = StorageEntrySection.DISK_FILES,
                title = skill.name,
                bytes = directorySize(directory),
                updatedAt = skill.updatedAt.takeIf { it > 0L },
                count = skill.useCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                selectable = true,
            )
        }
        val unregistered = root.listFiles().orEmpty()
            .filter { it.canonicalOrAbsolutePath() !in registeredDirectories }
            .map { file ->
                StorageContentEntry(
                    id = "unregistered:${file.name}",
                    type = StorageDetailType.SKILLS,
                    kind = StorageEntryKind.OTHER_FILE,
                    section = StorageEntrySection.DISK_FILES,
                    title = file.name,
                    bytes = directorySize(file),
                    updatedAt = newestModifiedAt(file),
                )
            }
        return reconcilePhysicalTotal(
            type = StorageDetailType.SKILLS,
            totalBytes = totalBytes,
            entries = (registered + unregistered).sortedByDescending(StorageContentEntry::bytes),
        )
    }

    private fun scanToolArchives(): List<StorageContentEntry> {
        val root = toolResultRoots().first()
        return root.listFiles().orEmpty().map { archive ->
            StorageContentEntry(
                id = archive.name,
                type = StorageDetailType.TOOL_RESULTS,
                kind = StorageEntryKind.TOOL_ARCHIVE,
                section = StorageEntrySection.DISK_FILES,
                title = archive.name.take(12),
                bytes = directorySize(archive),
                updatedAt = newestModifiedAt(archive),
                count = archive.walkTopDown().count(File::isFile),
                selectable = true,
                absolutePath = archive.absolutePath,
            )
        }.sortedByDescending(StorageContentEntry::bytes)
    }

    private fun scanFiles(
        type: StorageDetailType,
        roots: List<File>,
        kind: StorageEntryKind,
    ): List<StorageContentEntry> = roots.flatMap { root ->
        if (!root.exists()) return@flatMap emptyList()
        root.walkTopDown().filter(File::isFile).map { file ->
            StorageContentEntry(
                id = "${root.name}/${file.relativeTo(root).invariantSeparatorsPath}",
                type = type,
                kind = kind,
                section = StorageEntrySection.DISK_FILES,
                title = file.name,
                bytes = file.length(),
                updatedAt = file.lastModified().takeIf { it > 0L },
                selectable = true,
                absolutePath = file.absolutePath,
            )
        }.toList()
    }.sortedByDescending(StorageContentEntry::bytes)

    private fun scanOtherData(totalBytes: Long): List<StorageContentEntry> {
        val knownFileDirectories = setOf(
            FileManager.CHAT_ATTACHMENTS_DIR,
            "chat_images",
            "chat_images_temp",
            "skills",
            "agent_tool_results",
        )
        val entries = dataRoot.listFiles().orEmpty().flatMap { topLevel ->
            when (topLevel.name) {
                "cache", "code_cache", "databases" -> emptyList()
                "files" -> topLevel.listFiles().orEmpty()
                    .filterNot { it.name in knownFileDirectories }
                else -> listOf(topLevel)
            }
        }.map { file ->
            StorageContentEntry(
                id = "other:${file.relativeTo(dataRoot).invariantSeparatorsPath}",
                type = StorageDetailType.OTHER_DATA,
                kind = StorageEntryKind.OTHER_FILE,
                section = StorageEntrySection.DISK_FILES,
                title = file.relativeTo(dataRoot).invariantSeparatorsPath,
                bytes = directorySize(file),
                updatedAt = newestModifiedAt(file),
            )
        }.sortedByDescending(StorageContentEntry::bytes)
        return reconcilePhysicalTotal(StorageDetailType.OTHER_DATA, totalBytes, entries)
    }

    private fun reconcilePhysicalTotal(
        type: StorageDetailType,
        totalBytes: Long,
        entries: List<StorageContentEntry>,
    ): List<StorageContentEntry> {
        val remainder = (totalBytes - entries.sumOf(StorageContentEntry::bytes)).coerceAtLeast(0L)
        if (remainder == 0L) return entries
        return entries + StorageContentEntry(
            id = "system-remainder:${type.name}",
            type = type,
            kind = StorageEntryKind.SYSTEM_REMAINDER,
            section = StorageEntrySection.DISK_FILES,
            title = "",
            bytes = remainder,
        )
    }

    private fun attachmentRoots(): List<File> = listOf(
        File(appContext.filesDir, FileManager.CHAT_ATTACHMENTS_DIR),
        File(appContext.filesDir, "chat_images"),
    )

    private fun temporaryRoots(): List<File> = listOf(File(appContext.filesDir, "chat_images_temp"))

    private fun toolResultRoots(): List<File> = listOf(File(appContext.filesDir, "agent_tool_results"))
}

/** 目标必须是允许目录中的子项，目录本身永远不能被逐项删除。 */
internal fun safeChildOf(target: File, allowedRoots: List<File>): File? {
    val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return null
    val targetPath = canonicalTarget.toPath()
    val allowed = allowedRoots.any { root ->
        val rootPath = root.toPath()
        targetPath != rootPath && targetPath.startsWith(rootPath)
    }
    return canonicalTarget.takeIf { allowed }
}

private fun newestModifiedAt(file: File): Long? {
    if (!file.exists()) return null
    val value = if (file.isFile) file.lastModified() else {
        file.walkTopDown().maxOfOrNull(File::lastModified) ?: file.lastModified()
    }
    return value.takeIf { it > 0L }
}

private fun File.canonicalOrAbsolutePath(): String =
    runCatching { canonicalPath }.getOrElse { absolutePath }

private fun String.directoryKey(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

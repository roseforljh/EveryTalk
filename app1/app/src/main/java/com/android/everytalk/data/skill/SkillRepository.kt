package com.android.everytalk.data.skill

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.daos.SkillDao
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.database.entities.SkillVersionEntity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class SkillRepository(
    context: Context,
    private val dao: SkillDao = AppDatabase.getDatabase(context.applicationContext).skillDao(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "skills")

    fun observeAll(): Flow<List<SkillInstallationEntity>> = dao.observeAll()

    suspend fun getAll(): List<SkillInstallationEntity> = dao.getAll()

    suspend fun get(skillId: String): SkillInstallationEntity? = dao.getInstallation(skillId)

    suspend fun setEnabled(skillId: String, enabled: Boolean) = dao.setEnabled(skillId, enabled)

    suspend fun delete(skillId: String) {
        dao.delete(skillId)
        File(root, skillId.directoryKey()).deleteRecursively()
    }

    /**
     * 用户创建 Skill 的最小入口。附带文件后续仍通过同一导入逻辑生成新哈希版本。
     */
    suspend fun create(name: String, description: String, rules: String): SkillInstallationEntity {
        require(name.isNotBlank()) { "Skill 名称不能为空" }
        require(description.isNotBlank()) { "Skill 用途说明不能为空" }
        require(rules.isNotBlank()) { "Skill 具体规则不能为空" }
        val temporary = File(root, ".create-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            File(temporary, "SKILL.md").writeText(
                """---
name: ${name.trim()}
description: ${description.trim()}
---

# ${name.trim()}

${rules.trim()}
""",
                Charsets.UTF_8,
            )
            importDirectory(temporary, SkillSourceType.USER_CREATED)
        } finally {
            temporary.deleteRecursively()
        }
    }

    suspend fun importDirectory(
        sourceRoot: File,
        sourceType: SkillSourceType = SkillSourceType.LOCAL_IMPORT,
        sourceRepository: String? = null,
        sourcePath: String? = null,
        auditStatus: SkillAuditStatus = SkillAuditStatus.UNVERIFIED,
        versionLabel: String? = null,
        auditJson: String? = null,
    ): SkillInstallationEntity {
        val validated = SkillPackageValidator.validate(sourceRoot)
        require(auditStatus != SkillAuditStatus.FAIL) { "安全审计失败的 Skill 禁止安装" }
        val skillId = if (sourceType == SkillSourceType.REMOTE) {
            require(!sourceRepository.isNullOrBlank() && !sourcePath.isNullOrBlank()) { "远端 Skill 缺少来源" }
            "remote:${sourceRepository.trim().trimEnd('/')}#${sourcePath.trim().trim('/')}"
        } else {
            "local:${UUID.randomUUID()}"
        }
        return installValidatedVersion(
            skillId = skillId,
            sourceRoot = sourceRoot,
            validated = validated,
            sourceType = sourceType,
            sourceRepository = sourceRepository,
            sourcePath = sourcePath,
            auditStatus = auditStatus,
            versionLabel = versionLabel,
            auditJson = auditJson,
        )
    }

    /** 新版本先写入不可变目录，校验成功后再一次性切换数据库指针。 */
    private suspend fun installValidatedVersion(
        skillId: String,
        sourceRoot: File,
        validated: ValidatedSkillPackage,
        sourceType: SkillSourceType,
        sourceRepository: String?,
        sourcePath: String?,
        auditStatus: SkillAuditStatus,
        versionLabel: String?,
        auditJson: String?,
    ): SkillInstallationEntity {
        val versionRoot = File(File(root, skillId.directoryKey()), validated.contentHash)
        if (!versionRoot.exists()) {
            val staging = File(root, ".install-${UUID.randomUUID()}")
            try {
                sourceRoot.copyRecursively(staging, overwrite = false)
                require(SkillPackageValidator.validate(staging).contentHash == validated.contentHash) {
                    "Skill 复制后哈希不一致"
                }
                versionRoot.parentFile?.mkdirs()
                require(staging.renameTo(versionRoot)) { "Skill 安装目录写入失败" }
            } finally {
                staging.deleteRecursively()
            }
        }
        val now = System.currentTimeMillis()
        val previous = dao.getInstallation(skillId)
        val installation = SkillInstallationEntity(
            skillId = skillId,
            name = validated.name,
            description = validated.description,
            sourceType = sourceType.name,
            sourceRepository = sourceRepository,
            sourcePath = sourcePath,
            currentHash = validated.contentHash,
            enabled = previous?.enabled ?: when {
                sourceType == SkillSourceType.USER_CREATED -> true
                sourceType == SkillSourceType.LOCAL_IMPORT -> true
                auditStatus == SkillAuditStatus.PASS -> true
                else -> false
            },
            invocationMode = validated.invocationMode.name,
            auditStatus = auditStatus.name,
            updateHash = null,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = previous?.lastUsedAt,
            useCount = previous?.useCount ?: 0,
        )
        dao.saveVersion(
            installation = installation,
            version = SkillVersionEntity(
                skillId = skillId,
                contentHash = validated.contentHash,
                versionLabel = versionLabel,
                rootPath = versionRoot.absolutePath,
                manifestJson = json.encodeToString(ListSerializer(SkillFileManifestEntry.serializer()), validated.manifest),
                frontmatterJson = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    validated.frontmatter,
                ),
                auditJson = auditJson,
                installedAt = now,
            ),
        )
        return installation
    }

    /**
     * 从完整 GitHub 仓库 ZIP 中只提取用户选择的 Skill。
     * 仓库内其他文件不会进入安装目录，也不会占用单个 Skill 的文件额度。
     */
    suspend fun importRemoteArchive(
        input: InputStream,
        sourceRepository: String,
        skillName: String,
        auditStatus: SkillAuditStatus = SkillAuditStatus.UNVERIFIED,
        versionLabel: String? = null,
        auditJson: String? = null,
    ): SkillInstallationEntity {
        require(skillName.isNotBlank()) { "远端 Skill 名称不能为空" }
        root.mkdirs()
        val archive = File(root, ".remote-${UUID.randomUUID()}.zip")
        val temporary = File(root, ".remote-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            var archiveBytes = 0L
            archive.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    archiveBytes += read
                    require(archiveBytes <= MAX_SKILL_BYTES) { "Skill 仓库压缩包超过 100 MB" }
                    output.write(buffer, 0, read)
                }
            }

            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                entries.forEach { validateArchiveEntryName(it.name) }
                val skillFile = chooseRemoteSkillFile(zip, entries, skillName)
                val selectedRoot = skillFile.name.substringBeforeLast('/', "")
                val prefix = selectedRoot.takeIf(String::isNotBlank)?.plus('/') ?: ""
                var fileCount = 0
                var totalBytes = 0L

                entries.filter { !it.isDirectory && it.name.startsWith(prefix) }.forEach { entry ->
                    val relative = entry.name.removePrefix(prefix)
                    if ('/' !in relative && relative != "SKILL.md" && selectedRoot.isBlank()) return@forEach
                    require(relative.isNotBlank()) { "Skill 文件路径无效" }
                    fileCount++
                    require(fileCount <= MAX_SKILL_FILES) { "Skill 文件数超过 $MAX_SKILL_FILES" }
                    val target = File(temporary, relative).canonicalFile
                    require(target.toPath().startsWith(temporary.canonicalFile.toPath())) { "Skill 文件路径越界" }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { entryInput ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = entryInput.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_SKILL_BYTES) { "Skill 解压后超过 100 MB" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }

                val sourcePath = selectedRoot.substringAfter('/', missingDelimiterValue = ".").ifBlank { "." }
                importDirectory(
                    sourceRoot = temporary,
                    sourceType = SkillSourceType.REMOTE,
                    sourceRepository = sourceRepository,
                    sourcePath = sourcePath,
                    auditStatus = auditStatus,
                    versionLabel = versionLabel,
                    auditJson = auditJson,
                )
            }
        } finally {
            archive.delete()
            temporary.deleteRecursively()
        }
    }

    /** 从系统文件选择器导入 ZIP。解压阶段只写普通文件，不执行任何内容。 */
    suspend fun importZip(input: InputStream): SkillInstallationEntity {
        val temporary = File(root, ".zip-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            var fileCount = 0
            var totalBytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalizedName = entry.name.replace('\\', '/')
                    require(
                        normalizedName.isNotBlank() &&
                            !normalizedName.startsWith('/') &&
                            normalizedName.split('/').none { it == ".." },
                    ) { "ZIP 包含非法路径" }
                    val target = File(temporary, normalizedName).canonicalFile
                    require(target.toPath().startsWith(temporary.canonicalFile.toPath())) { "ZIP 文件路径越界" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        fileCount++
                        require(fileCount <= MAX_SKILL_FILES) { "Skill 文件数超过 $MAX_SKILL_FILES" }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_SKILL_BYTES) { "Skill 解压后超过 100 MB" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            importDirectory(resolveImportedRoot(temporary))
        } finally {
            temporary.deleteRecursively()
        }
    }

    /** 从 Android 文档树复制目录。Provider 的特殊对象只会被复制成普通文件。 */
    suspend fun importDocumentTree(treeUri: Uri): SkillInstallationEntity {
        val temporary = File(root, ".tree-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            var fileCount = 0
            var totalBytes = 0L
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

            fun copyChildren(documentId: String, destination: File) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idColumn)
                        val name = cursor.getString(nameColumn)
                        require(name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\\' !in name) {
                            "目录包含非法文件名"
                        }
                        val target = File(destination, name)
                        if (cursor.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            target.mkdirs()
                            copyChildren(childId, target)
                        } else {
                            fileCount++
                            require(fileCount <= MAX_SKILL_FILES) { "Skill 文件数超过 $MAX_SKILL_FILES" }
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            target.parentFile?.mkdirs()
                            appContext.contentResolver.openInputStream(documentUri)?.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        totalBytes += read
                                        require(totalBytes <= MAX_SKILL_BYTES) { "Skill 目录超过 100 MB" }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            } ?: error("无法读取文件：$name")
                        }
                    }
                } ?: error("无法读取所选目录")
            }

            copyChildren(rootDocumentId, temporary)
            importDirectory(resolveImportedRoot(temporary))
        } finally {
            temporary.deleteRecursively()
        }
    }

    suspend fun createSnapshot(
        manualReferences: List<MessageSkillReference> = emptyList(),
    ): SkillRequestSnapshot {
        val enabled = dao.getEnabled()
        val automatic = enabled.mapNotNull { entity ->
            val mode = enumValueOrNull<SkillInvocationMode>(entity.invocationMode) ?: return@mapNotNull null
            if (mode == SkillInvocationMode.MANUAL_ONLY) return@mapNotNull null
            entity.toSnapshotEntry(mode)
        }
        val validManual = manualReferences.distinctBy(MessageSkillReference::skillId).filter { reference ->
            enabled.any { it.skillId == reference.skillId && it.currentHash == reference.contentHash }
        }
        return SkillRequestSnapshot(automaticCatalog = automatic, manualReferences = validManual)
    }

    suspend fun readSkillMarkdown(skillId: String, contentHash: String): String =
        readTextFile(skillId, contentHash, "SKILL.md")

    suspend fun readTextFile(skillId: String, contentHash: String, relativePath: String): String {
        val version = dao.getVersion(skillId, contentHash) ?: error("Skill 版本不存在")
        val rootFile = File(version.rootPath).canonicalFile
        val target = File(rootFile, relativePath.replace('\\', '/')).canonicalFile
        require(target.toPath().startsWith(rootFile.toPath())) { "Skill 文件路径越界" }
        require(target.isFile) { "Skill 文件不存在" }
        val manifest = manifest(version)
        val normalizedPath = rootFile.toPath().relativize(target.toPath()).toString().replace('\\', '/')
        val entry = manifest.firstOrNull { it.path == normalizedPath } ?: error("Skill 文件不在安装清单中")
        require(entry.text) { "该文件不是可读取的文本文件" }
        return target.readText(Charsets.UTF_8)
    }

    suspend fun manifest(skillId: String, contentHash: String): List<SkillFileManifestEntry> {
        val version = dao.getVersion(skillId, contentHash) ?: error("Skill 版本不存在")
        return manifest(version)
    }

    suspend fun versionFile(skillId: String, contentHash: String, relativePath: String): File {
        val version = dao.getVersion(skillId, contentHash) ?: error("Skill 版本不存在")
        val rootFile = File(version.rootPath).canonicalFile
        val target = File(rootFile, relativePath.replace('\\', '/')).canonicalFile
        require(target.toPath().startsWith(rootFile.toPath()) && target.isFile) { "Skill 文件路径无效" }
        require(manifest(version).any { it.path == relativePath.replace('\\', '/') }) { "Skill 文件不在安装清单中" }
        return target
    }

    suspend fun versionLabel(skillId: String, contentHash: String): String? =
        dao.getVersion(skillId, contentHash)?.versionLabel

    suspend fun auditJson(skillId: String, contentHash: String): String? =
        dao.getVersion(skillId, contentHash)?.auditJson

    suspend fun markAvailableUpdate(skillId: String, remoteHash: String?) = dao.setUpdateHash(skillId, remoteHash)

    suspend fun diff(skillId: String, remoteFiles: List<SkillFileManifestEntry>): SkillFileDiff {
        val installation = dao.getInstallation(skillId) ?: error("Skill 不存在")
        val local = manifest(skillId, installation.currentHash).associateBy(SkillFileManifestEntry::path)
        val remote = remoteFiles.associateBy(SkillFileManifestEntry::path)
        return SkillFileDiff(
            added = (remote.keys - local.keys).sorted(),
            modified = (remote.keys intersect local.keys).filter { remote[it]?.sha256 != local[it]?.sha256 }.sorted(),
            removed = (local.keys - remote.keys).sorted(),
        )
    }

    /** 远端原版保持只读，编辑动作先复制成新的用户 Skill。 */
    suspend fun copyAsUserSkill(skillId: String): SkillInstallationEntity {
        val installation = dao.getInstallation(skillId) ?: error("Skill 不存在")
        val version = dao.getVersion(skillId, installation.currentHash) ?: error("Skill 版本不存在")
        return importDirectory(File(version.rootPath), SkillSourceType.USER_CREATED)
    }

    suspend fun updateSkillMarkdown(skillId: String, markdown: String): SkillInstallationEntity =
        mutateLocalSkill(skillId) { staging ->
            require(markdown.isNotBlank()) { "SKILL.md 不能为空" }
            File(staging, "SKILL.md").writeText(markdown, Charsets.UTF_8)
        }

    suspend fun addOrReplaceFile(skillId: String, relativePath: String, input: InputStream): SkillInstallationEntity =
        mutateLocalSkill(skillId) { staging ->
            val normalized = requireEditableRelativePath(relativePath)
            val target = File(staging, normalized).canonicalFile
            require(target.toPath().startsWith(staging.canonicalFile.toPath())) { "Skill 文件路径越界" }
            target.parentFile?.mkdirs()
            var total = 0L
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_SKILL_BYTES) { "文件超过 Skill 大小限制" }
                    output.write(buffer, 0, read)
                }
            }
        }

    suspend fun deleteFile(skillId: String, relativePath: String): SkillInstallationEntity =
        mutateLocalSkill(skillId) { staging ->
            val normalized = requireEditableRelativePath(relativePath)
            require(normalized != "SKILL.md") { "SKILL.md 不能删除" }
            val target = File(staging, normalized).canonicalFile
            require(target.toPath().startsWith(staging.canonicalFile.toPath()) && target.isFile) { "Skill 文件不存在" }
            require(target.delete()) { "Skill 文件删除失败" }
        }

    private suspend fun mutateLocalSkill(skillId: String, mutate: (File) -> Unit): SkillInstallationEntity {
        val installation = dao.getInstallation(skillId) ?: error("Skill 不存在")
        require(installation.sourceType != SkillSourceType.REMOTE.name) { "下载的原版需要先复制为用户 Skill" }
        val version = dao.getVersion(skillId, installation.currentHash) ?: error("Skill 版本不存在")
        val staging = File(root, ".edit-${UUID.randomUUID()}")
        return try {
            File(version.rootPath).copyRecursively(staging, overwrite = false)
            mutate(staging)
            val validated = SkillPackageValidator.validate(staging)
            installValidatedVersion(
                skillId = skillId,
                sourceRoot = staging,
                validated = validated,
                sourceType = enumValueOrNull<SkillSourceType>(installation.sourceType) ?: SkillSourceType.LOCAL_IMPORT,
                sourceRepository = installation.sourceRepository,
                sourcePath = installation.sourcePath,
                auditStatus = enumValueOrNull<SkillAuditStatus>(installation.auditStatus) ?: SkillAuditStatus.UNVERIFIED,
                versionLabel = null,
                auditJson = null,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun recordUse(skillId: String) = dao.recordUse(skillId)

    private fun manifest(version: SkillVersionEntity): List<SkillFileManifestEntry> =
        json.decodeFromString(ListSerializer(SkillFileManifestEntry.serializer()), version.manifestJson)

    private fun resolveImportedRoot(temporary: File): File {
        if (File(temporary, "SKILL.md").isFile) return temporary
        val candidates = temporary.walkTopDown().filter { it.isFile && it.name == "SKILL.md" }.toList()
        require(candidates.size == 1) { "导入内容必须只包含一个 SKILL.md" }
        return candidates.single().parentFile ?: error("SKILL.md 路径无效")
    }

    private fun chooseRemoteSkillFile(
        zip: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        skillName: String,
    ): java.util.zip.ZipEntry {
        val skillFiles = entries.filter { !it.isDirectory && it.name.substringAfterLast('/') == "SKILL.md" }
        require(skillFiles.isNotEmpty()) { "来源仓库没有包含 SKILL.md" }
        val normalizedName = skillName.normalizeSkillName()
        val directoryMatches = skillFiles.filter {
            it.name.substringBeforeLast('/', "").substringAfterLast('/').normalizeSkillName() == normalizedName
        }
        if (directoryMatches.size == 1) return directoryMatches.single()

        val metadataMatches = skillFiles.filter { entry ->
            if (entry.size > MAX_SKILL_MARKDOWN_BYTES) return@filter false
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                parseSkillFrontmatter(reader.readText())["name"]?.normalizeSkillName() == normalizedName
            }
        }
        return when {
            metadataMatches.size == 1 -> metadataMatches.single()
            directoryMatches.isNotEmpty() -> error("来源仓库存在多个同名 Skill")
            else -> error("来源仓库中找不到 Skill：$skillName")
        }
    }
}

private fun requireEditableRelativePath(value: String): String {
    val normalized = value.trim().replace('\\', '/')
    require(
        normalized.isNotBlank() && !normalized.startsWith('/') &&
            normalized.split('/').none { it.isBlank() || it == "." || it == ".." },
    ) { "Skill 文件路径无效" }
    require(normalized.substringBefore('/') in setOf("scripts", "references", "templates", "assets")) {
        "文件只能放入 scripts、references、templates 或 assets"
    }
    return normalized
}

private fun validateArchiveEntryName(name: String) {
    val normalized = name.replace('\\', '/')
    require(
        normalized.isNotBlank() &&
            !normalized.startsWith('/') &&
            normalized.split('/').none { it == ".." },
    ) { "ZIP 包含非法路径" }
}

private fun String.normalizeSkillName(): String = lowercase().replace('_', '-').replace(' ', '-')

private const val MAX_SKILL_MARKDOWN_BYTES = 1024L * 1024L

private fun SkillInstallationEntity.toSnapshotEntry(mode: SkillInvocationMode): SkillSnapshotEntry =
    SkillSnapshotEntry(
        skillId = skillId,
        name = name,
        description = description,
        sourceType = enumValueOrNull<SkillSourceType>(sourceType) ?: SkillSourceType.LOCAL_IMPORT,
        sourceRepository = sourceRepository,
        sourcePath = sourcePath,
        contentHash = currentHash,
        invocationMode = mode,
    )

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }

private fun String.directoryKey(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

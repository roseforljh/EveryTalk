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
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun observePackages(): Flow<List<InstalledSkillPackage>> = observeAll().map { it.toInstalledSkillPackages() }

    suspend fun getAll(): List<SkillInstallationEntity> = dao.getAll()

    suspend fun get(skillId: String): SkillInstallationEntity? = dao.getInstallation(skillId)

    suspend fun getPackage(packageId: String): InstalledSkillPackage? =
        dao.getPackageChildren(packageId).toInstalledSkillPackages().singleOrNull()

    suspend fun setEnabled(skillId: String, enabled: Boolean) = dao.setEnabled(skillId, enabled)

    suspend fun setPackageEnabled(packageId: String, enabled: Boolean) = dao.setPackageEnabled(packageId, enabled)

    suspend fun delete(skillId: String) {
        dao.delete(skillId)
        File(root, skillId.directoryKey()).deleteRecursively()
    }

    suspend fun deletePackage(packageId: String) {
        val children = dao.getPackageChildren(packageId)
        dao.deletePackage(packageId)
        children.forEach { child -> File(root, child.skillId.directoryKey()).deleteRecursively() }
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
        versionLabel: String? = null,
    ): SkillInstallationEntity {
        val validated = SkillPackageValidator.validate(sourceRoot)
        val localPackageId = "local:${UUID.randomUUID()}"
        val skillId = if (sourceType == SkillSourceType.REMOTE) {
            require(!sourceRepository.isNullOrBlank() && !sourcePath.isNullOrBlank()) { "远端 Skill 缺少来源" }
            "remote:${sourceRepository.trim().trimEnd('/')}#${sourcePath.trim().trim('/')}"
        } else {
            "$localPackageId#."
        }
        val packageId = if (sourceType == SkillSourceType.REMOTE) {
            "remote:${sourceRepository.orEmpty().removePrefix("https://github.com/").trimEnd('/')}"
        } else {
            localPackageId
        }
        return installValidatedVersion(
            skillId = skillId,
            sourceRoot = sourceRoot,
            validated = validated,
            sourceType = sourceType,
            sourceRepository = sourceRepository,
            sourcePath = sourcePath,
            versionLabel = versionLabel,
            packageId = packageId,
            packageName = if (sourceType == SkillSourceType.REMOTE) {
                sourceRepository.orEmpty().trimEnd('/').substringAfterLast('/')
            } else {
                validated.name
            },
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
        versionLabel: String?,
        packageId: String,
        packageName: String,
    ): SkillInstallationEntity {
        val versionRoot = materializeVersion(skillId, sourceRoot, validated)
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
            enabled = previous?.enabled ?: true,
            invocationMode = validated.invocationMode.name,
            updateHash = null,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = previous?.lastUsedAt,
            useCount = previous?.useCount ?: 0,
            packageId = previous?.effectivePackageId() ?: packageId,
            packageName = previous?.effectivePackageName() ?: packageName,
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
                installedAt = now,
            ),
        )
        return installation
    }

    /**
     * 下载完整远端包。全部文件先进入临时目录并校验，最后一次性切换所有子 Skill。
     */
    suspend fun importRemotePackage(
        detail: RemoteSkillPackageDetail,
        copyFile: (detail: RemoteSkillPackageDetail, entry: RemoteSkillPackageFile, target: File) -> Unit,
    ): InstalledSkillPackage {
        require(detail.skills.isNotEmpty()) { "Skill 包为空" }
        val fileCount = detail.skills.sumOf { it.files.size }
        val totalBytes = detail.skills.sumOf { skill -> skill.files.sumOf(RemoteSkillPackageFile::size) }
        require(fileCount <= MAX_SKILL_FILES) { "Skill 包文件数超过 $MAX_SKILL_FILES" }
        require(totalBytes <= MAX_SKILL_BYTES) { "Skill 包超过 100 MB" }
        val temporary = File(root, ".remote-package-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val roots = detail.skills.mapIndexed { index, skill ->
                val childRoot = File(temporary, index.toString()).apply { mkdirs() }
                skill.files.forEach { entry ->
                    val relative = requireRemoteRelativePath(entry.path)
                    val target = File(childRoot, relative).canonicalFile
                    require(target.toPath().startsWith(childRoot.canonicalFile.toPath())) { "Skill 文件路径越界" }
                    copyFile(detail, entry, target)
                    require(target.isFile && target.length() == entry.size) { "Skill 文件下载不完整：${entry.path}" }
                }
                PackageSkillRoot(childRoot, skill.sourcePath)
            }
            installPackageRoots(
                packageId = detail.packageId,
                packageName = detail.name,
                roots = roots,
                sourceType = SkillSourceType.REMOTE,
                sourceRepository = detail.sourceRepository,
                versionLabel = detail.contentHash,
            )
        } finally {
            temporary.deleteRecursively()
        }
    }

    /** 所有子 Skill 的不可变版本准备完成后，再用一个 Room 事务切换整包。 */
    private suspend fun installPackageRoots(
        packageId: String,
        packageName: String,
        roots: List<PackageSkillRoot>,
        sourceType: SkillSourceType,
        sourceRepository: String?,
        versionLabel: String?,
    ): InstalledSkillPackage {
        require(roots.isNotEmpty()) { "Skill 包中没有找到 SKILL.md" }
        val previousChildren = dao.getPackageChildren(packageId)
        val previousById = previousChildren.associateBy(SkillInstallationEntity::skillId)
        val packageEnabled = previousChildren.firstOrNull()?.enabled ?: true
        val now = System.currentTimeMillis()
        val prepared = roots.map { child ->
            val validated = SkillPackageValidator.validate(child.root)
            val normalizedSourcePath = child.sourcePath.trim().replace('\\', '/').trim('/').ifBlank { "." }
            val skillId = if (sourceType == SkillSourceType.REMOTE) {
                "remote:${sourceRepository.orEmpty().trimEnd('/')}#$normalizedSourcePath"
            } else {
                "$packageId#$normalizedSourcePath"
            }
            val versionRoot = materializeVersion(skillId, child.root, validated)
            val previous = previousById[skillId]
            val installation = SkillInstallationEntity(
                skillId = skillId,
                name = validated.name,
                description = validated.description,
                sourceType = sourceType.name,
                sourceRepository = sourceRepository,
                sourcePath = normalizedSourcePath,
                currentHash = validated.contentHash,
                enabled = packageEnabled,
                invocationMode = validated.invocationMode.name,
                updateHash = null,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
                lastUsedAt = previous?.lastUsedAt,
                useCount = previous?.useCount ?: 0,
                packageId = packageId,
                packageName = packageName,
            )
            installation to SkillVersionEntity(
                skillId = skillId,
                contentHash = validated.contentHash,
                versionLabel = versionLabel,
                rootPath = versionRoot.absolutePath,
                manifestJson = json.encodeToString(ListSerializer(SkillFileManifestEntry.serializer()), validated.manifest),
                frontmatterJson = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    validated.frontmatter,
                ),
                installedAt = now,
            )
        }
        dao.replacePackage(packageId, prepared.map { it.first }, prepared.map { it.second })
        val installedIds = prepared.map { it.first.skillId }.toSet()
        previousChildren.filterNot { it.skillId in installedIds }
            .forEach { File(root, it.skillId.directoryKey()).deleteRecursively() }
        return prepared.map { it.first }.toInstalledSkillPackages().single()
    }

    private fun materializeVersion(
        skillId: String,
        sourceRoot: File,
        validated: ValidatedSkillPackage,
    ): File {
        val versionRoot = File(File(root, skillId.directoryKey()), validated.contentHash)
        if (versionRoot.exists()) return versionRoot
        val staging = File(root, ".install-${UUID.randomUUID()}")
        try {
            sourceRoot.copyRecursively(staging, overwrite = false)
            require(SkillPackageValidator.validate(staging).contentHash == validated.contentHash) { "Skill 复制后哈希不一致" }
            versionRoot.parentFile?.mkdirs()
            require(staging.renameTo(versionRoot)) { "Skill 安装目录写入失败" }
        } finally {
            staging.deleteRecursively()
        }
        return versionRoot
    }

    /** 从系统文件选择器导入 ZIP。解压阶段只写普通文件，不执行任何内容。 */
    suspend fun importZip(input: InputStream, packageName: String? = null): InstalledSkillPackage {
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
            val packageRoot = resolveImportedPackageRoot(temporary)
            importLocalPackage(packageRoot, packageName ?: packageRoot.name)
        } finally {
            temporary.deleteRecursively()
        }
    }

    /** 从 Android 文档树复制目录。Provider 的特殊对象只会被复制成普通文件。 */
    suspend fun importDocumentTree(treeUri: Uri, packageName: String? = null): InstalledSkillPackage {
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
            val packageRoot = resolveImportedPackageRoot(temporary)
            importLocalPackage(packageRoot, packageName ?: packageRoot.name)
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

    suspend fun packageVersionLabel(packageId: String): String? {
        val child = dao.getPackageChildren(packageId).firstOrNull() ?: return null
        return versionLabel(child.skillId, child.currentHash)
    }

    suspend fun markPackageAvailableUpdate(packageId: String, remoteHash: String?) =
        dao.setPackageUpdateHash(packageId, remoteHash)

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
                versionLabel = null,
                packageId = installation.effectivePackageId(),
                packageName = installation.effectivePackageName(),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun recordUse(skillId: String) = dao.recordUse(skillId)

    private fun manifest(version: SkillVersionEntity): List<SkillFileManifestEntry> =
        json.decodeFromString(ListSerializer(SkillFileManifestEntry.serializer()), version.manifestJson)

    /** 本地目录和 ZIP 也按包发现全部独立 SKILL.md。 */
    private suspend fun importLocalPackage(packageRoot: File, packageName: String): InstalledSkillPackage {
        val skillRoots = discoverSkillRoots(packageRoot)
        val packageId = "local:${UUID.randomUUID()}"
        val staging = File(root, ".local-package-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val materialized = skillRoots.mapIndexed { index, skillRoot ->
                val target = File(staging, index.toString()).apply { mkdirs() }
                copyIndependentSkillRoot(packageRoot, skillRoot, skillRoots, target)
                val sourcePath = packageRoot.canonicalFile.toPath().relativize(skillRoot.canonicalFile.toPath())
                    .toString().replace('\\', '/').ifBlank { "." }
                PackageSkillRoot(target, sourcePath)
            }
            installPackageRoots(
                packageId = packageId,
                packageName = packageName.ifBlank { skillRoots.first().name },
                roots = materialized,
                sourceType = SkillSourceType.LOCAL_IMPORT,
                sourceRepository = null,
                versionLabel = null,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun discoverSkillRoots(packageRoot: File): List<File> {
        val canonicalPackageRoot = packageRoot.canonicalFile
        val discovered = packageRoot.walkTopDown()
            .filter { it.isFile && it.name == "SKILL.md" }
            .mapNotNull(File::getParentFile)
            .filter { candidate ->
                val relative = canonicalPackageRoot.toPath().relativize(candidate.canonicalFile.toPath())
                    .toString().replace('\\', '/')
                relative.split('/').none { it.lowercase() in LOCAL_IGNORED_SKILL_ROOT_SEGMENTS }
            }
            .distinctBy { it.canonicalPath }
            .sortedBy { it.canonicalPath }
            .toList()
        val roots = discovered.groupBy { it.name.lowercase() }.values.map { duplicates ->
            duplicates.minWith(
                compareBy<File> { candidate ->
                    val relative = canonicalPackageRoot.toPath().relativize(candidate.canonicalFile.toPath())
                        .toString().replace('\\', '/')
                    when {
                        relative == "skills" || relative.startsWith("skills/") -> 0
                        relative.substringBefore('/').startsWith('.') -> 2
                        else -> 1
                    }
                }.thenBy { it.canonicalPath.length },
            )
        }.sortedBy { it.canonicalPath }
        require(roots.isNotEmpty()) { "导入内容中没有找到 SKILL.md" }
        return roots
    }

    private fun copyIndependentSkillRoot(
        packageRoot: File,
        skillRoot: File,
        allRoots: List<File>,
        target: File,
    ) {
        val canonicalRoot = skillRoot.canonicalFile
        val nestedRoots = allRoots.map(File::getCanonicalFile).filter { it != canonicalRoot }
        skillRoot.walkTopDown().filter(File::isFile).forEach { source ->
            val canonicalSource = source.canonicalFile
            if (nestedRoots.any { nested -> canonicalSource.toPath().startsWith(nested.toPath()) }) return@forEach
            val relative = canonicalRoot.toPath().relativize(canonicalSource.toPath()).toString().replace('\\', '/')
            if (skillRoot.canonicalFile == packageRoot.canonicalFile && allRoots.size > 1) {
                val first = relative.substringBefore('/').lowercase()
                if (relative != "SKILL.md" && first !in LOCAL_ROOT_RESOURCE_DIRECTORIES) return@forEach
            }
            val destination = File(target, relative)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = false)
        }
    }

    private fun resolveImportedPackageRoot(temporary: File): File {
        if (File(temporary, "SKILL.md").isFile) return temporary
        val children = temporary.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
        return children.singleOrNull()?.takeIf(File::isDirectory) ?: temporary
    }

}

private data class PackageSkillRoot(val root: File, val sourcePath: String)

private val LOCAL_IGNORED_SKILL_ROOT_SEGMENTS = setOf(
    ".git", ".github", "node_modules", "build", "dist", "references", "templates",
    "assets", "scripts", "examples", "example", "test", "tests",
)

private val LOCAL_ROOT_RESOURCE_DIRECTORIES = setOf("scripts", "references", "templates", "assets")

private fun requireRemoteRelativePath(value: String): String {
    val normalized = value.trim().replace('\\', '/').trim('/')
    require(
        normalized.isNotBlank() && normalized.split('/').none { it.isBlank() || it == "." || it == ".." },
    ) { "Skill 文件路径无效" }
    return normalized
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
        packageName = effectivePackageName(),
    )

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }

private fun String.directoryKey(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

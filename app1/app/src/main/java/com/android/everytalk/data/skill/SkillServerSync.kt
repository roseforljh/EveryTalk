package com.android.everytalk.data.skill

import com.android.everytalk.data.computer.ComputerFileEncoding
import com.android.everytalk.data.computer.ComputerFileTransfer
import com.android.everytalk.data.computer.ComputerFileWriteMode
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerWorkspaceManager
import com.android.everytalk.data.computer.ComputerSshConnection
import com.android.everytalk.data.computer.ComputerWorkspace
import java.io.OutputStream
import java.security.MessageDigest

data class SyncedSkill(val skillId: String, val contentHash: String, val workspacePath: String)

/** 只把本次 request_agent 明确列出的 Skill 版本同步到当前 Workspace。 */
class SkillServerSync(
    private val skills: SkillRepository,
    private val computers: ComputerRepository,
) {
    private val transfer = ComputerFileTransfer()
    private val workspaces = ComputerWorkspaceManager(computers)

    suspend fun sync(
        context: ComputerRequestContext,
        snapshot: SkillRequestSnapshot?,
        requiredSkillIds: List<String>,
    ): List<SyncedSkill> {
        if (requiredSkillIds.isEmpty()) return emptyList()
        val entries = requiredSkillIds.distinct().map { skillId ->
            snapshot?.automaticCatalog?.firstOrNull { it.skillId == skillId }
                ?: snapshot?.manualReferences?.firstOrNull { it.skillId == skillId }?.let { reference ->
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
                ?: error("Agent 申请包含当前请求快照之外的 Skill")
        }
        val workspace = workspaces.prepare(context.workspaceId)
        return computers.withConnection(context.computerId) { connection, _ ->
            entries.map { entry ->
                val directory = skillServerDirectory(entry.skillId, entry.contentHash)
                val marker = "$directory/.complete"
                val manifest = skills.manifest(entry.skillId, entry.contentHash)
                var cached = runCatching {
                    transfer.read(connection, workspace, marker, limit = 256, encoding = ComputerFileEncoding.UTF8).content == entry.contentHash
                }.getOrDefault(false)
                if (cached) cached = verifyWorkspaceCopy(connection, workspace, directory, manifest)
                if (!cached) cached = restoreServerCache(connection, workspace, directory, entry.contentHash, manifest)
                if (!cached) {
                    clearWorkspaceCopy(connection, workspace, directory)
                    manifest.forEachIndexed { index, file ->
                        val local = skills.versionFile(entry.skillId, entry.contentHash, file.path)
                        local.inputStream().use { input ->
                            val result = transfer.upload(
                                connection = connection,
                                workspace = workspace,
                                toolCallId = "skillsync_${index}_${entry.contentHash.take(8)}",
                                destinationPath = "$directory/${file.path}",
                                source = input,
                                expectedSize = file.size,
                                overwrite = true,
                            )
                            require(result.sha256 == file.sha256) { "Skill 服务器同步哈希不一致：${file.path}" }
                        }
                    }
                    transfer.write(
                        connection = connection,
                        workspace = workspace,
                        toolCallId = "skillsync_marker_${entry.contentHash.take(8)}",
                        path = marker,
                        content = entry.contentHash,
                        mode = ComputerFileWriteMode.OVERWRITE,
                        createParents = true,
                    )
                    require(verifyWorkspaceCopy(connection, workspace, directory, manifest)) { "Skill 服务器同步校验失败" }
                    publishServerCache(connection, workspace, directory, entry.contentHash)
                }
                SyncedSkill(entry.skillId, entry.contentHash, "/workspace/$directory")
            }
        }
    }

    /** 服务器公共缓存只接受本地生成的十六进制目录名，不拼接 Skill 原始路径。 */
    private suspend fun restoreServerCache(
        connection: ComputerSshConnection,
        workspace: ComputerWorkspace,
        directory: String,
        contentHash: String,
        manifest: List<SkillFileManifestEntry>,
    ): Boolean {
        val key = directory.substringAfter(".everytalk/skills/").substringBefore('/')
        val result = connection.execute(
            command = """
                set -eu
                cache="${'$'}HOME/.everytalk/skill-cache/$key/$contentHash"
                target="${'$'}HOME/.everytalk/workspaces/${workspace.id}/$directory"
                [ -d "${'$'}cache" ] && [ ! -L "${'$'}cache" ] && [ "${'$'}(cat "${'$'}cache/.complete" 2>/dev/null || true)" = "$contentHash" ] || exit 42
                rm -rf -- "${'$'}target"
                mkdir -p "${'$'}{target%/*}"
                cp -a -- "${'$'}cache" "${'$'}target"
            """.trimIndent(),
            timeoutMillis = 30_000,
            maxOutputBytes = 8 * 1024,
        )
        if (result.timedOut || result.exitCode != 0) return false
        return verifyWorkspaceCopy(connection, workspace, directory, manifest)
    }

    private suspend fun publishServerCache(
        connection: ComputerSshConnection,
        workspace: ComputerWorkspace,
        directory: String,
        contentHash: String,
    ) {
        val key = directory.substringAfter(".everytalk/skills/").substringBefore('/')
        val result = connection.execute(
            command = """
                set -eu
                source="${'$'}HOME/.everytalk/workspaces/${workspace.id}/$directory"
                cache="${'$'}HOME/.everytalk/skill-cache/$key/$contentHash"
                if [ ! -d "${'$'}cache" ]; then
                    mkdir -p "${'$'}{cache%/*}"
                    temporary="${'$'}cache.tmp.${'$'}${'$'}"
                    rm -rf -- "${'$'}temporary"
                    cp -a -- "${'$'}source" "${'$'}temporary"
                    chmod -R go-rwx "${'$'}temporary"
                    mv -- "${'$'}temporary" "${'$'}cache" 2>/dev/null || rm -rf -- "${'$'}temporary"
                fi
                find "${'$'}{cache%/*}" -mindepth 1 -maxdepth 1 -type d -mtime +30 ! -name "$contentHash" -exec rm -rf -- {} +
            """.trimIndent(),
            timeoutMillis = 30_000,
            maxOutputBytes = 8 * 1024,
        )
        require(!result.timedOut && result.exitCode == 0) { "Skill 服务器缓存写入失败" }
    }

    private suspend fun clearWorkspaceCopy(
        connection: ComputerSshConnection,
        workspace: ComputerWorkspace,
        directory: String,
    ) {
        val result = connection.execute(
            command = "rm -rf -- \"${'$'}HOME/.everytalk/workspaces/${workspace.id}/$directory\"",
            timeoutMillis = 30_000,
            maxOutputBytes = 8 * 1024,
        )
        require(!result.timedOut && result.exitCode == 0) { "Skill 旧同步目录清理失败" }
    }

    private suspend fun verifyWorkspaceCopy(
        connection: ComputerSshConnection,
        workspace: ComputerWorkspace,
        directory: String,
        manifest: List<SkillFileManifestEntry>,
    ): Boolean {
        val count = connection.execute(
            command = "find \"${'$'}HOME/.everytalk/workspaces/${workspace.id}/$directory\" -type f -print 2>/dev/null | wc -l",
            timeoutMillis = 30_000,
            maxOutputBytes = 1024,
        )
        if (count.timedOut || count.exitCode != 0 || count.stdout.trim().toIntOrNull() != manifest.size + 1) return false
        return manifest.all { file ->
            runCatching {
                val result = transfer.download(
                    connection,
                    workspace,
                    "$directory/${file.path}",
                    NULL_OUTPUT,
                )
                result.bytes == file.size && result.sha256 == file.sha256
            }.getOrDefault(false)
        }
    }

    private companion object {
        val NULL_OUTPUT = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
    }
}

private fun String.safeDirectoryKey(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun skillServerDirectory(skillId: String, contentHash: String): String {
    require(contentHash.length == 64 && contentHash.all { it in '0'..'9' || it in 'a'..'f' }) { "Skill 内容哈希无效" }
    return ".everytalk/skills/${skillId.safeDirectoryKey()}/$contentHash"
}

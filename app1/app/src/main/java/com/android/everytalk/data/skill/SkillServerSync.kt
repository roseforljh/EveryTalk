package com.android.everytalk.data.skill

import com.android.everytalk.data.computer.ComputerFileTransfer
import com.android.everytalk.data.computer.ComputerFileWriteMode
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerSshConnection
import java.security.MessageDigest

/** Skill 缓存根目录在 /tmp，服务器重启即清空。 */
private const val SKILL_REMOTE_ROOT = "/tmp/everytalk-\$(id -u)"

data class SyncedSkill(val skillId: String, val contentHash: String, val workspacePath: String)

/**
 * 只把本次 request_agent 明确列出的 Skill 版本同步到服务器。
 * 文件按内容哈希缓存在 /tmp/everytalk-<uid>/skills 下，多个会话共用，重启自然清空，不需要删除流程。
 */
class SkillServerSync(
    private val skills: SkillRepository,
    private val computers: ComputerRepository,
) {
    private val transfer = ComputerFileTransfer()

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
        return computers.withConnection(context.computerId) { connection, _ ->
            entries.map { entry ->
                val (directory, cached) = prepareCacheDirectory(connection, entry.skillId, entry.contentHash)
                if (!cached) {
                    skills.manifest(entry.skillId, entry.contentHash).forEachIndexed { index, file ->
                        skills.versionFile(entry.skillId, entry.contentHash, file.path).inputStream().use { input ->
                            val result = transfer.upload(
                                connection = connection,
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
                        toolCallId = "skillsync_marker_${entry.contentHash.take(8)}",
                        path = "$directory/.complete",
                        content = entry.contentHash,
                        mode = ComputerFileWriteMode.OVERWRITE,
                    )
                }
                SyncedSkill(entry.skillId, entry.contentHash, directory)
            }
        }
    }

    /**
     * 一条命令完成：确认私有缓存根目录、清理 30 天未用版本、判断当前版本是否已完整同步。
     * 只接受本地生成的十六进制目录名，不拼接 Skill 原始路径。
     */
    private suspend fun prepareCacheDirectory(
        connection: ComputerSshConnection,
        skillId: String,
        contentHash: String,
    ): Pair<String, Boolean> {
        val relative = skillServerDirectory(skillId, contentHash)
        val result = connection.execute(
            command = """
                set -eu
                umask 077
                root="$SKILL_REMOTE_ROOT"
                [ -e "${'$'}root" ] || mkdir -m 700 "${'$'}root"
                [ -d "${'$'}root" ] && [ ! -L "${'$'}root" ] && [ "${'$'}(stat -c %u "${'$'}root")" = "${'$'}(id -u)" ]
                mkdir -p "${'$'}root/skills"
                find "${'$'}root/skills" -mindepth 2 -maxdepth 2 -type d -mtime +30 -exec rm -rf -- {} + 2>/dev/null || true
                dir="${'$'}root/$relative"
                if [ "${'$'}(cat "${'$'}dir/.complete" 2>/dev/null || true)" = "$contentHash" ]; then
                    touch "${'$'}dir"
                    echo cached=1
                else
                    rm -rf -- "${'$'}dir"
                    mkdir -p "${'$'}dir"
                    echo cached=0
                fi
                echo "dir=${'$'}dir"
            """.trimIndent(),
            timeoutMillis = 30_000,
            maxOutputBytes = 8 * 1024,
        )
        require(!result.timedOut && result.exitCode == 0) { "Skill 服务器缓存目录准备失败" }
        val values = result.stdout.lineSequence().mapNotNull { line ->
            line.substringBefore('=', "").takeIf(String::isNotEmpty)?.let { it to line.substringAfter('=') }
        }.toMap()
        val directory = values["dir"]?.takeIf { it.startsWith("/") } ?: error("Skill 服务器缓存目录无效")
        return directory to (values["cached"] == "1")
    }
}

private fun String.safeDirectoryKey(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun skillServerDirectory(skillId: String, contentHash: String): String {
    require(contentHash.length == 64 && contentHash.all { it in '0'..'9' || it in 'a'..'f' }) { "Skill 内容哈希无效" }
    return "skills/${skillId.safeDirectoryKey()}/$contentHash"
}

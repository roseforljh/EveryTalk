package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val WORKSPACE_COMMAND_TIMEOUT_MILLIS = 30_000L
private const val CONTAINER_HELPER = "/usr/local/libexec/everytalk-containerctl"

/** 会话在每台服务器上拥有独立持久 Workspace，关闭 Agent 或切换服务器都不会删除它。 */
class ComputerWorkspaceManager(private val repository: ComputerRepository) {
    private val workspaceLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun getOrCreate(computerId: String, conversationId: String): ComputerWorkspace {
        require(conversationId.isNotBlank()) { "Conversation ID 不能为空" }
        val lockKey = "$computerId\u0000$conversationId"
        return workspaceLocks.computeIfAbsent(lockKey) { Mutex() }.withLock {
            val dao = repository.dao()
            val existing = dao.getWorkspace(computerId, conversationId)?.toModel()
            if (existing?.status == ComputerWorkspaceStatus.READY) {
                val restored = existing.copy(lastUsedAt = System.currentTimeMillis())
                if (existing.runMode == ComputerRunMode.CONTAINER) {
                    val computer = repository.getComputer(computerId)
                        ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
                    try {
                        // Container 禁止自动重启。会话真正使用它时，才通过受限 helper 主动恢复。
                        repository.withConnection(computerId) { connection, _ ->
                            ensureContainerWorkspace(connection, computer, existing.id)
                        }
                    } catch (error: Throwable) {
                        dao.upsertWorkspace(restored.copy(status = ComputerWorkspaceStatus.ERROR).toEntity())
                        throw error
                    }
                }
                dao.upsertWorkspace(restored.toEntity())
                return@withLock restored
            }

            val computer = repository.getComputer(computerId)
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
            val workspace = existing ?: newWorkspace(computer, conversationId)
            dao.upsertWorkspace(workspace.copy(status = ComputerWorkspaceStatus.CREATING).toEntity())

            try {
                val ready = repository.withConnection(computerId) { connection, _ ->
                    val hostPath = ensureHostWorkspace(connection, workspace.id)
                    if (workspace.runMode == ComputerRunMode.CONTAINER) {
                        ensureContainerWorkspace(connection, computer, workspace.id)
                    }
                    workspace.copy(
                        hostPath = hostPath,
                        status = ComputerWorkspaceStatus.READY,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }
                dao.upsertWorkspace(ready.toEntity())
                ready
            } catch (error: Throwable) {
                dao.upsertWorkspace(
                    workspace.copy(
                        status = ComputerWorkspaceStatus.ERROR,
                        lastUsedAt = System.currentTimeMillis(),
                    ).toEntity(),
                )
                throw error
            }
        }
    }

    suspend fun getWorkspace(computerId: String, conversationId: String): ComputerWorkspace? =
        repository.dao().getWorkspace(computerId, conversationId)?.toModel()

    suspend fun deleteMapping(workspaceId: String) {
        repository.dao().deleteWorkspace(workspaceId)
    }

    /**
     * 按详情页选择清理远端 Workspace。
     * Container 模式始终删除对应 Container；Host Path 只有二次确认后才删除。
     */
    suspend fun deleteRemote(workspaceId: String, deleteFiles: Boolean) {
        ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
        val workspace = repository.dao().getWorkspaceById(workspaceId)?.toModel()
            ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
        repository.dao().upsertWorkspace(workspace.copy(status = ComputerWorkspaceStatus.DELETING).toEntity())
        try {
            repository.withConnection(workspace.computerId, requireReady = false) { connection, computer ->
                val result = when (workspace.runMode) {
                    ComputerRunMode.CONTAINER -> {
                        val helper = if (computer.username == "root") {
                            CONTAINER_HELPER
                        } else {
                            "sudo -n -- $CONTAINER_HELPER"
                        }
                        connection.execute(
                            command = "$helper delete-workspace $workspaceId $deleteFiles",
                            timeoutMillis = WORKSPACE_COMMAND_TIMEOUT_MILLIS,
                            maxOutputBytes = 64 * 1024,
                        )
                    }
                    ComputerRunMode.DIRECT -> {
                        if (!deleteFiles) return@withConnection
                        connection.execute(
                            command = directWorkspaceDeleteCommand(workspaceId),
                            timeoutMillis = WORKSPACE_COMMAND_TIMEOUT_MILLIS,
                            maxOutputBytes = 64 * 1024,
                        )
                    }
                }
                if (result.timedOut || result.exitCode != 0) {
                    throw ComputerException(
                        ComputerErrorCodes.WORKSPACE_NOT_READY,
                        "清理远端 Workspace 失败",
                        retryable = true,
                    )
                }
            }
        } catch (error: Throwable) {
            repository.dao().upsertWorkspace(workspace.copy(status = ComputerWorkspaceStatus.ERROR).toEntity())
            throw error
        }
    }

    private fun directWorkspaceDeleteCommand(workspaceId: String): String = """
        set -eu
        workspace="${'$'}HOME/.everytalk/workspaces/$workspaceId"
        case "${'$'}workspace" in
            */.everytalk/workspaces/ws_*) rm -rf -- "${'$'}workspace" ;;
            *) exit 61 ;;
        esac
    """.trimIndent()

    private fun newWorkspace(computer: Computer, conversationId: String): ComputerWorkspace {
        val id = "ws_${UUID.randomUUID().toString().replace("-", "")}"
        ComputerIdentifier.requireValid(id, "Workspace ID")
        return ComputerWorkspace(
            id = id,
            computerId = computer.id,
            conversationId = conversationId,
            runMode = computer.runMode,
            hostPath = "~/.everytalk/workspaces/$id",
            containerName = if (computer.runMode == ComputerRunMode.CONTAINER) "everytalk-$id" else null,
            containerImage = if (computer.runMode == ComputerRunMode.CONTAINER) computer.sandboxImage else null,
        )
    }

    private suspend fun ensureHostWorkspace(connection: ComputerSshConnection, workspaceId: String): String {
        ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
        val result = connection.execute(
            command = """
                umask 077
                workspace="${'$'}HOME/.everytalk/workspaces/$workspaceId"
                mkdir -p "${'$'}workspace/.everytalk/runtime" "${'$'}workspace/.everytalk/background" "${'$'}workspace/.everytalk/previews"
                chmod 700 "${'$'}HOME/.everytalk" "${'$'}HOME/.everytalk/workspaces" "${'$'}workspace" "${'$'}workspace/.everytalk" "${'$'}workspace/.everytalk/runtime" "${'$'}workspace/.everytalk/background" "${'$'}workspace/.everytalk/previews"
                metadata="${'$'}workspace/.everytalk/workspace.json"
                if [ ! -f "${'$'}metadata" ]; then
                    temporary="${'$'}metadata.tmp.${'$'}${'$'}"
                    printf '%s\n' '{"version":1,"workspace_id":"$workspaceId"}' > "${'$'}temporary"
                    chmod 600 "${'$'}temporary"
                    mv -f "${'$'}temporary" "${'$'}metadata"
                fi
                cd "${'$'}workspace" && pwd -P
            """.trimIndent(),
            timeoutMillis = WORKSPACE_COMMAND_TIMEOUT_MILLIS,
            maxOutputBytes = 64 * 1024,
        )
        if (result.timedOut || result.exitCode != 0) {
            throw ComputerException(
                ComputerErrorCodes.WORKSPACE_NOT_READY,
                "创建 Workspace 失败",
                retryable = true,
            )
        }
        val hostPath = result.stdout.lineSequence().lastOrNull(String::isNotBlank)?.trim().orEmpty()
        if (!hostPath.startsWith('/') || hostPath.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径无效")
        }
        return hostPath
    }

    private suspend fun ensureContainerWorkspace(
        connection: ComputerSshConnection,
        computer: Computer,
        workspaceId: String,
    ) {
        ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
        val helperCommand = if (computer.username == "root") {
            "$CONTAINER_HELPER ensure-workspace $workspaceId"
        } else {
            "sudo -n -- $CONTAINER_HELPER ensure-workspace $workspaceId"
        }
        val result = connection.execute(
            command = helperCommand,
            timeoutMillis = 5 * 60 * 1000L,
            maxOutputBytes = 256 * 1024,
        )
        if (result.timedOut || result.exitCode != 0) {
            throw ComputerException(
                ComputerErrorCodes.WORKSPACE_NOT_READY,
                "创建 Workspace Container 失败",
                retryable = true,
            )
        }
    }
}

internal object ComputerIdentifier {
    fun requireValid(value: String, fieldName: String, maxLength: Int = 128): String {
        if (
            value.isEmpty() ||
            value.length > maxLength ||
            value.any { character ->
                !(character in 'a'..'z' || character in 'A'..'Z' || character.isDigit() || character == '_' || character == '-')
            }
        ) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "$fieldName 无效")
        }
        return value
    }
}

/** 将模型路径限制为当前 `/workspace` 下的规范相对路径。 */
internal object ComputerWorkspacePath {
    fun normalize(input: String, allowRoot: Boolean = false): String {
        if (input.isEmpty() || input.length > 4096 || input.any { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' }) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径无效")
        }
        val relative = when {
            input == "/workspace" -> ""
            input.startsWith("/workspace/") -> input.substring("/workspace/".length)
            input.startsWith('/') -> throw ComputerException(
                ComputerErrorCodes.WORKSPACE_PATH_INVALID,
                "只允许访问 /workspace",
            )
            else -> input
        }
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径禁止使用 ..")
        }
        val normalized = parts.joinToString("/")
        if (!allowRoot && normalized.isEmpty()) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "必须指定 Workspace 内文件")
        }
        return normalized
    }

    fun display(relativePath: String): String = if (relativePath.isEmpty()) "/workspace" else "/workspace/$relativePath"
}

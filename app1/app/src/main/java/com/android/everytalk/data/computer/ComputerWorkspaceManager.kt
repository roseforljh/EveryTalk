package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val WORKSPACE_COMMAND_TIMEOUT_MILLIS = 30_000L
private const val CONTAINER_HELPER = "/usr/local/libexec/everytalk-containerctl"

/** 会话在每台服务器上拥有独立持久 Workspace，关闭 Agent 或切换服务器都不会删除它。 */
class ComputerWorkspaceManager(private val repository: ComputerRepository) {
    private val workspaceLocks = ComputerKeyedMutexPool()

    /**
     * 在模型请求前只创建本地 Workspace 映射，不连接 VPS。
     * 远端目录由 prepare 在后台准备，模型首轮思考因此可以与 SSH 冷启动并行。
     */
    suspend fun getOrCreateLocal(computerId: String, conversationId: String): ComputerWorkspace {
        require(conversationId.isNotBlank()) { "Conversation ID 不能为空" }
        val lockKey = "$computerId\u0000$conversationId"
        return workspaceLocks.forKey(lockKey).withLock {
            val dao = repository.dao()
            dao.getWorkspace(computerId, conversationId)?.toModel()?.let { return@withLock it }
            val computer = repository.getComputer(computerId)
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
            newWorkspace(computer, conversationId).also { workspace ->
                dao.upsertWorkspace(workspace.toEntity())
            }
        }
    }

    /**
     * 按模型请求已经冻结的 Workspace ID 准备远端目录。
     * 会话 ID 在首条消息入库时会变化，Workspace ID 始终不变，因此它才是并发准备的稳定主键。
     */
    suspend fun prepare(workspaceId: String): ComputerWorkspace {
        ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
        return workspaceLocks.forKey(workspaceId).withLock {
            val dao = repository.dao()
            val existing = dao.getWorkspaceById(workspaceId)?.toModel()
                ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
            val computerId = existing.computerId
            if (existing.status == ComputerWorkspaceStatus.READY) {
                val restored = existing.copy(lastUsedAt = System.currentTimeMillis())
                dao.updateWorkspaceRuntimeState(
                    workspaceId = existing.id,
                    hostPath = restored.hostPath,
                    status = restored.status.name,
                    lastUsedAt = restored.lastUsedAt,
                )
                return@withLock dao.getWorkspaceById(existing.id)?.toModel() ?: restored
            }

            dao.updateWorkspaceRuntimeState(
                workspaceId = existing.id,
                hostPath = existing.hostPath,
                status = ComputerWorkspaceStatus.CREATING.name,
            )

            try {
                val ready = repository.withConnection(computerId) { connection, _ ->
                    val hostPath = ensureHostWorkspace(connection, existing.id)
                    existing.copy(
                        hostPath = hostPath,
                        status = ComputerWorkspaceStatus.READY,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }
                dao.updateWorkspaceRuntimeState(
                    workspaceId = ready.id,
                    hostPath = ready.hostPath,
                    status = ready.status.name,
                    lastUsedAt = ready.lastUsedAt,
                )
                dao.getWorkspaceById(ready.id)?.toModel() ?: ready
            } catch (error: Throwable) {
                dao.updateWorkspaceRuntimeState(
                    workspaceId = existing.id,
                    hostPath = existing.hostPath,
                    status = ComputerWorkspaceStatus.ERROR.name,
                )
                throw error
            }
        }
    }

    /** 只有模型明确选择 CONTAINER 时才启动或创建容器，Host SSH 不受容器故障影响。 */
    suspend fun prepareContainer(workspaceId: String) {
        ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
        workspaceLocks.forKey(workspaceId).withLock {
            val workspace = repository.dao().getWorkspaceById(workspaceId)?.toModel()
                ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
            if (workspace.runMode != ComputerRunMode.CONTAINER) {
                throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器没有配置隔离环境")
            }
            val computer = repository.getComputer(workspace.computerId)
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
            if (computer.status != ComputerStatus.READY) {
                throw ComputerException(
                    ComputerErrorCodes.HELPER_INTEGRITY_FAILED,
                    "隔离环境尚未就绪，请先修复运行环境",
                    action = "REPAIR_COMPUTER",
                )
            }
            repository.withConnection(workspace.computerId) { connection, _ ->
                ensureContainerWorkspace(connection, computer, workspace.id)
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
                        connection.execute(
                            command = directWorkspaceDeleteCommand(workspaceId, deleteFiles),
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

    private fun directWorkspaceDeleteCommand(workspaceId: String, deleteFiles: Boolean): String = """
        set -eu
        workspace="${'$'}HOME/.everytalk/workspaces/$workspaceId"
        background_root="${'$'}workspace/.everytalk/background"
        if [ -d "${'$'}background_root" ] && [ ! -L "${'$'}background_root" ]; then
            for process_dir in "${'$'}background_root"/process_*; do
                [ -d "${'$'}process_dir" ] && [ ! -L "${'$'}process_dir" ] || continue
                process_id="${'$'}{process_dir##*/}"
                case "${'$'}process_id" in process_*[!A-Za-z0-9_-]*|process_) continue ;; esac
                state_file="${'$'}process_dir/state"
                [ -f "${'$'}state_file" ] && [ ! -L "${'$'}state_file" ] || continue
                pid="${'$'}(awk -F= '${'$'}1 == "pid" { print ${'$'}2; exit }' "${'$'}state_file")"
                start_ticks="${'$'}(awk -F= '${'$'}1 == "start_ticks" { print ${'$'}2; exit }' "${'$'}state_file")"
                execution_id="${'$'}(awk -F= '${'$'}1 == "execution_id" { print ${'$'}2; exit }' "${'$'}state_file")"
                status="${'$'}(awk -F= '${'$'}1 == "status" { print ${'$'}2; exit }' "${'$'}state_file")"
                case "${'$'}pid" in ''|*[!0-9]*) pid=0 ;; esac
                case "${'$'}start_ticks" in ''|*[!0-9]*) start_ticks=0 ;; esac
                case "${'$'}execution_id" in ''|*[!A-Za-z0-9_-]*) execution_id=unknown ;; esac
                if [ "${'$'}status" = RUNNING ] && [ "${'$'}pid" -gt 1 ] && [ -r "/proc/${'$'}pid/stat" ]; then
                    actual_ticks="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)"
                    actual_sid="${'$'}(ps -o sid= -p "${'$'}pid" 2>/dev/null | tr -d ' ' || true)"
                    wrapper_argument="${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/cmdline" | while IFS= read -r argument; do
                        case "${'$'}argument" in
                            "${'$'}HOME/.everytalk/bin/everytalk-runtime-wrapper") printf '%s' "${'$'}argument"; break ;;
                            "${'$'}HOME/.everytalk/bin/everytalk-runtime-wrapper-"*) printf '%s' "${'$'}argument"; break ;;
                        esac
                    done)"
                    if [ "${'$'}wrapper_argument" != "${'$'}HOME/.everytalk/bin/everytalk-runtime-wrapper" ]; then
                        wrapper_version="${'$'}{wrapper_argument##*-}"
                        case "${'$'}wrapper_version" in
                            *[!0-9a-f]*|'') wrapper_argument='' ;;
                        esac
                        [ "${'$'}{#wrapper_version}" -eq 64 ] || wrapper_argument=''
                    fi
                    if [ "${'$'}actual_ticks" = "${'$'}start_ticks" ] && [ "${'$'}actual_sid" = "${'$'}pid" ] &&
                        [ -n "${'$'}wrapper_argument" ] &&
                        tr '\000' '\n' < "/proc/${'$'}pid/cmdline" | grep -Fqx -- "${'$'}wrapper_argument" &&
                        tr '\000' '\n' < "/proc/${'$'}pid/cmdline" | grep -Fqx -- "${'$'}process_dir"; then
                        kill -TERM "-${'$'}pid" 2>/dev/null || true
                        attempt=0
                        while kill -0 "${'$'}pid" 2>/dev/null && [ "${'$'}attempt" -lt 50 ]; do
                            sleep 0.1
                            attempt="${'$'}((attempt + 1))"
                        done
                        if kill -0 "${'$'}pid" 2>/dev/null; then
                            final_ticks="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)"
                            final_sid="${'$'}(ps -o sid= -p "${'$'}pid" 2>/dev/null | tr -d ' ' || true)"
                            if [ "${'$'}final_ticks" = "${'$'}start_ticks" ] && [ "${'$'}final_sid" = "${'$'}pid" ]; then
                                kill -KILL "-${'$'}pid" 2>/dev/null || true
                            fi
                        fi
                    fi
                fi
                temporary_state="${'$'}process_dir/state.tmp.${'$'}${'$'}"
                {
                    printf 'process_id=%s\n' "${'$'}process_id"
                    printf 'execution_id=%s\n' "${'$'}execution_id"
                    printf 'pid=%s\n' "${'$'}pid"
                    printf 'start_ticks=%s\n' "${'$'}start_ticks"
                    printf 'status=STOPPED\n'
                    printf 'updated_at=%s\n' "${'$'}(date +%s)"
                } > "${'$'}temporary_state"
                chmod 600 "${'$'}temporary_state"
                mv -f "${'$'}temporary_state" "${'$'}state_file"
            done
        fi
        if [ '$deleteFiles' = true ]; then
            case "${'$'}workspace" in
                */.everytalk/workspaces/ws_*) rm -rf -- "${'$'}workspace" ;;
                *) exit 61 ;;
            esac
        fi
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
                mkdir -p "${'$'}workspace/.everytalk/runtime" "${'$'}workspace/.everytalk/executions" "${'$'}workspace/.everytalk/background" "${'$'}workspace/.everytalk/previews"
                chmod 700 "${'$'}HOME/.everytalk" "${'$'}HOME/.everytalk/workspaces" "${'$'}workspace" "${'$'}workspace/.everytalk" "${'$'}workspace/.everytalk/runtime" "${'$'}workspace/.everytalk/executions" "${'$'}workspace/.everytalk/background" "${'$'}workspace/.everytalk/previews"
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

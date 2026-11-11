package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Workspace 只是“会话 ↔ 服务器”的本地绑定，用来关联执行记录、终端和预览。
 * 服务器只作为 SSH 工具使用，不在上面创建任何会话目录。
 */
class ComputerWorkspaceManager(private val repository: ComputerRepository) {
    private val workspaceLocks = ComputerKeyedMutexPool()

    /** 在模型请求前只创建本地 Workspace 映射，不连接 VPS。 */
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
     * 为 Cloudflare Computer 保存 App 私有 Workspace 映射，读取不会创建目录。
     * Cloudflare 不是 SSH 主机，不能复用 prepare() 的远端目录流程；这个 Workspace
     * 只服务于 just-bash、Worker 打包和本地文件保存，状态创建后即可 READY。
     */
    suspend fun getOrCreateAppLocal(
        computerId: String,
        conversationId: String,
        root: File,
    ): ComputerWorkspace {
        require(conversationId.isNotBlank()) { "Conversation ID 不能为空" }
        require(root.isAbsolute) { "App Workspace 必须使用绝对路径" }
        val lockKey = "$computerId\u0000$conversationId"
        return workspaceLocks.forKey(lockKey).withLock {
            val dao = repository.dao()
            val computer = repository.getComputer(computerId)
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Computer 不存在")
            require(computer.provider == ComputerProvider.CLOUDFLARE) { "App Workspace 只用于 Cloudflare" }
            dao.getWorkspace(computerId, conversationId)?.toModel()?.let { existing ->
                // 修复早期版本误写的远端路径；保持 ID，已有本地文件原位保留。
                val local = existing.copy(hostPath = File(root, existing.id).absolutePath,
                    status = ComputerWorkspaceStatus.READY, runMode = ComputerRunMode.DIRECT,
                    containerName = null, containerImage = null)
                if (local != existing) dao.upsertWorkspace(local.toEntity())
                return@withLock local
            }
            val now = System.currentTimeMillis()
            val localId = "ws_local_${UUID.randomUUID().toString().replace("-", "")}"
            val workspace = ComputerWorkspace(
                id = localId,
                computerId = computerId,
                conversationId = conversationId,
                runMode = ComputerRunMode.DIRECT,
                hostPath = File(root, localId).canonicalPath,
                containerName = null,
                containerImage = null,
                status = ComputerWorkspaceStatus.READY,
                createdAt = now,
                lastUsedAt = now,
            )
            // 只有 local_file_save 的可信批准可以创建持久目录。
            dao.upsertWorkspace(workspace.toEntity())
            workspace
        }
    }

    /** 按模型请求已经冻结的 Workspace ID 标记就绪；不连接服务器。 */
    suspend fun prepare(workspaceId: String): ComputerWorkspace {
        ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
        return workspaceLocks.forKey(workspaceId).withLock {
            val dao = repository.dao()
            val existing = dao.getWorkspaceById(workspaceId)?.toModel()
                ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
            if (repository.getComputer(existing.computerId)?.provider == ComputerProvider.CLOUDFLARE) {
                throw ComputerException("PROVIDER_MISMATCH", "Cloudflare Workspace 不支持 SSH 准备")
            }
            dao.updateWorkspaceRuntimeState(
                workspaceId = existing.id,
                hostPath = SSH_HOME_PATH,
                status = ComputerWorkspaceStatus.READY.name,
                lastUsedAt = System.currentTimeMillis(),
            )
            dao.getWorkspaceById(existing.id)?.toModel() ?: existing
        }
    }

    suspend fun getWorkspace(computerId: String, conversationId: String): ComputerWorkspace? =
        repository.dao().getWorkspace(computerId, conversationId)?.toModel()

    suspend fun deleteMapping(workspaceId: String) {
        repository.dao().deleteWorkspace(workspaceId)
    }

    private fun newWorkspace(computer: Computer, conversationId: String): ComputerWorkspace {
        val id = "ws_${UUID.randomUUID().toString().replace("-", "")}"
        ComputerIdentifier.requireValid(id, "Workspace ID")
        return ComputerWorkspace(
            id = id,
            computerId = computer.id,
            conversationId = conversationId,
            runMode = ComputerRunMode.DIRECT,
            hostPath = SSH_HOME_PATH,
        )
    }
}

/** SSH 会话的默认工作目录：登录用户的 Home。 */
internal const val SSH_HOME_PATH = "~"

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

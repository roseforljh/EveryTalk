package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 将 local_bash 的 JSON 参数转换为 just-bash 请求，并限制命令和工作目录。 */
class LocalBashToolExecutor(
    private val runtime: JustBashRuntime,
    private val workspaceRoot: (String) -> File,
    private val files: LocalWorkspaceFileBridge = LocalWorkspaceFileBridge(),
    private val enabled: () -> Boolean = { true },
) {
    suspend fun execute(arguments: JsonObject, context: ComputerRequestContext?): JsonObject {
        if (!enabled()) return error("local_bash 当前已关闭")
        val command = (arguments["command"] as? JsonPrimitive)?.content.orEmpty()
        if (command.isBlank() || command.length > 32_000) return error("command 无效")
        val workspaceId = context?.workspaceId?.takeIf(String::isNotBlank)
            ?: return error("缺少 Workspace 会话上下文")
        // 解析器只能定位已经存在的 Workspace。执行 local_bash 本身不能创建持久目录。
        val root = workspaceRoot(workspaceId)
        val requestedCwd = (arguments["cwd"] as? JsonPrimitive)?.content ?: "."
        val cwd = when {
            requestedCwd.isBlank() || requestedCwd == "." -> "/"
            requestedCwd.startsWith('/') -> requestedCwd
            else -> "/$requestedCwd"
        }
        if (cwd.length > 512 || '\\' in cwd || ':' in cwd || cwd.split('/').any { it == ".." }) {
            return error("cwd 只能位于当前 Workspace")
        }
        return try {
            // 没有持久化 Workspace 时使用空快照；读取操作仍可执行，且不会为了它创建目录。
            val loadedFiles = if (root.isDirectory) files.load(root) else emptyMap()
            val result = runtime.execute(
            LocalShellRequest(
                    id = "local-${UUID.randomUUID()}",
                    command = command,
                    cwd = cwd,
                    files = loadedFiles,
                    timeoutMs = ((arguments["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 10_000L).coerceIn(100, 30_000),
                    maxOutputChars = ((arguments["max_output_chars"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20_000).coerceIn(1_000, 200_000),
                ),
            )
            // local_bash 只在本次调用的内存快照中运行。即使命令包含 mkdir/cp/mv/rm，
            // 也不能绕过用户确认直接修改持久 Workspace；需要保存时使用 local_file_save。
            val changedFiles = result.files.filter { (path, content) -> loadedFiles[path] != content }
            val deletedFiles = loadedFiles.keys - result.files.keys
            val outputLimit = ((arguments["max_output_chars"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20_000).coerceIn(1_000, 200_000)
            val safeOut = ComputerExternalOutput.text(result.stdout, outputLimit)
            val safeErr = ComputerExternalOutput.text(result.stderr, maxOf(1, outputLimit - safeOut.text.length))
            buildJsonObject {
                put("ok", result.ok)
                put("exit_code", result.exitCode)
                put("stdout", safeOut.text)
                put("stderr", safeErr.text)
                put("truncated", result.truncated || safeOut.truncated || safeErr.truncated)
                put("untrusted_external_data", true)
                put("persisted", false)
                put("workspace_changes_discarded", changedFiles.isNotEmpty() || deletedFiles.isNotEmpty())
                put("files_changed", changedFiles.size + deletedFiles.size)
                put("changed_files", ComputerExternalOutput.text(changedFiles.keys.sorted().joinToString("\n"), 8_000).text)
                put("deleted_files", ComputerExternalOutput.text(deletedFiles.sorted().joinToString("\n"), 8_000).text)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            error("local_bash 执行失败：请检查 Workspace 路径、文件格式和配额")
        }
    }

    private fun error(message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", message)
    }
}

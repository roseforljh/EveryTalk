package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_FILE_READ_LIMIT = 256 * 1024

/** 七个 Computer Tool 的统一参数校验、幂等、状态和路由入口。 */
class ComputerToolExecutor(
    context: Context,
    private val repository: ComputerRepository,
    private val workspaceManager: ComputerWorkspaceManager,
    private val previewManager: ComputerPreviewManager,
    private val secretManager: ComputerWorkspaceSecretManager,
    private val attachmentBridge: ComputerAttachmentBridge? = null,
    private val publicPreviewConfirmer: suspend (ComputerPublicPreviewRequest) -> Boolean = { false },
    private val hostCommandConfirmer: suspend (ComputerHostCommandConfirmationRequest) -> Boolean = { false },
) : AutoCloseable {
    private val fileTransfer = ComputerFileTransfer()
    private val runtimeEnvelope = ComputerRuntimeEnvelope(context.applicationContext)
    private val terminalManager = ComputerTerminalManager(repository)
    private val completedResults = ConcurrentHashMap<String, JsonElement>()

    suspend fun execute(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
    ): JsonElement {
        val foregroundActivity = repository.acquireForegroundActivity()
        return try {
            executeWhileActive(toolName, arguments, toolCallId, requestContext)
        } finally {
            foregroundActivity.close()
        }
    }

    /** 在模型首轮思考期间建立 SSH Transport，并完成 Runtime 版本校验。 */
    suspend fun prewarm(requestContext: ComputerRequestContext) {
        val workspace = requireRequestWorkspace(requestContext)
        repository.withConnection(requestContext.computerId) { connection, computer ->
            runtimeEnvelope.prewarm(connection, computer)
        }
        // 读取 Workspace 能确保预热和后续工具调用绑定同一个本地请求快照。
        check(workspace.id == requestContext.workspaceId)
    }

    private suspend fun executeWhileActive(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
    ): JsonElement {
        if (toolName !in ComputerToolNames.all) {
            return errorEnvelope("", ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Computer Tool 不存在"))
        }
        val workspace = requireRequestWorkspace(requestContext)
        val currentRequestContext = requestContext.copy(conversationId = workspace.conversationId)
        val toolCallKey = ComputerToolRequestHasher.toolCallKey(toolCallId, requestContext)
        val requestHash = ComputerToolRequestHasher.requestHash(toolName, arguments, requestContext)
        completedResults[toolCallKey]?.let { return it }

        val dao = repository.dao()
        val existing = dao.getExecutionByToolCallId(toolCallKey)?.toModel()
        if (existing != null) {
            if (existing.requestHash != requestHash) {
                return errorEnvelope(
                    existing.id,
                    ComputerException(ComputerErrorCodes.IDEMPOTENCY_CONFLICT, "Tool Call ID 与原请求不一致"),
                )
            }
            if (existing.status in setOf(
                    ComputerExecutionStatus.QUEUED,
                    ComputerExecutionStatus.STARTING,
                    ComputerExecutionStatus.RUNNING,
                    ComputerExecutionStatus.UNKNOWN,
                )
            ) {
                return errorEnvelope(
                    existing.id,
                    ComputerException(
                        ComputerErrorCodes.EXECUTION_UNKNOWN,
                        "原 Tool Call 的远端状态无法确认",
                        action = "CHECK_EXECUTION",
                    ),
                )
            }
            return buildJsonObject {
                put("ok", existing.status == ComputerExecutionStatus.SUCCEEDED)
                put("execution_id", existing.id)
                put("recovered", true)
                put("summary", existing.safeSummary.orEmpty())
                existing.errorCode?.let { code ->
                    put("error", buildJsonObject {
                        put("code", code)
                        put("message", "上次执行结果仅保留了安全摘要")
                        put("retryable", false)
                    })
                }
            }
        }

        var execution = ComputerExecution(
            id = "execution_${UUID.randomUUID().toString().replace("-", "")}",
            toolCallId = toolCallKey,
            computerId = requestContext.computerId,
            workspaceId = requestContext.workspaceId,
            toolName = toolName,
            requestHash = requestHash,
            status = ComputerExecutionStatus.STARTING,
            startedAt = System.currentTimeMillis(),
        )
        dao.upsertExecution(execution.toEntity())

        return try {
            execution = execution.copy(status = ComputerExecutionStatus.RUNNING)
            dao.upsertExecution(execution.toEntity())
            val data = dispatch(toolName, arguments, toolCallId, currentRequestContext, workspace, execution.id)
            val response = successEnvelope(execution.id, data)
            execution = execution.copy(
                status = ComputerExecutionStatus.SUCCEEDED,
                finishedAt = System.currentTimeMillis(),
                safeSummary = safeSummary(toolName),
            )
            dao.upsertExecution(execution.toEntity())
            completedResults[toolCallKey] = response
            response
        } catch (error: CancellationException) {
            dao.upsertExecution(
                execution.copy(
                    status = ComputerExecutionStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis(),
                    safeSummary = "$toolName 已取消",
                ).toEntity(),
            )
            throw error
        } catch (error: Throwable) {
            val computerError = error as? ComputerException ?: ComputerException(
                ComputerErrorCodes.EXECUTION_UNKNOWN,
                "Computer Tool 执行失败",
                retryable = true,
                cause = error,
            )
            val status = if (computerError.code == ComputerErrorCodes.EXECUTION_UNKNOWN) {
                ComputerExecutionStatus.UNKNOWN
            } else {
                ComputerExecutionStatus.FAILED
            }
            dao.upsertExecution(
                execution.copy(
                    status = status,
                    finishedAt = System.currentTimeMillis(),
                    errorCode = computerError.code,
                    safeSummary = "$toolName：${computerError.code}",
                ).toEntity(),
            )
            errorEnvelope(execution.id, computerError)
        }
    }

    private suspend fun dispatch(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
    ): JsonElement = when (toolName) {
        ComputerToolNames.EXEC -> executeCommand(arguments, requestContext, workspace, executionId)
        ComputerToolNames.READ_FILE -> readFile(arguments, requestContext, workspace)
        ComputerToolNames.WRITE_FILE -> writeFile(arguments, requestContext, workspace, executionId)
        ComputerToolNames.TERMINAL -> terminal(arguments, requestContext, workspace)
        ComputerToolNames.UPLOAD -> upload(arguments, requestContext, workspace, executionId)
        ComputerToolNames.DOWNLOAD -> download(arguments, requestContext, workspace)
        ComputerToolNames.OPEN_PORT -> openPort(arguments, requestContext)
        else -> throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Computer Tool 不存在")
    }

    private suspend fun executeCommand(
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
    ): JsonElement {
        val environment = arguments.objectOrEmpty("env").mapValues { (name, value) ->
            value.stringValue("env.$name")
        }
        val secretNames = arguments.stringList("secret_names")
        val command = arguments.requiredString("command")
        val target = when (arguments.optionalString("target") ?: "container") {
            "container" -> ComputerExecTarget.CONTAINER
            "host" -> ComputerExecTarget.HOST
            else -> throw invalidArgument("target")
        }
        val cwd = arguments.optionalString("cwd") ?: if (target == ComputerExecTarget.HOST) "~" else "/workspace"
        val stdin = arguments.optionalString("stdin")
        val timeoutMillis = arguments.optionalLong("timeout_ms") ?: 120_000
        val background = arguments.optionalBoolean("background") ?: false
        val asRoot = arguments.optionalBoolean("as_root") ?: false
        if (target == ComputerExecTarget.HOST && secretNames.isNotEmpty()) {
            throw ComputerException(
                ComputerErrorCodes.WORKSPACE_PATH_INVALID,
                "VPS 主机命令不允许注入 Workspace Secret",
            )
        }
        val secrets = secretManager.loadSelected(workspace.id, secretNames)
        val request = ComputerExecRequest(
            command = command,
            cwd = cwd,
            environment = environment,
            secrets = secrets,
            stdin = stdin,
            timeoutMillis = timeoutMillis,
            background = background,
            asRoot = asRoot,
            target = target,
        )
        val result = try {
            val runRequest: suspend (ComputerExecRequest) -> ComputerExecResult = { frozenRequest ->
                repository.withConnection(context.computerId) { connection, computer ->
                    runtimeEnvelope.execute(connection, computer, workspace, executionId, frozenRequest)
                }
            }
            if (target == ComputerExecTarget.HOST) {
                val computer = repository.getComputer(context.computerId)
                    ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
                executeHostCommandWithConfirmation(
                    request = request,
                    permissionMode = context.permissionMode,
                    askUserApproval = arguments.optionalBoolean("ask_user_approval"),
                    confirmationRequest = { assessment ->
                        ComputerHostCommandConfirmationRequest(
                            requestId = executionId,
                            context = context,
                            computerName = computer.displayName,
                            command = request.command,
                            cwd = request.cwd,
                            requestsPrivilege = request.asRoot ||
                                ComputerHostCommandRisk.PRIVILEGE_ESCALATION in assessment.risks,
                            reason = assessment.reason ?: "AI 请求确认这次 VPS 命令",
                            risks = assessment.risks,
                        )
                    },
                    confirmer = hostCommandConfirmer,
                    execute = runRequest,
                )
            } else {
                runRequest(request)
            }
        } finally {
            secrets.values.forEach { it.fill('\u0000') }
        }
        return buildJsonObject {
            put("exit_code", result.exitCode?.let(::JsonPrimitive) ?: JsonNull)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timed_out", result.timedOut)
            put("stdout_truncated", result.stdoutTruncated)
            put("stderr_truncated", result.stderrTruncated)
            result.processId?.let { put("process_id", it) }
            result.pid?.let { put("pid", it) }
            result.logPath?.let { put("log_path", it) }
        }
    }

    private suspend fun readFile(
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
    ): JsonElement {
        val encoding = when (arguments.optionalString("encoding") ?: "utf8") {
            "utf8" -> ComputerFileEncoding.UTF8
            "base64" -> ComputerFileEncoding.BASE64
            else -> throw invalidArgument("encoding")
        }
        val page = repository.withConnection(context.computerId) { connection, _ ->
            fileTransfer.read(
                connection = connection,
                workspace = workspace,
                path = arguments.requiredString("path"),
                offset = arguments.optionalLong("offset") ?: 0,
                limit = (arguments.optionalLong("limit") ?: DEFAULT_FILE_READ_LIMIT.toLong()).toIntChecked("limit"),
                encoding = encoding,
            )
        }
        return buildJsonObject {
            put("path", page.path)
            put("content", page.content)
            put("encoding", page.encoding.name.lowercase())
            put("offset", page.offset)
            page.nextOffset?.let { put("next_offset", it) }
            put("size", page.size)
            put("truncated", page.truncated)
        }
    }

    private suspend fun writeFile(
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
    ): JsonElement {
        val encoding = when (arguments.optionalString("encoding") ?: "utf8") {
            "utf8" -> ComputerFileEncoding.UTF8
            "base64" -> ComputerFileEncoding.BASE64
            else -> throw invalidArgument("encoding")
        }
        val mode = when (arguments.optionalString("mode") ?: "overwrite") {
            "overwrite" -> ComputerFileWriteMode.OVERWRITE
            "append" -> ComputerFileWriteMode.APPEND
            else -> throw invalidArgument("mode")
        }
        val result = repository.withConnection(context.computerId) { connection, _ ->
            fileTransfer.write(
                connection = connection,
                workspace = workspace,
                toolCallId = executionId,
                path = arguments.requiredString("path"),
                content = arguments.requiredString("content"),
                encoding = encoding,
                mode = mode,
                createParents = arguments.optionalBoolean("create_parents") ?: false,
            )
        }
        return buildJsonObject {
            put("path", result.path)
            put("bytes_written", result.bytesWritten)
            put("size", result.size)
        }
    }

    private suspend fun terminal(
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
    ): JsonElement {
        val action = arguments.requiredString("action")
        val terminalId = arguments.optionalString("terminal_id")
        return when (action) {
            "open" -> terminalManager.open(
                context,
                workspace,
                columns = (arguments.optionalLong("cols") ?: 120).toIntChecked("cols"),
                rows = (arguments.optionalLong("rows") ?: 40).toIntChecked("rows"),
            ).toJson()
            "read" -> terminalManager.read(
                context,
                terminalId ?: throw invalidArgument("terminal_id"),
                arguments.optionalLong("cursor") ?: 0,
            ).toJson()
            "write" -> {
                terminalManager.write(
                    context,
                    terminalId ?: throw invalidArgument("terminal_id"),
                    arguments.requiredString("input"),
                )
                buildJsonObject { put("written", true) }
            }
            "resize" -> {
                terminalManager.resize(
                    context,
                    terminalId ?: throw invalidArgument("terminal_id"),
                    (arguments.optionalLong("cols") ?: throw invalidArgument("cols")).toIntChecked("cols"),
                    (arguments.optionalLong("rows") ?: throw invalidArgument("rows")).toIntChecked("rows"),
                )
                buildJsonObject { put("resized", true) }
            }
            "close" -> {
                terminalManager.close(context, terminalId ?: throw invalidArgument("terminal_id"))
                buildJsonObject { put("closed", true) }
            }
            else -> throw invalidArgument("action")
        }
    }

    private suspend fun upload(
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
    ): JsonElement {
        val bridge = attachmentBridge
            ?: throw ComputerException(ComputerErrorCodes.UPLOAD_INTERRUPTED, "当前请求没有附件桥接器")
        val attachmentId = arguments.requiredString("attachment_id")
        val source = bridge.resolveUpload(context.conversationId, attachmentId)
            ?: throw ComputerException(ComputerErrorCodes.UPLOAD_INTERRUPTED, "当前会话不存在该附件")
        val result = source.openStream().use { input ->
            repository.withConnection(context.computerId) { connection, _ ->
                fileTransfer.upload(
                    connection = connection,
                    workspace = workspace,
                    toolCallId = executionId,
                    destinationPath = arguments.requiredString("destination_path"),
                    source = input,
                    expectedSize = source.size,
                    overwrite = arguments.optionalBoolean("overwrite") ?: false,
                )
            }
        }
        return buildJsonObject {
            put("path", result.path)
            put("name", source.displayName)
            put("mime", source.mimeType)
            put("size", result.bytes)
            put("sha256", result.sha256)
        }
    }

    private suspend fun download(
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
    ): JsonElement {
        val bridge = attachmentBridge
            ?: throw ComputerException(ComputerErrorCodes.DOWNLOAD_INTERRUPTED, "当前请求没有下载桥接器")
        val sourcePath = arguments.requiredString("source_path")
        val name = arguments.optionalString("suggested_name")
            ?: sourcePath.substringAfterLast('/').ifBlank { "download.bin" }
        val downloaded = bridge.receiveDownload(context.conversationId, name) { output ->
            repository.withConnection(context.computerId) { connection, _ ->
                fileTransfer.download(connection, workspace, sourcePath, output)
            }
        }
        return buildJsonObject {
            put("attachment_id", downloaded.attachment.id)
            put("name", downloaded.attachment.displayName)
            put("mime", downloaded.attachment.mimeType)
            put("size", downloaded.transfer.bytes)
            put("sha256", downloaded.transfer.sha256)
        }
    }

    private suspend fun openPort(arguments: JsonObject, context: ComputerRequestContext): JsonElement {
        val port = (arguments.optionalLong("port") ?: throw invalidArgument("port")).toIntChecked("port")
        val protocol = arguments.optionalString("protocol") ?: "http"
        val visibility = arguments.optionalString("visibility") ?: "private"
        val target = when (arguments.optionalString("target") ?: "container") {
            "container" -> ComputerExecTarget.CONTAINER
            "host" -> ComputerExecTarget.HOST
            else -> throw invalidArgument("target")
        }
        val result = when (visibility) {
            "private" -> previewManager.openPrivate(context, port, protocol, target)
            "public" -> {
                val request = ComputerPublicPreviewRequest(
                    context = context,
                    port = port,
                    protocol = protocol,
                    expiresInSeconds = arguments.optionalLong("expires_in_seconds"),
                    target = target,
                )
                val requiresConfirmation = when (context.permissionMode) {
                    ComputerPermissionMode.MANUAL -> true
                    ComputerPermissionMode.SMART -> arguments.optionalBoolean("ask_user_approval")
                        ?: throw invalidArgument("ask_user_approval")
                    ComputerPermissionMode.FULL -> false
                }
                if (requiresConfirmation && !publicPreviewConfirmer(request)) {
                    throw ComputerException(
                        ComputerErrorCodes.PUBLIC_PORT_BLOCKED,
                        "用户未确认 Public Preview",
                        action = "CONFIRM_PUBLIC_PREVIEW",
                    )
                }
                previewManager.confirmPublic(request)
            }
            else -> throw invalidArgument("visibility")
        }
        return buildJsonObject {
            put("preview_id", result.preview.id)
            put("url", result.url)
            put("visibility", result.preview.visibility.name.lowercase())
            put("target", result.preview.target.name.lowercase())
            result.preview.expiresAt?.let { put("expires_at", it) }
            result.warning?.let { put("warning", it) }
        }
    }

    private suspend fun requireRequestWorkspace(context: ComputerRequestContext): ComputerWorkspace {
        val workspace = repository.dao().getWorkspaceById(context.workspaceId)?.toModel()
            ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
        if (!workspace.matchesRequestContext(context)) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 请求快照不匹配")
        }
        return workspace
    }

    private fun successEnvelope(executionId: String, data: JsonElement): JsonObject = buildJsonObject {
        put("ok", true)
        put("execution_id", executionId)
        put("data", data)
    }

    private fun errorEnvelope(executionId: String, error: ComputerException): JsonObject = buildJsonObject {
        put("ok", false)
        put("execution_id", executionId)
        put("error", buildJsonObject {
            put("code", error.code)
            put("message", error.message)
            put("retryable", error.retryable)
            error.action?.let { put("action", it) }
        })
    }

    private fun safeSummary(toolName: String): String = when (toolName) {
        ComputerToolNames.EXEC -> "exec 已完成"
        ComputerToolNames.READ_FILE -> "read_file 已读取文件页"
        ComputerToolNames.WRITE_FILE -> "write_file 已写入文件"
        ComputerToolNames.TERMINAL -> "terminal 操作已完成"
        ComputerToolNames.UPLOAD -> "upload 已完成"
        ComputerToolNames.DOWNLOAD -> "download 已保存本地附件"
        ComputerToolNames.OPEN_PORT -> "open_port 已创建 Preview"
        else -> "Computer Tool 已完成"
    }

    /** 删除 Workspace 前关闭仍在本机进程中的 PTY，避免留下失效会话。 */
    fun closeWorkspace(workspaceId: String) {
        terminalManager.closeWorkspace(workspaceId)
    }

    fun closeTransientConnections() {
        terminalManager.closeActiveSessions()
    }

    override fun close() {
        terminalManager.close()
        completedResults.clear()
    }
}

internal object ComputerToolRequestHasher {
    fun toolCallKey(toolCallId: String, context: ComputerRequestContext): String {
        if (toolCallId.isBlank() || toolCallId.length > 1024 || toolCallId.any(Char::isISOControl)) {
            throw ComputerException(ComputerErrorCodes.IDEMPOTENCY_CONFLICT, "Tool Call ID 无效")
        }
        return sha256("${context.conversationId}\u0000${context.computerId}\u0000${context.workspaceId}\u0000$toolCallId")
    }

    fun requestHash(toolName: String, arguments: JsonObject, context: ComputerRequestContext): String = sha256(
        "$toolName\u0000${context.conversationId}\u0000${context.computerId}\u0000${context.workspaceId}\u0000${canonical(arguments)}",
    )

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${JsonPrimitive(key)}:${canonical(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> element.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun JsonObject.requiredString(name: String): String = optionalString(name)
    ?: throw invalidArgument(name)

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw invalidArgument(name)
    if (!primitive.isString) throw invalidArgument(name)
    return primitive.content
}

private fun JsonObject.optionalLong(name: String): Long? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.longOrNull ?: throw invalidArgument(name)
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.booleanOrNull ?: throw invalidArgument(name)
}

private fun JsonObject.objectOrEmpty(name: String): JsonObject {
    val value = this[name] ?: return JsonObject(emptyMap())
    if (value is JsonNull) return JsonObject(emptyMap())
    return value as? JsonObject ?: throw invalidArgument(name)
}

private fun JsonObject.stringList(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? JsonArray ?: throw invalidArgument(name)
    return array.mapIndexed { index, element -> element.stringValue("$name[$index]") }
}

private fun JsonElement.stringValue(name: String): String {
    val primitive = this as? JsonPrimitive ?: throw invalidArgument(name)
    if (!primitive.isString) throw invalidArgument(name)
    return primitive.content
}

private fun Long.toIntChecked(name: String): Int {
    if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw invalidArgument(name)
    return toInt()
}

private fun ComputerTerminalReadResult.toJson(): JsonObject = buildJsonObject {
    put("terminal_id", terminalId)
    put("output", output)
    put("cursor", cursor)
    put("dropped_before_cursor", droppedBeforeCursor)
    put("open", open)
}

private fun invalidArgument(name: String) = ComputerException(
    ComputerErrorCodes.WORKSPACE_PATH_INVALID,
    "Tool 参数 $name 无效",
)

package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import com.android.everytalk.util.AppLogger

private const val DEFAULT_FILE_READ_LIMIT = 256 * 1024
private const val MAX_COMPLETED_RESULTS_IN_MEMORY = 32

/** 八个 Computer Tool 的统一参数校验、幂等、状态和路由入口。 */
class ComputerToolExecutor(
    context: Context,
    private val repository: ComputerRepository,
    private val previewManager: ComputerPreviewManager,
    private val secretManager: ComputerWorkspaceSecretManager,
    private val attachmentBridge: ComputerAttachmentBridge? = null,
    private val publicPreviewConfirmer: suspend (ComputerPublicPreviewRequest) -> Boolean = { false },
    private val hostCommandConfirmer: suspend (ComputerHostCommandConfirmationRequest) -> Boolean = { false },
) : AutoCloseable {
    private val fileTransfer = ComputerFileTransfer()
    private val terminalManager = ComputerTerminalManager(repository)
    /**
     * 只缓存最近的完整工具结果，保证短期重试能拿到原响应。
     * 历史幂等事实已经落在 Room，不能再让可能包含文件正文的 Json 永久占用内存。
     */
    private val completedResults = Collections.synchronizedMap(
        object : LinkedHashMap<String, JsonElement>(MAX_COMPLETED_RESULTS_IN_MEMORY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>?): Boolean =
                size > MAX_COMPLETED_RESULTS_IN_MEMORY
        },
    )

    suspend fun execute(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit = {},
    ): JsonElement {
        val foregroundActivity = repository.acquireForegroundActivity()
        return try {
            // 权限模式可以在 Agent 运行期间从服务器详情页修改，不能沿用启动时快照。
            executeWhileActive(
                toolName,
                arguments,
                toolCallId,
                repository.refreshPermissionMode(requestContext),
                updateStatus,
            )
        } finally {
            foregroundActivity.close()
        }
    }

    /** 在模型首轮思考期间建立 SSH Transport，后续工具调用直接复用这条连接。 */
    suspend fun prewarm(requestContext: ComputerRequestContext) {
        val workspace = requireRequestWorkspace(requestContext)
        repository.withConnection(requestContext.computerId) { _, _ -> }
        // 读取 Workspace 能确保预热和后续工具调用绑定同一个本地请求快照。
        check(workspace.id == requestContext.workspaceId)
    }

    /**
     * 在创建 ComputerExecution 或连接 VPS 前冻结审批内容。
     * 返回 null 代表当前权限档位允许直接执行。
     */
    suspend fun approvalRequest(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
        phase: ComputerToolApprovalPhase,
    ): ComputerToolApprovalRequest? {
        if (toolName !in ComputerToolNames.all) return null
        val currentRequestContext = repository.refreshPermissionMode(requestContext)
        val workspace = requireRequestWorkspace(currentRequestContext)
        val context = currentRequestContext.copy(conversationId = workspace.conversationId)
        val computer = repository.getComputer(context.computerId)
            ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")

        if (phase == ComputerToolApprovalPhase.RETRY_UNKNOWN) {
            val key = ComputerToolRequestHasher.toolCallKey(toolCallId, context)
            val existing = repository.dao().getExecutionByToolCallId(key)?.toModel()
                ?.takeIf { it.status == ComputerExecutionStatus.UNKNOWN }
                ?: return null
            if (!ComputerToolCallSafety.requiresUnknownApproval(toolName, arguments, context.permissionMode)) {
                return null
            }
            return ComputerToolApprovalRequest.UnknownExecution(
                toolCallId = toolCallId,
                context = context,
                computerName = computer.displayName,
                toolName = toolName,
                detail = approvalDetail(toolName, arguments),
                isWriteOperation = !ComputerToolCallSafety.isReadOnly(toolName, arguments),
            )
        }

        return when (toolName) {
            ComputerToolNames.EXEC -> hostCommandApproval(arguments, toolCallId, context, computer)
            ComputerToolNames.OPEN_PORT -> publicPreviewApproval(arguments, toolCallId, context, computer)
            else -> null
        }
    }

    private suspend fun executeWhileActive(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit,
    ): JsonElement {
        if (toolName !in ComputerToolNames.all) {
            return errorEnvelope("", ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Computer Tool 不存在"))
        }
        val workspace = requireRequestWorkspace(requestContext)
        val currentRequestContext = requestContext.copy(conversationId = workspace.conversationId)
        val toolCallKey = ComputerToolRequestHasher.toolCallKey(toolCallId, currentRequestContext)
        val requestHash = ComputerToolRequestHasher.requestHash(toolName, arguments, currentRequestContext)
        completedResults[toolCallKey]?.let { return it }

        val dao = repository.dao()
        val existing = dao.getExecutionByToolCallId(toolCallKey)?.toModel()
        if (existing != null) {
            if (existing.requestHash != requestHash) {
                return errorEnvelope(
                    existing.id,
                    ComputerException(ComputerErrorCodes.IDEMPOTENCY_CONFLICT, "Tool Call ID 与原请求不一致"),
                    status = existing.status.name,
                    remoteStatus = existing.remoteStatus?.name,
                    target = existing.target?.name,
                    durationMillis = executionDurationMillis(existing),
                )
            }
            if (existing.status == ComputerExecutionStatus.UNKNOWN &&
                currentRequestContext.retryUnknownToolCallId == toolCallId
            ) {
                // 用户明确选择重新执行后废弃旧 UNKNOWN 记录；toolCallId 唯一索引随后接管新执行。
                dao.deleteExecution(existing.id)
            } else if (existing.status in setOf(
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
                    status = existing.status.name,
                    remoteStatus = existing.remoteStatus?.name,
                    target = existing.target?.name,
                    durationMillis = executionDurationMillis(existing),
                )
            } else {
                return buildJsonObject {
                    put("ok", existing.status == ComputerExecutionStatus.SUCCEEDED)
                    put("execution_id", existing.id)
                    put("status", existing.status.name)
                    existing.remoteStatus?.let { put("remote_status", it.name) }
                    existing.target?.let { put("target", it.name) }
                    existing.remoteExitCode?.let { put("exit_code", it) }
                    put("duration_ms", executionDurationMillis(existing))
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
        }

        val initialExecRequest = if (toolName == ComputerToolNames.EXEC) {
            parseExecRequestWithoutSecrets(arguments)
        } else {
            null
        }
        val executionId = "execution_${UUID.randomUUID().toString().replace("-", "")}"
        var execution = ComputerExecution(
            id = executionId,
            toolCallId = toolCallKey,
            computerId = currentRequestContext.computerId,
            workspaceId = currentRequestContext.workspaceId,
            toolName = toolName,
            requestHash = requestHash,
            status = ComputerExecutionStatus.STARTING,
            startedAt = System.currentTimeMillis(),
            target = initialExecRequest?.let { ComputerExecTarget.HOST },
            completionMode = initialExecRequest?.let { ComputerExecutionCompletionMode.WAIT_FOR_RESULT },
            runId = currentRequestContext.runId,
        )
        dao.upsertExecution(execution.toEntity())

        return try {
            execution = execution.copy(status = ComputerExecutionStatus.RUNNING)
            dao.upsertExecution(execution.toEntity())
            val data = dispatch(
                toolName,
                arguments,
                toolCallId,
                currentRequestContext,
                workspace,
                execution.id,
                requestHash,
                updateStatus,
            )
            val observed = dao.getExecutionById(execution.id)?.toModel() ?: execution
            // exec 的结果状态和退出码来自本次通道返回的数据，其他工具成功返回即成功。
            val execResult = (data as? JsonObject)?.takeIf { toolName == ComputerToolNames.EXEC }
            val remoteStatus = (execResult?.get("status") as? JsonPrimitive)?.content
                ?.let { status -> ComputerRemoteStatus.entries.firstOrNull { it.name == status } }
            val finalStatus = when (remoteStatus) {
                ComputerRemoteStatus.FAILED -> ComputerExecutionStatus.FAILED
                ComputerRemoteStatus.TIMED_OUT -> ComputerExecutionStatus.TIMED_OUT
                else -> ComputerExecutionStatus.SUCCEEDED
            }
            execution = observed.copy(
                status = finalStatus,
                remoteStatus = remoteStatus ?: observed.remoteStatus,
                remoteExitCode = (execResult?.get("exit_code") as? JsonPrimitive)?.content?.toIntOrNull(),
                finishedAt = System.currentTimeMillis(),
                safeSummary = safeSummary(toolName),
            )
            dao.upsertExecution(execution.toEntity())
            val response = successEnvelope(
                execution.id,
                data,
                ok = finalStatus == ComputerExecutionStatus.SUCCEEDED,
            )
            completedResults[toolCallKey] = response
            response
        } catch (error: CancellationException) {
            // 取消时 SSH 层已向远端进程发 TERM，通道随之关闭，本地直接收尾。
            val observed = dao.getExecutionById(execution.id)?.toModel() ?: execution
            dao.upsertExecution(
                observed.copy(
                    status = ComputerExecutionStatus.CANCELLED,
                    remoteStatus = if (toolName == ComputerToolNames.EXEC) ComputerRemoteStatus.CANCELLED else observed.remoteStatus,
                    finishedAt = System.currentTimeMillis(),
                    safeSummary = "$toolName 已取消",
                ).toEntity(),
            )
            throw error
        } catch (error: Throwable) {
            val computerError = when (error) {
                is ComputerException -> error
                else -> ComputerException(
                    ComputerErrorCodes.EXECUTION_UNKNOWN,
                    "Computer Tool 执行失败",
                    retryable = true,
                    cause = error,
                )
            }
            AppLogger.warn(
                "ComputerRuntime",
                "Execution 失败 execution=${execution.id} code=${computerError.code} " +
                    "type=${error::class.java.simpleName} message=${error.message}",
            )
            val status = if (computerError.code in setOf(
                    ComputerErrorCodes.EXECUTION_UNKNOWN,
                    ComputerErrorCodes.EXECUTION_RESULT_UNAVAILABLE,
                    ComputerErrorCodes.EXECUTION_STATE_INVALID,
                )
            ) {
                ComputerExecutionStatus.UNKNOWN
            } else {
                ComputerExecutionStatus.FAILED
            }
            val observed = dao.getExecutionById(execution.id)?.toModel() ?: execution
            val updated = observed.copy(
                status = status,
                finishedAt = System.currentTimeMillis(),
                errorCode = computerError.code,
                safeSummary = "$toolName：${computerError.code}",
            )
            dao.upsertExecution(updated.toEntity())
            errorEnvelope(
                executionId = execution.id,
                error = computerError,
                status = status.name,
                remoteStatus = updated.remoteStatus?.name,
                target = updated.target?.name,
                exitCode = updated.remoteExitCode ?: updated.exitCode,
                durationMillis = executionDurationMillis(updated),
            )
        }
    }

    private suspend fun dispatch(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
        requestHash: String,
        updateStatus: suspend (String?) -> Unit,
    ): JsonElement = when (toolName) {
        ComputerToolNames.EXEC -> executeCommand(arguments, toolCallId, requestContext, executionId, requestHash, updateStatus)
        ComputerToolNames.READ_FILE -> readFile(arguments, requestContext)
        ComputerToolNames.WRITE_FILE -> writeFile(arguments, requestContext, executionId)
        ComputerToolNames.EDIT -> editFile(arguments, requestContext, executionId)
        ComputerToolNames.TERMINAL -> terminal(arguments, requestContext, workspace)
        ComputerToolNames.UPLOAD -> upload(arguments, requestContext, executionId)
        ComputerToolNames.DOWNLOAD -> download(arguments, requestContext)
        ComputerToolNames.OPEN_PORT -> openPort(arguments, toolCallId, requestContext)
        else -> throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Computer Tool 不存在")
    }

    private suspend fun executeCommand(
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        executionId: String,
        requestHash: String,
        updateStatus: suspend (String?) -> Unit,
    ): JsonElement {
        val request = parseExecRequest(arguments, context, loadSecrets = true)
        val readOnlyRequest = ComputerToolCallSafety.isReadOnly(ComputerToolNames.EXEC, arguments)
        val runRequest: suspend (ComputerExecRequest) -> ComputerExecResult = { frozenRequest ->
            executeDirectRequestWithRetry(
                context = context,
                request = frozenRequest,
                readOnlyRequest = readOnlyRequest,
                updateStatus = updateStatus,
            )
        }
        val result = if (context.approvedToolCallId != toolCallId) {
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
                        requestsPrivilege = ComputerHostCommandRisk.PRIVILEGE_ESCALATION in assessment.risks,
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
        return buildJsonObject {
            val status = result.remoteStatus ?: when {
                result.timedOut -> ComputerRemoteStatus.TIMED_OUT
                result.exitCode == 0 -> ComputerRemoteStatus.SUCCEEDED
                else -> ComputerRemoteStatus.FAILED
            }
            put("status", status.name)
            put("exit_code", result.exitCode?.let(::JsonPrimitive) ?: JsonNull)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timed_out", result.timedOut)
            put("stdout_truncated", result.stdoutTruncated)
            put("stderr_truncated", result.stderrTruncated)
            put("duration_ms", result.durationMillis ?: 0L)
        }
    }

    /** 只读命令在连接中断时换一条新连接重跑一次；写操作不自动重跑，避免重复修改 VPS。 */
    private suspend fun executeDirectRequestWithRetry(
        context: ComputerRequestContext,
        request: ComputerExecRequest,
        readOnlyRequest: Boolean,
        updateStatus: suspend (String?) -> Unit,
    ): ComputerExecResult {
        try {
            return executeDirectRequest(context, request, updateStatus)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!shouldRetryReadOnlyExecution(readOnlyRequest, error)) throw error
            updateStatus("SSH 连接中断，正在重新执行只读命令")
            repository.invalidateConnection(context.computerId)
            return executeDirectRequest(context, request, updateStatus)
        }
    }

    private suspend fun executeDirectRequest(
        context: ComputerRequestContext,
        request: ComputerExecRequest,
        updateStatus: suspend (String?) -> Unit,
    ): ComputerExecResult = repository.withConnection(context.computerId) { connection, _ ->
        updateStatus("正在运行")
        connection.runComputerExec(request).also { result ->
            updateStatus(
                when (result.remoteStatus) {
                    ComputerRemoteStatus.SUCCEEDED -> "命令执行完成"
                    ComputerRemoteStatus.TIMED_OUT -> "命令执行超时"
                    else -> "命令执行失败"
                },
            )
        }
    }

    private suspend fun readFile(
        arguments: JsonObject,
        context: ComputerRequestContext,
    ): JsonElement {
        val encoding = when (arguments.optionalString("encoding") ?: "utf8") {
            "utf8" -> ComputerFileEncoding.UTF8
            "base64" -> ComputerFileEncoding.BASE64
            else -> throw invalidArgument("encoding")
        }
        val page = repository.withConnection(context.computerId) { connection, _ ->
            fileTransfer.read(
                connection = connection,
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

    private suspend fun editFile(
        arguments: JsonObject,
        context: ComputerRequestContext,
        executionId: String,
    ): JsonElement {
        val rawEdits = arguments["edits"] as? JsonArray ?: throw invalidArgument("edits")
        if (rawEdits.isEmpty()) throw invalidArgument("edits")
        val edits = rawEdits.mapIndexed { index, element ->
            val edit = element as? JsonObject ?: throw invalidArgument("edits[$index]")
            ComputerTextEdit(
                oldText = edit.requiredString("oldText"),
                newText = edit.requiredString("newText"),
            )
        }
        val result = repository.withConnection(context.computerId) { connection, _ ->
            fileTransfer.edit(
                connection = connection,
                toolCallId = executionId,
                path = arguments.requiredString("path"),
                edits = edits,
            )
        }
        return buildJsonObject {
            put("path", result.path)
            put("replacements", result.replacements)
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
    ): JsonElement {
        val bridge = attachmentBridge
            ?: throw ComputerException(ComputerErrorCodes.DOWNLOAD_INTERRUPTED, "当前请求没有下载桥接器")
        val runId = context.runId
            ?: throw ComputerException(ComputerErrorCodes.DOWNLOAD_INTERRUPTED, "当前请求没有 Agent 任务 ID")
        val sourcePath = arguments.requiredString("source_path")
        val name = arguments.optionalString("suggested_name")
            ?: sourcePath.substringAfterLast('/').ifBlank { "download.bin" }
        val downloaded = bridge.receiveDownload(context.conversationId, runId, name) { output ->
            repository.withConnection(context.computerId) { connection, _ ->
                fileTransfer.download(connection, sourcePath, output)
            }
        }
        return buildJsonObject {
            put("attachment_id", downloaded.attachment.id)
            put("file_link", "everytalk-file:${downloaded.attachment.id}")
            put("name", downloaded.attachment.displayName)
            put("mime", downloaded.attachment.mimeType)
            put("size", downloaded.transfer.bytes)
            put("sha256", downloaded.transfer.sha256)
            put("saved_path", downloaded.savedDownload.path)
            put("saved_name", downloaded.savedDownload.name)
        }
    }

    private suspend fun openPort(
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
    ): JsonElement {
        val port = (arguments.optionalLong("port") ?: throw invalidArgument("port")).toIntChecked("port")
        val protocol = arguments.optionalString("protocol") ?: "http"
        val visibility = arguments.optionalString("visibility") ?: "private"
        val result = when (visibility) {
            "private" -> previewManager.openPrivate(context, port, protocol)
            "public" -> {
                val request = ComputerPublicPreviewRequest(
                    context = context,
                    port = port,
                    protocol = protocol,
                    expiresInSeconds = arguments.optionalLong("expires_in_seconds"),
                )
                val requiresConfirmation = when (context.permissionMode) {
                    ComputerPermissionMode.MANUAL -> true
                    ComputerPermissionMode.SMART -> arguments.optionalBoolean("ask_user_approval")
                        ?: throw invalidArgument("ask_user_approval")
                    ComputerPermissionMode.FULL -> false
                }
                if (requiresConfirmation && context.approvedToolCallId != toolCallId &&
                    !publicPreviewConfirmer(request)
                ) {
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

    private fun successEnvelope(
        executionId: String,
        data: JsonElement,
        ok: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("ok", ok)
        put("execution_id", executionId)
        put("data", data)
    }

    /**
     * 错误也遵守 Computer Tool Result 的固定外层协议。
     * 即使远端状态未知，模型仍能看到当前本地状态和等待时长，避免把错误结果误读成普通文本。
     */
    private fun errorEnvelope(
        executionId: String,
        error: ComputerException,
        status: String = ComputerExecutionStatus.FAILED.name,
        remoteStatus: String? = null,
        target: String? = null,
        exitCode: Int? = null,
        durationMillis: Long = 0L,
    ): JsonObject = buildJsonObject {
        put("ok", false)
        put("execution_id", executionId)
        put("status", status)
        put("duration_ms", durationMillis.coerceAtLeast(0L))
        remoteStatus?.let { put("remote_status", it) }
        target?.let { put("target", it) }
        exitCode?.let { put("exit_code", it) }
        put("error", buildJsonObject {
            put("code", error.code)
            put("message", error.message)
            put("retryable", error.retryable)
            error.action?.let { put("action", it) }
        })
    }

    private fun executionDurationMillis(execution: ComputerExecution): Long =
        ((execution.finishedAt ?: System.currentTimeMillis()) - (execution.startedAt ?: System.currentTimeMillis()))
            .coerceAtLeast(0L)

    private fun safeSummary(toolName: String): String = when (toolName) {
        ComputerToolNames.EXEC -> "exec 已完成"
        ComputerToolNames.READ_FILE -> "read_file 已读取文件页"
        ComputerToolNames.WRITE_FILE -> "write_file 已写入文件"
        ComputerToolNames.EDIT -> "edit 已修改文件"
        ComputerToolNames.TERMINAL -> "terminal 操作已完成"
        ComputerToolNames.UPLOAD -> "upload 已完成"
        ComputerToolNames.DOWNLOAD -> "download 已保存到下载/App/AppTalk，并添加聊天附件"
        ComputerToolNames.OPEN_PORT -> "open_port 已创建 Preview"
        else -> "Computer Tool 已完成"
    }

    private suspend fun hostCommandApproval(
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        computer: Computer,
    ): ComputerToolApprovalRequest? {
        val request = parseExecRequest(arguments, context, loadSecrets = false)
        requireValidComputerExecRequest(request)
        val assessment = ComputerHostCommandPolicy.assess(request)
        val requiresConfirmation = when (context.permissionMode) {
            ComputerPermissionMode.MANUAL -> assessment.requiresConfirmation
            ComputerPermissionMode.SMART -> arguments.optionalBoolean("ask_user_approval")
                ?: throw invalidArgument("ask_user_approval")
            ComputerPermissionMode.FULL -> false
        }
        if (!requiresConfirmation) return null
        return ComputerToolApprovalRequest.HostCommand(
            toolCallId = toolCallId,
            request = ComputerHostCommandConfirmationRequest(
                requestId = toolCallId,
                context = context,
                computerName = computer.displayName,
                command = request.command,
                cwd = request.cwd,
                requestsPrivilege = ComputerHostCommandRisk.PRIVILEGE_ESCALATION in assessment.risks,
                reason = assessment.reason ?: "AI 请求确认这次 VPS 命令",
                risks = assessment.risks,
            ),
        )
    }

    private fun publicPreviewApproval(
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        computer: Computer,
    ): ComputerToolApprovalRequest? {
        if ((arguments.optionalString("visibility") ?: "private") != "public") return null
        val requiresConfirmation = when (context.permissionMode) {
            ComputerPermissionMode.MANUAL -> true
            ComputerPermissionMode.SMART -> arguments.optionalBoolean("ask_user_approval")
                ?: throw invalidArgument("ask_user_approval")
            ComputerPermissionMode.FULL -> false
        }
        if (!requiresConfirmation) return null
        return ComputerToolApprovalRequest.PublicPreview(
            toolCallId = toolCallId,
            computerName = computer.displayName,
            request = ComputerPublicPreviewRequest(
                context = context,
                port = (arguments.optionalLong("port") ?: throw invalidArgument("port")).toIntChecked("port"),
                protocol = arguments.optionalString("protocol") ?: "http",
                expiresInSeconds = arguments.optionalLong("expires_in_seconds"),
            ),
        )
    }

    private fun approvalDetail(toolName: String, arguments: JsonObject): String = when (toolName) {
        ComputerToolNames.EXEC -> arguments.optionalString("command").orEmpty()
        ComputerToolNames.READ_FILE, ComputerToolNames.WRITE_FILE, ComputerToolNames.EDIT ->
            arguments.optionalString("path").orEmpty()
        ComputerToolNames.UPLOAD -> arguments.optionalString("destination_path").orEmpty()
        ComputerToolNames.DOWNLOAD -> arguments.optionalString("path").orEmpty()
        ComputerToolNames.OPEN_PORT -> arguments.optionalLong("port")?.toString().orEmpty()
        else -> toolName
    }

    private suspend fun parseExecRequest(
        arguments: JsonObject,
        context: ComputerRequestContext,
        loadSecrets: Boolean,
    ): ComputerExecRequest {
        val request = parseExecRequestWithoutSecrets(arguments)
        if (!loadSecrets) return request
        val secretNames = arguments.stringList("secret_names")
        if (secretNames.isEmpty()) return request
        throw ComputerException(
            ComputerErrorCodes.CREDENTIAL_MISSING,
            "通用 exec 禁止通过 secret_names 注入 Secret，请使用已注册的语义 capability Adapter",
        )
    }

    /** 审批预检只解析参数，不读取 Keystore，避免用户批准前触碰 Secret。 */
    private fun parseExecRequestWithoutSecrets(arguments: JsonObject): ComputerExecRequest {
        if (arguments.stringList("secret_names").isNotEmpty()) {
            throw ComputerException(
                ComputerErrorCodes.CREDENTIAL_MISSING,
                "通用 exec 不允许携带 Secret；请使用已注册的语义 capability Adapter",
            )
        }
        return ComputerExecRequest(
            command = arguments.requiredString("command"),
            cwd = arguments.optionalString("cwd") ?: "~",
            timeoutMillis = arguments.optionalLong("timeout_ms") ?: 120_000,
        )
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

/** 只读执行最多自动恢复一次；写操作和协议错误必须交给上层处理。 */
internal fun shouldRetryReadOnlyExecution(readOnlyRequest: Boolean, error: Throwable): Boolean {
    if (!readOnlyRequest || error is CancellationException) return false
    return when (error) {
        is ComputerException -> error.retryable
        is IOException -> true
        else -> false
    }
}

object ComputerToolRequestHasher {
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

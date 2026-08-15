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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.android.everytalk.util.AppLogger
import com.android.everytalk.data.skill.SkillSecretSessionStore

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
        updateStatus: suspend (String?) -> Unit = {},
    ): JsonElement {
        val foregroundActivity = repository.acquireForegroundActivity()
        return try {
            executeWhileActive(toolName, arguments, toolCallId, requestContext, updateStatus)
        } finally {
            foregroundActivity.close()
        }
    }

    /**
     * 停止当前会话或指定 Run 的全部受管远端任务（包含前台与后台 RETURN_HANDLE 任务）。
     * 任务归属从 Workspace 反查或直接按 runId 查，先写取消意图再发 SSH 取消。
     */
    suspend fun cancelActiveExecutions(conversationId: String, runId: String? = null): Boolean {
        val executions = if (!runId.isNullOrBlank()) {
            val byRun = repository.dao().getCancellableRemoteExecutionsForRun(runId)
            if (byRun.isNotEmpty()) byRun else repository.dao().getCancellableRemoteExecutionsForConversation(conversationId)
        } else {
            if (conversationId.isBlank()) return true
            repository.dao().getCancellableRemoteExecutionsForConversation(conversationId)
        }
        var allSucceeded = true
        for (execution in executions) {
            try {
                // 先落库取消意图，再进入 SSH。停止按钮随后取消本地 Agent 协程时，
                // 恢复扫描仍能识别这条远端任务需要继续确认，避免竞态窗口永久丢失。
                repository.dao().markRemoteExecutionCancellationRequested(execution.id)
                // 本地记录还在但服务器/Workspace 已被删除时，返回 null 不能向用户报告“已取消”。
                val snapshot = repository.cancelRemoteExecution(execution.id)
                if (snapshot == null || snapshot.status in setOf(
                        ComputerRemoteStatus.UNKNOWN,
                        ComputerRemoteStatus.STOPPED,
                        ComputerRemoteStatus.MISSING,
                        ComputerRemoteStatus.STARTING,
                        ComputerRemoteStatus.RUNNING,
                    )) {
                    allSucceeded = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                allSucceeded = false
                AppLogger.warn(
                    "ComputerRuntime",
                    "取消远端 Execution 失败 execution=${execution.id}: ${error.message}",
                )
                val observedAt = System.currentTimeMillis()
                val errorCode = (error as? ComputerException)?.code
                    ?: ComputerErrorCodes.EXECUTION_CANCEL_FAILED
                repository.dao().markRemoteExecutionUnknown(
                    executionId = execution.id,
                    errorCode = errorCode,
                    observedAt = observedAt,
                    localStatus = ComputerExecutionStatus.UNKNOWN.name,
                    finishedAt = observedAt,
                )
            }
        }
        return allSucceeded
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
        val workspace = requireRequestWorkspace(requestContext)
        val context = requestContext.copy(conversationId = workspace.conversationId)
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
            if (
                existing.toolName == ComputerToolNames.EXEC &&
                existing.remoteStatePath != null &&
                existing.remoteStatus in setOf(
                    ComputerRemoteStatus.SUCCEEDED,
                    ComputerRemoteStatus.FAILED,
                    ComputerRemoteStatus.TIMED_OUT,
                    ComputerRemoteStatus.CANCELLED,
                )
            ) {
                // 远端已经有终态时优先补取结果。即使上次因协议读取失败落成 UNKNOWN，
                // 也不能让“重新执行”覆盖这份可恢复结果并重复修改 VPS。
                return recoverCompletedExecution(existing, arguments, currentRequestContext, workspace)
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
            target = initialExecRequest?.target,
            completionMode = initialExecRequest?.let {
                if (it.background) ComputerExecutionCompletionMode.RETURN_HANDLE
                else ComputerExecutionCompletionMode.WAIT_FOR_RESULT
            },
            remoteProcessId = initialExecRequest?.let { "process_$executionId" },
            remoteStatePath = initialExecRequest?.let {
                initialRemoteStatePath(workspace, executionId, it.target)
            },
            remoteStatus = initialExecRequest?.let { ComputerRemoteStatus.STARTING },
            runId = currentRequestContext.runId,
        )
        dao.upsertExecution(execution.toEntity())

        return try {
            if (toolName == ComputerToolNames.EXEC) {
                // 在真正建立 SSH 前先落下目标和固定状态路径，停止按钮不会错过“刚要启动”的命令。
                val request = requireNotNull(initialExecRequest)
                dao.updateRemoteExecutionReference(
                    executionId = execution.id,
                    target = request.target.name,
                    completionMode = if (request.background) {
                        ComputerExecutionCompletionMode.RETURN_HANDLE.name
                    } else {
                        ComputerExecutionCompletionMode.WAIT_FOR_RESULT.name
                    },
                    remoteProcessId = "process_${execution.id}",
                    remoteStatePath = initialRemoteStatePath(workspace, execution.id, request.target),
                    remoteStatus = ComputerRemoteStatus.STARTING.name,
                    runId = currentRequestContext.runId,
                )
            }
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
            // 远端轮询期间会单独写入 target、remoteStatus 等字段，收尾必须读取最新行，
            // 否则用最初的本地对象 Upsert 会把这些状态覆盖为空。
            val observed = dao.getExecutionById(execution.id)?.toModel() ?: execution
            val finalStatus = when {
                toolName != ComputerToolNames.EXEC -> ComputerExecutionStatus.SUCCEEDED
                observed.remoteStatus == ComputerRemoteStatus.FAILED -> ComputerExecutionStatus.FAILED
                observed.remoteStatus == ComputerRemoteStatus.TIMED_OUT -> ComputerExecutionStatus.TIMED_OUT
                observed.remoteStatus == ComputerRemoteStatus.CANCELLED -> ComputerExecutionStatus.CANCELLED
                observed.remoteStatus in setOf(
                    ComputerRemoteStatus.UNKNOWN,
                    ComputerRemoteStatus.MISSING,
                    ComputerRemoteStatus.STOPPED,
                ) -> ComputerExecutionStatus.UNKNOWN
                else -> ComputerExecutionStatus.SUCCEEDED
            }
            execution = observed.copy(
                status = finalStatus,
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
            val observed = dao.getExecutionById(execution.id)?.toModel() ?: execution
            val remoteStillActive = observed.remoteStatus == null || observed.remoteStatus in setOf(
                ComputerRemoteStatus.STARTING,
                ComputerRemoteStatus.RUNNING,
            )
            // 本地协程取消不等于 VPS 已停止。远端仍活跃时保留取消意图并继续对账，
            // 只有收到 VPS 终态后才写入 finishedAt。
            val localStatus = if (observed.remoteStatus == ComputerRemoteStatus.UNKNOWN && !remoteStillActive) {
                ComputerExecutionStatus.UNKNOWN
            } else {
                ComputerExecutionStatus.CANCELLED
            }
            dao.upsertExecution(
                observed.copy(
                    status = localStatus,
                    finishedAt = if (remoteStillActive) null else System.currentTimeMillis(),
                    errorCode = when {
                        remoteStillActive -> ComputerErrorCodes.EXECUTION_CANCEL_REQUESTED
                        localStatus == ComputerExecutionStatus.UNKNOWN -> ComputerErrorCodes.EXECUTION_CANCEL_FAILED
                        else -> observed.errorCode
                    },
                    safeSummary = "$toolName 已取消",
                ).toEntity(),
            )
            throw error
        } catch (error: Throwable) {
            val computerError = when (error) {
                is ComputerException -> error
                is ComputerRemoteExecutionProtocolException -> ComputerException(
                    code = error.protocolCode,
                    message = error.message ?: "远端执行协议无效",
                    retryable = false,
                    cause = error,
                )
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
        ComputerToolNames.EXEC -> executeCommand(arguments, toolCallId, requestContext, workspace, executionId, requestHash, updateStatus)
        ComputerToolNames.READ_FILE -> readFile(arguments, requestContext, workspace)
        ComputerToolNames.WRITE_FILE -> writeFile(arguments, requestContext, workspace, executionId)
        ComputerToolNames.TERMINAL -> terminal(arguments, requestContext, workspace)
        ComputerToolNames.UPLOAD -> upload(arguments, requestContext, workspace, executionId)
        ComputerToolNames.DOWNLOAD -> download(arguments, requestContext, workspace)
        ComputerToolNames.OPEN_PORT -> openPort(arguments, toolCallId, requestContext)
        else -> throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Computer Tool 不存在")
    }

    private suspend fun executeCommand(
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
        requestHash: String,
        updateStatus: suspend (String?) -> Unit,
    ): JsonElement {
        val request = parseExecRequest(arguments, context, loadSecrets = true)
        val secrets = request.secrets
        val readOnlyRequest = ComputerToolCallSafety.isReadOnly(ComputerToolNames.EXEC, arguments)
        val result = try {
            if (request.target == ComputerExecTarget.CONTAINER) {
                workspaceManager.prepareContainer(workspace.id)
            }
            val runRequest: suspend (ComputerExecRequest) -> ComputerExecResult = { frozenRequest ->
                executeManagedRequestWithRecovery(
                    context = context,
                    workspace = workspace,
                    executionId = executionId,
                    requestHash = requestHash,
                    request = frozenRequest,
                    readOnlyRequest = readOnlyRequest,
                    updateStatus = updateStatus,
                )
            }
            if (request.target == ComputerExecTarget.HOST && context.approvedToolCallId != toolCallId) {
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
            result.target?.let { put("target", it.name) }
            result.processId?.let { put("process_id", it) }
            result.pid?.let { put("pid", it) }
            result.logPath?.let {
                put("log_path", it)
                put("log_reference", it)
            }
        }
    }

    /**
     * 只读命令的单次断线恢复。
     *
     * Wrapper 使用固定 Execution ID 和 request hash 做幂等判断，因此恢复时重新接入
     * 同一 Execution 只会读取已有状态，不会再次创建一条命令。写操作不进入这里的自动重试。
     */
    private suspend fun executeManagedRequestWithRecovery(
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
        requestHash: String,
        request: ComputerExecRequest,
        readOnlyRequest: Boolean,
        updateStatus: suspend (String?) -> Unit,
    ): ComputerExecResult {
        try {
            return executeManagedRequest(
                context = context,
                workspace = workspace,
                executionId = executionId,
                requestHash = requestHash,
                request = request,
                updateStatus = updateStatus,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!shouldRetryReadOnlyExecution(readOnlyRequest, error)) throw error
            updateStatus("SSH 连接中断，正在恢复命令")
            // 连接可能仍被池误判为可用，先丢弃旧 Transport，再用同一 Execution ID 接回。
            repository.invalidateConnection(context.computerId)
            return executeManagedRequest(
                context = context,
                workspace = workspace,
                executionId = executionId,
                requestHash = requestHash,
                request = request,
                updateStatus = updateStatus,
            )
        }
    }

    /**
     * 以固定 Execution ID 启动 VPS 进程，再按状态协议等待或返回句柄。
     * 远端引用先写 Room，启动确认丢失时恢复流程仍能按同一 ID 查询，禁止重复执行。
     */
    private suspend fun executeManagedRequest(
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
        executionId: String,
        requestHash: String,
        request: ComputerExecRequest,
        updateStatus: suspend (String?) -> Unit,
    ): ComputerExecResult = repository.withConnection(context.computerId) { connection, computer ->
        val startedAtMillis = System.currentTimeMillis()
        val processId = "process_$executionId"
        updateStatus("正在启动命令")
        val statePath = if (request.target == ComputerExecTarget.HOST) {
            "~/.everytalk/host-executions/$executionId/state"
        } else if (computer.runMode == ComputerRunMode.DIRECT) {
            "~/.everytalk/workspaces/${workspace.id}/.everytalk/executions/$executionId/state"
        } else {
            "/workspace/.everytalk/executions/$executionId/state"
        }
        repository.dao().updateRemoteExecutionReference(
            executionId = executionId,
            target = request.target.name,
            completionMode = if (request.background) {
                ComputerExecutionCompletionMode.RETURN_HANDLE.name
            } else {
                ComputerExecutionCompletionMode.WAIT_FOR_RESULT.name
            },
            remoteProcessId = processId,
            remoteStatePath = statePath,
            remoteStatus = ComputerRemoteStatus.STARTING.name,
            runId = context.runId,
        )
        val started = runtimeEnvelope.startManagedExecution(
            connection = connection,
            computer = computer,
            workspace = workspace,
            executionId = executionId,
            requestHash = requestHash,
            request = request,
        )
        // Wrapper 可能先落 STARTING 再切 RUNNING。后台 Tool 已经拿到可恢复句柄，
        // 对模型和 Room 都统一呈现 RUNNING，避免返回 STARTING 后下一轮被误判成未启动。
        val observedStart = if (request.background && started.status == ComputerRemoteStatus.STARTING) {
            started.copy(status = ComputerRemoteStatus.RUNNING)
        } else {
            started
        }
        observeRemoteExecution(executionId, request, observedStart)

        if (request.background) {
            updateStatus("后台任务已启动")
            return@withConnection ComputerExecResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                processId = observedStart.processId,
                pid = observedStart.pid,
                logPath = statePath.removeSuffix("/state"),
                // Tool 已拿到可恢复句柄，STARTING 对用户和模型统一呈现为 RUNNING。
                remoteStatus = observedStart.status,
                durationMillis = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L),
                target = request.target,
            )
        }

        var snapshot = started
        var waitMillis = 100L
        val deadline = System.currentTimeMillis() + request.timeoutMillis + 30_000L
        updateStatus("正在运行，已用时 ${formatElapsed(System.currentTimeMillis() - startedAtMillis)}")
        while (snapshot.status !in setOf(
                ComputerRemoteStatus.SUCCEEDED,
                ComputerRemoteStatus.FAILED,
                ComputerRemoteStatus.TIMED_OUT,
                ComputerRemoteStatus.CANCELLED,
                ComputerRemoteStatus.STOPPED,
                ComputerRemoteStatus.MISSING,
                ComputerRemoteStatus.UNKNOWN,
            )) {
            if (System.currentTimeMillis() >= deadline) {
                throw ComputerException(
                    ComputerErrorCodes.EXECUTION_UNKNOWN,
                    "等待 VPS Execution 超时，远端状态仍需恢复确认",
                    retryable = true,
                )
            }
            delay(waitMillis)
            snapshot = runtimeEnvelope.queryExecutionStatus(
                connection = connection,
                computer = computer,
                workspace = workspace,
                executionId = executionId,
                target = request.target,
                expectedProcessId = "process_$executionId",
                expectedRequestHash = requestHash,
            )
            observeRemoteExecution(executionId, request, snapshot)
            if (snapshot.status in setOf(
                    ComputerRemoteStatus.STARTING,
                    ComputerRemoteStatus.RUNNING,
                )
            ) {
                updateStatus("正在运行，已用时 ${formatElapsed(System.currentTimeMillis() - startedAtMillis)}")
            }
            waitMillis = (waitMillis * 2).coerceAtMost(1_000L)
        }

        if (snapshot.status in setOf(ComputerRemoteStatus.MISSING, ComputerRemoteStatus.UNKNOWN, ComputerRemoteStatus.STOPPED)) {
            throw ComputerException(
                ComputerErrorCodes.EXECUTION_UNKNOWN,
                "无法确认 VPS Execution 的最终结果",
                retryable = true,
            )
        }
        val remoteResult = runtimeEnvelope.readExecutionResult(
            connection = connection,
            computer = computer,
            workspace = workspace,
            executionId = executionId,
            maxBytes = COMPUTER_EXEC_OUTPUT_BYTES,
            target = request.target,
            expectedProcessId = "process_$executionId",
            expectedRequestHash = requestHash,
        )
        observeRemoteExecution(executionId, request, remoteResult.snapshot)
        updateStatus(
            when (remoteResult.snapshot.status) {
                ComputerRemoteStatus.SUCCEEDED -> "命令执行完成"
                ComputerRemoteStatus.TIMED_OUT -> "命令执行超时"
                ComputerRemoteStatus.CANCELLED -> "命令已取消"
                else -> "命令执行失败"
            },
        )
        ComputerExecResult(
            exitCode = remoteResult.snapshot.exitCode,
            stdout = redact(remoteResult.stdout, request.secrets.values),
            stderr = redact(remoteResult.stderr, request.secrets.values),
            timedOut = remoteResult.snapshot.status == ComputerRemoteStatus.TIMED_OUT,
            stdoutTruncated = remoteResult.stdoutTruncated,
            stderrTruncated = remoteResult.stderrTruncated,
            remoteStatus = remoteResult.snapshot.status,
            durationMillis = (
                (remoteResult.snapshot.updatedAt ?: System.currentTimeMillis()) - startedAtMillis
            ).coerceAtLeast(0L),
            target = request.target,
        )
    }

    /**
     * App 崩溃后补取已经完成的远端 exec，禁止重新执行同一个写操作。
     * 远端日志仍按固定上限读取，读取失败会交给 AgentToolRuntime 转成错误 Tool Result。
     */
    private suspend fun recoverCompletedExecution(
        execution: ComputerExecution,
        arguments: JsonObject,
        context: ComputerRequestContext,
        workspace: ComputerWorkspace,
    ): JsonElement {
        val request = parseExecRequest(arguments, context, loadSecrets = true)
        return try {
            val target = execution.target ?: request.target
            if (target != request.target) {
                throw ComputerException(ComputerErrorCodes.EXECUTION_PROTOCOL_MISMATCH, "恢复的执行目标与原请求不一致")
            }
            val remoteResult = repository.withConnection(context.computerId) { connection, computer ->
                runtimeEnvelope.readExecutionResult(
                    connection = connection,
                    computer = computer,
                    workspace = workspace,
                    executionId = execution.id,
                    maxBytes = COMPUTER_EXEC_OUTPUT_BYTES,
                    target = target,
                    expectedProcessId = execution.remoteProcessId,
                    expectedRequestHash = execution.requestHash,
                )
            }
            if (remoteResult.snapshot.status !in setOf(
                    ComputerRemoteStatus.SUCCEEDED,
                    ComputerRemoteStatus.FAILED,
                    ComputerRemoteStatus.TIMED_OUT,
                    ComputerRemoteStatus.CANCELLED,
                )
            ) {
                throw ComputerException(
                    ComputerErrorCodes.EXECUTION_UNKNOWN,
                    "远端 Execution 尚未形成最终结果",
                    retryable = true,
                )
            }
            observeRemoteExecution(execution.id, request, remoteResult.snapshot)
            val response = successEnvelope(
                execution.id,
                buildJsonObject {
                put("ok", remoteResult.snapshot.status == ComputerRemoteStatus.SUCCEEDED)
                put("status", remoteResult.snapshot.status.name)
                put("exit_code", remoteResult.snapshot.exitCode?.let(::JsonPrimitive) ?: JsonNull)
                put("stdout", redact(remoteResult.stdout, request.secrets.values))
                put("stderr", redact(remoteResult.stderr, request.secrets.values))
                put("timed_out", remoteResult.snapshot.status == ComputerRemoteStatus.TIMED_OUT)
                put("stdout_truncated", remoteResult.stdoutTruncated)
                put("stderr_truncated", remoteResult.stderrTruncated)
                put(
                    "duration_ms",
                    ((remoteResult.snapshot.updatedAt ?: System.currentTimeMillis()) -
                        (execution.startedAt ?: System.currentTimeMillis())).coerceAtLeast(0L),
                )
                put("target", target.name)
                put("recovered", true)
                put("process_id", remoteResult.snapshot.processId)
                execution.remoteStatePath?.removeSuffix("/state")?.let {
                    put("log_path", it)
                    put("log_reference", it)
                }
                },
                ok = remoteResult.snapshot.status == ComputerRemoteStatus.SUCCEEDED,
            )
            completedResults[execution.toolCallId] = response
            response
        } finally {
            request.secrets.values.forEach { it.fill('\u0000') }
        }
    }

    private suspend fun observeRemoteExecution(
        executionId: String,
        request: ComputerExecRequest,
        snapshot: ComputerRemoteExecutionSnapshot,
    ) {
        val terminal = snapshot.status in setOf(
            ComputerRemoteStatus.SUCCEEDED,
            ComputerRemoteStatus.FAILED,
            ComputerRemoteStatus.TIMED_OUT,
            ComputerRemoteStatus.CANCELLED,
        )
        val backgroundHandle = request.background
        val localStatus = if (backgroundHandle) {
            null
        } else {
            when (snapshot.status) {
                ComputerRemoteStatus.SUCCEEDED -> ComputerExecutionStatus.SUCCEEDED.name
                ComputerRemoteStatus.FAILED -> ComputerExecutionStatus.FAILED.name
                ComputerRemoteStatus.TIMED_OUT -> ComputerExecutionStatus.TIMED_OUT.name
                ComputerRemoteStatus.CANCELLED -> ComputerExecutionStatus.CANCELLED.name
                ComputerRemoteStatus.UNKNOWN,
                ComputerRemoteStatus.MISSING,
                ComputerRemoteStatus.STOPPED,
                -> ComputerExecutionStatus.UNKNOWN.name
                else -> null
            }
        }
        repository.dao().updateRemoteExecutionObservation(
            executionId = executionId,
            target = request.target.name,
            remoteProcessId = snapshot.processId,
            remoteStatus = snapshot.status.name,
            remoteExitCode = snapshot.exitCode,
            observedAt = System.currentTimeMillis(),
            localStatus = localStatus,
            finishedAt = if (terminal && localStatus != null) System.currentTimeMillis() else null,
            localExitCode = if (terminal) snapshot.exitCode else null,
            errorCode = when (snapshot.status) {
                ComputerRemoteStatus.UNKNOWN,
                ComputerRemoteStatus.MISSING,
                ComputerRemoteStatus.STOPPED,
                -> ComputerErrorCodes.EXECUTION_UNKNOWN
                else -> null
            },
        )
    }

    /** 只在回填模型前过滤本次请求携带的 Secret，Room 和 VPS 日志不保存过滤后的副本。 */
    private fun redact(output: String, secrets: Collection<CharArray>): String {
        var redacted = output
        secrets.forEach { secret ->
            if (secret.isNotEmpty()) redacted = redacted.replace(String(secret), "[REDACTED]")
        }
        return redacted
    }

    /** 执行链只显示短时间，不把轮询细节写入 AgentEntry。 */
    private fun formatElapsed(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
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

    private suspend fun openPort(
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
    ): JsonElement {
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
        ComputerToolNames.TERMINAL -> "terminal 操作已完成"
        ComputerToolNames.UPLOAD -> "upload 已完成"
        ComputerToolNames.DOWNLOAD -> "download 已保存本地附件"
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
        if (request.target != ComputerExecTarget.HOST) return null
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
                requestsPrivilege = request.asRoot ||
                    ComputerHostCommandRisk.PRIVILEGE_ESCALATION in assessment.risks,
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
        val target = when (arguments.optionalString("target") ?: "container") {
            "container" -> ComputerExecTarget.CONTAINER
            "host" -> ComputerExecTarget.HOST
            else -> throw invalidArgument("target")
        }
        return ComputerToolApprovalRequest.PublicPreview(
            toolCallId = toolCallId,
            computerName = computer.displayName,
            request = ComputerPublicPreviewRequest(
                context = context,
                port = (arguments.optionalLong("port") ?: throw invalidArgument("port")).toIntChecked("port"),
                protocol = arguments.optionalString("protocol") ?: "http",
                expiresInSeconds = arguments.optionalLong("expires_in_seconds"),
                target = target,
            ),
        )
    }

    private fun approvalDetail(toolName: String, arguments: JsonObject): String = when (toolName) {
        ComputerToolNames.EXEC -> arguments.optionalString("command").orEmpty()
        ComputerToolNames.READ_FILE, ComputerToolNames.WRITE_FILE -> arguments.optionalString("path").orEmpty()
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
        val workspaceSecrets = runCatching { secretManager.loadSelected(context.workspaceId, secretNames) }.getOrDefault(emptyMap())
        val missing = secretNames.filterNot(workspaceSecrets::containsKey)
        val sessionSecrets = SkillSecretSessionStore.loadSelected(context.runId, missing)
        if (workspaceSecrets.size + sessionSecrets.size != secretNames.distinct().size) {
            workspaceSecrets.values.forEach { it.fill('\u0000') }
            sessionSecrets.values.forEach { it.fill('\u0000') }
            throw ComputerException(ComputerErrorCodes.CREDENTIAL_MISSING, "请求的 Secret 不存在")
        }
        return request.copy(secrets = workspaceSecrets + sessionSecrets)
    }

    /** 审批预检只解析参数，不读取 Keystore，避免用户批准前触碰 Secret。 */
    private fun parseExecRequestWithoutSecrets(arguments: JsonObject): ComputerExecRequest {
        val target = when (arguments.optionalString("target") ?: "container") {
            "container" -> ComputerExecTarget.CONTAINER
            "host" -> ComputerExecTarget.HOST
            else -> throw invalidArgument("target")
        }
        val secretNames = arguments.stringList("secret_names")
        if (target == ComputerExecTarget.HOST && secretNames.isNotEmpty()) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "VPS 主机命令不允许注入 Workspace Secret")
        }
        return ComputerExecRequest(
            command = arguments.requiredString("command"),
            cwd = arguments.optionalString("cwd") ?: if (target == ComputerExecTarget.HOST) "~" else "/workspace",
            environment = arguments.objectOrEmpty("env").mapValues { (name, value) -> value.stringValue("env.$name") },
            secrets = emptyMap(),
            stdin = arguments.optionalString("stdin"),
            timeoutMillis = arguments.optionalLong("timeout_ms") ?: 120_000,
            background = arguments.optionalBoolean("background") ?: false,
            asRoot = arguments.optionalBoolean("as_root") ?: false,
            target = target,
        )
    }

    /** 生成可由停止按钮复用的固定状态路径，不接受模型传入绝对路径。 */
    private fun initialRemoteStatePath(
        workspace: ComputerWorkspace,
        executionId: String,
        target: ComputerExecTarget,
    ): String = when {
        target == ComputerExecTarget.HOST -> "~/.everytalk/host-executions/$executionId/state"
        workspace.runMode == ComputerRunMode.DIRECT ->
            "~/.everytalk/workspaces/${workspace.id}/.everytalk/executions/$executionId/state"
        else -> "/workspace/.everytalk/executions/$executionId/state"
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

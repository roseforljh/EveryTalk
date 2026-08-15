package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import com.android.everytalk.data.database.entities.AgentEntryEntity
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRequestUsageEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.modelParameterProtocol
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerExecutionStatus
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.ComputerToolRequestHasher
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.ComputerToolCallSafety
import com.android.everytalk.data.database.daos.ComputerDao
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.withLock

/**
 * Agent 运行事实的唯一写入口。每次真实模型请求独立保存，禁止再按可见 AI 消息累计 Usage。
 */
class AgentRunStore(
    private val dao: AgentDao,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) {
    private val approvalDecisionLock = kotlinx.coroutines.sync.Mutex()
    private val entryAppendLock = kotlinx.coroutines.sync.Mutex()
    suspend fun createRun(
        sessionId: String,
        userMessageId: String,
        visibleAssistantMessageId: String,
        configIdSnapshot: String?,
        request: ChatRequest,
    ): AgentRunEntity {
        val now = System.currentTimeMillis()
        return AgentRunEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            userMessageId = userMessageId,
            visibleAssistantMessageId = visibleAssistantMessageId,
            configIdSnapshot = configIdSnapshot,
            requestSnapshotJson = json.encodeToString(
                AgentRequestSnapshot.serializer(),
                request.toRecoverySnapshot(),
            ),
            status = AgentRunStatus.CREATED.name,
            currentRequestOrdinal = 0,
            terminalReason = null,
            createdAt = now,
            updatedAt = now,
        ).also { dao.upsertRun(it) }
    }

    suspend fun getRun(runId: String): AgentRunEntity? = dao.getRun(runId)

    suspend fun getRunByVisibleMessage(messageId: String): AgentRunEntity? =
        dao.getRunByVisibleMessage(messageId)

    suspend fun getWaitingApprovalRuns(): List<AgentRunEntity> = dao.getWaitingApprovalRuns()

    suspend fun getPendingModelContinuationRuns(): List<AgentRunEntity> = dao.getPendingModelContinuationRuns()

    /** 远端执行期间不能被 App 重启流程改成 INTERRUPTED，恢复时再按 Execution 对账。 */
    suspend fun getWaitingRemoteExecutionRuns(): List<AgentRunEntity> = dao.getWaitingRemoteExecutionRuns()

    /** App 重启后仍可能关联远端 Execution 的中断 Run。 */
    suspend fun getInterruptedRuns(): List<AgentRunEntity> = dao.getInterruptedRuns()

    suspend fun nextModelTurnOrdinal(runId: String): Int = dao.getRequests(runId)
        .mapNotNull(AgentRequestEntity::modelTurnOrdinal)
        .maxOrNull()
        ?.plus(1)
        ?: 1

    suspend fun completedCompactionCount(runId: String): Int = dao.getRequests(runId)
        .count { it.purpose == AgentRequestPurpose.COMPACTION.name && it.status == AgentRequestStatus.COMPLETED.name }

    suspend fun finalExecutedToolCalls(runId: String): List<AgentContentBlock.ToolCall> {
        val entries = dao.getEntries(runId)
        val completedIds = entries.asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_RESULT.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        return entries
            .filter { it.kind == AgentEntryKind.ASSISTANT.name && it.status == AgentEntryStatus.FINAL.name }
            .flatMap(::decodeAssistantToolCalls)
            .filter { it.id in completedIds }
    }

    suspend fun hasFinalToolResult(runId: String, toolCallId: String): Boolean =
        dao.getEntries(runId).any { entry ->
            entry.kind == AgentEntryKind.TOOL_RESULT.name &&
                entry.status == AgentEntryStatus.FINAL.name &&
                entry.toolCallId == toolCallId
        }

    /**
     * 保存需要让模型知道的手机生命周期或 SSH 事件。
     * 同一 Run、Execution、事件类型只写一次，重连循环不会重复污染上下文。
     */
    suspend fun appendStatusEvent(
        runId: String,
        reason: String,
        message: String,
        executionId: String? = null,
    ): Boolean = entryAppendLock.withLock {
        val eventKey = "$reason:${executionId.orEmpty()}"
        val existing = dao.getEntries(runId).any { entry ->
            entry.kind == AgentEntryKind.STATUS.name &&
                runCatching {
                    json.parseToJsonElement(entry.payloadJson).jsonObject["event_key"]?.jsonPrimitive?.content == eventKey
                }.getOrDefault(false)
        }
        if (existing) return@withLock false
        val now = System.currentTimeMillis()
        dao.upsertEntry(
            newEntry(
                runId = runId,
                sequence = dao.nextEntrySequence(runId),
                kind = AgentEntryKind.STATUS,
                requestId = null,
                toolCallId = null,
                payloadJson = buildJsonObject {
                    put("event_key", eventKey)
                    put("reason", reason)
                    put("message", message)
                    executionId?.let { put("execution_id", it) }
                }.toString(),
                status = AgentEntryStatus.FINAL,
                now = now,
            ),
        )
        true
    }

    /** 根据持久化 Assistant Entry 找回原 Tool Call，供远端完成后读取真实结果。 */
    suspend fun findToolCall(runId: String, toolCallId: String): AgentContentBlock.ToolCall? =
        dao.getEntries(runId).asReversed().firstNotNullOfOrNull { entry ->
            if (entry.kind != AgentEntryKind.ASSISTANT.name) return@firstNotNullOfOrNull null
            decodeAssistantToolCalls(entry).firstOrNull { it.id == toolCallId }
        }

    fun decodeRequestSnapshot(run: AgentRunEntity): AgentRequestSnapshot? = run.requestSnapshotJson
        ?.let { encoded -> runCatching { json.decodeFromString(AgentRequestSnapshot.serializer(), encoded) }.getOrNull() }

    fun restoreChatRequest(run: AgentRunEntity, apiKey: String): ChatRequest? {
        val snapshot = decodeRequestSnapshot(run) ?: return null
        return runCatching {
            ChatRequest(
                messages = snapshot.messages,
                provider = snapshot.provider,
                channel = snapshot.channel,
                apiAddress = snapshot.apiAddress,
                apiKey = apiKey,
                model = snapshot.model,
                forceGoogleReasoningPrompt = snapshot.forceGoogleReasoningPrompt,
                useWebSearch = snapshot.useWebSearch,
                generationConfig = snapshot.generationConfig,
                tools = snapshot.toolsJson?.let(::jsonElementToStringMapList),
                toolChoice = snapshot.toolChoiceJson?.let(::jsonElementToAny),
                qwenEnableSearch = snapshot.qwenEnableSearch,
                customModelParameters = snapshot.customModelParametersJson?.let(::jsonElementToStringMap),
                customExtraBody = snapshot.customExtraBodyJson?.let(::jsonElementToStringMap),
                conversationId = run.sessionId,
                enableCodeExecution = snapshot.enableCodeExecution,
                contextManagement = snapshot.contextManagement,
                localComputerRequestContext = snapshot.computerRequestContext,
                localSkillSnapshot = snapshot.skillSnapshot,
            )
        }.getOrNull()
    }

    /** 用户批准 request_agent 后，先把服务器快照和工具写回原 Run，再继续同一次请求。 */
    suspend fun updateRequestSnapshot(run: AgentRunEntity, request: ChatRequest): AgentRunEntity = run.copy(
        requestSnapshotJson = json.encodeToString(AgentRequestSnapshot.serializer(), request.toRecoverySnapshot()),
        updatedAt = System.currentTimeMillis(),
    ).also { dao.upsertRun(it) }

    suspend fun updateRunStatus(
        run: AgentRunEntity,
        status: AgentRunStatus,
        requestOrdinal: Int = run.currentRequestOrdinal,
        terminalReason: String? = run.terminalReason,
    ): AgentRunEntity = run.copy(
        status = status.name,
        currentRequestOrdinal = requestOrdinal,
        terminalReason = terminalReason,
        updatedAt = System.currentTimeMillis(),
    ).also { dao.upsertRun(it) }

    suspend fun createRequest(
        run: AgentRunEntity,
        ordinal: Int,
        purpose: AgentRequestPurpose,
        modelTurnOrdinal: Int?,
        provider: String,
        endpoint: String?,
        model: String,
        transcriptFingerprint: String,
        snapshot: AgentContextSnapshotEntity,
    ): AgentRequestEntity {
        val request = AgentRequestEntity(
            id = snapshot.requestId,
            runId = run.id,
            ordinal = ordinal,
            purpose = purpose.name,
            modelTurnOrdinal = modelTurnOrdinal,
            attempt = 1,
            retryOfRequestId = null,
            provider = provider,
            endpoint = endpoint,
            model = model,
            payloadFingerprint = transcriptFingerprint,
            status = AgentRequestStatus.PREPARED.name,
            finishReason = null,
            startedAt = null,
            firstEventAt = null,
            finishedAt = null,
        )
        dao.persistRequestFacts(request, snapshot)
        return request
    }

    suspend fun updateRequest(
        request: AgentRequestEntity,
        status: AgentRequestStatus,
        finishReason: String? = request.finishReason,
        startedAt: Long? = request.startedAt,
        firstEventAt: Long? = request.firstEventAt,
        finishedAt: Long? = request.finishedAt,
    ): AgentRequestEntity = request.copy(
        status = status.name,
        finishReason = finishReason,
        startedAt = startedAt,
        firstEventAt = firstEventAt,
        finishedAt = finishedAt,
    ).also { dao.upsertRequest(it) }

    /** 用户取消时同步收尾尚未结束的模型请求，避免数据库长期停留在 STREAMING。 */
    suspend fun cancelOpenRequests(runId: String, reason: String?) {
        val finishedAt = System.currentTimeMillis()
        dao.getRequests(runId)
            .filter { request ->
                request.status == AgentRequestStatus.PREPARED.name ||
                    request.status == AgentRequestStatus.STREAMING.name
            }
            .forEach { request ->
                updateRequest(
                    request = request,
                    status = AgentRequestStatus.CANCELLED,
                    finishReason = reason ?: "USER_CANCELLED",
                    finishedAt = finishedAt,
                )
            }
    }

    suspend fun saveUsage(requestId: String, usage: TokenUsage): AgentRequestUsageEntity {
        val prompt = usage.inputTokens?.coerceAtLeast(0L)
        val cacheRead = usage.cachedInputTokens?.coerceAtLeast(0L)
        val freshInput = when {
            prompt == null -> null
            cacheRead == null -> prompt
            else -> (prompt - cacheRead).coerceAtLeast(0L)
        }
        val output = usage.outputTokens?.coerceAtLeast(0L)
        val computedTotal = usage.totalTokens?.coerceAtLeast(0L) ?: safeAdd(prompt, output)
        return AgentRequestUsageEntity(
            requestId = requestId,
            promptTokens = prompt,
            freshInputTokens = freshInput,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = usage.cacheWriteTokens?.coerceAtLeast(0L),
            outputTokens = output,
            reasoningTokens = usage.reasoningTokens?.coerceAtLeast(0L),
            requestTotalTokens = computedTotal,
            providerTotalTokens = usage.totalTokens?.coerceAtLeast(0L),
            source = usage.source.name,
            quality = when {
                usage.source.name == "ESTIMATED" -> AgentUsageQuality.ESTIMATED.name
                usage.isFinal -> AgentUsageQuality.MEASURED.name
                else -> AgentUsageQuality.PARTIAL.name
            },
            rawUsageJson = json.encodeToString(TokenUsage.serializer(), usage),
        ).also { dao.upsertUsage(it) }
    }

    suspend fun appendAssistant(
        runId: String,
        requestId: String,
        turn: AgentAssistantTurn,
        status: AgentEntryStatus = AgentEntryStatus.FINAL,
    ): AgentEntryEntity = appendEntry(
        runId = runId,
        kind = AgentEntryKind.ASSISTANT,
        requestId = requestId,
        toolCallId = null,
        payloadJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AgentContentBlock.serializer()),
            turn.blocks,
        ),
        status = status,
    )

    suspend fun appendToolResult(
        runId: String,
        requestId: String,
        result: AgentContentBlock.ToolResult,
    ): AgentEntryEntity = appendEntry(
        runId = runId,
        kind = AgentEntryKind.TOOL_RESULT,
        requestId = requestId,
        toolCallId = result.toolCallId,
        payloadJson = json.encodeToString(AgentContentBlock.ToolResult.serializer(), result),
        status = AgentEntryStatus.FINAL,
    )

    /** Executor 调用前保存开始事实。进程退出后可区分“尚未开始”和“结果未落库”。 */
    suspend fun appendToolExecutionStarted(
        runId: String,
        requestId: String,
        call: AgentContentBlock.ToolCall,
    ): AgentEntryEntity = appendEntry(
        runId = runId,
        kind = AgentEntryKind.TOOL_EXECUTION_STARTED,
        requestId = requestId,
        toolCallId = call.id,
        payloadJson = json.encodeToString(
            AgentToolExecutionRecord.serializer(),
            AgentToolExecutionRecord(requestId, call, System.currentTimeMillis()),
        ),
        status = AgentEntryStatus.FINAL,
    )

    /** 审批请求和 WAITING_APPROVAL 原子写入，进程退出时不会产生隐藏审批。 */
    suspend fun pauseForApproval(
        run: AgentRunEntity,
        record: AgentApprovalRecord,
    ): AgentRunEntity {
        val now = System.currentTimeMillis()
        val entry = newEntry(
            runId = run.id,
            sequence = dao.nextEntrySequence(run.id),
            kind = AgentEntryKind.APPROVAL_REQUEST,
            requestId = record.requestId,
            toolCallId = record.toolCall.id,
            payloadJson = json.encodeToString(AgentApprovalRecord.serializer(), record),
            status = AgentEntryStatus.FINAL,
            now = now,
        )
        val waiting = run.copy(
            status = AgentRunStatus.WAITING_APPROVAL.name,
            terminalReason = null,
            updatedAt = now,
        )
        dao.persistApprovalPause(entry, waiting)
        return waiting
    }

    suspend fun pendingApproval(runId: String): AgentApprovalRecord? {
        val entries = dao.getEntries(runId)
        val decisions = entries.asSequence()
            .filter { it.kind == AgentEntryKind.APPROVAL_DECISION.name }
            .mapNotNull { entry -> decodeApproval(entry.payloadJson) }
            .map(AgentApprovalRecord::approvalRequestId)
            .toSet()
        return entries.asReversed().firstNotNullOfOrNull { entry ->
            if (entry.kind != AgentEntryKind.APPROVAL_REQUEST.name) return@firstNotNullOfOrNull null
            decodeApproval(entry.payloadJson)?.takeIf { it.approvalRequestId !in decisions }
        }
    }

    /** 已决策但尚未生成 ToolResult 的审批。它是进程退出后自动续接的唯一依据。 */
    suspend fun decidedApprovalAwaitingResult(runId: String): AgentApprovalRecord? {
        // 新审批可能是执行后产生的 UNKNOWN 卡片，必须先等用户处理，禁止旧决定越过它。
        if (pendingApproval(runId) != null) return null
        val entries = dao.getEntries(runId)
        val completedToolCallIds = entries.asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_RESULT.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        return entries.asReversed().firstNotNullOfOrNull { entry ->
            if (entry.kind != AgentEntryKind.APPROVAL_DECISION.name) return@firstNotNullOfOrNull null
            decodeApproval(entry.payloadJson)?.takeIf { record ->
                record.decision != null && record.toolCall.id !in completedToolCallIds
            }
        }
    }

    /**
     * 恢复最后一个已完成 Assistant 的工具批次。
     * 已完成调用跳过；尚未开始的调用保留；已开始却缺少结果的普通工具会先补错误结果。
     */
    suspend fun recoverInterruptedToolBatch(
        runId: String,
        computerDao: ComputerDao,
    ): AgentApprovalRecord? {
        val entries = dao.getEntries(runId)
        if (decidedApprovalAwaitingResult(runId) != null || pendingApproval(runId) != null) return null
        val assistantEntry = entries.asReversed().firstOrNull { entry ->
            entry.kind == AgentEntryKind.ASSISTANT.name && entry.status == AgentEntryStatus.FINAL.name
        } ?: return null
        val calls = decodeAssistantToolCalls(assistantEntry)
        if (calls.isEmpty()) return null
        val completedIds = entries.asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_RESULT.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        val startedIds = entries.asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_EXECUTION_STARTED.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        val context = decodeRequestSnapshot(dao.getRun(runId) ?: return null)?.computerRequestContext

        for (call in calls) {
            if (call.id in completedIds || call.id !in startedIds) continue
            if (call.name in ComputerToolNames.all && context != null) {
                val execution = computerDao.getExecutionByToolCallId(
                    ComputerToolRequestHasher.toolCallKey(call.id, context),
                )
                if (execution == null) {
                    // 开始事实与 ComputerExecution 分属两个存储事务，崩溃可能发生在二者之间。
                    // 只读调用直接回填错误给 AI；可能修改服务器的调用才交给用户决定。
                    if (!ComputerToolCallSafety.requiresUnknownApproval(call.name, call.arguments, context.permissionMode)) {
                        appendToolResult(runId, assistantEntry.requestId ?: return null, resolvedUnknownToolResult(call))
                        continue
                    }
                    return pauseRecoveredUnknownExecution(
                        run = dao.getRun(runId) ?: return null,
                        requestId = assistantEntry.requestId ?: return null,
                        call = call,
                        pendingCalls = calls.dropWhile { it.id != call.id },
                        context = context,
                        computerDao = computerDao,
                    )
                }
                if (execution.status == ComputerExecutionStatus.UNKNOWN.name ||
                    execution.remoteStatus in setOf(
                        com.android.everytalk.data.computer.ComputerRemoteStatus.STARTING.name,
                        com.android.everytalk.data.computer.ComputerRemoteStatus.RUNNING.name,
                    ) ||
                    execution.status in setOf(
                        ComputerExecutionStatus.QUEUED.name,
                        ComputerExecutionStatus.STARTING.name,
                        ComputerExecutionStatus.RUNNING.name,
                    )
                ) return null
            } else {
                if (call.name in ComputerToolNames.all) return null
                if (!hasFinalToolResult(runId, call.id)) {
                    appendToolResult(
                        runId,
                        assistantEntry.requestId ?: return null,
                        interruptedToolResult(call),
                    )
                }
            }
        }

        val refreshedCompletedIds = dao.getEntries(runId).asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_RESULT.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        val nextCall = calls.firstOrNull { it.id !in refreshedCompletedIds }
        val anchor = nextCall ?: calls.last()
        val replayCurrentCall = nextCall != null && nextCall.id in startedIds
        val resumePendingOnly = !replayCurrentCall && nextCall != null
        return AgentApprovalRecord(
            approvalRequestId = "recovered:${anchor.id}",
            requestId = assistantEntry.requestId ?: return null,
            toolCall = anchor,
            pendingToolCalls = if (nextCall == null) emptyList() else calls.dropWhile { it.id != nextCall.id },
            decision = if (replayCurrentCall) AgentApprovalDecision.APPROVED else AgentApprovalDecision.KEEP_UNKNOWN,
            decidedAt = System.currentTimeMillis(),
            toolResultAlreadyPersisted = nextCall == null,
            resumePendingToolCallsOnly = resumePendingOnly,
        )
    }

    /** Agent 已记录开始但 ComputerExecution 尚未出现时，保守恢复为 UNKNOWN 审批。 */
    private suspend fun pauseRecoveredUnknownExecution(
        run: AgentRunEntity,
        requestId: String,
        call: AgentContentBlock.ToolCall,
        pendingCalls: List<AgentContentBlock.ToolCall>,
        context: ComputerRequestContext,
        computerDao: ComputerDao,
    ): AgentApprovalRecord? {
        val record = AgentApprovalRecord(
            approvalRequestId = UUID.randomUUID().toString(),
            requestId = requestId,
            toolCall = call,
            pendingToolCalls = pendingCalls,
            request = ComputerToolApprovalRequest.UnknownExecution(
                toolCallId = call.id,
                context = context,
                computerName = computerDao.getComputer(context.computerId)?.displayName ?: "VPS",
                toolName = call.name,
                detail = unknownExecutionDetail(call),
                isWriteOperation = !ComputerToolCallSafety.isReadOnly(call.name, call.arguments),
            ),
        )
        pauseForApproval(run, record)
        // 新卡片留在 WAITING_APPROVAL；自动恢复扫描不得立即消费它。
        return null
    }

    /** WAITING_APPROVAL 和 INTERRUPTED 都可能包含尚未消费的决定。 */
    suspend fun resumableApprovalRuns(computerDao: ComputerDao): List<Pair<AgentRunEntity, AgentApprovalRecord>> =
        (dao.getWaitingApprovalRuns() + dao.getInterruptedRuns() + dao.getWaitingRemoteExecutionRuns())
            .distinctBy(AgentRunEntity::id)
            .mapNotNull { run ->
                (decidedApprovalAwaitingResult(run.id) ?: recoverInterruptedToolBatch(run.id, computerDao))?.let { run to it }
            }

    /**
     * Computer 恢复把运行中的命令标为 UNKNOWN 后，为原 Tool Call 补建持久审批。
     * 只处理尚未有 ToolResult/审批的调用，重复启动不会生成第二张卡片。
     */
    suspend fun recoverUnknownComputerExecutions(computerDao: ComputerDao) {
        val interruptedRuns = dao.getInterruptedRuns() + dao.getWaitingRemoteExecutionRuns()
        for (execution in computerDao.getUnknownExecutions()) {
            val candidate = interruptedRuns.firstNotNullOfOrNull { run ->
                val snapshot = decodeRequestSnapshot(run) ?: return@firstNotNullOfOrNull null
                val context = snapshot.computerRequestContext ?: return@firstNotNullOfOrNull null
                val entries = dao.getEntries(run.id)
                val callEntry = entries.asReversed().firstOrNull { entry ->
                    entry.kind == AgentEntryKind.ASSISTANT.name &&
                        entry.status == AgentEntryStatus.FINAL.name &&
                        decodeAssistantToolCalls(entry).any { call ->
                            runCatching { ComputerToolRequestHasher.toolCallKey(call.id, context) }.getOrNull() == execution.toolCallId
                        }
                } ?: return@firstNotNullOfOrNull null
                val calls = decodeAssistantToolCalls(callEntry)
                val call = calls.firstOrNull { toolCall ->
                    runCatching { ComputerToolRequestHasher.toolCallKey(toolCall.id, context) }.getOrNull() == execution.toolCallId
                } ?: return@firstNotNullOfOrNull null
                val alreadyHandled = entries.any { entry ->
                    entry.toolCallId == call.id && (
                        entry.kind == AgentEntryKind.TOOL_RESULT.name ||
                            entry.kind == AgentEntryKind.APPROVAL_REQUEST.name &&
                            decodeApproval(entry.payloadJson)?.request is ComputerToolApprovalRequest.UnknownExecution
                        )
                }
                if (alreadyHandled) null else UnknownExecutionCandidate(
                    run = run,
                    requestId = callEntry.requestId ?: return@firstNotNullOfOrNull null,
                    call = call,
                    pendingCalls = calls.dropWhile { it.id != call.id },
                    context = context,
                )
            } ?: continue
            if (!ComputerToolCallSafety.requiresUnknownApproval(
                    candidate.call.name,
                    candidate.call.arguments,
                    candidate.context.permissionMode,
                )
            ) {
                appendToolResult(candidate.run.id, candidate.requestId, resolvedUnknownToolResult(candidate.call))
                continue
            }
            val record = AgentApprovalRecord(
                approvalRequestId = UUID.randomUUID().toString(),
                requestId = candidate.requestId,
                toolCall = candidate.call,
                pendingToolCalls = candidate.pendingCalls,
                request = ComputerToolApprovalRequest.UnknownExecution(
                    toolCallId = candidate.call.id,
                    context = candidate.context,
                    computerName = computerDao.getComputer(candidate.context.computerId)?.displayName ?: "VPS",
                    toolName = candidate.call.name,
                    detail = unknownExecutionDetail(candidate.call),
                    isWriteOperation = !ComputerToolCallSafety.isReadOnly(candidate.call.name, candidate.call.arguments),
                ),
            )
            pauseForApproval(candidate.run, record)
        }
    }

    /** 同一 approvalRequestId 只允许生成一条决定，防止双击导致重复执行。 */
    suspend fun decideApproval(
        runId: String,
        approvalRequestId: String,
        decision: AgentApprovalDecision,
    ): AgentApprovalRecord? = approvalDecisionLock.withLock {
        val pending = pendingApproval(runId)?.takeIf { it.approvalRequestId == approvalRequestId }
            ?: return@withLock null
        val decided = pending.copy(decision = decision, decidedAt = System.currentTimeMillis())
        val run = dao.getRun(runId) ?: return@withLock null
        val now = System.currentTimeMillis()
        val entry = newEntry(
            runId = runId,
            sequence = dao.nextEntrySequence(runId),
            kind = AgentEntryKind.APPROVAL_DECISION,
            requestId = pending.requestId,
            toolCallId = pending.toolCall.id,
            payloadJson = json.encodeToString(AgentApprovalRecord.serializer(), decided),
            status = AgentEntryStatus.FINAL,
            now = now,
        )
        dao.persistApprovalDecision(
            entry = entry,
            interruptedRun = run.copy(
                status = AgentRunStatus.INTERRUPTED.name,
                terminalReason = "APPROVAL_DECIDED_PENDING_RESUME",
                updatedAt = now,
            ),
        )
        decided
    }

    suspend fun saveContinuation(
        run: AgentRunEntity,
        request: ChatRequest,
        continuation: ProviderTurnContinuation,
        systemPromptFingerprint: String,
        toolSchemaFingerprint: String,
        compactionId: String?,
    ) {
        val configId = run.configIdSnapshot ?: return
        val endpoint = request.apiAddress.orEmpty()
        val protocol = continuation.protocol.name
        dao.upsertContinuationState(
            ProviderContinuationStateEntity(
                id = continuationStateId(run.sessionId, configId, protocol, request.provider, endpoint, request.model),
                sessionId = run.sessionId,
                configId = configId,
                protocol = protocol,
                provider = request.provider,
                endpoint = endpoint,
                model = request.model,
                systemPromptFingerprint = systemPromptFingerprint,
                toolSchemaFingerprint = toolSchemaFingerprint,
                summarizedThroughItemId = compactionId,
                opaqueStateJson = json.encodeToString(ProviderTurnContinuation.serializer(), continuation),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun loadContinuation(
        sessionId: String,
        configId: String?,
        request: ChatRequest,
        systemPromptFingerprint: String,
        toolSchemaFingerprint: String,
        compactionId: String?,
    ): ProviderTurnContinuation? {
        val stableConfigId = configId ?: return null
        val protocol = modelParameterProtocol(request.channel).name
        val state = dao.getContinuationState(
            sessionId,
            stableConfigId,
            protocol,
            request.provider,
            request.apiAddress.orEmpty(),
            request.model,
        ) ?: return null
        if (state.systemPromptFingerprint != systemPromptFingerprint ||
            state.toolSchemaFingerprint != toolSchemaFingerprint ||
            state.summarizedThroughItemId != compactionId
        ) {
            dao.deleteContinuationState(state.id)
            return null
        }
        val continuation = runCatching {
            json.decodeFromString(ProviderTurnContinuation.serializer(), state.opaqueStateJson)
        }.getOrElse {
            dao.deleteContinuationState(state.id)
            return null
        }
        if (continuation.protocol.name != state.protocol) {
            dao.deleteContinuationState(state.id)
            return null
        }
        return continuation
    }

    suspend fun usageSummary(runId: String): AgentUsageSummary {
        val requests = dao.getRequests(runId)
        val latestAgentRequest = requests.lastOrNull { it.purpose == AgentRequestPurpose.AGENT_TURN.name }
        val activeContext = latestAgentRequest?.let { request -> dao.getContextSnapshot(request.id) }
        val totals = dao.getRunTokenTotals(runId)
        return AgentUsageSummary(
            activeContextTokens = activeContext?.activeContextTokens ?: 0L,
            activeContext = activeContext,
            runInputTokens = totals.inputTokens,
            runOutputTokens = totals.outputTokens,
            runTotalTokens = totals.totalTokens,
            requestCount = totals.requestCount,
            compactionRequestCount = requests.count { it.purpose == AgentRequestPurpose.COMPACTION.name },
        )
    }

    /**
     * 把可见 AI 投影还原为真实 Agent transcript。
     * 已有关联 Run 的可见 AI 消息必须被内部 Assistant 与 Tool Result 替换，禁止两份内容同时入模。
     */
    suspend fun expandTranscript(
        sessionId: String,
        messages: List<AbstractApiMessage>,
    ): List<AbstractApiMessage> {
        val runsByMessageId = dao.getRunsForSession(sessionId)
            .associateBy(AgentRunEntity::visibleAssistantMessageId)
        if (runsByMessageId.isEmpty()) return messages

        return buildList {
            messages.forEach { message ->
                val run = runsByMessageId[message.id]
                if (run == null) {
                    add(message)
                    return@forEach
                }
                val expanded = decodeFinalTranscriptEntries(run.id)
                if (expanded.isEmpty()) add(message) else addAll(expanded)
            }
        }
    }

    /** 恢复 Run 时把已完成的当前 Assistant/ToolResult 接回原始请求快照。 */
    suspend fun appendRunTranscript(
        runId: String,
        messages: List<AbstractApiMessage>,
    ): List<AbstractApiMessage> = messages + decodeFinalTranscriptEntries(runId)

    /** 进程重建时按 AgentEntry.sequence 还原思考和工具顺序，供可见消息恢复执行抽屉。 */
    suspend fun executionTrace(runId: String): List<ExecutionTraceEvent> = buildList {
        fun appendNarrative(text: String) {
            if (text.isBlank()) return
            val previous = lastOrNull()
            if (previous is ExecutionTraceEvent.Reasoning) {
                this[lastIndex] = previous.copy(text = previous.text + text)
            } else {
                add(ExecutionTraceEvent.Reasoning(text))
            }
        }

        dao.getEntries(runId).forEach { entry ->
            when (entry.kind) {
                AgentEntryKind.ASSISTANT.name -> {
                    val blocks = decodeAssistantBlocks(entry)
                    val isToolRound = blocks.any { it is AgentContentBlock.ToolCall }
                    blocks.forEach { block ->
                        when (block) {
                            is AgentContentBlock.Reasoning -> appendNarrative(block.text)
                            is AgentContentBlock.Text -> if (isToolRound) appendNarrative(block.text)
                            is AgentContentBlock.ToolCall -> add(
                                ExecutionTraceEvent.Tool(block.toExecutionStep(completed = false))
                            )
                            is AgentContentBlock.ToolResult -> Unit
                        }
                    }
                }
                AgentEntryKind.TOOL_RESULT.name -> {
                    val toolCallId = entry.toolCallId ?: return@forEach
                    val index = indexOfLast { event ->
                        event is ExecutionTraceEvent.Tool && event.step.id == toolCallId
                    }
                    if (index >= 0) {
                        val tool = this[index] as ExecutionTraceEvent.Tool
                        this[index] = tool.copy(step = tool.step.copy(completed = true))
                    }
                }
            }
        }
    }

    suspend fun sessionTotalTokens(sessionId: String): Long =
        dao.getSessionTokenTotals(sessionId).totalTokens

    suspend fun latestCompaction(sessionId: String): AgentCompactionEntryEntity? =
        dao.getLatestCompaction(sessionId)

    /** 摘要完整生成后一次写入有效检查点，失败或取消不会覆盖旧检查点。 */
    suspend fun saveCompaction(
        sessionId: String,
        configIdSnapshot: String?,
        plan: AgentCompactionPlan,
        summary: String,
        summaryRequestId: String,
        estimatedTokensAfter: Long,
        retainedTailJson: String,
    ): AgentCompactionEntryEntity = AgentCompactionEntryEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        configIdSnapshot = configIdSnapshot,
        summary = summary,
        summarizedThroughItemId = plan.summarizedThroughItemId,
        prefixFingerprint = plan.prefixFingerprint,
        retainedTailJson = retainedTailJson,
        tokensBefore = plan.tokensBefore,
        estimatedTokensAfter = estimatedTokensAfter,
        summaryRequestId = summaryRequestId,
        status = AgentCompactionStatus.COMPLETED.name,
        createdAt = System.currentTimeMillis(),
    ).also { dao.upsertCompaction(it) }

    private suspend fun appendEntry(
        runId: String,
        kind: AgentEntryKind,
        requestId: String?,
        toolCallId: String?,
        payloadJson: String,
        status: AgentEntryStatus,
    ): AgentEntryEntity = entryAppendLock.withLock {
        val now = System.currentTimeMillis()
        newEntry(
            runId = runId,
            sequence = dao.nextEntrySequence(runId),
            kind = kind,
            requestId = requestId,
            toolCallId = toolCallId,
            payloadJson = payloadJson,
            status = status,
            now = now,
        ).also { dao.upsertEntry(it) }
    }

    private fun newEntry(
        runId: String,
        sequence: Long,
        kind: AgentEntryKind,
        requestId: String?,
        toolCallId: String?,
        payloadJson: String,
        status: AgentEntryStatus,
        now: Long,
    ): AgentEntryEntity = AgentEntryEntity(
            id = UUID.randomUUID().toString(),
            runId = runId,
            sequence = sequence,
            kind = kind.name,
            requestId = requestId,
            toolCallId = toolCallId,
            payloadJson = payloadJson,
            status = status.name,
            createdAt = now,
            finalizedAt = now.takeIf { status == AgentEntryStatus.FINAL },
        )

    private suspend fun decodeFinalTranscriptEntries(runId: String): List<AbstractApiMessage> =
        dao.getEntries(runId).mapNotNull { entry ->
            if (entry.status != AgentEntryStatus.FINAL.name) return@mapNotNull null
            when (entry.kind) {
                AgentEntryKind.ASSISTANT.name -> decodeAssistantEntry(entry)
                AgentEntryKind.TOOL_RESULT.name -> decodeToolResultEntry(entry)
                AgentEntryKind.STATUS.name -> decodeStatusEntry(entry)
                else -> null
            }
        }

    private fun decodeAssistantEntry(entry: AgentEntryEntity): AgentAssistantApiMessage? =
        runCatching {
            val blocks = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AgentContentBlock.serializer()),
                entry.payloadJson,
            )
            AgentAssistantApiMessage(
                id = "assistant:${entry.requestId ?: entry.id}",
                text = blocks.filterIsInstance<AgentContentBlock.Text>().joinToString("") { it.text },
                reasoning = blocks.filterIsInstance<AgentContentBlock.Reasoning>().joinToString("") { it.text },
                toolCalls = blocks.filterIsInstance<AgentContentBlock.ToolCall>().map { call ->
                    AgentToolCallApiPart(call.id, call.name, call.arguments)
                },
            )
        }.getOrNull()

    private fun decodeAssistantToolCalls(entry: AgentEntryEntity): List<AgentContentBlock.ToolCall> = runCatching {
        decodeAssistantBlocks(entry).filterIsInstance<AgentContentBlock.ToolCall>()
    }.getOrDefault(emptyList())

    private fun decodeAssistantBlocks(entry: AgentEntryEntity): List<AgentContentBlock> =
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(AgentContentBlock.serializer()),
            entry.payloadJson,
        )

    private fun decodeToolResultEntry(entry: AgentEntryEntity): AgentToolResultApiMessage? =
        runCatching {
            val result = json.decodeFromString(AgentContentBlock.ToolResult.serializer(), entry.payloadJson)
            AgentToolResultApiMessage(
                id = "tool:${result.toolCallId}",
                toolCallId = result.toolCallId,
                toolName = result.toolName,
                content = result.content,
                isError = result.isError,
            )
        }.getOrNull()

    /** 状态事件作为内部 system 消息进入恢复请求，让 AI 解释现状且禁止重复执行。 */
    private fun decodeStatusEntry(entry: AgentEntryEntity): SimpleTextApiMessage? = runCatching {
        val payload = json.parseToJsonElement(entry.payloadJson).jsonObject
        val reason = payload["reason"]?.jsonPrimitive?.content ?: return@runCatching null
        val message = payload["message"]?.jsonPrimitive?.content.orEmpty()
        val executionId = payload["execution_id"]?.jsonPrimitive?.content
        SimpleTextApiMessage(
            id = "status:${entry.id}",
            role = "system",
            content = buildString {
                append("[EveryTalk Agent 状态] ").append(reason).append(": ").append(message)
                executionId?.let { append("，execution_id=").append(it) }
                append("。这是已有任务的状态，禁止重复创建或重跑命令。")
            },
        )
    }.getOrNull()

    private fun decodeApproval(payloadJson: String): AgentApprovalRecord? = runCatching {
        json.decodeFromString(AgentApprovalRecord.serializer(), payloadJson)
    }.getOrNull()
}

private fun interruptedToolResult(call: AgentContentBlock.ToolCall): AgentContentBlock.ToolResult =
    AgentContentBlock.ToolResult(
        toolCallId = call.id,
        toolName = call.name,
        content = JsonPrimitive("App 在工具执行期间退出，结果未能确认；为避免重复产生外部操作，本次调用未自动重放"),
        isError = true,
    )

private data class UnknownExecutionCandidate(
    val run: AgentRunEntity,
    val requestId: String,
    val call: AgentContentBlock.ToolCall,
    val pendingCalls: List<AgentContentBlock.ToolCall>,
    val context: ComputerRequestContext,
)

private fun unknownExecutionDetail(call: AgentContentBlock.ToolCall): String = when (call.name) {
    "exec" -> (call.arguments["command"] as? JsonPrimitive)?.content.orEmpty()
    "read_file", "write_file" -> (call.arguments["path"] as? JsonPrimitive)?.content.orEmpty()
    "upload" -> (call.arguments["destination_path"] as? JsonPrimitive)?.content.orEmpty()
    "download" -> (call.arguments["source_path"] as? JsonPrimitive)?.content.orEmpty()
    "open_port" -> (call.arguments["port"] as? JsonPrimitive)?.content.orEmpty()
    else -> call.name
}

/** 无需人工决定时把 UNKNOWN 明确交回 AI，禁止恢复流程卡在隐藏审批上。 */
private fun resolvedUnknownToolResult(call: AgentContentBlock.ToolCall): AgentContentBlock.ToolResult =
    AgentContentBlock.ToolResult(
        toolCallId = call.id,
        toolName = call.name,
        content = JsonPrimitive("上次操作的结果无法确认，未自动重复执行。请根据用户目标和当前服务器状态决定下一步。"),
        isError = true,
    )

private fun AgentContentBlock.ToolCall.toExecutionStep(completed: Boolean): ExecutionStep {
    val label = unknownExecutionDetail(this).ifBlank { name }.replace('\r', ' ').replace('\n', ' ').take(120)
    val type = if (name in setOf("exec", "read_file", "write_file", "terminal", "upload", "download", "open_port")) {
        ExecutionStepType.Agent
    } else {
        ExecutionStepType.Tool
    }
    return ExecutionStep(
        id = id,
        type = type,
        title = if (type == ExecutionStepType.Agent) "运行 Agent" else "调用工具",
        labels = listOf(label),
        completed = completed,
        reasoningBefore = "",
    )
}

/** 请求快照只保留恢复需要的本地参数，API Key 和设备标识禁止落库。 */
private fun ChatRequest.toRecoverySnapshot(): AgentRequestSnapshot = AgentRequestSnapshot(
    messages = messages,
    provider = provider,
    channel = channel,
    apiAddress = apiAddress,
    model = model,
    forceGoogleReasoningPrompt = forceGoogleReasoningPrompt,
    useWebSearch = useWebSearch,
    generationConfig = generationConfig,
    toolsJson = tools?.let(::anyToJsonElement),
    toolChoiceJson = toolChoice?.let(::anyToJsonElement),
    qwenEnableSearch = qwenEnableSearch,
    customModelParametersJson = customModelParameters?.let(::anyToJsonElement),
    customExtraBodyJson = customExtraBody?.let(::anyToJsonElement),
    enableCodeExecution = enableCodeExecution,
    contextManagement = contextManagement,
    computerRequestContext = localComputerRequestContext,
    skillSnapshot = localSkillSnapshot,
)

private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
        (key as? String ?: throw IllegalArgumentException("恢复快照包含非字符串 Map Key")) to anyToJsonElement(item)
    })
    is Iterable<*> -> JsonArray(value.map(::anyToJsonElement))
    is Array<*> -> JsonArray(value.map(::anyToJsonElement))
    else -> throw IllegalArgumentException("恢复快照包含不支持的参数类型：${value::class.simpleName}")
}

internal fun jsonElementToAny(element: JsonElement): Any? = when (element) {
    JsonNull -> null
    is JsonObject -> element.mapValues { (_, value) -> jsonElementToAny(value) }
    is JsonArray -> element.map(::jsonElementToAny)
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.boolean
        element.longOrNull != null -> element.long
        element.doubleOrNull != null -> element.double
        else -> element.content
    }
}

private fun jsonElementToStringMap(element: JsonElement): Map<String, Any> =
    (jsonElementToAny(element) as? Map<*, *>)
        ?.entries
        ?.associate { (key, value) ->
            (key as? String ?: throw IllegalArgumentException("恢复参数名无效")) to
                (value ?: throw IllegalArgumentException("恢复参数值为空"))
        }
        ?: throw IllegalArgumentException("恢复参数不是对象")

private fun jsonElementToStringMapList(element: JsonElement): List<Map<String, Any>> =
    (jsonElementToAny(element) as? List<*>)
        ?.map { item ->
            val map = item as? Map<*, *> ?: throw IllegalArgumentException("恢复工具定义不是对象")
            map.entries.associate { (key, value) ->
                (key as? String ?: throw IllegalArgumentException("恢复工具字段无效")) to
                    (value ?: throw IllegalArgumentException("恢复工具字段为空"))
            }
        }
        ?: throw IllegalArgumentException("恢复工具定义不是数组")

private fun continuationStateId(
    sessionId: String,
    configId: String,
    protocol: String,
    provider: String,
    endpoint: String,
    model: String,
): String = agentTranscriptFingerprint(listOf(sessionId, configId, protocol, provider, endpoint, model))

internal fun agentTranscriptFingerprint(parts: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun safeAdd(left: Long?, right: Long?): Long? = when {
    left == null -> right
    right == null -> left
    left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    else -> left + right
}

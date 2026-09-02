package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import com.android.everytalk.data.database.entities.AgentEntryEntity
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRequestUsageEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.AgentRunSnapshotChunkEntity
import com.android.everytalk.data.database.entities.AgentSteeringMessageEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.modelParameterProtocol
import com.android.everytalk.data.DataClass.toApiText
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerExecutionStatus
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.ComputerToolRequestHasher
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.ComputerToolCallSafety
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.skill.SkillRequestSnapshot
import com.android.everytalk.models.SelectedMediaItem
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
typealias AgentQueuedMessageMaterializer = suspend (
    instruction: AgentSteeringInstruction,
    request: ChatRequest,
) -> AbstractApiMessage

typealias AgentSkillReferenceValidator = suspend (
    references: List<MessageSkillReference>,
) -> List<MessageSkillReference>

data class ConsumedAgentFollowUp(
    val message: AbstractApiMessage,
    val instruction: AgentSteeringInstruction,
)

private data class PreparedQueuedUserMessage(
    val instruction: AgentSteeringInstruction,
    val message: AbstractApiMessage,
    val payloadJson: String,
    val historyMessage: MessageEntity,
    val updatedRun: AgentRunEntity?,
    val updatedSnapshot: AgentRequestSnapshot?,
    val snapshotChunks: List<AgentRunSnapshotChunkEntity>,
) {
    fun toEntry(runId: String, sequence: Long, kind: AgentEntryKind): AgentEntryEntity {
        val now = System.currentTimeMillis()
        return AgentEntryEntity(
            id = "${kind.name.lowercase()}:${instruction.id}",
            runId = runId,
            sequence = sequence,
            kind = kind.name,
            requestId = null,
            toolCallId = null,
            payloadJson = payloadJson,
            status = AgentEntryStatus.FINAL.name,
            createdAt = instruction.createdAt,
            finalizedAt = now,
        )
    }
}

class AgentRunStore(
    private val dao: AgentDao,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
    private val queuedMessageMaterializer: AgentQueuedMessageMaterializer = { instruction, _ ->
        require(instruction.attachments.isEmpty()) { "当前 AgentRunStore 没有配置附件读取器" }
        SimpleTextApiMessage(
            id = "queued:${instruction.id}",
            role = "user",
            content = instruction.contentParts.toApiText(instruction.content),
        )
    },
    private val skillReferenceValidator: AgentSkillReferenceValidator = { it },
) {
    private val approvalDecisionLock = kotlinx.coroutines.sync.Mutex()
    private val entryAppendLock = kotlinx.coroutines.sync.Mutex()
    private val snapshotReadLock = kotlinx.coroutines.sync.Mutex()
    private val snapshotCache = ConcurrentHashMap<String, AgentRequestSnapshot>()
    suspend fun createRun(
        sessionId: String,
        userMessageId: String,
        visibleAssistantMessageId: String,
        configIdSnapshot: String?,
        request: ChatRequest,
    ): AgentRunEntity {
        val now = System.currentTimeMillis()
        val snapshot = request.toRecoverySnapshot()
        val encodedSnapshot = json.encodeToString(AgentRequestSnapshot.serializer(), snapshot)
        val run = AgentRunEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            userMessageId = userMessageId,
            visibleAssistantMessageId = visibleAssistantMessageId,
            configIdSnapshot = configIdSnapshot,
            // 大快照放入分块表，主表必须保持轻量，避免 CursorWindow 单行溢出。
            requestSnapshotJson = null,
            status = AgentRunStatus.CREATED.name,
            currentRequestOrdinal = 0,
            terminalReason = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.startRunSupersedingWaitingApprovals(
            run = run,
            snapshotChunks = agentRequestSnapshotChunks(run.id, encodedSnapshot),
            reason = AgentTerminalReasons.SUPERSEDED_BY_NEW_RUN,
        )
        snapshotCache[run.id] = snapshot
        request.initialExecutionCheckpoint(now)?.let { checkpoint ->
            saveExecutionCheckpoint(run.id, checkpoint)
        }
        return run
    }

    suspend fun getRun(runId: String): AgentRunEntity? = dao.getRun(runId)

    suspend fun getRunByVisibleMessage(messageId: String): AgentRunEntity? =
        dao.getRunByVisibleMessage(messageId)

    suspend fun getRunsForSession(sessionId: String): List<AgentRunEntity> =
        dao.getRunsForSession(sessionId)

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

    /** 老 Run 没有该 Entry 时返回 null，不需要数据库迁移或补写默认值。 */
    suspend fun executionCheckpoint(runId: String): ExecutionCheckpoint? = dao.getEntries(runId)
        .lastOrNull { entry -> entry.kind == AgentEntryKind.EXECUTION_CHECKPOINT.name }
        ?.let(::decodeExecutionCheckpoint)

    /**
     * 使用固定 Entry ID 原地更新当前检查点，避免每次状态变化都让 agent_entries 无限增长。
     * Entry 的 sequence 首次创建后保持不变，恢复时仍能稳定读取最新内容。
     */
    suspend fun updateExecutionCheckpoint(
        runId: String,
        update: (ExecutionCheckpoint) -> ExecutionCheckpoint,
    ): ExecutionCheckpoint = entryAppendLock.withLock {
        val existing = dao.getEntries(runId)
            .lastOrNull { entry -> entry.kind == AgentEntryKind.EXECUTION_CHECKPOINT.name }
        val current = existing?.let(::decodeExecutionCheckpoint) ?: ExecutionCheckpoint()
        val now = System.currentTimeMillis()
        val updated = update(current).copy(updatedAt = now)
        val payload = json.encodeToString(ExecutionCheckpoint.serializer(), updated)
        val entry = existing?.copy(
            payloadJson = payload,
            status = AgentEntryStatus.FINAL.name,
            finalizedAt = now,
        ) ?: newEntry(
            runId = runId,
            sequence = dao.nextEntrySequence(runId),
            kind = AgentEntryKind.EXECUTION_CHECKPOINT,
            requestId = null,
            toolCallId = null,
            payloadJson = payload,
            status = AgentEntryStatus.FINAL,
            now = now,
        ).copy(id = "execution-checkpoint:$runId")
        dao.upsertEntry(entry)
        updated
    }

    suspend fun updateExecutionStep(
        runId: String,
        currentStep: String?,
        resumeInstruction: String? = null,
    ): ExecutionCheckpoint = updateExecutionCheckpoint(runId) { current ->
        current.copy(
            currentStep = currentStep?.compactCheckpointText(),
            resumeInstruction = resumeInstruction?.compactCheckpointText(),
        )
    }

    /** steering 是最新用户明确要求，机械覆盖 Goal 和恢复指令，并追加明确写出的硬约束。 */
    private suspend fun mergeExecutionCheckpointInstruction(runId: String, content: String) {
        val instruction = content.compactCheckpointText()
        val constraints = content.explicitConstraintLines()
        updateExecutionCheckpoint(runId) { current ->
            current.copy(
                currentGoal = instruction,
                hardConstraints = (current.hardConstraints + constraints).distinct().takeLast(MAX_CHECKPOINT_CONSTRAINTS),
                resumeInstruction = instruction,
            )
        }
    }

    private suspend fun saveExecutionCheckpoint(runId: String, checkpoint: ExecutionCheckpoint) {
        updateExecutionCheckpoint(runId) { checkpoint }
    }

    suspend fun finalExecutedToolCalls(runId: String): List<AgentContentBlock.ToolCall> {
        val entries = dao.getEntries(runId)
        val completedCalls = entries.asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_RESULT.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull { entry -> entry.toolCallId?.let { callId -> entry.requestId to callId } }
            .toSet()
        return entries
            .filter { it.kind == AgentEntryKind.ASSISTANT.name && it.status == AgentEntryStatus.FINAL.name }
            .flatMap { entry ->
                decodeAssistantToolCalls(entry).filter { call -> (entry.requestId to call.id) in completedCalls }
            }
    }

    /** ToolResult 必须同时绑定模型回合，防止 Provider 在后续回合复用 Tool Call ID。 */
    suspend fun finalToolResult(
        runId: String,
        requestId: String,
        toolCallId: String,
    ): AgentContentBlock.ToolResult? = dao.getEntries(runId).asReversed().firstNotNullOfOrNull { entry ->
        if (
            entry.kind == AgentEntryKind.TOOL_RESULT.name &&
                entry.status == AgentEntryStatus.FINAL.name &&
                entry.toolCallId == toolCallId &&
                entry.requestId == requestId
        ) {
            runCatching {
                json.decodeFromString(AgentContentBlock.ToolResult.serializer(), entry.payloadJson)
            }.getOrNull()
        } else {
            null
        }
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
    suspend fun findToolCall(
        runId: String,
        toolCallId: String,
        requestId: String? = null,
    ): AgentContentBlock.ToolCall? =
        dao.getEntries(runId).asReversed().firstNotNullOfOrNull { entry ->
            if (
                entry.kind != AgentEntryKind.ASSISTANT.name ||
                (requestId != null && entry.requestId != requestId)
            ) return@firstNotNullOfOrNull null
            decodeAssistantToolCalls(entry).firstOrNull { it.id == toolCallId }
        }

    suspend fun decodeRequestSnapshot(run: AgentRunEntity): AgentRequestSnapshot? {
        snapshotCache[run.id]?.let { return it }
        return snapshotReadLock.withLock {
            snapshotCache[run.id]?.let { return@withLock it }
            // requestSnapshotJson 只用于兼容 24 版及更早的数据；25 版开始分页读取小块。
            val encoded = run.requestSnapshotJson ?: readChunkedSnapshot(run.id) ?: return@withLock null
            runCatching {
                json.decodeFromString(AgentRequestSnapshot.serializer(), encoded)
            }.getOrNull()?.also { snapshotCache[run.id] = it }
        }
    }

    /**
     * 每次最多读取 8 个 64K 块，保证单次 CursorWindow 数据量明显低于 Android 的约 2MB 上限。
     * 块编号必须从 0 连续递增，缺块时拒绝拼出半份上下文。
     */
    private suspend fun readChunkedSnapshot(runId: String): String? {
        val snapshot = StringBuilder()
        var expectedChunkIndex = 0
        while (true) {
            val page = dao.getRunSnapshotChunkPage(
                runId = runId,
                afterChunkIndex = expectedChunkIndex - 1,
                limit = AGENT_REQUEST_SNAPSHOT_READ_PAGE_SIZE,
            )
            if (page.isEmpty()) return snapshot.takeIf { expectedChunkIndex > 0 }?.toString()
            page.forEach { chunk ->
                if (chunk.chunkIndex != expectedChunkIndex) return null
                snapshot.append(chunk.payload)
                expectedChunkIndex += 1
            }
            if (page.size < AGENT_REQUEST_SNAPSHOT_READ_PAGE_SIZE) return snapshot.toString()
        }
    }

    suspend fun restoreChatRequest(run: AgentRunEntity, apiKey: String): ChatRequest? {
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
    suspend fun updateRequestSnapshot(run: AgentRunEntity, request: ChatRequest): AgentRunEntity {
        val snapshot = request.toRecoverySnapshot()
        val encodedSnapshot = json.encodeToString(AgentRequestSnapshot.serializer(), snapshot)
        return run.copy(
            requestSnapshotJson = null,
            updatedAt = System.currentTimeMillis(),
        ).also { updated ->
            dao.persistRunSnapshot(updated, agentRequestSnapshotChunks(updated.id, encodedSnapshot))
            snapshotCache[updated.id] = snapshot
        }
    }

    suspend fun updateRunStatus(
        run: AgentRunEntity,
        status: AgentRunStatus,
        requestOrdinal: Int = run.currentRequestOrdinal,
        terminalReason: String? = run.terminalReason,
    ): AgentRunEntity {
        val now = System.currentTimeMillis()
        if (status == AgentRunStatus.CANCELLED) {
            dao.cancelActiveRunById(run.id, terminalReason ?: AgentTerminalReasons.USER_STOP, now)
            dao.revokeGrantsForRun(run.id)
            dao.revokeResourceLeasesForRun(run.id)
            snapshotCache.remove(run.id)
            dao.deleteRunSnapshotChunks(run.id)
            dao.deleteContinuationStates(run.sessionId)
            return dao.getRun(run.id) ?: run.copy(
                status = status.name,
                terminalReason = terminalReason,
                updatedAt = now,
                runGeneration = run.runGeneration + 1,
            )
        }
        val changed = dao.updateRunStatusIfActive(
            runId = run.id,
            expectedGeneration = run.runGeneration,
            status = status.name,
            requestOrdinal = requestOrdinal,
            terminalReason = terminalReason,
            updatedAt = now,
        )
        val persisted = dao.getRun(run.id) ?: run
        if (changed == 1 && status in AGENT_TERMINAL_RUN_STATUSES) snapshotCache.remove(run.id)
        if (changed == 1 && status in AGENT_FINAL_RUN_STATUSES) dao.deleteRunSnapshotChunks(run.id)
        return persisted
    }

    /** 按消息 ID 原子记录用户停止，并清掉只服务于恢复的内存快照。 */
    suspend fun cancelActiveRunByVisibleMessage(
        messageId: String,
        reason: String,
    ): AgentRunEntity? {
        val changed = dao.cancelActiveRunByVisibleMessage(
            messageId = messageId,
            reason = reason,
            updatedAt = System.currentTimeMillis(),
        )
        val run = dao.getRunByVisibleMessage(messageId)
        if (changed > 0 && run != null) {
            dao.revokeGrantsForRun(run.id)
            dao.revokeResourceLeasesForRun(run.id)
            snapshotCache.remove(run.id)
            dao.deleteRunSnapshotChunks(run.id)
            dao.deleteContinuationStates(run.sessionId)
        }
        return run
    }

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
        retryOfRequest: AgentRequestEntity? = null,
    ): AgentRequestEntity {
        val request = AgentRequestEntity(
            id = snapshot.requestId,
            runId = run.id,
            ordinal = ordinal,
            purpose = purpose.name,
            modelTurnOrdinal = modelTurnOrdinal,
            attempt = (retryOfRequest?.attempt ?: 0) + 1,
            retryOfRequestId = retryOfRequest?.id,
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
    ): AgentEntryEntity = entryAppendLock.withLock {
        val payloadJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AgentContentBlock.serializer()),
            turn.blocks,
        )
        val now = System.currentTimeMillis()
        if (status == AgentEntryStatus.PARTIAL) {
            val existing = dao.getPartialAssistantEntry(runId, requestId)
            val checkpoint = existing?.copy(payloadJson = payloadJson) ?: newEntry(
                runId = runId,
                sequence = dao.nextEntrySequence(runId),
                kind = AgentEntryKind.ASSISTANT,
                requestId = requestId,
                toolCallId = null,
                payloadJson = payloadJson,
                status = status,
                now = now,
            ).copy(id = "assistant-partial:$requestId")
            dao.upsertEntry(checkpoint)
            checkpoint
        } else {
            // 先写最终事实，再清理检查点。即使两步之间进程退出，恢复读取也会优先最终事实。
            val finalEntry = newEntry(
                runId = runId,
                sequence = dao.nextEntrySequence(runId),
                kind = AgentEntryKind.ASSISTANT,
                requestId = requestId,
                toolCallId = null,
                payloadJson = payloadJson,
                status = status,
                now = now,
            )
            dao.upsertEntry(finalEntry)
            dao.deletePartialAssistantEntries(runId)
            finalEntry
        }
    }

    /**
     * Provider retry 不能继承失败 attempt 的临时 Assistant。
     * 一个 Run 同时最多只有一个模型请求在流式输出，因此清理该 Run 的 PARTIAL 即可。
     */
    suspend fun discardPartialAssistantAttempt(runId: String) {
        entryAppendLock.withLock {
            dao.deletePartialAssistantEntries(runId)
        }
    }

    /**
     * 只有最后一条模型请求仍为 INTERRUPTED 才能重试。
     * 历史中断若已被后续成功请求覆盖，不能再成为新请求的 retryOf。
     */
    suspend fun latestInterruptedAgentRequest(runId: String): AgentRequestEntity? =
        dao.getRequests(runId)
            .lastOrNull { request -> request.purpose == AgentRequestPurpose.AGENT_TURN.name }
            ?.takeIf { request -> request.status == AgentRequestStatus.INTERRUPTED.name }

    suspend fun appendToolResult(
        runId: String,
        requestId: String,
        result: AgentContentBlock.ToolResult,
    ): AgentEntryEntity = entryAppendLock.withLock {
        // 第一个最终结果就是事实源。恢复、重复 callback 或双重调度都不能追加第二份结果。
        dao.getEntries(runId).firstOrNull { entry ->
            entry.kind == AgentEntryKind.TOOL_RESULT.name &&
                entry.status == AgentEntryStatus.FINAL.name &&
                entry.requestId == requestId &&
                entry.toolCallId == result.toolCallId
        } ?: run {
            val now = System.currentTimeMillis()
            newEntry(
                runId = runId,
                sequence = dao.nextEntrySequence(runId),
                kind = AgentEntryKind.TOOL_RESULT,
                requestId = requestId,
                toolCallId = result.toolCallId,
                payloadJson = json.encodeToString(AgentContentBlock.ToolResult.serializer(), result),
                status = AgentEntryStatus.FINAL,
                now = now,
            ).copy(id = stableToolResultEntryId(runId, requestId, result.toolCallId))
                .also { dao.upsertEntry(it) }
        }
    }

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

    /**
     * 判断最近一轮 Assistant 的完整工具批次是否已经按 Pi 语义要求结束 Agent。
     *
     * `terminate` 属于受信 Executor 控制元数据，随 ToolResult 账本持久化。App 如果在
     * ToolResult 落库后、Run 标记完成前退出，恢复流程必须读取这份事实，不能额外请求一次模型。
     */
    suspend fun latestCompletedToolBatchTerminates(runId: String): Boolean {
        val entries = dao.getEntries(runId)
        val assistant = entries.asReversed().firstOrNull { entry ->
            entry.kind == AgentEntryKind.ASSISTANT.name && entry.status == AgentEntryStatus.FINAL.name
        } ?: return false
        val calls = decodeAssistantToolCalls(assistant)
        if (calls.isEmpty()) return false
        val requestId = assistant.requestId ?: return false
        val callIds = calls.mapTo(hashSetOf(), AgentContentBlock.ToolCall::id)
        val resultsByCallId = entries.asSequence()
            .filter { entry ->
                entry.kind == AgentEntryKind.TOOL_RESULT.name &&
                    entry.status == AgentEntryStatus.FINAL.name &&
                    entry.requestId == requestId &&
                    entry.toolCallId in callIds
            }
            .mapNotNull { entry ->
                runCatching {
                    json.decodeFromString(AgentContentBlock.ToolResult.serializer(), entry.payloadJson)
                }.getOrNull()
            }
            .associateBy(AgentContentBlock.ToolResult::toolCallId)
        return calls.all { call -> resultsByCallId[call.id]?.terminate == true }
    }

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
        val completedToolCalls = entries.asSequence()
            .filter { it.kind == AgentEntryKind.TOOL_RESULT.name && it.status == AgentEntryStatus.FINAL.name }
            .mapNotNull { entry -> entry.toolCallId?.let { callId -> entry.requestId to callId } }
            .toSet()
        return entries.asReversed().firstNotNullOfOrNull { entry ->
            if (entry.kind != AgentEntryKind.APPROVAL_DECISION.name) return@firstNotNullOfOrNull null
            decodeApproval(entry.payloadJson)?.takeIf { record ->
                record.decision != null && (record.requestId to record.toolCall.id) !in completedToolCalls
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
        val assistantRequestId = assistantEntry.requestId ?: return null
        val calls = decodeAssistantToolCalls(assistantEntry)
        if (calls.isEmpty()) return null
        val completedIds = entries.asSequence()
            .filter {
                it.kind == AgentEntryKind.TOOL_RESULT.name &&
                    it.status == AgentEntryStatus.FINAL.name &&
                    it.requestId == assistantRequestId
            }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        val startedIds = entries.asSequence()
            .filter {
                it.kind == AgentEntryKind.TOOL_EXECUTION_STARTED.name &&
                    it.status == AgentEntryStatus.FINAL.name &&
                    it.requestId == assistantRequestId
            }
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
                        appendToolResult(runId, assistantRequestId, resolvedUnknownToolResult(call))
                        continue
                    }
                    return pauseRecoveredUnknownExecution(
                        run = dao.getRun(runId) ?: return null,
                        requestId = assistantRequestId,
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
                if (finalToolResult(runId, assistantRequestId, call.id) == null) {
                    appendToolResult(
                        runId,
                        assistantRequestId,
                        interruptedToolResult(call),
                    )
                }
            }
        }

        val refreshedCompletedIds = dao.getEntries(runId).asSequence()
            .filter {
                it.kind == AgentEntryKind.TOOL_RESULT.name &&
                    it.status == AgentEntryStatus.FINAL.name &&
                    it.requestId == assistantRequestId
            }
            .mapNotNull(AgentEntryEntity::toolCallId)
            .toSet()
        val nextCall = calls.firstOrNull { it.id !in refreshedCompletedIds }
        val anchor = nextCall ?: calls.last()
        val replayCurrentCall = nextCall != null && nextCall.id in startedIds
        val resumePendingOnly = !replayCurrentCall && nextCall != null
        return AgentApprovalRecord(
            approvalRequestId = "recovered:${anchor.id}",
            requestId = assistantRequestId,
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
        val continuationRequestId = continuation.assistantMessageId
            ?.removePrefix("assistant:")
            ?.takeIf(String::isNotBlank)
        if (continuationRequestId == null || !dao.hasFinalAssistantForRequest(continuationRequestId)) {
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

    /** 进程重建时按 AgentEntry.sequence 还原正文、思考和工具顺序。 */
    suspend fun executionTrace(runId: String): List<ExecutionTraceEvent> = buildList {
        fun appendContent(text: String, startedAtMillis: Long) {
            if (text.isBlank()) return
            val previous = lastOrNull()
            if (previous is ExecutionTraceEvent.Content) {
                this[lastIndex] = previous.copy(text = previous.text + text)
            } else {
                add(ExecutionTraceEvent.Content(text, startedAtMillis))
            }
        }

        fun appendNarrative(text: String, startedAtMillis: Long) {
            if (text.isBlank()) return
            val previous = lastOrNull()
            if (previous is ExecutionTraceEvent.Reasoning) {
                this[lastIndex] = previous.copy(text = previous.text + text)
            } else {
                add(ExecutionTraceEvent.Reasoning(text, startedAtMillis))
            }
        }

        val entries = dao.getEntries(runId)
        val finalizedAssistantRequests = entries.asSequence()
            .filter { entry ->
                entry.kind == AgentEntryKind.ASSISTANT.name && entry.status == AgentEntryStatus.FINAL.name
            }
            .mapNotNullTo(hashSetOf()) { it.requestId }
        val latestFinalAssistantSequence = entries.asSequence()
            .filter { entry ->
                entry.kind == AgentEntryKind.ASSISTANT.name && entry.status == AgentEntryStatus.FINAL.name
            }
            .maxOfOrNull { it.sequence }
        entries.forEach { entry ->
            if (entry.status == AgentEntryStatus.PARTIAL.name &&
                (entry.requestId in finalizedAssistantRequests || entry.sequence < (latestFinalAssistantSequence ?: Long.MIN_VALUE))
            ) {
                return@forEach
            }
            when (entry.kind) {
                AgentEntryKind.ASSISTANT.name -> {
                    val blocks = decodeAssistantBlocks(entry)
                    blocks.forEach { block ->
                        when (block) {
                            is AgentContentBlock.Reasoning -> appendNarrative(block.text, entry.createdAt)
                            is AgentContentBlock.Text -> appendContent(block.text, entry.createdAt)
                            is AgentContentBlock.ToolCall -> add(
                                ExecutionTraceEvent.Tool(
                                    step = block.toExecutionStep(completed = false),
                                    startedAtMillis = entry.createdAt,
                                )
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
                        this[index] = tool.copy(
                            step = tool.step.copy(completed = true),
                            finishedAtMillis = entry.finalizedAt ?: entry.createdAt,
                        )
                    }
                }
            }
        }
    }

    suspend fun sessionTotalTokens(sessionId: String): Long =
        dao.getSessionTokenTotals(sessionId).totalTokens

    suspend fun latestCompaction(sessionId: String): AgentCompactionEntryEntity? =
        dao.getLatestCompaction(sessionId)

    /** 完整 steering 入队。载荷只含正文、Skill 引用和附件文件引用。 */
    suspend fun enqueueSteering(runId: String, instruction: AgentSteeringInstruction): Boolean {
        val run = dao.getRun(runId) ?: return false
        val request = restoreChatRequest(run, apiKey = "") ?: return false
        return dao.enqueueSteeringWithUserMessage(
            id = instruction.id,
            runId = runId,
            content = instruction.content,
            payloadJson = json.encodeToString(AgentSteeringInstruction.serializer(), instruction),
            createdAt = instruction.createdAt,
            userMessage = instruction.toHistoryMessage(request).toEntity(run.sessionId),
        )
    }

    /**
     * 在模型轮次之间消费 steering。Pi 默认 one-at-a-time，剩余消息留到下一模型边界。
     * 队列消费、Transcript 事实和新增 Skill 冻结快照在同一 Room 事务提交。
     */
    suspend fun consumePendingSteering(runId: String): List<AbstractApiMessage> {
        val consumed = entryAppendLock.withLock {
            val steering = dao.getPendingSteering(runId).firstOrNull() ?: return@withLock null
            val instruction = steering.payloadJson?.let { payload ->
                runCatching {
                    json.decodeFromString(AgentSteeringInstruction.serializer(), payload)
                }.getOrNull()
            } ?: AgentSteeringInstruction(
                id = steering.id,
                content = steering.content,
                createdAt = steering.createdAt,
            )
            val prepared = prepareQueuedUserMessage(runId, instruction) ?: return@withLock null
            val entry = prepared.toEntry(
                runId = runId,
                sequence = dao.nextEntrySequence(runId),
                kind = AgentEntryKind.STEERING,
            )
            if (!dao.consumeSteering(
                    entry = entry,
                    steeringId = steering.id,
                    consumedAt = System.currentTimeMillis(),
                    updatedRun = prepared.updatedRun,
                    snapshotChunks = prepared.snapshotChunks,
                )
            ) return@withLock null
            prepared.updatedSnapshot?.let { snapshotCache[runId] = it }
            prepared
        } ?: return emptyList()
        mergeExecutionCheckpointInstruction(runId, consumed.instruction.content)
        return listOf(consumed.message)
    }

    /**
     * Agent 原本准备结束时才消费一条 Pending，等价于 Pi followUp one-at-a-time。
     * Pending 删除和 FOLLOW_UP Transcript 写入为同一事务，强杀后只会看到其中一种状态。
     */
    suspend fun consumePendingFollowUp(runId: String, sessionId: String): ConsumedAgentFollowUp? {
        val consumed = entryAppendLock.withLock {
            val pending = dao.getPendingFollowUpHead(sessionId)
                ?.takeIf { it.status == "PENDING" }
                ?: return@withLock null
            val instruction = AgentSteeringInstruction(
                id = pending.id,
                content = pending.content,
                contentParts = pending.contentParts,
                attachments = pending.attachments,
                createdAt = pending.createdAt,
            )
            val prepared = prepareQueuedUserMessage(runId, instruction) ?: return@withLock null
            val entry = prepared.toEntry(
                runId = runId,
                sequence = dao.nextEntrySequence(runId),
                kind = AgentEntryKind.FOLLOW_UP,
            )
            if (!dao.consumePendingFollowUp(
                    entry = entry,
                    pendingId = pending.id,
                    userMessage = prepared.historyMessage,
                    updatedRun = prepared.updatedRun,
                    snapshotChunks = prepared.snapshotChunks,
                )
            ) return@withLock null
            prepared.updatedSnapshot?.let { snapshotCache[runId] = it }
            prepared
        } ?: return null
        mergeExecutionCheckpointInstruction(runId, consumed.instruction.content)
        return ConsumedAgentFollowUp(consumed.message, consumed.instruction)
    }

    private suspend fun prepareQueuedUserMessage(
        runId: String,
        instruction: AgentSteeringInstruction,
    ): PreparedQueuedUserMessage? {
        val run = dao.getRun(runId) ?: return null
        val request = restoreChatRequest(run, apiKey = "") ?: return null
        val requestedReferences = instruction.contentParts
            .filterIsInstance<MessageContentPart.SkillReference>()
            .map { it.reference }
            .distinctBy(MessageSkillReference::skillId)
        val validReferences = skillReferenceValidator(requestedReferences)
            .distinctBy(MessageSkillReference::skillId)
        val validKeys = validReferences.mapTo(mutableSetOf()) { it.skillId to it.contentHash }
        val normalizedParts = instruction.contentParts.map { part ->
            if (part is MessageContentPart.SkillReference &&
                (part.reference.skillId to part.reference.contentHash) !in validKeys
            ) {
                MessageContentPart.Text("<skill_ref_unavailable>${part.reference.displayName}</skill_ref_unavailable>")
            } else {
                part
            }
        }
        val normalized = instruction.copy(contentParts = normalizedParts)
        val currentSnapshot = decodeRequestSnapshot(run) ?: return null
        val oldSkills = currentSnapshot.skillSnapshot
        val mergedSkills = if (validReferences.isEmpty()) {
            oldSkills
        } else {
            SkillRequestSnapshot(
                automaticCatalog = oldSkills?.automaticCatalog.orEmpty(),
                manualReferences = (oldSkills?.manualReferences.orEmpty() + validReferences)
                    .distinctBy { it.skillId to it.contentHash },
                createdAt = oldSkills?.createdAt ?: System.currentTimeMillis(),
            )
        }
        val updatedSnapshot = currentSnapshot.copy(skillSnapshot = mergedSkills)
            .takeIf { it != currentSnapshot }
        val updatedRun = updatedSnapshot?.let { run.copy(updatedAt = System.currentTimeMillis()) }
        val snapshotChunks = updatedSnapshot?.let { snapshot ->
            agentRequestSnapshotChunks(
                runId,
                json.encodeToString(AgentRequestSnapshot.serializer(), snapshot),
            )
        }.orEmpty()
        val message = queuedMessageMaterializer(
            normalized,
            request.copy(localSkillSnapshot = mergedSkills),
        )
        return PreparedQueuedUserMessage(
            instruction = normalized,
            message = message,
            payloadJson = json.encodeToString(AgentSteeringInstruction.serializer(), normalized),
            historyMessage = normalized.toHistoryMessage(request).toEntity(run.sessionId),
            updatedRun = updatedRun,
            updatedSnapshot = updatedSnapshot,
            snapshotChunks = snapshotChunks,
        )
    }

    private fun AgentSteeringInstruction.toHistoryMessage(request: ChatRequest): Message = Message(
        id = id,
        text = content,
        contentParts = contentParts,
        sender = Sender.User,
        contentStarted = true,
        timestamp = createdAt,
        imageUrls = attachments.mapNotNull { attachment ->
            when (attachment) {
                is SelectedMediaItem.ImageFromUri -> attachment.filePath ?: attachment.uri.toString()
                is SelectedMediaItem.ImageFromBitmap -> attachment.model
                is SelectedMediaItem.GenericFile,
                is SelectedMediaItem.Audio,
                -> null
            }
        }.takeIf { it.isNotEmpty() },
        attachments = attachments,
        modelName = request.model,
        providerName = request.provider,
    )

    /** 只有没有待处理 steering 时才能把 Run 原子结束，避免 steer 与完成竞态。 */
    suspend fun completeRunIfNoPendingSteering(
        run: AgentRunEntity,
        requestOrdinal: Int,
        terminalReason: String?,
    ): AgentRunEntity? {
        val updated = dao.completeRunIfNoQueuedUserMessage(
            runId = run.id,
            sessionId = run.sessionId,
            requestOrdinal = requestOrdinal,
            terminalReason = terminalReason,
            updatedAt = System.currentTimeMillis(),
        )
        if (updated == 1) {
            snapshotCache.remove(run.id)
            dao.deleteRunSnapshotChunks(run.id)
        }
        return run.copy(
            status = AgentRunStatus.COMPLETED.name,
            currentRequestOrdinal = requestOrdinal,
            terminalReason = terminalReason,
            updatedAt = System.currentTimeMillis(),
        ).takeIf { updated == 1 }
    }

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

    private suspend fun decodeFinalTranscriptEntries(runId: String): List<AbstractApiMessage> {
        val requestsById = dao.getRequests(runId).associateBy(AgentRequestEntity::id)
        val sourceProtocol = dao.getRun(runId)
            ?.let { run -> decodeRequestSnapshot(run) }
            ?.channel
            ?.let(::modelParameterProtocol)
        return dao.getEntries(runId).mapNotNull { entry ->
            if (entry.status != AgentEntryStatus.FINAL.name) return@mapNotNull null
            when (entry.kind) {
                AgentEntryKind.ASSISTANT.name -> decodeAssistantEntry(
                    entry,
                    entry.requestId?.let(requestsById::get),
                    sourceProtocol,
                )
                AgentEntryKind.TOOL_RESULT.name -> decodeToolResultEntry(entry)
                AgentEntryKind.STATUS.name -> decodeStatusEntry(entry)
                AgentEntryKind.STEERING.name,
                AgentEntryKind.FOLLOW_UP.name,
                -> decodeQueuedUserEntry(entry, runId)
                else -> null
            }
        }
    }

    private fun decodeAssistantEntry(
        entry: AgentEntryEntity,
        request: AgentRequestEntity?,
        sourceProtocol: ModelParameterProtocol?,
    ): AgentAssistantApiMessage? =
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
                    AgentToolCallApiPart(
                        call.id,
                        call.name,
                        call.arguments,
                        call.thoughtSignature,
                        call.namespace,
                    )
                },
                contentParts = blocks.mapNotNull { block ->
                    when (block) {
                        is AgentContentBlock.Text -> AgentAssistantContentApiPart.Text(block.text, block.thoughtSignature)
                        is AgentContentBlock.Reasoning ->
                            AgentAssistantContentApiPart.Reasoning(
                                block.text,
                                block.thoughtSignature,
                                block.redacted,
                            )
                        is AgentContentBlock.ToolCall -> AgentAssistantContentApiPart.ToolCall(
                            AgentToolCallApiPart(
                                block.id,
                                block.name,
                                block.arguments,
                                block.thoughtSignature,
                                block.namespace,
                            ),
                        )
                        is AgentContentBlock.ToolResult -> null
                    }
                },
                sourceProvider = request?.provider,
                sourceEndpoint = request?.endpoint,
                sourceModel = request?.model,
                sourceProtocol = AgentAssistantTurn(blocks).sourceProtocol ?: sourceProtocol,
                stopReason = request?.finishReason,
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
                contentBlocks = result.contentBlocks,
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

    private suspend fun decodeQueuedUserEntry(
        entry: AgentEntryEntity,
        runId: String,
    ): AbstractApiMessage? = runCatching {
        val instruction = json.decodeFromString(AgentSteeringInstruction.serializer(), entry.payloadJson)
        val run = dao.getRun(runId) ?: return@runCatching null
        val request = restoreChatRequest(run, apiKey = "") ?: return@runCatching null
        queuedMessageMaterializer(instruction, request)
    }.getOrNull()

    private fun decodeExecutionCheckpoint(entry: AgentEntryEntity): ExecutionCheckpoint? = runCatching {
        json.decodeFromString(ExecutionCheckpoint.serializer(), entry.payloadJson)
    }.getOrNull()

    private fun decodeApproval(payloadJson: String): AgentApprovalRecord? = runCatching {
        json.decodeFromString(AgentApprovalRecord.serializer(), payloadJson)
    }.getOrNull()
}

private const val MAX_CHECKPOINT_TEXT_CHARS = 4_000
private const val MAX_CHECKPOINT_CONSTRAINT_CHARS = 500
private const val MAX_CHECKPOINT_CONSTRAINTS = 16

/** 初始用户消息是当前 Run 唯一可靠的 Goal 来源，其他字段等待明确事件再更新。 */
private fun ChatRequest.initialExecutionCheckpoint(now: Long): ExecutionCheckpoint? {
    val instruction = messages.asReversed()
        .firstOrNull { message -> message.role.equals("user", ignoreCase = true) }
        ?.executionCheckpointText()
        ?.takeIf(String::isNotBlank)
        ?: return null
    return ExecutionCheckpoint(
        currentGoal = instruction.compactCheckpointText(),
        hardConstraints = instruction.explicitConstraintLines(),
        updatedAt = now,
    )
}

private fun AbstractApiMessage.executionCheckpointText(): String = when (this) {
    is SimpleTextApiMessage -> content
    is PartsApiMessage -> parts.filterIsInstance<ApiContentPart.Text>().joinToString("\n") { it.text }
    is AgentAssistantApiMessage -> text
    is AgentToolResultApiMessage -> content.toString()
}

/** 保留首尾，防止超长用户输入反过来挤满待保护的 Context。 */
private fun String.compactCheckpointText(): String {
    val normalized = trim()
    if (normalized.length <= MAX_CHECKPOINT_TEXT_CHARS) return normalized
    val side = MAX_CHECKPOINT_TEXT_CHARS / 2
    return normalized.take(side) + "\n…执行检查点已省略中间内容…\n" + normalized.takeLast(side)
}

/** 只提取以明确约束词开头的行。无法机械确认的内容保持在原始用户消息中。 */
private fun String.explicitConstraintLines(): List<String> = lineSequence()
    .map(String::trim)
    .map { line ->
        var value = line
        listOf("-", "*", "•").forEach { prefix ->
            if (value.startsWith(prefix)) value = value.removePrefix(prefix).trimStart()
        }
        value
    }
    .filter { line ->
        val lower = line.lowercase()
        CHECKPOINT_CONSTRAINT_PREFIXES.any(lower::startsWith)
    }
    .map { it.take(MAX_CHECKPOINT_CONSTRAINT_CHARS) }
    .distinct()
    .take(MAX_CHECKPOINT_CONSTRAINTS)
    .toList()

private val CHECKPOINT_CONSTRAINT_PREFIXES = listOf(
    "必须",
    "禁止",
    "不能",
    "不要",
    "只允许",
    "务必",
    "must ",
    "do not ",
    "don't ",
    "never ",
    "only ",
)

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

private const val AGENT_REQUEST_SNAPSHOT_CHUNK_CHARS = 65_536
private const val AGENT_REQUEST_SNAPSHOT_READ_PAGE_SIZE = 8
private val AGENT_TERMINAL_RUN_STATUSES = setOf(
    AgentRunStatus.COMPLETED,
    AgentRunStatus.FAILED,
    AgentRunStatus.CANCELLED,
    AgentRunStatus.INTERRUPTED,
)
private val AGENT_FINAL_RUN_STATUSES = setOf(
    AgentRunStatus.COMPLETED,
    AgentRunStatus.FAILED,
    AgentRunStatus.CANCELLED,
)

/**
 * 把恢复快照切成 CursorWindow 可安全读取的小行。
 * 边界遇到 Emoji 等代理对时把低代理一并放入当前块，重新拼接后文本保持原样。
 */
internal fun agentRequestSnapshotChunks(
    runId: String,
    encodedSnapshot: String,
    maxChars: Int = AGENT_REQUEST_SNAPSHOT_CHUNK_CHARS,
): List<AgentRunSnapshotChunkEntity> {
    require(maxChars > 0) { "快照分块大小必须大于 0" }
    if (encodedSnapshot.isEmpty()) {
        return listOf(AgentRunSnapshotChunkEntity(runId, 0, ""))
    }
    return buildList {
        var start = 0
        var chunkIndex = 0
        while (start < encodedSnapshot.length) {
            var end = (start + maxChars).coerceAtMost(encodedSnapshot.length)
            if (
                end < encodedSnapshot.length &&
                encodedSnapshot[end - 1].isHighSurrogate() &&
                encodedSnapshot[end].isLowSurrogate()
            ) {
                end += 1
            }
            add(
                AgentRunSnapshotChunkEntity(
                    runId = runId,
                    chunkIndex = chunkIndex,
                    payload = encodedSnapshot.substring(start, end),
                ),
            )
            start = end
            chunkIndex += 1
        }
    }
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

private fun stableToolResultEntryId(runId: String, requestId: String, toolCallId: String): String =
    "tool-result:" + agentTranscriptFingerprint(listOf(runId, requestId, toolCallId))

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

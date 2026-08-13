package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import com.android.everytalk.data.database.entities.AgentEntryEntity
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRequestUsageEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.network.TokenUsage
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Agent 运行事实的唯一写入口。每次真实模型请求独立保存，禁止再按可见 AI 消息累计 Usage。
 */
class AgentRunStore(
    private val dao: AgentDao,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) {
    suspend fun createRun(
        sessionId: String,
        userMessageId: String,
        visibleAssistantMessageId: String,
        configIdSnapshot: String?,
    ): AgentRunEntity {
        val now = System.currentTimeMillis()
        return AgentRunEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            userMessageId = userMessageId,
            visibleAssistantMessageId = visibleAssistantMessageId,
            configIdSnapshot = configIdSnapshot,
            status = AgentRunStatus.CREATED.name,
            currentRequestOrdinal = 0,
            terminalReason = null,
            createdAt = now,
            updatedAt = now,
        ).also { dao.upsertRun(it) }
    }

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
                val expanded = dao.getEntries(run.id).mapNotNull { entry ->
                    // 被取消或崩溃留下的半截 Assistant 没有可靠工具配对，长期上下文只读取最终事实。
                    if (entry.status != AgentEntryStatus.FINAL.name) return@mapNotNull null
                    when (entry.kind) {
                        AgentEntryKind.ASSISTANT.name -> decodeAssistantEntry(entry.id, entry.payloadJson)
                        AgentEntryKind.TOOL_RESULT.name -> decodeToolResultEntry(entry.id, entry.payloadJson)
                        else -> null
                    }
                }
                if (expanded.isEmpty()) add(message) else addAll(expanded)
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
    ): AgentEntryEntity {
        val now = System.currentTimeMillis()
        return AgentEntryEntity(
            id = UUID.randomUUID().toString(),
            runId = runId,
            sequence = dao.nextEntrySequence(runId),
            kind = kind.name,
            requestId = requestId,
            toolCallId = toolCallId,
            payloadJson = payloadJson,
            status = status.name,
            createdAt = now,
            finalizedAt = now.takeIf { status == AgentEntryStatus.FINAL },
        ).also { dao.upsertEntry(it) }
    }

    private fun decodeAssistantEntry(entryId: String, payloadJson: String): AgentAssistantApiMessage? =
        runCatching {
            val blocks = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AgentContentBlock.serializer()),
                payloadJson,
            )
            AgentAssistantApiMessage(
                id = "agent:$entryId",
                text = blocks.filterIsInstance<AgentContentBlock.Text>().joinToString("") { it.text },
                reasoning = blocks.filterIsInstance<AgentContentBlock.Reasoning>().joinToString("") { it.text },
                toolCalls = blocks.filterIsInstance<AgentContentBlock.ToolCall>().map { call ->
                    AgentToolCallApiPart(call.id, call.name, call.arguments)
                },
            )
        }.getOrNull()

    private fun decodeToolResultEntry(entryId: String, payloadJson: String): AgentToolResultApiMessage? =
        runCatching {
            val result = json.decodeFromString(AgentContentBlock.ToolResult.serializer(), payloadJson)
            AgentToolResultApiMessage(
                id = "agent:$entryId",
                toolCallId = result.toolCallId,
                toolName = result.toolName,
                content = result.content,
                isError = result.isError,
            )
        }.getOrNull()
}

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

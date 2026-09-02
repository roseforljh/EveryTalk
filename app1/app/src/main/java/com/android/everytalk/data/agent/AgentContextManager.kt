package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.network.AnthropicDirectClient
import com.android.everytalk.data.network.isOfficialAnthropicMessagesAddress
import com.android.everytalk.data.network.isOfficialOpenAIResponsesAddress
import com.android.everytalk.data.network.repairAgentToolHistory
import com.android.everytalk.statecontroller.RequestTokenEstimator
import com.android.everytalk.statecontroller.calibratedInputTokens
import com.android.everytalk.statecontroller.trimMessagesToContextWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val COMPACTION_META_SUMMARY_ROLE = "summary_role"
private const val COMPACTION_META_RETAINED_IDS = "retained_ids"
private const val EXECUTION_CHECKPOINT_MESSAGE_ID = "agent-execution-checkpoint"
private const val NORMAL_RECENT_RATIO_PERCENT = 20L
private const val TOOL_HEAVY_RECENT_RATIO_PERCENT = 30L
private const val MIN_RECENT_CONTEXT_PERCENT = 10L
private const val MAX_RECENT_CONTEXT_PERCENT = 35L
private const val HYSTERESIS_CONTEXT_PERCENT = 10L
private const val MIN_COMPACTION_SUMMARY_TOKENS = 32L

data class PreparedAgentContext(
    val messages: List<AbstractApiMessage>,
    val snapshot: AgentContextSnapshotEntity,
    val transcriptFingerprint: String,
    val compactionPlan: AgentCompactionPlan? = null,
)

/** 生成摘要成功前只是一份计划，旧检查点继续有效。 */
data class AgentCompactionPlan(
    val messagesToSummarize: List<AbstractApiMessage>,
    val previousSummary: String?,
    val summarizedThroughItemId: String,
    val prefixFingerprint: String,
    val retainedTailIds: List<String>,
    val summaryRole: String,
    val tokensBefore: Long,
    val triggerThreshold: Long = 0L,
    val recentTargetTokens: Long = 0L,
    val targetAfterCompaction: Long = 0L,
    val summaryOutputTokenLimit: Int = Int.MAX_VALUE,
) {
    val isSplitTurn: Boolean
        get() = summaryRole == "user"
}

/** 一次 prepare 使用的唯一预算口径，所有值均为当前请求的输入 token。 */
internal data class AgentContextBudget(
    val contextWindow: Long,
    val reserveTokens: Long,
    val triggerThreshold: Long,
    val recentTarget: Long,
    val targetAfterCompaction: Long,
    val fixedOverheadTokens: Long,
)

class AgentContextWindowException(message: String) : IllegalStateException(message)

/**
 * 每次模型请求前重新计算有效上下文。
 * 压缩沿用 Pi 的核心语义：旧摘要加最新一轮原文，最新用户消息永不进入摘要。
 */
class AgentContextManager(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun prepare(
        requestId: String,
        request: ChatRequest,
        limits: ModelTokenLimits,
        checkpoint: AgentCompactionEntryEntity? = null,
        executionCheckpoint: ExecutionCheckpoint? = null,
        forceLocalCompaction: Boolean = false,
    ): PreparedAgentContext {
        val canonical = removeOrphanToolResults(request.messages)
        val acceptedCheckpoint = checkpoint?.takeIf { it.matches(canonical) }
        val compacted = acceptedCheckpoint?.let { applyCheckpoint(canonical, it) } ?: canonical
        val effective = injectExecutionCheckpoint(compacted, executionCheckpoint)
        val calibration = request.contextManagement?.inputTokenCalibration ?: 0L
        val estimateBeforeTrim = RequestTokenEstimator.estimate(effective, request.tools)
        val activeBeforeTrim = calibratedInputTokens(estimateBeforeTrim, calibration)
        val reservedOutput = limits.maxOutputTokens.toLong()
        val contextWindow = limits.maxContextTokens.toLong()
        val inputBudget = contextWindow - reservedOutput
        val budget = contextBudget(
            request = request,
            limits = limits,
            effectiveMessages = effective,
            estimate = estimateBeforeTrim,
            acceptedCheckpoint = acceptedCheckpoint,
        )
        val prefersNativeCompaction = request.prefersNativeCompaction()
        val providerContinuation = request.localProviderContinuation
        val nativeThroughMessageId = providerContinuation?.compactedThroughMessageId
        val hasActiveNativeCompaction = prefersNativeCompaction &&
            !providerContinuation?.compactedContextJson.isNullOrBlank() &&
            !nativeThroughMessageId.isNullOrBlank() &&
            effective.any { it.id == nativeThroughMessageId }
        // 官方原生压缩先使用用户配置的软阈值。本地通用压缩保留到硬窗口前兜底。
        val genericCompactionThreshold = if (prefersNativeCompaction && !forceLocalCompaction) {
            contextWindow
        } else {
            budget.triggerThreshold
        }
        val compactedThroughIndex = acceptedCheckpoint
            ?.let { saved -> canonical.indexOfFirst { it.id == saved.summarizedThroughItemId } }
            ?: -1
        val hasNewCompactionSource = acceptedCheckpoint == null || canonical
            .drop(compactedThroughIndex + 1)
            .any { !it.role.equals("system", true) }
        val needsCompaction = request.contextManagement?.autoCompressionEnabled == true &&
            (forceLocalCompaction || !hasActiveNativeCompaction) &&
            hasNewCompactionSource &&
            // Pi 只比较当前上下文与压缩线；最大输出不参与触发，否则大输出模型会过早反复压缩。
            (forceLocalCompaction || activeBeforeTrim > genericCompactionThreshold)
        val plan = if (needsCompaction) {
            planCompaction(
                canonical = canonical,
                checkpoint = acceptedCheckpoint,
                tokensBefore = activeBeforeTrim,
                budget = budget,
            )
        } else {
            null
        }

        // 有可执行压缩计划时先返回完整有效上下文，AgentLoop 会生成并落库摘要后重新 prepare。
        val fitted = when {
            plan != null || hasActiveNativeCompaction -> effective
            else -> {
                removeOrphanToolResults(
                    trimMessagesToContextWindow(
                        messages = effective,
                        limits = limits,
                        tools = request.tools,
                        inputTokenCalibration = calibration,
                    )
                )
            }
        }
        val estimate = RequestTokenEstimator.estimate(fitted, request.tools)
        val active = calibratedInputTokens(estimate, calibration)
        if (plan == null && !hasActiveNativeCompaction && active > inputBudget) {
            throw AgentContextWindowException("当前请求的单轮内容超过模型上下文窗口")
        }
        val fingerprint = agentTranscriptFingerprint(fitted.map(::fingerprintPart))
        return PreparedAgentContext(
            messages = fitted,
            transcriptFingerprint = fingerprint,
            compactionPlan = plan,
            snapshot = AgentContextSnapshotEntity(
                requestId = requestId,
                systemPromptTokens = estimate.systemPromptTokens,
                conversationTextTokens = estimate.conversationTextTokens,
                mediaTokens = estimate.mediaTokens,
                toolSchemaTokens = estimate.toolSchemaTokens,
                protocolOverheadTokens = estimate.protocolOverheadTokens,
                estimatedPromptTokens = estimate.totalInputTokens,
                reservedOutputTokens = reservedOutput,
                contextWindowTokens = contextWindow,
                activeContextTokens = active,
                calibrationTokens = calibration,
                compactionId = acceptedCheckpoint?.id,
                transcriptFingerprint = fingerprint,
                source = "ESTIMATED",
            ),
        )
    }

    /** 缺失结果统一补成明确失败，避免裁剪或恢复时把已经发生的调用事实静默删除。 */
    internal fun removeOrphanToolResults(messages: List<AbstractApiMessage>): List<AbstractApiMessage> =
        messages.repairAgentToolHistory()

    /** 摘要输入使用数据标签，工具结果只取 2,000 字符，避免摘要请求再次被输出淹没。 */
    fun serializeForCompaction(plan: AgentCompactionPlan): String = buildString {
        plan.previousSummary?.takeIf(String::isNotBlank)?.let { previous ->
            append("<previous-summary>\n").append(previous.escapeBoundary())
                .append("\n</previous-summary>\n")
        }
        append("<conversation>\n")
        plan.messagesToSummarize.forEach { message ->
            append('[').append(message.role).append("]: ")
            append(message.compactionText()).append("\n\n")
        }
        append("</conversation>")
    }

    fun compactionMetadata(plan: AgentCompactionPlan): String = JsonObject(
        mapOf(
            COMPACTION_META_SUMMARY_ROLE to JsonPrimitive(plan.summaryRole),
            COMPACTION_META_RETAINED_IDS to JsonArray(plan.retainedTailIds.map(::JsonPrimitive)),
        )
    ).toString()

    /**
     * 摘要最多使用待压缩内容约三分之一的 token。
     * 固定给 4096 会让较短历史生成更长摘要，随后触发“压缩没有减少占用”。
     */
    fun compactionOutputTokenLimit(plan: AgentCompactionPlan, hardLimit: Int): Int {
        val sourceMessages = buildList {
            plan.previousSummary?.takeIf(String::isNotBlank)?.let { summary ->
                add(SimpleTextApiMessage(role = "system", content = summary))
            }
            addAll(plan.messagesToSummarize)
        }
        val sourceTokens = RequestTokenEstimator.estimate(sourceMessages, tools = null).totalInputTokens
        return (sourceTokens / 3L)
            .coerceAtLeast(MIN_COMPACTION_SUMMARY_TOKENS)
            .coerceAtMost(
                minOf(
                    hardLimit.coerceAtLeast(1).toLong(),
                    plan.summaryOutputTokenLimit.coerceAtLeast(1).toLong(),
                )
            )
            .toInt()
    }

    /**
     * 估算新摘要真正替换旧历史后的业务上下文占用。
     * 该值用于压缩记录，不能误用生成摘要那次请求自身的 token 数。
     */
    fun estimateCompactedContextTokens(
        request: ChatRequest,
        plan: AgentCompactionPlan,
        summary: String,
    ): Long {
        val canonical = removeOrphanToolResults(request.messages)
        val throughIndex = canonical.indexOfFirst { it.id == plan.summarizedThroughItemId }
        if (throughIndex < 0) return plan.tokensBefore
        val compactedMessages = buildList {
            addAll(canonical.filter { it.role.equals("system", true) })
            add(
                SimpleTextApiMessage(
                    id = "agent-compaction:estimate",
                    role = plan.summaryRole,
                    content = if (plan.isSplitTurn) {
                        "以下是当前会话较早部分的压缩摘要，请继续完成同一任务：\n\n$summary"
                    } else {
                        "以下是较早会话的压缩摘要，请据此延续对话：\n\n$summary"
                    },
                )
            )
            addAll(canonical.drop(throughIndex + 1).filterNot { it.role.equals("system", true) })
        }
        val estimate = RequestTokenEstimator.estimate(compactedMessages, request.tools)
        val calibration = request.contextManagement?.inputTokenCalibration ?: 0L
        return calibratedInputTokens(estimate, calibration)
    }

    /**
     * 统一预算公式：
     * contextWindow 是完整模型窗口；reserveTokens 只服务硬窗口输出预留；
     * triggerThreshold 只比较当前输入；targetAfterCompaction 比触发线低 10% 窗口；
     * recentTarget 在工具密集场景取可用输入的 30%，普通场景取 20%。
     */
    internal fun contextBudget(
        request: ChatRequest,
        limits: ModelTokenLimits,
        effectiveMessages: List<AbstractApiMessage>,
        estimate: com.android.everytalk.statecontroller.RequestTokenEstimate,
        acceptedCheckpoint: AgentCompactionEntryEntity?,
    ): AgentContextBudget {
        val contextWindow = limits.maxContextTokens.toLong().coerceAtLeast(1L)
        val reserveTokens = limits.maxOutputTokens.toLong().coerceAtLeast(0L)
        val configuredTrigger = request.contextManagement
            ?.compactThresholdTokens
            ?.coerceIn(1L, contextWindow)
            ?: (contextWindow - reserveTokens).coerceAtLeast(1L)
        val hysteresisGap = (contextWindow * HYSTERESIS_CONTEXT_PERCENT / 100L).coerceAtLeast(1L)
        val checkpointRetrigger = acceptedCheckpoint
            ?.estimatedTokensAfter
            ?.takeIf { it > 0L }
            ?.plus(hysteresisGap)
            ?.coerceAtMost(contextWindow)
        val triggerThreshold = maxOf(configuredTrigger, checkpointRetrigger ?: 1L)
            .coerceAtMost(contextWindow)
        val targetAfterCompaction = (triggerThreshold - hysteresisGap).coerceAtLeast(1L)
        val fixedOverhead = estimate.systemPromptTokens + estimate.toolSchemaTokens +
            estimate.protocolOverheadTokens
        val availableBeforeTrigger = (triggerThreshold - fixedOverhead).coerceAtLeast(1L)
        val toolHeavy = estimate.toolSchemaTokens > 0L || effectiveMessages.any { message ->
            message is AgentAssistantApiMessage && message.toolCalls.isNotEmpty() ||
                message is AgentToolResultApiMessage
        }
        val recentRatio = if (toolHeavy) TOOL_HEAVY_RECENT_RATIO_PERCENT else NORMAL_RECENT_RATIO_PERCENT
        val desiredRecent = (availableBeforeTrigger * recentRatio / 100L).coerceAtLeast(1L)
        val minimumRecent = (contextWindow * MIN_RECENT_CONTEXT_PERCENT / 100L).coerceAtLeast(1L)
        val maximumRecent = (contextWindow * MAX_RECENT_CONTEXT_PERCENT / 100L).coerceAtLeast(1L)
        val maximumAllowedByTarget = (
            targetAfterCompaction - fixedOverhead - MIN_COMPACTION_SUMMARY_TOKENS
            ).coerceAtLeast(1L)
        val upperRecent = minOf(maximumRecent, maximumAllowedByTarget).coerceAtLeast(1L)
        val lowerRecent = minOf(minimumRecent, upperRecent)
        val recentTarget = desiredRecent.coerceIn(lowerRecent, upperRecent)
        return AgentContextBudget(
            contextWindow = contextWindow,
            reserveTokens = reserveTokens,
            triggerThreshold = triggerThreshold,
            recentTarget = recentTarget,
            targetAfterCompaction = targetAfterCompaction,
            fixedOverheadTokens = fixedOverhead,
        )
    }

    /** ExecutionCheckpoint 是内部投影，位置固定在永久 system 后、历史摘要前。 */
    private fun injectExecutionCheckpoint(
        messages: List<AbstractApiMessage>,
        checkpoint: ExecutionCheckpoint?,
    ): List<AbstractApiMessage> {
        val active = checkpoint?.takeIf { value ->
            !value.currentGoal.isNullOrBlank() || value.hardConstraints.isNotEmpty() ||
                !value.currentStep.isNullOrBlank() || !value.resumeInstruction.isNullOrBlank()
        } ?: return messages
        val sanitized = messages.filterNot { it.id == EXECUTION_CHECKPOINT_MESSAGE_ID }
        val permanentSystem = sanitized.filter { message ->
            message.role.equals("system", ignoreCase = true) &&
                !message.id.startsWith("agent-compaction:")
        }
        val projectedHistory = sanitized.filterNot { it in permanentSystem }
        return buildList {
            addAll(permanentSystem)
            add(
                SimpleTextApiMessage(
                    id = EXECUTION_CHECKPOINT_MESSAGE_ID,
                    role = "system",
                    content = active.toContextProjection(),
                )
            )
            addAll(projectedHistory)
        }
    }

    private fun planCompaction(
        canonical: List<AbstractApiMessage>,
        checkpoint: AgentCompactionEntryEntity?,
        tokensBefore: Long,
        budget: AgentContextBudget,
    ): AgentCompactionPlan? {
        val systemIndexes = canonical.indices.filter { canonical[it].role.equals("system", true) }.toSet()
        val sourceStart = checkpoint
            ?.summarizedThroughItemId
            ?.let { id -> canonical.indexOfFirst { it.id == id } + 1 }
            ?.coerceAtLeast(0)
            ?: 0
        val candidateIndexes = canonical.indices.filter { it >= sourceStart && it !in systemIndexes }
        if (candidateIndexes.isEmpty()) return null
        val groups = atomicGroups(canonical, candidateIndexes)
        if (groups.size <= 1) return null

        // 从最新消息向前累计近期预算，并始终给摘要至少留下一个完整原子组。
        var retainedGroupStart = groups.lastIndex
        var retainedTokens = 0L
        for (groupIndex in groups.lastIndex downTo 1) {
            val groupTokens = groups[groupIndex].sumOf { index ->
                RequestTokenEstimator.estimateMessageTokens(canonical[index])
            }
            if (retainedTokens > 0L && retainedTokens + groupTokens > budget.recentTarget) break
            retainedGroupStart = groupIndex
            retainedTokens += groupTokens
            if (retainedTokens >= budget.recentTarget) break
        }
        val summarizedGroups = groups.take(retainedGroupStart)
        if (summarizedGroups.isEmpty()) return null
        val retainedGroups = groups.drop(retainedGroupStart)
        val firstRetainedMessage = canonical[retainedGroups.first().first()]
        val splitsTurn = !firstRetainedMessage.role.equals("user", true)
        val summaryOutputLimit = (budget.targetAfterCompaction - budget.fixedOverheadTokens - retainedTokens)
            .coerceAtLeast(MIN_COMPACTION_SUMMARY_TOKENS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        return createPlan(
            canonical = canonical,
            summarizedIndexes = summarizedGroups.flatten(),
            retainedIndexes = retainedGroups.flatten(),
            checkpoint = checkpoint,
            summaryRole = if (splitsTurn) "user" else "system",
            tokensBefore = tokensBefore,
            budget = budget,
            summaryOutputTokenLimit = summaryOutputLimit,
        )
    }

    private fun createPlan(
        canonical: List<AbstractApiMessage>,
        summarizedIndexes: List<Int>,
        retainedIndexes: List<Int>,
        checkpoint: AgentCompactionEntryEntity?,
        summaryRole: String,
        tokensBefore: Long,
        budget: AgentContextBudget,
        summaryOutputTokenLimit: Int,
    ): AgentCompactionPlan? {
        val normalizedIndexes = summarizedIndexes.distinct().sorted()
        val throughIndex = normalizedIndexes.lastOrNull() ?: return null
        val summarizedMessages = normalizedIndexes.map(canonical::get)
        return AgentCompactionPlan(
            messagesToSummarize = summarizedMessages,
            previousSummary = checkpoint?.summary,
            summarizedThroughItemId = canonical[throughIndex].id,
            prefixFingerprint = prefixFingerprint(canonical, throughIndex),
            retainedTailIds = retainedIndexes.map { canonical[it].id },
            summaryRole = summaryRole,
            tokensBefore = tokensBefore,
            triggerThreshold = budget.triggerThreshold,
            recentTargetTokens = budget.recentTarget,
            targetAfterCompaction = budget.targetAfterCompaction,
            summaryOutputTokenLimit = summaryOutputTokenLimit,
        )
    }

    private fun atomicGroups(
        messages: List<AbstractApiMessage>,
        indexes: List<Int>,
    ): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var cursor = 0
        while (cursor < indexes.size) {
            val index = indexes[cursor]
            val message = messages[index]
            if (message is AgentAssistantApiMessage && message.toolCalls.isNotEmpty()) {
                val expected = message.toolCalls.mapTo(mutableSetOf()) { it.id }
                val group = mutableListOf(index)
                var next = cursor + 1
                while (next < indexes.size && expected.isNotEmpty()) {
                    val nextMessage = messages[indexes[next]]
                    if (nextMessage !is AgentToolResultApiMessage || nextMessage.toolCallId !in expected) break
                    group += indexes[next]
                    expected -= nextMessage.toolCallId
                    next++
                }
                result += group
                cursor = next
            } else {
                result += listOf(index)
                cursor++
            }
        }
        return result
    }

    private fun AgentCompactionEntryEntity.matches(messages: List<AbstractApiMessage>): Boolean {
        if (status != AgentCompactionStatus.COMPLETED.name) return false
        val throughIndex = messages.indexOfFirst { it.id == summarizedThroughItemId }
        return throughIndex >= 0 && prefixFingerprint(messages, throughIndex) == prefixFingerprint
    }

    private fun applyCheckpoint(
        messages: List<AbstractApiMessage>,
        checkpoint: AgentCompactionEntryEntity,
    ): List<AbstractApiMessage> {
        val throughIndex = messages.indexOfFirst { it.id == checkpoint.summarizedThroughItemId }
        if (throughIndex < 0) return messages
        val summaryRole = runCatching {
            json.parseToJsonElement(checkpoint.retainedTailJson).let { it as? JsonObject }
                ?.get(COMPACTION_META_SUMMARY_ROLE)?.jsonPrimitive?.contentOrNull
        }.getOrNull().takeIf { it == "user" } ?: "system"
        return buildList {
            addAll(messages.filter { it.role.equals("system", true) })
            add(
                SimpleTextApiMessage(
                    id = "agent-compaction:${checkpoint.id}",
                    role = summaryRole,
                    content = if (summaryRole == "user") {
                        "以下是当前会话较早部分的压缩摘要，请继续完成同一任务：\n\n${checkpoint.summary}"
                    } else {
                        "以下是较早会话的压缩摘要，请据此延续对话：\n\n${checkpoint.summary}"
                    },
                )
            )
            addAll(messages.drop(throughIndex + 1).filterNot { it.role.equals("system", true) })
        }
    }

    private fun prefixFingerprint(messages: List<AbstractApiMessage>, throughIndex: Int): String =
        agentTranscriptFingerprint(messages.take(throughIndex + 1).map(::fingerprintPart))

    /** 官方接口先尝试 Provider 原生压缩，通用摘要保留为硬窗口兜底。 */
    private fun ChatRequest.prefersNativeCompaction(): Boolean {
        if (contextManagement?.autoCompressionEnabled != true) return false
        return when {
            channel.contains("codex", ignoreCase = true) ->
                isOfficialOpenAIResponsesAddress(apiAddress)
            channel.contains("anthropic", ignoreCase = true) ->
                isOfficialAnthropicMessagesAddress(apiAddress) &&
                    AnthropicDirectClient.isNativeCompactionAvailable(apiAddress, model)
            else -> false
        }
    }

    private fun fingerprintPart(message: AbstractApiMessage): String = when (message) {
        is SimpleTextApiMessage -> buildString {
            if (!message.role.equals("system", true)) append(message.id)
            append('|').append(message.role).append('|').append(message.name).append('|').append(message.content)
        }
        is PartsApiMessage -> buildString {
            if (!message.role.equals("system", true)) append(message.id)
            append('|').append(message.role).append('|').append(message.name)
            message.parts.forEach { append('|').append(it) }
        }
        is AgentAssistantApiMessage -> buildString {
            append(message.id).append('|').append(message.role).append('|')
            append(message.sourceProvider).append('|').append(message.sourceEndpoint).append('|')
                .append(message.sourceModel).append('|').append(message.sourceProtocol).append('|')
                .append(message.stopReason).append('|')
            append(message.reasoning).append('|').append(message.text)
            message.toolCalls.forEach { call ->
                append('|').append(call.id).append('|').append(call.name).append('|').append(call.arguments)
                    .append('|').append(call.thoughtSignature).append('|').append(call.namespace)
            }
            message.contentParts.forEach { append('|').append(it) }
        }
        is AgentToolResultApiMessage ->
            "${message.id}|${message.role}|${message.toolCallId}|${message.toolName}|${message.content}|" +
                "${message.contentBlocks}|${message.isError}"
    }
}

private fun AbstractApiMessage.compactionText(): String = when (this) {
    is SimpleTextApiMessage -> content.escapeBoundary()
    is PartsApiMessage -> parts.joinToString("\n") { part ->
        when (part) {
            is ApiContentPart.Text -> part.text.escapeBoundary()
            is ApiContentPart.FileUri -> "[文件：${part.mimeType} ${part.uri}]"
            is ApiContentPart.InlineData -> "[内联附件：${part.mimeType}，${part.base64Data.length} 字符]"
        }
    }
    is AgentAssistantApiMessage -> buildString {
        if (reasoning.isNotBlank()) append("[思考] ").append(reasoning.escapeBoundary()).append('\n')
        if (text.isNotBlank()) append(text.escapeBoundary()).append('\n')
        toolCalls.forEach { call ->
            append("[工具调用] ").append(call.name).append(' ')
                .append(call.arguments.toString().escapeBoundary()).append('\n')
        }
    }.trimEnd()
    is AgentToolResultApiMessage -> toolResultCompactionText(this)
}

/** 按 ET 现有 Computer 工具信封提取关键字段，未知工具保留首尾避免丢失尾部错误。 */
internal fun compactAgentToolResultForCompaction(message: AgentToolResultApiMessage): String {
    val raw = message.content.toString()
    val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val data = parsed?.get("data") as? JsonObject
    val error = parsed?.get("error") as? JsonObject
    fun value(key: String): String? = (parsed?.get(key) ?: data?.get(key) ?: error?.get(key))
        ?.toString()?.trim('"')?.takeIf(String::isNotBlank)
    val head = when {
        message.toolName in setOf("read_file", "read") -> buildString {
            append("tool: read\n")
            value("path")?.let { append("file: ").append(it).append('\n') }
            value("offset")?.let { append("range offset: ").append(it).append('\n') }
            value("next_offset")?.let { append("next_offset: ").append(it).append('\n') }
            append("result:\n")
            value("content")?.let(::append)
        }
        message.toolName.contains("grep", true) || message.toolName.contains("search", true) -> buildString {
            append("tool: search\n")
            value("query")?.let { append("query: ").append(it).append('\n') }
            value("match_count")?.let { append("match_count: ").append(it).append('\n') }
            append("result:\n")
            append(compactHeadTail(raw, 1_800))
        }
        message.toolName in setOf("exec", "bash", "terminal") -> buildString {
            append("tool: bash\n")
            value("command")?.let { append("command: ").append(it).append('\n') }
            value("cwd")?.let { append("cwd: ").append(it).append('\n') }
            value("exit_code")?.let { append("exitCode: ").append(it).append('\n') }
            value("stderr")?.let { append("stderr: ").append(compactHeadTail(it, 1_000)).append('\n') }
            value("stdout")?.let { append("stdout: ").append(compactHeadTail(it, 1_000)).append('\n') }
            error?.get("message")?.let { append("error: ").append(it).append('\n') }
        }
        message.toolName in setOf("edit", "write_file", "write") -> buildString {
            append("tool: ").append(message.toolName).append('\n')
            value("path")?.let { append("file: ").append(it).append('\n') }
            value("replacements")?.let { append("replacements: ").append(it).append('\n') }
            value("bytes_written")?.let { append("bytesWritten: ").append(it).append('\n') }
            append("status: ").append(if (message.isError) "失败" else "成功")
        }
        else -> "tool: ${message.toolName}\nresult:\n${compactHeadTail(raw, 2_000)}"
    }
    return "[工具结果 id=${message.toolCallId} name=${message.toolName} status=${if (message.isError) "失败" else "成功"}] " +
        head.escapeBoundary()
}

private fun toolResultCompactionText(message: AgentToolResultApiMessage): String =
    compactAgentToolResultForCompaction(message)

private fun compactHeadTail(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    val side = maxChars / 2
    return value.take(side) + "\n…省略 ${value.length - maxChars} 字符…\n" + value.takeLast(side)
}

private fun String.escapeBoundary(): String =
    replace("</conversation>", "&lt;/conversation&gt;", ignoreCase = true)

private fun ExecutionCheckpoint.toContextProjection(): String = buildString {
    append("[EveryTalk Execution Checkpoint]\n")
    currentGoal?.takeIf(String::isNotBlank)?.let { append("目标：").append(it).append('\n') }
    if (hardConstraints.isNotEmpty()) {
        append("硬约束：\n")
        hardConstraints.forEach { append("- ").append(it).append('\n') }
    }
    currentStep?.takeIf(String::isNotBlank)?.let { append("当前步骤：").append(it).append('\n') }
    resumeInstruction?.takeIf(String::isNotBlank)?.let { append("恢复指令：").append(it).append('\n') }
    append("以上是当前执行检查点，优先级高于历史摘要；不要用历史摘要覆盖这些事实。")
}

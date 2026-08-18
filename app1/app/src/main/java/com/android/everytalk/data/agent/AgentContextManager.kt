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
) {
    val isSplitTurn: Boolean
        get() = summaryRole == "user"
}

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
    ): PreparedAgentContext {
        val canonical = removeOrphanToolResults(request.messages)
        val acceptedCheckpoint = checkpoint?.takeIf { it.matches(canonical) }
        val effective = acceptedCheckpoint?.let { applyCheckpoint(canonical, it) } ?: canonical
        val calibration = request.contextManagement?.inputTokenCalibration ?: 0L
        val estimateBeforeTrim = RequestTokenEstimator.estimate(effective, request.tools)
        val activeBeforeTrim = calibratedInputTokens(estimateBeforeTrim, calibration)
        val reservedOutput = limits.maxOutputTokens.toLong()
        val contextWindow = limits.maxContextTokens.toLong()
        val inputBudget = contextWindow - reservedOutput
        val threshold = request.contextManagement
            ?.compactThresholdTokens
            ?.coerceIn(1L, contextWindow.coerceAtLeast(1L))
            ?: inputBudget
        val compactedThroughIndex = acceptedCheckpoint
            ?.let { saved -> canonical.indexOfFirst { it.id == saved.summarizedThroughItemId } }
            ?: -1
        val hasNewCompactionSource = acceptedCheckpoint == null || canonical
            .drop(compactedThroughIndex + 1)
            .any { !it.role.equals("system", true) }
        val needsCompaction = request.contextManagement?.autoCompressionEnabled == true &&
            hasNewCompactionSource &&
            activeBeforeTrim + reservedOutput >= threshold
        val plan = if (needsCompaction) {
            planCompaction(
                canonical = canonical,
                checkpoint = acceptedCheckpoint,
                tokensBefore = activeBeforeTrim + reservedOutput,
            )
        } else {
            null
        }
        if (needsCompaction && plan == null) {
            throw AgentContextWindowException("上下文已达到压缩阈值，但没有可压缩的早期历史")
        }

        // 有可执行压缩计划时先返回完整有效上下文，AgentLoop 会生成并落库摘要后重新 prepare。
        val fitted = if (plan != null) {
            effective
        } else {
            removeOrphanToolResults(
                trimMessagesToContextWindow(
                    messages = effective,
                    limits = limits,
                    tools = request.tools,
                    inputTokenCalibration = calibration,
                )
            )
        }
        val estimate = RequestTokenEstimator.estimate(fitted, request.tools)
        val active = calibratedInputTokens(estimate, calibration)
        if (plan == null && active > inputBudget) {
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

    /**
     * 只接受结果齐全的工具组。Assistant 一次返回的多个 Tool Call 共同组成一个原子组。
     */
    internal fun removeOrphanToolResults(messages: List<AbstractApiMessage>): List<AbstractApiMessage> {
        val completeToolCalls = messages
            .filterIsInstance<AgentToolResultApiMessage>()
            .mapTo(mutableSetOf()) { it.toolCallId }
        val retainedAssistantIds = messages
            .filterIsInstance<AgentAssistantApiMessage>()
            .filter { assistant ->
                assistant.toolCalls.isEmpty() || assistant.toolCalls.all { it.id in completeToolCalls }
            }
            .flatMapTo(mutableSetOf()) { assistant -> assistant.toolCalls.map { it.id } }

        return messages.filter { message ->
            when (message) {
                is AgentAssistantApiMessage -> message.toolCalls.isEmpty() ||
                    message.toolCalls.all { it.id in completeToolCalls }
                is AgentToolResultApiMessage -> message.toolCallId in retainedAssistantIds
                else -> true
            }
        }
    }

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
            .coerceAtLeast(32L)
            .coerceAtMost(hardLimit.coerceAtLeast(1).toLong())
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
        return calibratedInputTokens(estimate, calibration) +
            (request.contextManagement?.reservedOutputTokens?.toLong() ?: 0L)
    }

    private fun planCompaction(
        canonical: List<AbstractApiMessage>,
        checkpoint: AgentCompactionEntryEntity?,
        tokensBefore: Long,
    ): AgentCompactionPlan? {
        val systemIndexes = canonical.indices.filter { canonical[it].role.equals("system", true) }.toSet()
        val sourceStart = checkpoint
            ?.summarizedThroughItemId
            ?.let { id -> canonical.indexOfFirst { it.id == id } + 1 }
            ?.coerceAtLeast(0)
            ?: 0
        val candidateIndexes = canonical.indices.filter { it >= sourceStart && it !in systemIndexes }
        if (candidateIndexes.isEmpty()) return null

        val latestUserPosition = candidateIndexes.indexOfLast { canonical[it].role.equals("user", true) }
        if (latestUserPosition > 0) {
            return createPlan(
                canonical = canonical,
                summarizedIndexes = candidateIndexes.take(latestUserPosition),
                retainedIndexes = candidateIndexes.drop(latestUserPosition),
                checkpoint = checkpoint,
                summaryRole = "system",
                tokensBefore = tokensBefore,
            )
        }

        // 检查点之后没有新用户消息时，只压缩已完成的早期工具组，保留最后一个原子组。
        if (latestUserPosition >= 0) return null
        val groups = atomicGroups(canonical, candidateIndexes)
        if (groups.size <= 1) return null
        return createPlan(
            canonical = canonical,
            summarizedIndexes = groups.dropLast(1).flatten(),
            retainedIndexes = groups.last(),
            checkpoint = checkpoint,
            summaryRole = "system",
            tokensBefore = tokensBefore,
        )
    }

    private fun createPlan(
        canonical: List<AbstractApiMessage>,
        summarizedIndexes: List<Int>,
        retainedIndexes: List<Int>,
        checkpoint: AgentCompactionEntryEntity?,
        summaryRole: String,
        tokensBefore: Long,
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
            append(message.reasoning).append('|').append(message.text)
            message.toolCalls.forEach { call ->
                append('|').append(call.id).append('|').append(call.name).append('|').append(call.arguments)
            }
        }
        is AgentToolResultApiMessage ->
            "${message.id}|${message.role}|${message.toolCallId}|${message.toolName}|${message.content}|${message.isError}"
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
    is AgentToolResultApiMessage -> {
        val raw = content.toString()
        val limited = if (raw.length <= 2_000) raw else {
            raw.take(1_000) + "\n…省略 ${raw.length - 2_000} 字符…\n" + raw.takeLast(1_000)
        }
        "[工具结果 $toolName] ${limited.escapeBoundary()}"
    }
}

private fun String.escapeBoundary(): String =
    replace("</conversation>", "&lt;/conversation&gt;", ignoreCase = true)

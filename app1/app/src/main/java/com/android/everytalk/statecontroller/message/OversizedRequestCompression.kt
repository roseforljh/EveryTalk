package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.toThinkingConfig
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

internal const val CONTEXT_COMPRESSION_RUNNING_STATUS = "正在压缩上下文"
internal const val CONTEXT_COMPRESSION_FAILURE_PREFIX = "上下文压缩失败："

private const val OVERSIZED_COMPRESSION_MAX_ROUNDS = 5
private const val OVERSIZED_COMPRESSION_MAX_OUTPUT_TOKENS = 4_096
private const val OVERSIZED_COMPRESSION_PROMPT_RESERVE_TOKENS = 512L
private const val OVERSIZED_COMPRESSION_MIN_TARGET_TOKENS = 64L
private const val CONTEXT_COMPRESSION_MAX_REQUEST_RETRIES = 2
private const val CONTEXT_COMPRESSION_INITIAL_RETRY_DELAY_MILLIS = 500L

internal class ContextCompressionException(
    val displayReason: String,
    val category: RequestErrorCategory? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(displayReason, cause) {
    companion object {
        fun from(error: Throwable): ContextCompressionException {
            if (error is ContextCompressionException) return error
            val reason = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()
                ?.lineSequence()
                ?.firstOrNull()
                ?.take(500)
                ?: "未知错误"
            val category = ContextErrorClassifier.classify(error)
            return ContextCompressionException(
                displayReason = reason,
                category = category,
                retryable = category == RequestErrorCategory.NETWORK,
                cause = error,
            )
        }

        fun from(event: AppStreamEvent.Error): ContextCompressionException {
            val info = event.toProviderErrorInfo()
            val category = ContextErrorClassifier.classify(info)
            val status = info.status
            val retryableStatus = status in setOf(408, 409, 425, 429) ||
                (status != null && status >= 500)
            val networkLike = listOf(
                "network", "connection", "timeout", "timed out", "socket", "网络", "连接", "超时",
            ).any { marker -> info.message.contains(marker, ignoreCase = true) }
            return ContextCompressionException(
                displayReason = info.message.lineSequence().firstOrNull()?.take(500) ?: "未知错误",
                category = category,
                retryable = retryableStatus || category == RequestErrorCategory.RATE_LIMITED || networkLike,
            )
        }
    }
}

internal fun contextCompressionFailureText(error: Throwable): String =
    CONTEXT_COMPRESSION_FAILURE_PREFIX + ContextCompressionException.from(error).displayReason

internal suspend fun collectContextCompressionResponse(events: Flow<AppStreamEvent>): String {
    val streamedText = StringBuilder()
    var finalText: String? = null
    var streamError: AppStreamEvent.Error? = null
    var terminalReceived = false
    events.collect { event ->
        when (event) {
            is AppStreamEvent.Text -> streamedText.append(event.text)
            is AppStreamEvent.Content -> streamedText.append(event.text)
            is AppStreamEvent.ContentFinal -> if (event.text.isNotBlank()) finalText = event.text
            is AppStreamEvent.Error -> if (streamError == null) streamError = event
            is AppStreamEvent.Finish,
            is AppStreamEvent.StreamEnd -> terminalReceived = true
            else -> Unit
        }
    }
    streamError?.let { throw ContextCompressionException.from(it) }
    if (!terminalReceived) {
        throw ContextCompressionException(
            displayReason = "压缩响应流在完成前中断",
            category = RequestErrorCategory.NETWORK,
            retryable = true,
        )
    }
    return (finalText ?: streamedText.toString()).trim()
        .takeIf(String::isNotEmpty)
        ?: throw ContextCompressionException("摘要模型未返回有效内容")
}

internal suspend fun <T> runContextCompressionWithRetries(
    pause: suspend (Long) -> Unit = { millis -> delay(millis) },
    operation: suspend () -> T,
): T {
    var retryCount = 0
    while (true) {
        try {
            return operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val compressionError = ContextCompressionException.from(error)
            if (!compressionError.retryable || retryCount >= CONTEXT_COMPRESSION_MAX_REQUEST_RETRIES) {
                throw compressionError
            }
            retryCount++
            pause(contextCompressionRetryDelayMillis(retryCount))
        }
    }
}

internal data class ContextCompressionChunk(
    val index: Int,
    val total: Int,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

private data class PendingCompressionChunk(
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/**
 * 按行边界切分待压缩文本，超长单行才按字符切分。
 */
internal fun splitTextForContextCompression(
    text: String,
    maxTokens: Long,
): List<ContextCompressionChunk> {
    require(maxTokens > 0L)
    if (text.isEmpty()) return emptyList()

    val pending = mutableListOf<PendingCompressionChunk>()
    val current = StringBuilder()
    var currentTokens = 0L
    var currentStartLine = 1
    var currentEndLine = 1

    fun flush() {
        if (current.isEmpty()) return
        pending += PendingCompressionChunk(currentStartLine, currentEndLine, current.toString())
        current.clear()
        currentTokens = 0L
    }

    fun appendSegment(segment: String, lineNumber: Int) {
        val segmentTokens = RequestTokenEstimator.estimateText(segment)
        if (segmentTokens > maxTokens) {
            flush()
            splitLongTextSegment(segment, maxTokens).forEach { part ->
                pending += PendingCompressionChunk(lineNumber, lineNumber, part)
            }
            return
        }
        val combinedTokens = currentTokens + segmentTokens
        if (current.isNotEmpty() && combinedTokens > maxTokens) flush()
        if (current.isEmpty()) currentStartLine = lineNumber
        currentEndLine = lineNumber
        current.append(segment)
        currentTokens += segmentTokens
    }

    val lines = text.split('\n')
    lines.forEachIndexed { index, line ->
        val segment = if (index < lines.lastIndex) "$line\n" else line
        if (segment.isNotEmpty()) appendSegment(segment, index + 1)
    }
    flush()

    return pending.mapIndexed { index, chunk ->
        ContextCompressionChunk(
            index = index + 1,
            total = pending.size,
            startLine = chunk.startLine,
            endLine = chunk.endLine,
            text = chunk.text,
        )
    }
}

private fun splitLongTextSegment(text: String, maxTokens: Long): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var low = start + 1
        var high = text.length
        var acceptedEnd = low
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (RequestTokenEstimator.estimateText(text.substring(start, middle)) <= maxTokens) {
                acceptedEnd = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        result += text.substring(start, acceptedEnd)
        start = acceptedEnd
    }
    return result
}

internal suspend fun compressTextWithChunks(
    sourceText: String,
    targetTokens: Long,
    limits: ModelTokenLimits,
    summarizeChunk: suspend (ContextCompressionChunk, Int) -> String,
): String {
    if (sourceText.isBlank()) throw ContextCompressionException("待压缩内容为空")
    if (targetTokens < OVERSIZED_COMPRESSION_MIN_TARGET_TOKENS) {
        throw ContextCompressionException("模型窗口没有足够空间保留压缩摘要")
    }

    val maximumSummaryOutput = minOf(
        OVERSIZED_COMPRESSION_MAX_OUTPUT_TOKENS,
        limits.maxOutputTokens,
    ).coerceAtLeast(1)
    var chunkTokens = (
        limits.maxContextTokens.toLong() -
            maximumSummaryOutput.toLong() -
            OVERSIZED_COMPRESSION_PROMPT_RESERVE_TOKENS
        ).coerceAtLeast(1L)
    var source = sourceText
    var previousTokens = RequestTokenEstimator.estimateText(source)
    var round = 0
    var resizeAttempts = 0

    while (round < OVERSIZED_COMPRESSION_MAX_ROUNDS) {
        val chunks = splitTextForContextCompression(source, chunkTokens)
        if (chunks.isEmpty()) throw ContextCompressionException("待压缩内容为空")
        val requestedPerChunk = (targetTokens / chunks.size.toLong()).coerceAtLeast(1L)
        val outputUpperBound = maximumSummaryOutput.toLong().coerceAtLeast(1L)
        val outputTokens = requestedPerChunk.coerceAtMost(outputUpperBound).toInt().coerceAtLeast(1)
        val summaries = try {
            chunks.map { chunk ->
                val summary = summarizeChunk(chunk, outputTokens).trim()
                if (summary.isEmpty()) throw ContextCompressionException("摘要模型未返回有效内容")
                summary
            }
        } catch (error: ContextCompressionException) {
            if (
                error.category == RequestErrorCategory.INPUT_CONTEXT_TOO_LONG &&
                resizeAttempts < 4 &&
                chunkTokens > OVERSIZED_COMPRESSION_MIN_TARGET_TOKENS
            ) {
                chunkTokens = (chunkTokens * 3L / 4L)
                    .coerceAtLeast(OVERSIZED_COMPRESSION_MIN_TARGET_TOKENS)
                resizeAttempts++
                continue
            }
            throw error
        }
        val combined = if (summaries.size == 1) {
            summaries.single()
        } else {
            summaries.mapIndexed { index, summary ->
                val chunk = chunks[index]
                "[片段 ${chunk.index}/${chunk.total}，行 ${chunk.startLine}-${chunk.endLine}]\n$summary"
            }.joinToString("\n\n")
        }
        val combinedTokens = RequestTokenEstimator.estimateText(combined)
        if (combinedTokens <= targetTokens) return combined
        if (round > 0 && combinedTokens >= previousTokens) {
            throw ContextCompressionException("压缩结果未能继续缩小")
        }
        source = combined
        previousTokens = combinedTokens
        round++
    }
    throw ContextCompressionException("多轮压缩后仍无法放入模型上下文窗口")
}

internal suspend fun compressOversizedLatestUserTurn(
    messages: List<AbstractApiMessage>,
    tools: List<Map<String, Any>>?,
    limits: ModelTokenLimits,
    inputTokenCalibration: Long = 0L,
    additionalContextTokens: Long = 0L,
    onCompressionStarted: suspend () -> Unit = {},
    summarizeChunk: suspend (ContextCompressionChunk, Int) -> String,
): List<AbstractApiMessage> {
    val inputBudget = limits.maxContextTokens.toLong() - limits.maxOutputTokens.toLong()
    if (inputBudget <= 0L) {
        throw ContextCompressionException("预留输出已占满模型上下文窗口")
    }
    if (
        calibratedInputTokens(
            RequestTokenEstimator.estimate(
                messages,
                tools,
                additionalContextTokens = additionalContextTokens,
            ),
            inputTokenCalibration.coerceAtLeast(0L),
        ) <= inputBudget
    ) return messages

    val userIndex = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
    if (userIndex < 0) throw ContextCompressionException("请求中没有可压缩的用户内容")
    val sourceText = messages[userIndex].compressionSourceText()
    if (sourceText.isBlank()) {
        throw ContextCompressionException("媒体附件和协议开销已超过模型可用输入空间")
    }

    val withoutCurrentText = messages.toMutableList().apply {
        this[userIndex] = this[userIndex].replaceCompressionText("")
    }
    val fixedTokens = calibratedInputTokens(
        RequestTokenEstimator.estimate(
            withoutCurrentText,
            tools,
            additionalContextTokens = additionalContextTokens,
        ),
        inputTokenCalibration.coerceAtLeast(0L),
    )
    val wrapperTokens = RequestTokenEstimator.estimateText("[当前用户输入已自动压缩]\n")
    val targetTokens = inputBudget - fixedTokens - wrapperTokens
    if (targetTokens < OVERSIZED_COMPRESSION_MIN_TARGET_TOKENS) {
        throw ContextCompressionException("系统提示、工具定义和媒体附件已占满模型可用输入空间")
    }

    onCompressionStarted()
    val summary = compressTextWithChunks(
        sourceText = sourceText,
        targetTokens = targetTokens,
        limits = limits,
        summarizeChunk = summarizeChunk,
    )
    val compressed = messages.toMutableList().apply {
        this[userIndex] = this[userIndex].replaceCompressionText(
            "[当前用户输入已自动压缩]\n$summary",
        )
    }
    val finalTokens = calibratedInputTokens(
        RequestTokenEstimator.estimate(
            compressed,
            tools,
            additionalContextTokens = additionalContextTokens,
        ),
        inputTokenCalibration.coerceAtLeast(0L),
    )
    if (finalTokens > inputBudget) {
        throw ContextCompressionException("压缩后请求仍超出模型上下文窗口")
    }
    return compressed
}

private fun AbstractApiMessage.compressionSourceText(): String = when (this) {
    is SimpleTextApiMessage -> content
    is PartsApiMessage -> parts.mapIndexedNotNull { index, part ->
        (part as? ApiContentPart.Text)?.let { "[文本段 ${index + 1}]\n${it.text}" }
    }.joinToString("\n\n")
}

private fun AbstractApiMessage.replaceCompressionText(text: String): AbstractApiMessage = when (this) {
    is SimpleTextApiMessage -> copy(content = text)
    is PartsApiMessage -> copy(
        parts = buildList {
            var inserted = false
            parts.forEach { part ->
                if (part is ApiContentPart.Text) {
                    if (!inserted && text.isNotEmpty()) add(ApiContentPart.Text(text))
                    inserted = true
                } else {
                    add(part)
                }
            }
            if (!inserted && text.isNotEmpty()) add(0, ApiContentPart.Text(text))
        },
    )
}

internal suspend fun MessageSender.requestContextCompressionCompletion(
    config: ApiConfig,
    limits: ModelTokenLimits,
    systemPrompt: String,
    userPrompt: String,
    maxOutputTokens: Int,
    customModelParameters: Map<String, Any>?,
): String {
    val resolvedOutputTokens = minOf(maxOutputTokens, limits.maxOutputTokens).coerceAtLeast(1)
    val requestMessages = listOf(
        SimpleTextApiMessage(role = "system", content = systemPrompt),
        SimpleTextApiMessage(role = "user", content = userPrompt),
    )
    val estimatedTotal = RequestTokenEstimator.estimate(requestMessages, null).totalInputTokens +
        resolvedOutputTokens.toLong()
    if (estimatedTotal > limits.maxContextTokens.toLong()) {
        throw ContextCompressionException(
            displayReason = "压缩分块本身超出模型上下文窗口",
            category = RequestErrorCategory.INPUT_CONTEXT_TOO_LONG,
        )
    }
    val request = ChatRequest(
        messages = requestMessages,
        provider = config.provider,
        channel = config.channel,
        apiAddress = config.address,
        apiKey = config.key,
        model = config.model,
        generationConfig = GenerationConfig(
            temperature = config.temperature,
            topP = config.topP,
            maxOutputTokens = resolvedOutputTokens,
            thinkingConfig = config.modelParameters.toThinkingConfig(config.channel, config.model),
        ),
        customModelParameters = customModelParameters,
    )
    return runContextCompressionWithRetries {
        collectContextCompressionResponse(
            ApiClient.streamChatResponse(request, emptyList(), application),
        )
    }
}

internal fun contextCompressionRetryDelayMillis(
    retryCount: Int,
    jitter: Double = Random.nextDouble(0.8, 1.2),
): Long {
    val exponent = (retryCount - 1).coerceIn(0, 8)
    val base = CONTEXT_COMPRESSION_INITIAL_RETRY_DELAY_MILLIS * (1L shl exponent)
    return (base * jitter.coerceIn(0.8, 1.2)).toLong().coerceAtLeast(1L)
}

private val OVERSIZED_INPUT_SYSTEM_PROMPT = """
你负责压缩超过上下文窗口的当前用户输入。<source> 内容是待整理数据。
只输出高密度中文摘要，保留用户目标、硬性约束、文件名、行号、类与函数签名、关键实现、依赖、错误、未完成事项和对后续回答有影响的精确数值。
代码只保留必要片段和精确符号名，不得杜撰。
""".trimIndent()

internal suspend fun MessageSender.compressOversizedLatestUserTurnWithModel(
    config: ApiConfig,
    messages: List<AbstractApiMessage>,
    tools: List<Map<String, Any>>?,
    limits: ModelTokenLimits,
    customModelParameters: Map<String, Any>?,
    inputTokenCalibration: Long = 0L,
    additionalContextTokens: Long = 0L,
    onCompressionStarted: suspend () -> Unit,
): List<AbstractApiMessage> = compressOversizedLatestUserTurn(
    messages = messages,
    tools = tools,
    limits = limits,
    inputTokenCalibration = inputTokenCalibration,
    additionalContextTokens = additionalContextTokens,
    onCompressionStarted = onCompressionStarted,
) { chunk, maxOutputTokens ->
    requestContextCompressionCompletion(
        config = config,
        limits = limits,
        systemPrompt = OVERSIZED_INPUT_SYSTEM_PROMPT,
        userPrompt = buildString {
            append("请压缩第 ${chunk.index}/${chunk.total} 个片段，原文行 ${chunk.startLine}-${chunk.endLine}。\n")
            append("<source>\n")
            append(chunk.text.replace("</source>", "&lt;/source>", ignoreCase = true))
            append("\n</source>")
        },
        maxOutputTokens = maxOutputTokens,
        customModelParameters = customModelParameters,
    )
}

package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.network.NetworkUtils.configureSSERequest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

object AnthropicDirectClient {
    private const val TAG = "AnthropicDirectClient"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val DEFAULT_MAX_TOKENS = 8192
    private const val DEFAULT_THINKING_BUDGET = 1024
    private const val MAX_TOOL_LOOPS = 50
    private const val COMPACTION_BETA = "compact-2026-01-12"
    private const val COMPACTION_EDIT_TYPE = "compact_20260112"
    private val unsupportedNativeCompaction = ConcurrentHashMap.newKeySet<String>()

    private var mcpToolExecutor: AppToolExecutor? = null
    private var mcpToolExecutorOwner: Any? = null

    @Synchronized
    fun setMcpToolExecutor(
        owner: Any,
        executor: AppToolExecutor?,
    ) {
        mcpToolExecutorOwner = owner
        mcpToolExecutor = executor
    }

    @Synchronized
    fun setMcpToolExecutor(
        executor: LegacyAppToolExecutor?,
    ) {
        mcpToolExecutorOwner = null
        mcpToolExecutor = executor.toAppToolExecutor()
    }

    @Synchronized
    fun clearMcpToolExecutor(owner: Any) {
        if (mcpToolExecutorOwner === owner) {
            mcpToolExecutorOwner = null
            mcpToolExecutor = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest,
    ): Flow<AppStreamEvent> = channelFlow {
        var terminalSent = false
        try {
            val url = resolveMessagesUrl(request.apiAddress)
            val continuationMessages = mutableListOf<JsonObject>()
            var latestActiveUsage: TokenUsage? = null
            var nativeContextManagementEnabled = shouldUseNativeContextManagement(request)
            var roundTripRestoredCompaction = hasRestoredAnthropicCompaction(request)
            var nativeCompactionObserved = false
            var resetNativeStateAfterCompletion = roundTripRestoredCompaction && !nativeContextManagementEnabled

            repeat(MAX_TOOL_LOOPS) { loopIndex ->
                var parseResult: AnthropicParseResult? = null
                var retryWithoutNativeContextManagement: Boolean
                val roundContentBuffer = ToolRoundContentBuffer { event ->
                    send(event)
                    yield()
                }
                do {
                    retryWithoutNativeContextManagement = false
                    val payload = buildAnthropicPayload(
                        request = request,
                        continuationMessages = continuationMessages,
                        nativeContextManagementEnabled = nativeContextManagementEnabled,
                        allowRestoredCompaction = nativeContextManagementEnabled || roundTripRestoredCompaction,
                        includeRequestMessages = !nativeCompactionObserved,
                    )

                    client.preparePost(url) {
                        contentType(ContentType.Application.Json)
                        header("x-api-key", request.apiKey.filterNot(Char::isWhitespace))
                        header("anthropic-version", ANTHROPIC_VERSION)
                        if (nativeContextManagementEnabled || roundTripRestoredCompaction) {
                            header("anthropic-beta", COMPACTION_BETA)
                        }
                        configureSSERequest()
                        setBody(payload)
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            val errorBody = response.readErrorTextAtMost().orEmpty()
                            if (
                                nativeContextManagementEnabled &&
                                isUnsupportedNativeContextManagement(response.status.value, errorBody)
                            ) {
                                Log.w(TAG, "服务端不支持 Anthropic 原生压缩，本轮关闭后重试")
                                unsupportedNativeCompaction += nativeCompactionCapabilityKey(request)
                                nativeContextManagementEnabled = false
                                roundTripRestoredCompaction = hasRestoredAnthropicCompaction(request)
                                resetNativeStateAfterCompletion = roundTripRestoredCompaction
                                retryWithoutNativeContextManagement = true
                                return@execute
                            }
                            val result = NetworkUtils.handleApiError(response.status, errorBody, "Anthropic")
                            send(result.error)
                            send(result.finish)
                            terminalSent = true
                            return@execute
                        }

                        parseResult = parseAnthropicSse(response.bodyAsChannel()) { event ->
                            val orderedEvent = event.withRequestOrdinal(loopIndex + 1)
                            roundContentBuffer.accept(orderedEvent)
                        }
                    }
                    if (terminalSent) return@channelFlow
                } while (retryWithoutNativeContextManagement)

                val parsed = parseResult ?: error("Anthropic 流未返回解析结果")
                roundContentBuffer.finish(hasToolCalls = parsed.toolCalls.isNotEmpty())
                latestActiveUsage = parsed.activeUsage ?: latestActiveUsage
                parsed.errorMessage?.let { message ->
                    send(AppStreamEvent.Error("Anthropic API 错误: $message"))
                    send(AppStreamEvent.Finish("api_error"))
                    terminalSent = true
                    return@channelFlow
                }

                val assistantMessage = buildJsonObject {
                    put("role", "assistant")
                    put("content", parsed.assistantContent)
                }
                if (parsed.hasSuccessfulCompaction()) {
                    continuationMessages.clear()
                    nativeCompactionObserved = true
                }

                if (parsed.toolCalls.isEmpty()) {
                    if (nativeCompactionObserved) continuationMessages += assistantMessage
                    if (parsed.fullText.isNotEmpty()) {
                        send(AppStreamEvent.ContentFinal(parsed.fullText))
                    }
                    val management = request.contextManagement
                    if (nativeCompactionObserved && management != null) {
                        val canonicalMessages = JsonArray(continuationMessages)
                        val activeTokens = latestActiveUsage?.let { usage ->
                            safeTokenAdd(
                                usage.inputTokens?.coerceAtLeast(0L) ?: 0L,
                                usage.outputTokens?.coerceAtLeast(0L) ?: 0L,
                            )
                        }?.takeIf { it > 0L } ?: estimateToolLoopJsonTokens(canonicalMessages)
                        send(
                            AppStreamEvent.NativeContextCompaction(
                                inputJson = canonicalMessages.toString(),
                                configId = management.configId,
                                provider = request.provider,
                                channel = request.channel,
                                model = request.model,
                                compactionItemId = parsed.compactionIdentifier(),
                                estimatedTokens = activeTokens,
                                kind = NativeContextCompactionKind.ANTHROPIC_MESSAGES,
                            ),
                        )
                    } else if (resetNativeStateAfterCompletion && management != null) {
                        send(
                            AppStreamEvent.NativeContextCompaction(
                                inputJson = "",
                                configId = management.configId,
                                provider = request.provider,
                                channel = request.channel,
                                model = request.model,
                                reset = true,
                                kind = NativeContextCompactionKind.ANTHROPIC_MESSAGES,
                            ),
                        )
                    }
                    send(AppStreamEvent.Finish(parsed.stopReason ?: "stop"))
                    terminalSent = true
                    return@channelFlow
                }

                val executor = mcpToolExecutor
                if (executor == null) {
                    send(AppStreamEvent.Error("Anthropic 返回了工具调用，但工具执行器未初始化"))
                    send(AppStreamEvent.Finish("tool_executor_unavailable"))
                    terminalSent = true
                    return@channelFlow
                }

                continuationMessages += assistantMessage

                val toolResultBlocks = mutableListOf<JsonElement>()
                for (toolCall in parsed.toolCalls) {
                    send(
                        AppStreamEvent.ToolCall(
                            id = toolCall.id,
                            name = toolCall.name,
                            argumentsObj = toolCall.input,
                        ),
                    )
                    val execution = try {
                        val result = executor(
                            toolCall.name,
                            toolCall.input,
                            toolCall.id,
                            request.localComputerRequestContext,
                        ) { status ->
                            send(AppStreamEvent.ExecutionStatusUpdate(status))
                        }
                        computerExecutionCompletedEvent(result, toolCall.id)?.let { send(it) }
                        val webResults = WebSearchToolResultExtractor.extract(toolCall.name, result)
                        if (webResults.isNotEmpty()) {
                            send(AppStreamEvent.WebSearchResults(webResults))
                        }
                        ToolExecution(result = result, isError = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "工具 ${toolCall.name} 执行失败", error)
                        ToolExecution(
                            result = JsonPrimitive("Error: ${error.message ?: "Unknown error"}"),
                            isError = true,
                        )
                    }
                    toolResultBlocks += buildToolResultBlock(toolCall.id, execution)
                }
                val toolResults = JsonArray(toolResultBlocks)
                continuationMessages += buildJsonObject {
                    put("role", "user")
                    put("content", toolResults)
                }
                if (
                    compactAnthropicToolHistoryIfNeeded(
                        history = continuationMessages,
                        management = request.contextManagement,
                        usage = latestActiveUsage,
                    )
                ) {
                    send(AppStreamEvent.ExecutionStatusUpdate(TOOL_CONTEXT_COMPRESSION_STATUS))
                }
                Log.d(TAG, "完成 Anthropic 工具循环 ${loopIndex + 1}")
            }

            if (!terminalSent) {
                send(AppStreamEvent.Error("Anthropic 工具调用超过 $MAX_TOOL_LOOPS 轮限制"))
                send(AppStreamEvent.Finish("tool_loop_limit"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!terminalSent) {
                val result = NetworkUtils.handleConnectionError(error, "Anthropic")
                send(result.error)
                send(result.finish)
            }
        }
    }

    internal fun resolveMessagesUrl(apiAddress: String?): String {
        val raw = apiAddress?.trim().orEmpty()
        if (raw.isEmpty()) return "https://api.anthropic.com/v1/messages"
        val direct = raw.endsWith('#')
        val normalized = raw.removeSuffix("#").trimEnd('/')
        val base = if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            normalized
        } else {
            "https://$normalized"
        }
        if (direct || base.endsWith("/messages", ignoreCase = true)) return base
        return if (base.endsWith("/v1", ignoreCase = true)) "$base/messages" else "$base/v1/messages"
    }

    internal fun resolveModelsUrl(apiAddress: String): String {
        val raw = apiAddress.trim().removeSuffix("#").trimEnd('/')
        val base = when {
            raw.endsWith("/v1/messages", ignoreCase = true) -> raw.dropLast("/messages".length)
            raw.endsWith("/messages", ignoreCase = true) -> raw.dropLast("/messages".length)
            else -> raw
        }
        return if (base.endsWith("/v1", ignoreCase = true)) "$base/models" else "$base/v1/models"
    }

    internal fun buildAnthropicPayload(
        request: ChatRequest,
        continuationMessages: List<JsonObject> = emptyList(),
        nativeContextManagementEnabled: Boolean = shouldUseNativeContextManagement(request),
        allowRestoredCompaction: Boolean = nativeContextManagementEnabled,
        includeRequestMessages: Boolean = true,
    ): String {
        val preparedMessages = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
        val systemText = preparedMessages
            .filter { it.role.equals("system", ignoreCase = true) }
            .mapNotNull(::messageText)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        val messages = restoredAnthropicMessages(request, preparedMessages, allowRestoredCompaction)
            ?: preparedMessages
                .filterNot { it.role.equals("system", ignoreCase = true) }
                .mapNotNull(::toAnthropicMessage)
        val maxTokens = request.generationConfig?.maxOutputTokens
            ?.takeIf { it > 0 }
            ?: DEFAULT_MAX_TOKENS
        val thinkingConfig = request.generationConfig?.thinkingConfig
        val thinkingBudget = thinkingConfig
            ?.takeIf { it.reasoningMode == ReasoningMode.BUDGET && it.includeThoughts == true }
            ?.let { config -> config.thinkingBudget?.takeIf { it > 0 } ?: DEFAULT_THINKING_BUDGET }
            ?.takeIf { it >= DEFAULT_THINKING_BUDGET && it < maxTokens }
        val usesAdaptiveThinking = thinkingConfig?.reasoningMode == ReasoningMode.EFFORT &&
            thinkingConfig.includeThoughts == true

        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (systemText.isNotBlank()) put("system", systemText)
            putJsonArray("messages") {
                if (includeRequestMessages) messages.forEach(::add)
                continuationMessages.forEach(::add)
            }
            if (nativeContextManagementEnabled) {
                putJsonObject("context_management") {
                    putJsonArray("edits") {
                        addJsonObject {
                            put("type", COMPACTION_EDIT_TYPE)
                            putJsonObject("trigger") {
                                put("type", "input_tokens")
                                put(
                                    "value",
                                    checkNotNull(request.contextManagement).compactThresholdTokens,
                                )
                            }
                        }
                    }
                }
            }

            if (usesAdaptiveThinking) {
                putJsonObject("thinking") {
                    put("type", "adaptive")
                }
                putJsonObject("output_config") {
                    put("effort", thinkingConfig.reasoningEffort)
                }
            } else if (thinkingBudget != null) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget)
                }
            } else {
                request.generationConfig?.temperature?.let { put("temperature", it) }
                request.generationConfig?.topP?.let { put("top_p", it) }
            }

            val tools = toAnthropicTools(PromptCachePolicy.normalizeTools(request.tools))
            if (tools.isNotEmpty()) {
                put("tools", tools)
                toAnthropicToolChoice(request.toolChoice)?.let { put("tool_choice", it) }
            }
        }.toString()
    }

    private fun messageText(message: AbstractApiMessage): String? = when (message) {
        is SimpleTextApiMessage -> message.content
        is PartsApiMessage -> message.parts
            .filterIsInstance<ApiContentPart.Text>()
            .joinToString("\n") { it.text }
    }

    private fun restoredAnthropicMessages(
        request: ChatRequest,
        preparedMessages: List<AbstractApiMessage>,
        allowRestoredCompaction: Boolean,
    ): List<JsonObject>? {
        if (!allowRestoredCompaction) return null
        val management = request.contextManagement ?: return null
        val state = management.restoredState ?: return null
        if (
            state.configId != management.configId ||
            !state.provider.equals(request.provider, ignoreCase = true) ||
            !state.channel.equals(request.channel, ignoreCase = true) ||
            !state.model.equals(request.model, ignoreCase = true)
        ) {
            return null
        }
        val throughId = state.anthropicThroughMessageId?.takeIf(String::isNotBlank) ?: return null
        val throughIndex = preparedMessages.indexOfFirst { it.id == throughId }
        if (throughIndex < 0 && !management.restoredStateCoversRequestPrefix) return null
        val canonical = parseCanonicalAnthropicMessages(state.anthropicMessagesJson) ?: return null
        return buildList {
            addAll(canonical)
            val uncoveredMessages = if (throughIndex >= 0) {
                preparedMessages.drop(throughIndex + 1)
            } else {
                preparedMessages
            }
            uncoveredMessages
                .filterNot { it.role.equals("system", ignoreCase = true) }
                .mapNotNullTo(this, ::toAnthropicMessage)
        }
    }

    private fun containsSuccessfulCompaction(messages: List<JsonObject>): Boolean =
        messages.any { message ->
            (message["content"] as? JsonArray).orEmpty().any { element ->
                val block = element as? JsonObject ?: return@any false
                block["type"]?.jsonPrimitive?.contentOrNull == "compaction" &&
                    block["content"].nullableString()?.isNotBlank() == true
            }
        }

    private fun parseCanonicalAnthropicMessages(json: String?): List<JsonObject>? = json
        ?.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull() }
        ?.mapNotNull { it as? JsonObject }
        ?.takeIf(::containsSuccessfulCompaction)

    private fun toAnthropicMessage(message: AbstractApiMessage): JsonObject? {
        val content = when (message) {
            is SimpleTextApiMessage -> buildJsonArray {
                message.content.takeIf(String::isNotEmpty)?.let { text ->
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            }
            is PartsApiMessage -> buildJsonArray {
                message.parts.forEach { part ->
                    when (part) {
                        is ApiContentPart.Text -> if (part.text.isNotEmpty()) {
                            addJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            }
                        }
                        is ApiContentPart.InlineData -> addInlineData(part)
                        is ApiContentPart.FileUri -> addJsonObject {
                            put("type", "text")
                            put("text", "[附件: ${part.uri}]")
                        }
                    }
                }
            }
        }
        if (content.isEmpty()) return null
        return buildJsonObject {
            put("role", if (message.role.equals("assistant", true) || message.role.equals("model", true)) "assistant" else "user")
            put("content", content)
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addInlineData(part: ApiContentPart.InlineData) {
        val mimeType = when (part.mimeType.lowercase()) {
            "image/jpg" -> "image/jpeg"
            else -> part.mimeType.lowercase()
        }
        if (mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", mimeType)
                    put("data", stripDataUriPrefix(part.base64Data))
                }
            }
        } else {
            addJsonObject {
                put("type", "text")
                put("text", "[不支持直接发送的附件类型: $mimeType]")
            }
        }
    }

    private fun stripDataUriPrefix(value: String): String {
        if (!value.startsWith("data:", ignoreCase = true)) return value
        val marker = ";base64,"
        val markerIndex = value.indexOf(marker, ignoreCase = true)
        return if (markerIndex >= 0) value.substring(markerIndex + marker.length) else value
    }

    private fun toAnthropicTools(tools: List<Map<String, Any>>?): JsonArray = buildJsonArray {
        tools.orEmpty().forEach { tool ->
            val function = tool["function"] as? Map<*, *>
            val name = (function?.get("name") ?: tool["name"]) as? String ?: return@forEach
            val description = (function?.get("description") ?: tool["description"]) as? String
            val schema = function?.get("parameters") ?: tool["input_schema"]
            addJsonObject {
                put("name", name)
                description?.takeIf(String::isNotBlank)?.let { put("description", it) }
                put("input_schema", anyToJsonElement(schema).let { it as? JsonObject ?: emptyInputSchema() })
            }
        }
    }

    private fun toAnthropicToolChoice(toolChoice: Any?): JsonObject? {
        if (toolChoice == null) return null
        if (toolChoice is String) {
            val type = when (toolChoice.lowercase()) {
                "required", "any" -> "any"
                "none" -> "none"
                else -> "auto"
            }
            return buildJsonObject { put("type", type) }
        }
        val choice = anyToJsonElement(toolChoice) as? JsonObject ?: return null
        val functionName = choice["function"]
            ?.let { it as? JsonObject }
            ?.get("name")
            ?.jsonPrimitive
            ?.contentOrNull
        val directName = choice["name"]?.jsonPrimitive?.contentOrNull
        val name = functionName ?: directName
        return if (name != null) {
            buildJsonObject {
                put("type", "tool")
                put("name", name)
            }
        } else {
            val requestedType = choice["type"]?.jsonPrimitive?.contentOrNull
            buildJsonObject {
                put("type", if (requestedType == "required") "any" else requestedType ?: "auto")
            }
        }
    }

    private fun emptyInputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Map<*, *> -> JsonObject(
            value.entries.mapNotNull { entry ->
                (entry.key as? String)?.let { it to anyToJsonElement(entry.value) }
            }.toMap(),
        )
        is Iterable<*> -> JsonArray(value.map(::anyToJsonElement))
        is Array<*> -> JsonArray(value.map(::anyToJsonElement))
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun buildToolResultBlock(toolUseId: String, execution: ToolExecution): JsonObject {
        val resultObject = execution.result as? JsonObject
        val images = resultObject?.get("_images") as? JsonArray
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", toolUseId)
            if (execution.isError) put("is_error", true)
            if (images.isNullOrEmpty()) {
                put("content", execution.result.toString())
            } else {
                putJsonArray("content") {
                    val textOnly = JsonObject(resultObject.filterKeys { it != "_images" })
                    if (textOnly.isNotEmpty()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", textOnly.toString())
                        }
                    }
                    images.forEach { image ->
                        val imageObject = image as? JsonObject ?: return@forEach
                        val data = imageObject["base64"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val mediaType = imageObject["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/jpeg"
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", mediaType)
                                put("data", stripDataUriPrefix(data))
                            }
                        }
                    }
                }
            }
        }
    }

    internal suspend fun parseAnthropicSse(
        channel: ByteReadChannel,
        emitEvent: suspend (AppStreamEvent) -> Unit,
    ): AnthropicParseResult {
        val reader = BoundedSseLineReader(channel)
        val dataLines = mutableListOf<String>()
        val blocks = linkedMapOf<Int, AnthropicStreamBlock>()
        var stopReason: String? = null
        var errorMessage: String? = null
        var reasoningOpen = false
        var messageStopped = false
        var latestActiveUsage: TokenUsage? = null
        var latestIterationsUsage: TokenUsage? = null

        suspend fun emitUsage(usage: JsonObject, isFinal: Boolean) {
            latestActiveUsage = parseAnthropicTokenUsage(
                usage = usage,
                previous = latestActiveUsage,
                isFinal = isFinal,
            )
            parseAnthropicIterationsUsage(usage, isFinal)?.let { parsed ->
                latestIterationsUsage = parsed
            }
            val emittedUsage = latestIterationsUsage?.copy(isFinal = isFinal) ?: latestActiveUsage
            emittedUsage?.let { parsed -> emitEvent(AppStreamEvent.Usage(parsed)) }
        }

        suspend fun processEvent() {
            if (dataLines.isEmpty()) return
            val raw = dataLines.joinToString("\n")
            dataLines.clear()
            val event = Json.parseToJsonElement(raw).jsonObject
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val usage = ((event["message"] as? JsonObject)?.get("usage") as? JsonObject)
                    usage?.let { emitUsage(it, isFinal = false) }
                }
                "content_block_start" -> {
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
                    val block = event["content_block"] as? JsonObject ?: return
                    val state = AnthropicStreamBlock(
                        index = index,
                        type = block["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        id = block["id"]?.jsonPrimitive?.contentOrNull,
                        name = block["name"]?.jsonPrimitive?.contentOrNull,
                        initialInput = block["input"] as? JsonObject,
                        compactionContent = block["content"].nullableString(),
                        encryptedContent = block["encrypted_content"].nullableString(),
                    )
                    block["text"]?.jsonPrimitive?.contentOrNull?.let(state.text::append)
                    block["thinking"]?.jsonPrimitive?.contentOrNull?.let(state.text::append)
                    block["signature"]?.jsonPrimitive?.contentOrNull?.let(state.signature::append)
                    blocks[index] = state
                    if (state.text.isNotEmpty()) {
                        when (state.type) {
                            "thinking" -> {
                                reasoningOpen = true
                                emitEvent(AppStreamEvent.Reasoning(state.text.toString()))
                            }
                            "text" -> emitEvent(AppStreamEvent.Content(state.text.toString()))
                        }
                    }
                }
                "content_block_delta" -> {
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
                    val state = blocks[index] ?: return
                    val delta = event["delta"] as? JsonObject ?: return
                    if (state.type == "compaction") {
                        delta["content"]?.takeUnless { it is JsonNull }?.let { content ->
                            state.compactionContent = state.compactionContent.orEmpty() + content.jsonPrimitive.content
                        }
                        delta["encrypted_content"]?.takeUnless { it is JsonNull }?.let { encryptedContent ->
                            state.encryptedContent = state.encryptedContent.orEmpty() +
                                encryptedContent.jsonPrimitive.content
                        }
                    }
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            if (reasoningOpen) {
                                emitEvent(AppStreamEvent.ReasoningFinish())
                                reasoningOpen = false
                            }
                            state.text.append(text)
                            emitEvent(AppStreamEvent.Content(text))
                        }
                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { thinking ->
                            reasoningOpen = true
                            state.text.append(thinking)
                            emitEvent(AppStreamEvent.Reasoning(thinking))
                        }
                        "signature_delta" -> delta["signature"]?.jsonPrimitive?.contentOrNull?.let(state.signature::append)
                        "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let(state.partialInput::append)
                    }
                }
                "message_delta" -> {
                    stopReason = (event["delta"] as? JsonObject)
                        ?.get("stop_reason")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: stopReason
                    (event["usage"] as? JsonObject)?.let { usage ->
                        emitUsage(usage, isFinal = true)
                    }
                }
                "message_stop" -> messageStopped = true
                "error" -> {
                    errorMessage = (event["error"] as? JsonObject)
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: "未知流式错误"
                    messageStopped = true
                }
            }
        }

        while (!messageStopped) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> processEvent()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
            }
        }
        processEvent()
        if (reasoningOpen) emitEvent(AppStreamEvent.ReasoningFinish())

        val sortedBlocks = blocks.values.sortedBy { it.index }
        val toolCalls = sortedBlocks.mapNotNull { block ->
            if (block.type != "tool_use") return@mapNotNull null
            val id = block.id ?: return@mapNotNull null
            val name = block.name ?: return@mapNotNull null
            val input = if (block.partialInput.isNotEmpty()) {
                runCatching { Json.parseToJsonElement(block.partialInput.toString()).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
            } else {
                block.initialInput ?: JsonObject(emptyMap())
            }
            AnthropicToolCall(id, name, input)
        }
        val assistantContent = buildJsonArray {
            sortedBlocks.forEach { block ->
                when (block.type) {
                    "text" -> if (block.text.isNotEmpty()) addJsonObject {
                        put("type", "text")
                        put("text", block.text.toString())
                    }
                    "thinking" -> if (block.text.isNotEmpty()) addJsonObject {
                        put("type", "thinking")
                        put("thinking", block.text.toString())
                        if (block.signature.isNotEmpty()) put("signature", block.signature.toString())
                    }
                    "tool_use" -> {
                        val call = toolCalls.firstOrNull { it.id == block.id } ?: return@forEach
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", call.input)
                        }
                    }
                    "compaction" -> addJsonObject {
                        put("type", "compaction")
                        val content = block.compactionContent
                        if (content == null) put("content", JsonNull) else put("content", content)
                        val encryptedContent = block.encryptedContent
                        if (encryptedContent == null) {
                            put("encrypted_content", JsonNull)
                        } else {
                            put("encrypted_content", encryptedContent)
                        }
                    }
                }
            }
        }
        return AnthropicParseResult(
            assistantContent = assistantContent,
            toolCalls = toolCalls,
            fullText = sortedBlocks.filter { it.type == "text" }.joinToString("") { it.text.toString() },
            stopReason = stopReason,
            errorMessage = errorMessage,
            activeUsage = latestActiveUsage,
        )
    }

    private fun parseAnthropicTokenUsage(
        usage: JsonObject,
        previous: TokenUsage?,
        isFinal: Boolean,
    ): TokenUsage? {
        val parsed = TokenUsage(
            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: previous?.inputTokens,
            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: previous?.outputTokens,
            cachedInputTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull
                ?: previous?.cachedInputTokens,
            cacheWriteTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull
                ?: previous?.cacheWriteTokens,
            isFinal = isFinal,
            source = TokenUsageSource.ANTHROPIC,
        )
        return parsed.takeIf {
            it.inputTokens != null || it.outputTokens != null ||
                it.cachedInputTokens != null || it.cacheWriteTokens != null
        }
    }

    private fun parseAnthropicIterationsUsage(
        usage: JsonObject,
        isFinal: Boolean,
    ): TokenUsage? {
        val iterations = (usage["iterations"] as? JsonArray)?.takeIf(JsonArray::isNotEmpty) ?: return null
        var inputTokens = 0L
        var outputTokens = 0L
        var cachedInputTokens = 0L
        var cacheWriteTokens = 0L
        var hasValidIteration = false
        iterations.forEach { element ->
            val iteration = element as? JsonObject ?: return@forEach
            hasValidIteration = true
            inputTokens = safeTokenAdd(inputTokens, iteration.nonNegativeToken("input_tokens"))
            outputTokens = safeTokenAdd(outputTokens, iteration.nonNegativeToken("output_tokens"))
            cachedInputTokens = safeTokenAdd(
                cachedInputTokens,
                iteration.nonNegativeToken("cache_read_input_tokens"),
            )
            cacheWriteTokens = safeTokenAdd(
                cacheWriteTokens,
                iteration.nonNegativeToken("cache_creation_input_tokens"),
            )
        }
        if (!hasValidIteration) return null
        return TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            cacheWriteTokens = cacheWriteTokens,
            totalTokens = safeTokenAdd(inputTokens, outputTokens),
            isFinal = isFinal,
            source = TokenUsageSource.ANTHROPIC,
        )
    }

    private fun safeTokenAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun JsonObject.nonNegativeToken(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L

    private fun JsonElement?.nullableString(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> content
        else -> null
    }

    private fun AnthropicParseResult.hasSuccessfulCompaction(): Boolean =
        assistantContent.any { element ->
            val block = element as? JsonObject ?: return@any false
            block["type"]?.jsonPrimitive?.contentOrNull == "compaction" &&
                block["content"].nullableString()?.isNotBlank() == true
        }

    private fun AnthropicParseResult.compactionIdentifier(): String? {
        val block = assistantContent.lastOrNull { element ->
            (element as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "compaction"
        } as? JsonObject ?: return null
        return block["encrypted_content"].nullableString()?.let { "anthropic:${it.hashCode()}" }
            ?: block["content"].nullableString()?.let { "anthropic:${it.hashCode()}" }
    }

    private data class AnthropicStreamBlock(
        val index: Int,
        val type: String,
        val id: String?,
        val name: String?,
        val initialInput: JsonObject?,
        var compactionContent: String?,
        var encryptedContent: String?,
        val text: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        val partialInput: StringBuilder = StringBuilder(),
    )

    private fun shouldUseNativeContextManagement(request: ChatRequest): Boolean =
        request.channel.contains("anthropic", ignoreCase = true) &&
            request.contextManagement?.autoCompressionEnabled == true &&
            request.contextManagement.compactThresholdTokens > 0L &&
            isOfficialAnthropicMessagesAddress(request.apiAddress) &&
            nativeCompactionCapabilityKey(request) !in unsupportedNativeCompaction

    private fun hasRestoredAnthropicCompaction(request: ChatRequest): Boolean =
        isOfficialAnthropicMessagesAddress(request.apiAddress) &&
            request.contextManagement?.restoredState?.let { state ->
                state.configId == request.contextManagement.configId &&
                    state.provider.equals(request.provider, ignoreCase = true) &&
                    state.channel.equals(request.channel, ignoreCase = true) &&
                    state.model.equals(request.model, ignoreCase = true) &&
                    !state.anthropicThroughMessageId.isNullOrBlank() &&
                    parseCanonicalAnthropicMessages(state.anthropicMessagesJson) != null
            } == true

    internal fun isNativeCompactionAvailable(apiAddress: String?, model: String): Boolean =
        nativeCompactionCapabilityKey(apiAddress, model) !in unsupportedNativeCompaction

    private fun nativeCompactionCapabilityKey(request: ChatRequest): String =
        nativeCompactionCapabilityKey(request.apiAddress, request.model)

    private fun nativeCompactionCapabilityKey(apiAddress: String?, model: String): String =
        "${resolveMessagesUrl(apiAddress).lowercase()}|${model.trim().lowercase()}"

    private fun isUnsupportedNativeContextManagement(status: Int, errorBody: String): Boolean {
        if (status != 400 && status != 422) return false
        val normalized = errorBody.lowercase()
        if (
            !normalized.contains("context_management") &&
            !normalized.contains(COMPACTION_EDIT_TYPE) &&
            !normalized.contains(COMPACTION_BETA)
        ) {
            return false
        }
        return listOf(
            "unknown",
            "unsupported",
            "unrecognized",
            "invalid parameter",
            "extra inputs",
            "not permitted",
        ).any(normalized::contains)
    }

    private data class ToolExecution(
        val result: JsonElement,
        val isError: Boolean,
    )
}

internal fun isOfficialAnthropicMessagesAddress(apiAddress: String?): Boolean =
    runCatching {
        io.ktor.http.Url(AnthropicDirectClient.resolveMessagesUrl(apiAddress)).host
            .equals("api.anthropic.com", ignoreCase = true)
    }.getOrDefault(false)

internal data class AnthropicToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
)

internal data class AnthropicParseResult(
    val assistantContent: JsonArray,
    val toolCalls: List<AnthropicToolCall>,
    val fullText: String,
    val stopReason: String?,
    val errorMessage: String?,
    val activeUsage: TokenUsage?,
)

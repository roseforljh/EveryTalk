package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.network.NetworkUtils.configureSSERequest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

object OpenAIResponsesClient {
    private const val TAG = "OpenAIResponsesClient"

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun streamSingleTurn(
        client: HttpClient,
        request: ChatRequest,
    ): Flow<AppStreamEvent> = channelFlow {
        val baseUrl = request.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: "https://api.openai.com"
        val endpoint = LlmEndpointResolver.resolve(
            protocol = ModelParameterProtocol.CODEX,
            apiAddress = baseUrl,
            model = request.model,
        )
        var terminalSent = false
        try {
            var nativeContextManagementEnabled = shouldUseNativeContextManagement(request)
            var resetNativeStateAfterCompletion = false
            var parsed: ResponsesParseResult? = null
            do {
                var retryWithoutNativeContextManagement = false
                val input = buildInitialResponsesInput(
                    request = request,
                    allowRestoredCompaction = nativeContextManagementEnabled,
                )
                client.preparePost(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildResponsesPayloadFromInput(
                            request = request,
                            input = input,
                            nativeContextManagementEnabled = nativeContextManagementEnabled,
                        ),
                    )
                    header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
                    configureSSERequest()
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = response.readErrorTextAtMost().orEmpty()
                        if (
                            nativeContextManagementEnabled &&
                            isUnsupportedNativeContextManagement(response.status.value, errorBody)
                        ) {
                            Log.w(TAG, "服务端不支持 context_management，本次请求关闭原生压缩后重试")
                            nativeContextManagementEnabled = false
                            resetNativeStateAfterCompletion = hasRestoredResponsesCompaction(request)
                            retryWithoutNativeContextManagement = true
                            return@execute
                        }
                        val result = NetworkUtils.handleApiError(
                            response.status,
                            errorBody,
                            "OpenAI-Responses",
                        )
                        terminalSent = true
                        send(result.error)
                        send(result.finish)
                        return@execute
                    }
                    parsed = parseResponsesSSEStream(
                        channel = response.bodyAsChannel(),
                        onToolCall = {},
                        emitEvent = { send(it) },
                    )
                }
                if (terminalSent) return@channelFlow
            } while (retryWithoutNativeContextManagement)

            val completed = parsed ?: error("Responses 流未返回可解析结果")
            val management = request.contextManagement
            val canonicalOutput = buildInitialResponsesInput(
                request = request,
                allowRestoredCompaction = true,
            ).toMutableList().apply { addAll(completed.outputItems) }
            val compactionItemId = pruneBeforeLatestCompaction(canonicalOutput)
            val compactedContextJson = compactionItemId?.let { JsonArray(canonicalOutput).toString() }
                ?: request.localProviderContinuation?.compactedContextJson
            if (completed.outputItems.isNotEmpty()) {
                send(
                    AppStreamEvent.ProviderContinuation(
                        protocol = com.android.everytalk.data.DataClass.ModelParameterProtocol.CODEX.name,
                        payloadJson = JsonArray(completed.outputItems).toString(),
                        compactedContextJson = compactedContextJson,
                    ),
                )
            }
            when {
                resetNativeStateAfterCompletion && management != null -> send(
                    AppStreamEvent.NativeContextCompaction(
                        inputJson = "",
                        configId = management.configId,
                        provider = request.provider,
                        channel = request.channel,
                        model = request.model,
                        reset = true,
                    ),
                )
                compactionItemId != null && management != null -> {
                    val canonical = JsonArray(canonicalOutput)
                    send(
                        AppStreamEvent.NativeContextCompaction(
                            inputJson = canonical.toString(),
                            configId = management.configId,
                            provider = request.provider,
                            channel = request.channel,
                            model = request.model,
                            compactionItemId = compactionItemId,
                            estimatedTokens = estimateToolLoopJsonTokens(canonical),
                        ),
                    )
                }
            }
            if (!terminalSent) send(AppStreamEvent.Finish(completed.stopReason))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val result = NetworkUtils.handleConnectionError(error, "OpenAI-Responses")
            send(result.error)
            send(result.finish)
        }
    }

    /** 兼容旧调用名；工具轮次统一由 AgentLoop 推进。 */
    suspend fun streamChatResponses(
        client: HttpClient,
        request: ChatRequest,
    ): Flow<AppStreamEvent> = streamSingleTurn(client, request)

    private data class ResponsesToolCallInfo(
        val callId: String,
        val name: String,
        val arguments: String
    )

    private data class ResponsesToolCallState(
        var providerCallId: String = "",
        var providerItemId: String? = null,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
        var namespace: String? = null,
    ) {
        val callId: String
            get() = responsesToolCallId(providerCallId.takeIf(String::isNotBlank), providerItemId)

        fun matches(callId: String?, itemId: String?): Boolean = when {
            !itemId.isNullOrBlank() && !providerItemId.isNullOrBlank() -> providerItemId == itemId
            !callId.isNullOrBlank() && providerCallId.isNotBlank() -> providerCallId == callId
            else -> false
        }
    }

    private data class ResponsesParseResult(
        val hasToolCalls: Boolean,
        val fullText: String,
        val reasoningContent: String = "",
        val outputItems: List<JsonElement> = emptyList(),
        val usage: TokenUsage? = null,
        val stopReason: String = "stop",
    )

    internal fun buildResponsesPayload(
        request: ChatRequest,
        previousOutput: List<JsonElement>
    ): String = buildResponsesPayloadFromInput(
        request = request,
        input = buildInitialResponsesInput(request) + previousOutput,
        nativeContextManagementEnabled = shouldUseNativeContextManagement(request),
    )

    private fun buildResponsesPayloadFromInput(
        request: ChatRequest,
        input: List<JsonElement>,
        nativeContextManagementEnabled: Boolean,
    ): String {
        val messagesWithSystemPrompt = PiOpenAIResponsesMessageAdapter.transformMessages(
            SystemPromptInjector.smartInjectSystemPrompt(request.messages),
            request,
        )
            .normalizeAgentToolHistory()
        val normalizedTools = PromptCachePolicy.normalizeTools(request.tools)
        val promptCacheKey = PromptCachePolicy.buildOpenAICacheKey(
            apiAddress = resolvedOpenAIApiAddress(request),
            model = request.model,
            messages = messagesWithSystemPrompt,
            tools = normalizedTools,
        )
        runCatching {
            Log.d(
                TAG,
                "Prompt cache prefix system=${PromptCachePolicy.systemFingerprint(messagesWithSystemPrompt)} " +
                    "profile=${PromptCachePolicy.toolProfile(normalizedTools)} " +
                    "tools=${PromptCachePolicy.toolSchemaHash(normalizedTools).take(16)}",
            )
        }

        return buildJsonObject {
            put("model", request.model)
            put("stream", true)
            if (isOfficialOpenAIResponsesAddress(resolvedOpenAIApiAddress(request))) {
                put("store", false)
            }
            if (nativeContextManagementEnabled) {
                putJsonArray("context_management") {
                    addJsonObject {
                        put("type", "compaction")
                        put(
                            "compact_threshold",
                            checkNotNull(request.contextManagement).compactThresholdTokens,
                        )
                    }
                }
            }
            promptCacheKey?.let { put("prompt_cache_key", it) }

            // instructions: 提取 system message
            val systemMsg = messagesWithSystemPrompt.firstOrNull { it.role == "system" }
            if (systemMsg != null) {
                val systemText = when (systemMsg) {
                    is SimpleTextApiMessage -> systemMsg.content
                    is PartsApiMessage -> systemMsg.parts.filterIsInstance<ApiContentPart.Text>()
                        .joinToString("\n") { it.text }
                    is AgentAssistantApiMessage -> systemMsg.text
                    is AgentToolResultApiMessage -> systemMsg.content.toString()
                }
                if (systemText.isNotBlank()) {
                    put("instructions", systemText)
                }
            }

            // input 已包含恢复的权威状态、新消息、完整模型输出项和工具结果。
            putJsonArray("input") {
                input.forEach(::add)
            }

            // 参数
            request.generationConfig?.let { config ->
                config.temperature?.let { put("temperature", it) }
                config.topP?.let { put("top_p", it) }
                config.maxOutputTokens?.let { put("max_output_tokens", it) }
            }

            // reasoning 参数（Responses API 专用）
            request.generationConfig?.thinkingConfig?.let { thinkingConfig ->
                putJsonObject("reasoning") {
                    put(
                        "effort",
                        if (thinkingConfig.reasoningMode == com.android.everytalk.data.DataClass.ReasoningMode.DISABLED) {
                            "none"
                        } else {
                            thinkingConfig.reasoningEffort
                        }
                    )
                    put("summary", "auto")
                }
            }

            // 工具注入 (Responses API 扁平格式)
            normalizedTools?.let { tools ->
                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        tools.forEach { toolDef ->
                            addJsonObject {
                                put("type", "function")
                                val funcMap = toolDef["function"]
                                if (funcMap is Map<*, *>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val funcTyped = funcMap as Map<String, Any>
                                    funcTyped["name"]?.let { put("name", it.toString()) }
                                    funcTyped["description"]?.let { put("description", it.toString()) }
                                    funcTyped["parameters"]?.let { put("parameters", anyToJsonElement(it)) }
                                } else {
                                    // 已经是扁平格式
                                    toolDef["name"]?.let { put("name", it.toString()) }
                                    toolDef["description"]?.let { put("description", it.toString()) }
                                    toolDef["parameters"]?.let { put("parameters", anyToJsonElement(it)) }
                                }
                                // Computer 与 MCP 工具允许省略可选参数，部分工具还接受动态对象。
                                // OpenAI 严格模式要求 properties 中所有字段都出现在 required，
                                // 因此这里明确关闭严格模式，避免请求在模型执行前被 400 拒绝。
                                put("strict", false)
                            }
                        }
                    }
                    put("tool_choice", request.toolChoice?.let(::anyToJsonElement) ?: JsonPrimitive("auto"))
                }
            }
        }.toString()
    }

    private fun buildInitialResponsesInput(
        request: ChatRequest,
        allowRestoredCompaction: Boolean = true,
    ): List<JsonElement> {
        val messagesWithSystemPrompt = PiOpenAIResponsesMessageAdapter.transformMessages(
            SystemPromptInjector.smartInjectSystemPrompt(request.messages),
            request,
        )
        val toolResultIds = messagesWithSystemPrompt
            .filterIsInstance<AgentToolResultApiMessage>()
            .map { it.toolCallId }
            .toSet()
        val runThroughIndex = request.localProviderContinuation
            ?.compactedThroughMessageId
            ?.let { throughId -> messagesWithSystemPrompt.indexOfFirst { it.id == throughId } }
            ?: -1
        val runNativeInput = request.localProviderContinuation
            ?.takeIf {
                allowRestoredCompaction &&
                    it.protocol == com.android.everytalk.data.DataClass.ModelParameterProtocol.CODEX &&
                    runThroughIndex >= 0
            }
            ?.compactedContextJson
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() }
            ?.toList()
        val nativeOutputCandidate = request.localProviderContinuation
            ?.takeIf { it.protocol == com.android.everytalk.data.DataClass.ModelParameterProtocol.CODEX }
            ?.payloadJson
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() }
            ?.takeIf(JsonArray::isNotEmpty)
        val latestAssistant = messagesWithSystemPrompt.filterIsInstance<AgentAssistantApiMessage>().lastOrNull()
        val nativeFunctionCalls = nativeOutputCandidate.orEmpty().mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            if (value["type"]?.jsonPrimitive?.contentOrNull != "function_call") return@mapNotNull null
            val callId = value["call_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val itemId = value["id"]?.jsonPrimitive?.contentOrNull
            (itemId?.let { "$callId|$it" } ?: callId) to value["name"]?.jsonPrimitive?.contentOrNull
        }
        val nativeFunctionCallIds = nativeFunctionCalls.map { it.first }
        val nativeText = nativeOutputCandidate?.toList()?.let(::extractOpenAICompletedOutputText).orEmpty()
        val continuationBelongsToAssistant = latestAssistant != null &&
            latestAssistant.canReplayNativeContinuation(request) &&
            (request.localProviderContinuation?.assistantMessageId == null ||
                request.localProviderContinuation.assistantMessageId == latestAssistant.id) &&
            (
                if (latestAssistant.toolCalls.isEmpty()) {
                    nativeFunctionCalls.isEmpty() && nativeText == latestAssistant.text
                } else {
                    nativeFunctionCalls.size == latestAssistant.toolCalls.size &&
                        nativeFunctionCalls.zip(latestAssistant.toolCalls).all { (native, call) ->
                            (native.first == call.id || native.first.substringBefore('|') == call.id) &&
                                native.second == call.name
                        }
                }
            )
        // 中断可能只保存模型的 function_call，却没来得及保存工具结果。
        // 这种 continuation 不能再发给 Responses，否则服务端会直接返回 400。
        val nativeOutput = nativeOutputCandidate?.takeIf {
            continuationBelongsToAssistant &&
            (nativeFunctionCallIds.isEmpty() || nativeFunctionCallIds.all { callId ->
                callId.isNotBlank() && toolResultIds.any { resultId ->
                    resultId == callId || resultId == callId.substringBefore('|')
                }
            })
        }
        val replacedAssistantId = nativeOutput?.let {
            messagesWithSystemPrompt.filterIsInstance<AgentAssistantApiMessage>().lastOrNull()?.id
        }
        val throughIndex = runThroughIndex.takeIf { runNativeInput != null } ?: -1
        val uncoveredMessages = messagesWithSystemPrompt
            .filterIndexed { index, message ->
                message.role.equals("system", ignoreCase = true) || index > throughIndex
            }
            .filterNot { it.role.equals("system", ignoreCase = true) }
        return buildList {
            if (allowRestoredCompaction) {
                (runNativeInput ?: restoredNativeInput(request))?.let(::addAll)
            }
            val replacementIndex = uncoveredMessages.indexOfFirst { it.id == replacedAssistantId }
            if (replacementIndex < 0) {
                addAll(PiOpenAIResponsesMessageAdapter.buildTransformedInput(uncoveredMessages, request))
            } else {
                addAll(PiOpenAIResponsesMessageAdapter.buildTransformedInput(uncoveredMessages.take(replacementIndex), request))
                addAll(checkNotNull(nativeOutput))
                addAll(
                    PiOpenAIResponsesMessageAdapter.buildTransformedInput(
                        uncoveredMessages.drop(replacementIndex + 1),
                        request,
                    ),
                )
            }
        }
    }

    private fun restoredNativeInput(request: ChatRequest): List<JsonElement>? {
        if (!shouldUseNativeContextManagement(request)) return null
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
        val inputJson = state.openAiResponsesInputJson?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Json.parseToJsonElement(inputJson) as? JsonArray }
            .getOrNull()
            ?.toList()
    }

    private fun activeNativeInput(request: ChatRequest): List<JsonElement>? =
        request.localProviderContinuation
            ?.takeIf { it.protocol == com.android.everytalk.data.DataClass.ModelParameterProtocol.CODEX }
            ?.compactedContextJson
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() }
            ?.toList()
            ?: restoredNativeInput(request)

    private fun hasRestoredResponsesCompaction(request: ChatRequest): Boolean =
        activeNativeInput(request)?.let(::latestCompactionItemId) != null

    private suspend fun parseResponsesSSEStream(
        channel: ByteReadChannel,
        onToolCall: (ResponsesToolCallInfo) -> Unit,
        emitEvent: suspend (AppStreamEvent) -> Unit
    ): ResponsesParseResult {
        val boundedChannel = BoundedSseLineReader(channel)
        val lineBuffer = StringBuilder()
        val fullText = StringBuilder()
        val fullReasoningContent = StringBuilder()
        var hasToolCalls = false
        var safetyBlocked = false
        val streamedOutputItems = linkedMapOf<String, JsonElement>()
        var completedOutputItems: List<JsonElement>? = null
        var completedResponseText: String? = null
        var finalUsage: TokenUsage? = null
        var stopReason = "stop"
        var sawTerminalResponse = false

        // 聚合 function_call 参数
        val toolCallsMap = mutableMapOf<String, ResponsesToolCallState>()

        /** Responses 的同一调用按 output_index 绑定；终止事件没有 index 时再按真实 ID 对账。 */
        fun upsertToolCall(
            outputIndex: Int?,
            callId: String?,
            itemId: String?,
            name: String?,
            arguments: String?,
            replaceArguments: Boolean,
            namespace: String?,
        ): ResponsesToolCallState? {
            val compositeId = responsesToolCallId(callId, itemId)
            val indexedKey = outputIndex?.let { "index:$it" }
            val existingKey = indexedKey?.takeIf(toolCallsMap::containsKey)
                ?: toolCallsMap.entries.firstOrNull { (_, state) -> state.matches(callId, itemId) }?.key
            if (compositeId.isBlank() && existingKey == null) return null
            val key = existingKey ?: indexedKey ?: "id:$compositeId"
            val state = toolCallsMap.getOrPut(key) { ResponsesToolCallState() }
            callId?.takeIf(String::isNotBlank)?.let { state.providerCallId = it }
            itemId?.takeIf(String::isNotBlank)?.let { state.providerItemId = it }
            name?.takeIf(String::isNotBlank)?.let { state.name = it }
            namespace?.let { state.namespace = it }
            arguments?.let { value ->
                if (replaceArguments) state.arguments.clear()
                state.arguments.append(value)
            }
            return state
        }

        try {
            while (true) {
                val line = boundedChannel.readLine() ?: break

                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            if (chunk == "[DONE]") break
                            try {
                                val event = Json.parseToJsonElement(chunk).jsonObject
                                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: ""
                                NativeWebSearchResultExtractor.extract(event)
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { sources ->
                                        emitEvent(AppStreamEvent.WebSearchResults(sources))
                                    }

                                when (type) {
                                    "response.output_text.delta" -> {
                                        val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (delta.isNotEmpty()) {
                                            fullText.append(delta)
                                            emitEvent(AppStreamEvent.Content(delta, null, ""))
                                        }
                                    }
                                    "response.output_text.done" -> {
                                        // 文本完成，不需要额外处理
                                    }
                                    "response.refusal.delta" -> {
                                        val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        if (delta.isNotEmpty()) {
                                            fullText.append(delta)
                                            emitEvent(AppStreamEvent.Content(delta, null, "refusal"))
                                        }
                                    }
                                    "response.refusal.done" -> Unit
                                    "response.function_call_arguments.delta" -> {
                                        upsertToolCall(
                                            outputIndex = event["output_index"]?.jsonPrimitive?.intOrNull,
                                            callId = event["call_id"]?.jsonPrimitive?.contentOrNull,
                                            itemId = event["item_id"]?.jsonPrimitive?.contentOrNull,
                                            name = event["name"]?.jsonPrimitive?.contentOrNull,
                                            arguments = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                            replaceArguments = false,
                                            namespace = event["namespace"]?.jsonPrimitive?.contentOrNull,
                                        )
                                        hasToolCalls = true
                                    }
                                    "response.function_call_arguments.done" -> {
                                        upsertToolCall(
                                            outputIndex = event["output_index"]?.jsonPrimitive?.intOrNull,
                                            callId = event["call_id"]?.jsonPrimitive?.contentOrNull,
                                            itemId = event["item_id"]?.jsonPrimitive?.contentOrNull,
                                            name = event["name"]?.jsonPrimitive?.contentOrNull,
                                            arguments = event["arguments"]?.jsonPrimitive?.contentOrNull,
                                            replaceArguments = event["arguments"] != null,
                                            namespace = event["namespace"]?.jsonPrimitive?.contentOrNull,
                                        )
                                        hasToolCalls = true
                                    }
                                    "response.reasoning_summary_text.delta" -> {
                                        val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (delta.isNotEmpty()) {
                                            fullReasoningContent.append(delta)
                                            emitEvent(AppStreamEvent.Reasoning(delta))
                                        }
                                    }
                                    "response.reasoning_summary_text.done" -> {
                                        emitEvent(AppStreamEvent.ReasoningFinish(null))
                                    }
                                    "response.output_item.added" -> {
                                        val item = event["item"]?.jsonObject
                                        val outputIndex = event["output_index"]?.jsonPrimitive?.intOrNull
                                        if (item != null) {
                                            streamedOutputItems[responseOutputItemKey(item, outputIndex)] = item
                                        }
                                        val itemType = item?.get("type")?.jsonPrimitive?.contentOrNull
                                        if (itemType == "function_call") {
                                            upsertToolCall(
                                                outputIndex = outputIndex,
                                                callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                                                itemId = item["id"]?.jsonPrimitive?.contentOrNull,
                                                name = item["name"]?.jsonPrimitive?.contentOrNull,
                                                arguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                                                replaceArguments = item["arguments"] != null,
                                                namespace = item["namespace"]?.jsonPrimitive?.contentOrNull,
                                            )
                                            hasToolCalls = true
                                        } else if (itemType == "compaction") {
                                            emitEvent(
                                                AppStreamEvent.ExecutionStatusUpdate(
                                                    TOOL_CONTEXT_COMPRESSION_STATUS
                                                )
                                            )
                                        } else if (itemType == "reasoning") {
                                            // 推理输出项开始
                                            val summary = item["summary"]
                                            if (summary is JsonArray) {
                                                summary.forEach { s ->
                                                    val text = (s as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                                                    if (!text.isNullOrEmpty()) {
                                                        fullReasoningContent.append(text)
                                                        emitEvent(AppStreamEvent.Reasoning(text))
                                                    }
                                                }
                                                emitEvent(AppStreamEvent.ReasoningFinish(null))
                                            }
                                        }
                                    }
                                    "response.output_item.done" -> {
                                        val item = event["item"] as? JsonObject
                                        val outputIndex = event["output_index"]?.jsonPrimitive?.intOrNull
                                        if (item != null) {
                                            streamedOutputItems[responseOutputItemKey(item, outputIndex)] = item
                                            val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                                            if (itemType == "compaction") {
                                                emitEvent(
                                                    AppStreamEvent.ExecutionStatusUpdate(
                                                        TOOL_CONTEXT_COMPRESSION_STATUS
                                                    )
                                                )
                                            }
                                            if (itemType == "function_call") {
                                                upsertToolCall(
                                                    outputIndex = outputIndex,
                                                    callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                                                    itemId = item["id"]?.jsonPrimitive?.contentOrNull,
                                                    name = item["name"]?.jsonPrimitive?.contentOrNull,
                                                    arguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                                                    replaceArguments = true,
                                                    namespace = item["namespace"]?.jsonPrimitive?.contentOrNull,
                                                )
                                                hasToolCalls = true
                                            } else if (itemType == "reasoning") {
                                                emitEvent(
                                                    AppStreamEvent.Reasoning(
                                                        text = "",
                                                        thoughtSignature = item.toString(),
                                                        signatureOnlyUpdate = true,
                                                    ),
                                                )
                                            } else if (itemType == "message") {
                                                item["id"]?.jsonPrimitive?.contentOrNull?.let { messageId ->
                                                    emitEvent(
                                                        AppStreamEvent.Content(
                                                            text = "",
                                                            thoughtSignature = buildJsonObject {
                                                                put("v", 1)
                                                                put("id", messageId)
                                                                item["phase"]?.jsonPrimitive?.contentOrNull?.let { phase ->
                                                                    put("phase", phase)
                                                                }
                                                            }.toString(),
                                                            signatureOnlyUpdate = true,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    "response.completed" -> {
                                        sawTerminalResponse = true
                                        val responseObject = event["response"] as? JsonObject
                                        completedOutputItems = (responseObject?.get("output") as? JsonArray)?.toList()
                                        completedResponseText = completedOutputItems
                                            ?.let(::extractOpenAICompletedOutputText)
                                        emitResponsesReplaySignatures(completedOutputItems.orEmpty(), emitEvent)
                                        completedOutputItems.orEmpty().forEach { outputItem ->
                                            val item = outputItem as? JsonObject ?: return@forEach
                                            val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                                            if (itemType != "function_call") {
                                                return@forEach
                                            }
                                            upsertToolCall(
                                                outputIndex = null,
                                                callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                                                itemId = item["id"]?.jsonPrimitive?.contentOrNull,
                                                name = item["name"]?.jsonPrimitive?.contentOrNull,
                                                arguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                                                replaceArguments = true,
                                                namespace = item["namespace"]?.jsonPrimitive?.contentOrNull,
                                            )
                                            hasToolCalls = true
                                        }
                                        val usage = responseObject?.get("usage") as? JsonObject
                                        finalUsage = usage?.let(::parseOpenAIResponsesTokenUsage)
                                        finalUsage?.let { parsedUsage ->
                                            emitEvent(AppStreamEvent.Usage(parsedUsage))
                                        }
                                        stopReason = "stop"
                                    }
                                    "response.incomplete" -> {
                                        sawTerminalResponse = true
                                        val responseObject = event["response"] as? JsonObject
                                        completedOutputItems = (responseObject?.get("output") as? JsonArray)?.toList()
                                        completedResponseText = completedOutputItems?.let(::extractOpenAICompletedOutputText)
                                        emitResponsesReplaySignatures(completedOutputItems.orEmpty(), emitEvent)
                                        finalUsage = (responseObject?.get("usage") as? JsonObject)
                                            ?.let(::parseOpenAIResponsesTokenUsage)
                                        finalUsage?.let { parsedUsage -> emitEvent(AppStreamEvent.Usage(parsedUsage)) }
                                        val reason = (responseObject?.get("incomplete_details") as? JsonObject)
                                            ?.get("reason")
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                        if (reason == "max_output_tokens") {
                                            stopReason = "length"
                                        } else if (ProviderSafetyResponse.isSafetyReason(reason)) {
                                            safetyBlocked = true
                                            emitEvent(ProviderSafetyResponse.error(reason))
                                            stopReason = "error"
                                        } else {
                                            throw IllegalStateException(
                                                reason?.let { "Response incomplete: $it" }
                                                    ?: "Response incomplete without a provider reason",
                                            )
                                        }
                                    }
                                    "response.failed" -> {
                                        sawTerminalResponse = true
                                        val responseObject = event["response"] as? JsonObject
                                        val errorObject = responseObject?.get("error") as? JsonObject
                                        val reason = errorObject?.get("code")?.jsonPrimitive?.contentOrNull
                                            ?: errorObject?.get("type")?.jsonPrimitive?.contentOrNull
                                        if (ProviderSafetyResponse.isSafetyReason(reason)) {
                                            safetyBlocked = true
                                            emitEvent(ProviderSafetyResponse.error(reason))
                                        } else {
                                            val message = errorObject?.get("message")
                                                ?.jsonPrimitive
                                                ?.contentOrNull
                                                ?: "Responses API 请求失败"
                                            throw IllegalStateException(message)
                                        }
                                    }
                                    "error" -> {
                                        val errorMsg = event["message"]?.jsonPrimitive?.contentOrNull
                                            ?: event["error"]?.let { errEl ->
                                                (errEl as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                                            } ?: "Unknown error"
                                        throw IllegalStateException(errorMsg)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "解析 Responses SSE 事件失败: ${e.message}")
                                throw e
                            }
                            if (safetyBlocked) break
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val dataContent = line.substring(5).trim()
                        val separatorLength = if (lineBuffer.isNotEmpty()) 1 else 0
                        if (separatorLength == 1) lineBuffer.append('\n')
                        lineBuffer.append(dataContent)
                    }
                    line.startsWith("event:") -> {
                        // Responses API 可能用 event: 行，忽略（数据在 data: 行）
                    }
                    line.startsWith(":") -> {
                        // 心跳
                    }
                }
            }

            if (safetyBlocked) {
                return ResponsesParseResult(
                    hasToolCalls = false,
                    fullText = "",
                    reasoningContent = "",
                    outputItems = emptyList(),
                    usage = finalUsage,
                    stopReason = "error",
                )
            }

            if (!sawTerminalResponse) {
                throw IllegalStateException("OpenAI Responses stream ended before a terminal response event")
            }

            // 发送聚合的工具调用
            if (toolCallsMap.isNotEmpty()) {
                toolCallsMap.values.forEach { state ->
                    val callId = state.callId
                    val name = state.name
                    val argsBuilder = state.arguments
                    val namespace = state.namespace
                    if (name.isNotBlank()) {
                        val toolInfo = ResponsesToolCallInfo(callId, name, argsBuilder.toString())
                        onToolCall(toolInfo)
                        emitEvent(AppStreamEvent.ToolCall(
                            id = callId,
                            name = name,
                            argumentsObj = try {
                                Json.parseToJsonElement(argsBuilder.toString()).jsonObject
                            } catch (_: Exception) {
                                JsonObject(emptyMap())
                            },
                            namespace = namespace,
                        ))
                    }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }

        val completedText = completedResponseText
            ?.takeIf { it.isNotEmpty() }
            ?: fullText.toString()
        val completedReasoning = fullReasoningContent.toString()
        if (completedText.isNotEmpty() && !hasToolCalls) {
            emitEvent(AppStreamEvent.ContentFinal(completedText, null, null))
        }

        val canonicalOutput = (
            completedOutputItems?.takeIf { it.isNotEmpty() }
                ?: streamedOutputItems.values.toList()
            ).toMutableList()
        toolCallsMap.values.forEach { state ->
            val callId = state.callId
            val name = state.name
            val arguments = state.arguments
            val namespace = state.namespace
            val providerCallId = callId.substringBefore('|')
            val providerItemId = callId.substringAfter('|', missingDelimiterValue = "").takeIf(String::isNotEmpty)
            val alreadyPresent = canonicalOutput.any { item ->
                val itemObject = item as? JsonObject
                itemObject?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" &&
                    itemObject["call_id"]?.jsonPrimitive?.contentOrNull == providerCallId &&
                    (providerItemId == null || itemObject["id"]?.jsonPrimitive?.contentOrNull == providerItemId)
            }
            if (!alreadyPresent && name.isNotBlank()) {
                canonicalOutput += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", providerCallId)
                    providerItemId?.let { put("id", it) }
                    put("name", name)
                    put("arguments", arguments.toString())
                    namespace?.let { put("namespace", it) }
                }
            }
        }

        return ResponsesParseResult(
            hasToolCalls = hasToolCalls,
            fullText = completedText,
            reasoningContent = completedReasoning,
            outputItems = canonicalOutput,
            usage = finalUsage,
            stopReason = if (hasToolCalls && stopReason == "stop") "tool_use" else stopReason,
        )
    }

    /** Pi 使用 call_id|item_id 保留 Responses 同批调用的真实唯一性。 */
    private fun responsesToolCallId(callId: String?, itemId: String?): String = when {
        !callId.isNullOrBlank() && !itemId.isNullOrBlank() -> "$callId|$itemId"
        !callId.isNullOrBlank() -> callId
        else -> itemId.orEmpty()
    }

    /** response.completed 携带供应商最终正文，可用于修复中途缺失的 delta。 */
    internal fun extractOpenAICompletedOutputText(outputItems: List<JsonElement>): String? {
        val text = buildString {
            for (outputItem in outputItems) {
                val item = outputItem as? JsonObject ?: continue
                if (item["type"]?.jsonPrimitive?.contentOrNull != "message") continue
                for (contentPart in (item["content"] as? JsonArray).orEmpty()) {
                    val part = contentPart as? JsonObject ?: continue
                    when (part["type"]?.jsonPrimitive?.contentOrNull) {
                        "output_text" -> part["text"]?.jsonPrimitive?.contentOrNull?.let(::append)
                        "refusal" -> (part["refusal"] ?: part["text"])
                            ?.jsonPrimitive?.contentOrNull?.let(::append)
                    }
                }
            }
        }
        return text.takeIf { it.isNotEmpty() }
    }

    /** 终止事件同样可能首次携带 message id、phase 和 reasoning item，必须进入持久化回放链。 */
    private suspend fun emitResponsesReplaySignatures(
        outputItems: List<JsonElement>,
        emitEvent: suspend (AppStreamEvent) -> Unit,
    ) {
        outputItems.forEach { outputItem ->
            val item = outputItem as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "reasoning" -> emitEvent(
                    AppStreamEvent.Reasoning(
                        text = "",
                        thoughtSignature = item.toString(),
                        signatureOnlyUpdate = true,
                    ),
                )
                "message" -> item["id"]?.jsonPrimitive?.contentOrNull?.let { messageId ->
                    emitEvent(
                        AppStreamEvent.Content(
                            text = "",
                            thoughtSignature = buildJsonObject {
                                put("v", 1)
                                put("id", messageId)
                                item["phase"]?.jsonPrimitive?.contentOrNull?.let { phase -> put("phase", phase) }
                            }.toString(),
                            signatureOnlyUpdate = true,
                        ),
                    )
                }
            }
        }
    }

    private fun responseOutputItemKey(item: JsonObject, outputIndex: Int?): String =
        item["id"]?.jsonPrimitive?.contentOrNull
            ?: item["call_id"]?.jsonPrimitive?.contentOrNull
            ?: "output:${outputIndex ?: item.hashCode()}"

    private fun parseOpenAIResponsesTokenUsage(usage: JsonObject): TokenUsage? {
        val inputDetails = usage["input_tokens_details"] as? JsonObject
        val outputDetails = usage["output_tokens_details"] as? JsonObject
        val parsed = TokenUsage(
            inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.longOrNull,
            outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.longOrNull,
            reasoningTokens = (outputDetails?.get("reasoning_tokens") as? JsonPrimitive)?.longOrNull,
            cachedInputTokens = (inputDetails?.get("cached_tokens") as? JsonPrimitive)?.longOrNull,
            cacheWriteTokens = (inputDetails?.get("cache_write_tokens") as? JsonPrimitive)?.longOrNull,
            totalTokens = (usage["total_tokens"] as? JsonPrimitive)?.longOrNull,
            isFinal = true,
            source = TokenUsageSource.OPENAI_RESPONSES,
        )
        return parsed.takeIf {
            it.inputTokens != null || it.outputTokens != null || it.reasoningTokens != null ||
                it.cachedInputTokens != null || it.cacheWriteTokens != null || it.totalTokens != null
        }
    }

    private fun shouldUseNativeContextManagement(request: ChatRequest): Boolean =
        request.channel.contains("codex", ignoreCase = true) &&
            request.contextManagement?.autoCompressionEnabled == true &&
            request.contextManagement.compactThresholdTokens > 0L &&
            isOfficialOpenAIResponsesAddress(resolvedOpenAIApiAddress(request))

    private fun isUnsupportedNativeContextManagement(status: Int, errorBody: String): Boolean {
        if (status != 400 && status != 422) return false
        val normalized = errorBody.lowercase()
        if (!normalized.contains("context_management")) return false
        return listOf("unknown", "unsupported", "unrecognized", "invalid parameter", "extra inputs")
            .any(normalized::contains)
    }

    private fun latestCompactionItemId(input: List<JsonElement>): String? {
        val item = input.asReversed().firstOrNull { element ->
            (element as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "compaction"
        } as? JsonObject ?: return null
        return item["id"]?.jsonPrimitive?.contentOrNull
            ?: "compaction:${item.hashCode()}"
    }

    private fun pruneBeforeLatestCompaction(input: MutableList<JsonElement>): String? {
        val index = input.indexOfLast { element ->
            (element as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "compaction"
        }
        if (index < 0) return null
        repeat(index) { input.removeAt(0) }
        return latestCompactionItemId(input)
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                (value as Map<String, Any>).forEach { (k, v) ->
                    put(k, anyToJsonElement(v))
                }
            }
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun resolvedOpenAIApiAddress(request: ChatRequest): String =
        request.apiAddress?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trim().takeIf { it.isNotEmpty() }
            ?: "https://api.openai.com"
}

internal fun isOfficialOpenAIResponsesAddress(apiAddress: String?): Boolean {
    val resolved = apiAddress?.trim()?.takeIf { it.isNotEmpty() }
        ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trim().takeIf { it.isNotEmpty() }
        ?: "https://api.openai.com"
    return runCatching { Url(resolved).host.equals("api.openai.com", ignoreCase = true) }
        .getOrDefault(false)
}

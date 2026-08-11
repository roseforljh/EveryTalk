package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
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
    private const val MAX_TOOL_LOOPS = 50

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
    suspend fun streamChatResponses(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
        var terminalSent = false
        try {
            Log.i(TAG, "启动 OpenAI Responses API 模式")

            var baseUrl = request.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
                ?: "https://api.openai.com"

            // 清理 URL：去掉可能存在的 /chat/completions 后缀
            baseUrl = baseUrl
                .replace(Regex("/v1/chat/completions/?$"), "")
                .replace(Regex("/chat/completions/?$"), "")
                .replace(Regex("/v1/responses/?$"), "")
                .trimEnd('/')

            val url = "$baseUrl/v1/responses"
            Log.d(TAG, "Responses URL: $url")

            val conversationInput = buildInitialResponsesInput(request).toMutableList()
            var latestCompactionItemId = latestCompactionItemId(conversationInput)
            var nativeContextManagementEnabled = shouldUseNativeContextManagement(request)
            var resetNativeStateAfterCompletion = false
            var loopCount = 0

            responseLoop@ while (loopCount < MAX_TOOL_LOOPS) {
                loopCount++
                Log.i(TAG, "循环 #$loopCount, 历史输入数: ${conversationInput.size}")

                val payload = buildResponsesPayloadFromInput(
                    request = request,
                    input = conversationInput,
                    nativeContextManagementEnabled = nativeContextManagementEnabled,
                )

                var pendingToolCalls = mutableListOf<ResponsesToolCallInfo>()
                var parseResult: ResponsesParseResult? = null
                var retryWithoutNativeContextManagement = false

                client.preparePost(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
                    configureSSERequest()
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = response.readErrorTextAtMost()
                        if (
                            nativeContextManagementEnabled &&
                            isUnsupportedNativeContextManagement(response.status.value, errorBody.orEmpty())
                        ) {
                            Log.w(TAG, "服务端不支持 context_management，本轮关闭原生压缩后重试")
                            nativeContextManagementEnabled = false
                            resetNativeStateAfterCompletion = latestCompactionItemId != null
                            retryWithoutNativeContextManagement = true
                            return@execute
                        }
                        val result = NetworkUtils.handleApiError(response.status, errorBody, "OpenAI-Responses")
                        terminalSent = true
                        send(result.error)
                        send(result.finish)
                        return@execute
                    }

                    Log.i(TAG, "Responses 连接成功 (loop $loopCount)")

                    parseResult = parseResponsesSSEStream(
                        channel = response.bodyAsChannel(),
                        onToolCall = { toolInfo ->
                            Log.d(TAG, "捕获工具调用: ${toolInfo.name}")
                            pendingToolCalls.add(toolInfo)
                        },
                        emitEvent = { event ->
                            send(event.withRequestOrdinal(loopCount))
                            kotlinx.coroutines.yield()
                        }
                    )
                }

                if (terminalSent) return@channelFlow
                if (retryWithoutNativeContextManagement) {
                    loopCount--
                    continue@responseLoop
                }

                val completedResponse = parseResult
                    ?: throw IllegalStateException("Responses 流未返回可解析结果")
                conversationInput.addAll(completedResponse.outputItems)
                latestCompactionItemId = pruneBeforeLatestCompaction(conversationInput)
                    ?: latestCompactionItemId

                Log.i(TAG, "循环 #$loopCount 结束, pendingToolCalls=${pendingToolCalls.size}")

                if (pendingToolCalls.isEmpty()) {
                    Log.i(TAG, "没有待处理的工具调用，结束循环")
                    break
                }

                if (mcpToolExecutor == null) {
                    Log.w(TAG, "有工具调用但没有执行器，跳过")
                    break
                }

                Log.i(TAG, "处理 ${pendingToolCalls.size} 个工具调用")

                for (toolInfo in pendingToolCalls) {
                    try {
                        val argsJson = try {
                            Json.parseToJsonElement(toolInfo.arguments).jsonObject
                        } catch (_: Exception) {
                            JsonObject(emptyMap())
                        }

                        val result = mcpToolExecutor!!.invoke(
                            toolInfo.name,
                            argsJson,
                            toolInfo.callId,
                            request.localComputerRequestContext,
                        ) { status ->
                            send(AppStreamEvent.ExecutionStatusUpdate(status))
                        }
                        Log.i(TAG, "工具 ${toolInfo.name} 执行成功")
                        computerExecutionCompletedEvent(result, toolInfo.callId)?.let { send(it) }

                        val webResults = WebSearchToolResultExtractor.extract(toolInfo.name, result)
                        if (webResults.isNotEmpty()) {
                            send(AppStreamEvent.WebSearchResults(webResults))
                        }

                        // Responses API 工具结果格式：function_call_output item
                        val resultObject = result as? JsonObject
                        val images = resultObject?.get("_images") as? JsonArray
                        val textResult = resultObject
                            ?.let { JsonObject(it.filterKeys { key -> key != "_images" }) }
                            ?: result
                        conversationInput.add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", toolInfo.callId)
                            if (images.isNullOrEmpty()) {
                                put("output", textResult.toString())
                            } else {
                                putJsonArray("output") {
                                    addJsonObject {
                                        put("type", "input_text")
                                        put("text", textResult.toString())
                                    }
                                    images.forEach { image ->
                                        val imageObject = image as? JsonObject ?: return@forEach
                                        val base64 = imageObject["base64"]?.jsonPrimitive?.contentOrNull
                                            ?.substringAfter("base64,")
                                            ?: return@forEach
                                        val mime = imageObject["mimeType"]?.jsonPrimitive?.contentOrNull
                                            ?: "image/jpeg"
                                        addJsonObject {
                                            put("type", "input_image")
                                            put("image_url", "data:$mime;base64,$base64")
                                        }
                                    }
                                }
                            }
                        })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "工具 ${toolInfo.name} 执行失败", e)
                        conversationInput.add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", toolInfo.callId)
                            put("output", "Error: ${e.message ?: "Unknown error"}")
                        })
                    }
                }

                if (
                    compactResponsesToolHistoryIfNeeded(
                        history = conversationInput,
                        management = request.contextManagement,
                        usage = completedResponse.usage?.copy(requestOrdinal = loopCount),
                    )
                ) {
                    send(AppStreamEvent.ExecutionStatusUpdate(TOOL_CONTEXT_COMPRESSION_STATUS))
                }

                pendingToolCalls.clear()
            }

            Log.i(TAG, "工具循环完成，发送 Finish")
            if (!terminalSent) {
                val management = request.contextManagement
                when {
                    resetNativeStateAfterCompletion && management != null -> {
                        send(
                            AppStreamEvent.NativeContextCompaction(
                                inputJson = "",
                                configId = management.configId,
                                provider = request.provider,
                                channel = request.channel,
                                model = request.model,
                                reset = true,
                            )
                        )
                    }
                    latestCompactionItemId != null && management != null -> {
                        send(
                            AppStreamEvent.NativeContextCompaction(
                                inputJson = JsonArray(conversationInput).toString(),
                                configId = management.configId,
                                provider = request.provider,
                                channel = request.channel,
                                model = request.model,
                                compactionItemId = latestCompactionItemId,
                                estimatedTokens = estimateToolLoopJsonTokens(JsonArray(conversationInput)),
                            )
                        )
                    }
                }
                terminalSent = true
                send(AppStreamEvent.Finish("stop"))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!terminalSent) {
                val result = NetworkUtils.handleConnectionError(e, "OpenAI-Responses")
                terminalSent = true
                send(result.error)
                send(result.finish)
            }
        }
    }

    private data class ResponsesToolCallInfo(
        val callId: String,
        val name: String,
        val arguments: String
    )

    private data class ResponsesParseResult(
        val hasToolCalls: Boolean,
        val fullText: String,
        val reasoningContent: String = "",
        val outputItems: List<JsonElement> = emptyList(),
        val usage: TokenUsage? = null,
    )

    private fun buildResponsesPayload(
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
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
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
            val thinkingConfig = request.generationConfig?.thinkingConfig
            putJsonObject("reasoning") {
                put(
                    "effort",
                    if (thinkingConfig?.reasoningMode == com.android.everytalk.data.DataClass.ReasoningMode.DISABLED) {
                        "none"
                    } else {
                        thinkingConfig?.reasoningEffort ?: com.android.everytalk.data.DataClass.DEFAULT_REASONING_EFFORT
                    }
                )
                put("summary", "auto")
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
                                    put("strict", true)
                                } else {
                                    // 已经是扁平格式
                                    toolDef["name"]?.let { put("name", it.toString()) }
                                    toolDef["description"]?.let { put("description", it.toString()) }
                                    toolDef["parameters"]?.let { put("parameters", anyToJsonElement(it)) }
                                    put("strict", true)
                                }
                            }
                        }
                    }
                    put("tool_choice", "auto")
                }
            }
        }.toString()
    }

    private fun buildInitialResponsesInput(request: ChatRequest): List<JsonElement> = buildList {
        restoredNativeInput(request)?.let(::addAll)
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
        messagesWithSystemPrompt
            .filterNot { it.role.equals("system", ignoreCase = true) }
            .mapTo(this) { message -> message.toResponsesInputItem() }
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

    private fun com.android.everytalk.data.DataClass.AbstractApiMessage.toResponsesInputItem(): JsonElement =
        when (this) {
            is SimpleTextApiMessage -> buildJsonObject {
                put("role", role)
                put("content", content)
            }
            is PartsApiMessage -> {
                val supportedParts = parts.filterNot {
                    it is ApiContentPart.FileUri && it.mimeType == "qwen-file-id"
                }
                if (supportedParts.all { it is ApiContentPart.Text }) {
                    buildJsonObject {
                        put("role", role)
                        put("content", supportedParts.joinToString("\n") {
                            (it as ApiContentPart.Text).text
                        })
                    }
                } else {
                    buildJsonObject {
                        put("role", role)
                        putJsonArray("content") {
                            supportedParts.forEach { part ->
                                when (part) {
                                    is ApiContentPart.Text -> addJsonObject {
                                        put("type", "input_text")
                                        put("text", part.text)
                                    }
                                    is ApiContentPart.InlineData -> addJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:${part.mimeType};base64,${part.base64Data}")
                                    }
                                    is ApiContentPart.FileUri -> addJsonObject {
                                        put("type", "input_text")
                                        put("text", "[Attachment: ${part.uri}]")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

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
        var finalUsage: TokenUsage? = null

        // 聚合 function_call 参数
        val toolCallsMap = mutableMapOf<String, Triple<String, String, StringBuilder>>() // callId -> (callId, name, args)

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
                                        val callId = event["call_id"]?.jsonPrimitive?.contentOrNull
                                            ?: event["item_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val existing = toolCallsMap[callId]
                                        if (existing != null) {
                                            existing.third.append(delta)
                                        } else {
                                            val name = event["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                            toolCallsMap[callId] = Triple(callId, name, StringBuilder(delta))
                                        }
                                        hasToolCalls = true
                                    }
                                    "response.function_call_arguments.done" -> {
                                        val callId = event["call_id"]?.jsonPrimitive?.contentOrNull
                                            ?: event["item_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val name = event["name"]?.jsonPrimitive?.contentOrNull
                                        val args = event["arguments"]?.jsonPrimitive?.contentOrNull
                                        if (callId.isNotBlank()) {
                                            val existing = toolCallsMap[callId]
                                            if (existing != null && name != null) {
                                                toolCallsMap[callId] = Triple(callId, name, StringBuilder(args ?: existing.third.toString()))
                                            } else if (name != null) {
                                                toolCallsMap[callId] = Triple(callId, name, StringBuilder(args ?: ""))
                                            }
                                        }
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
                                            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                ?: item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (callId.isNotBlank() && !toolCallsMap.containsKey(callId)) {
                                                toolCallsMap[callId] = Triple(callId, name, StringBuilder())
                                            }
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
                                                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                    ?: item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                val arguments = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                if (callId.isNotBlank()) {
                                                    toolCallsMap[callId] = Triple(
                                                        callId,
                                                        name,
                                                        StringBuilder(arguments),
                                                    )
                                                    hasToolCalls = true
                                                }
                                            }
                                        }
                                    }
                                    "response.completed" -> {
                                        val responseObject = event["response"] as? JsonObject
                                        completedOutputItems = (responseObject?.get("output") as? JsonArray)?.toList()
                                        completedOutputItems.orEmpty().forEach { outputItem ->
                                            val item = outputItem as? JsonObject ?: return@forEach
                                            if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call") {
                                                return@forEach
                                            }
                                            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                ?: item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            if (callId.isNotBlank()) {
                                                toolCallsMap[callId] = Triple(
                                                    callId,
                                                    item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                                    StringBuilder(
                                                        item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                    ),
                                                )
                                                hasToolCalls = true
                                            }
                                        }
                                        val usage = responseObject?.get("usage") as? JsonObject
                                        finalUsage = usage?.let(::parseOpenAIResponsesTokenUsage)
                                        finalUsage?.let { parsedUsage ->
                                            emitEvent(AppStreamEvent.Usage(parsedUsage))
                                        }
                                    }
                                    "response.incomplete" -> {
                                        val responseObject = event["response"] as? JsonObject
                                        val reason = (responseObject?.get("incomplete_details") as? JsonObject)
                                            ?.get("reason")
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                        if (ProviderSafetyResponse.isSafetyReason(reason)) {
                                            safetyBlocked = true
                                            emitEvent(ProviderSafetyResponse.error(reason))
                                        }
                                    }
                                    "response.failed" -> {
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
                )
            }

            // 发送聚合的工具调用
            if (toolCallsMap.isNotEmpty()) {
                toolCallsMap.values.forEach { (callId, name, argsBuilder) ->
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
                            }
                        ))
                    }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }

        val completedText = fullText.toString()
        val completedReasoning = fullReasoningContent.toString()
        if (completedText.isNotEmpty() && !hasToolCalls) {
            emitEvent(AppStreamEvent.ContentFinal(completedText, null, null))
        }

        val canonicalOutput = (
            completedOutputItems?.takeIf { it.isNotEmpty() }
                ?: streamedOutputItems.values.toList()
            ).toMutableList()
        toolCallsMap.values.forEach { (callId, name, arguments) ->
            val alreadyPresent = canonicalOutput.any { item ->
                val itemObject = item as? JsonObject
                itemObject?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" &&
                    (
                        itemObject["call_id"]?.jsonPrimitive?.contentOrNull == callId ||
                            itemObject["id"]?.jsonPrimitive?.contentOrNull == callId
                        )
            }
            if (!alreadyPresent && name.isNotBlank()) {
                canonicalOutput += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", callId)
                    put("name", name)
                    put("arguments", arguments.toString())
                }
            }
        }

        return ResponsesParseResult(
            hasToolCalls = hasToolCalls,
            fullText = completedText,
            reasoningContent = completedReasoning,
            outputItems = canonicalOutput,
            usage = finalUsage,
        )
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

package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.network.NetworkUtils.configureSSERequest
import com.android.everytalk.util.AiContentSafetyPolicy
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

object GeminiDirectClient {
    private const val TAG = "GeminiDirectClient"
    private const val LOCAL_TOOL_CALL_ID_PREFIX = "fc_local_"
    private val PART_DATA_FIELDS = setOf(
        "text",
        "inlineData",
        "fileData",
        "functionCall",
        "functionResponse",
        "executableCode",
        "codeExecutionResult",
    )
    
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun streamSingleTurn(
        client: HttpClient,
        request: ChatRequest,
    ): Flow<AppStreamEvent> = channelFlow {
        val baseUrl = request.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: com.android.everytalk.BuildConfig.GOOGLE_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: "https://generativelanguage.googleapis.com"
        val url = LlmEndpointResolver.resolve(
            protocol = ModelParameterProtocol.GEMINI,
            apiAddress = baseUrl,
            model = request.model,
        )
        var terminalSent = false
        try {
            client.preparePost(url) {
                url {
                    // Gemini 使用查询参数鉴权。覆盖同名参数，避免用户地址中已有 key 时重复发送。
                    parameters.remove("key")
                    parameters.append("key", request.apiKey)
                    parameters.remove("alt")
                    parameters.append("alt", "sse")
                }
                contentType(ContentType.Application.Json)
                setBody(withContext(Dispatchers.Default) { buildGeminiPayload(request) })
                configureSSERequest()
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val result = NetworkUtils.handleApiError(response.status, response.readErrorTextAtMost(), "Gemini")
                    terminalSent = true
                    send(result.error)
                    send(result.finish)
                    return@execute
                }
                val parsed = parseGeminiSSEStreamWithToolCapture(
                    channel = response.bodyAsChannel(),
                    onToolCall = {},
                    emitEvent = { send(it) },
                )
                parsed.assistantContent?.let { content ->
                    send(
                        AppStreamEvent.ProviderContinuation(
                            protocol = com.android.everytalk.data.DataClass.ModelParameterProtocol.GEMINI.name,
                            payloadJson = content.toString(),
                        )
                    )
                }
                terminalSent = true
                send(AppStreamEvent.Finish(parsed.finishReason))
            }
            if (!terminalSent) send(AppStreamEvent.Finish("turn_complete"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val result = NetworkUtils.handleConnectionError(error, "Gemini")
            send(result.error)
            send(result.finish)
        }
    }

    /** 兼容旧调用名；工具轮次统一由 AgentLoop 推进。 */
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest,
    ): Flow<AppStreamEvent> = streamSingleTurn(client, request)
    
    /**
     * 构建 Gemini API 请求体
     */
    internal fun buildGeminiPayload(request: ChatRequest): String {
        // 首先注入系统提示词（如果消息中没有系统消息，则自动注入）
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
        
        // 提取系统消息内容用于 systemInstruction
        val systemMessages = messagesWithSystemPrompt.filter { it.role == "system" }
        val systemContent = systemMessages.mapNotNull { msg ->
            when (msg) {
                is SimpleTextApiMessage -> msg.content.takeIf { it.isNotBlank() }
                is PartsApiMessage -> msg.parts.filterIsInstance<com.android.everytalk.data.DataClass.ApiContentPart.Text>()
                    .joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
                is AgentAssistantApiMessage -> msg.text.takeIf { it.isNotBlank() }
                is AgentToolResultApiMessage -> null
            }
        }.joinToString("\n\n").piSanitizeSurrogates()
        
        val enableWebSearch = request.useWebSearch == true
        val enableCodeExecution = shouldEnableCodeExecution(request)

        val payload = buildJsonObject {
            // 准备系统指令内容
            var effectiveSystemContent = systemContent
            if (enableCodeExecution) {
                // 强化提示：要求模型务必执行代码
                // Gemini 有时会偷懒只生成代码而不执行，这段提示能显著提高工具调用率
                val enforcementPrompt = "\n\nIMPORTANT: You have access to a code execution tool. When asked to calculate, plot, or solve problems, you MUST use the code execution tool to run the code and show the results/plots, instead of just writing the code."
                effectiveSystemContent = if (effectiveSystemContent.isBlank()) enforcementPrompt.trim() else effectiveSystemContent + enforcementPrompt
            }

            // 添加 systemInstruction（Gemini 原生系统指令字段）
            if (effectiveSystemContent.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", effectiveSystemContent)
                        }
                    }
                }
                Log.i(TAG, "📝 已注入系统指令 (${effectiveSystemContent.length} 字符)")
            }

            put(
                "contents",
                PiGeminiMessageAdapter.buildContents(
                    messagesWithSystemPrompt.filterNot { it.role == "system" },
                    request,
                ),
            )
            
            // 添加生成配置（包含 thinkingConfig）
            putJsonObject("generationConfig") {
                request.generationConfig?.let { config ->
                    config.temperature?.let { put("temperature", it) }
                    config.topP?.let { put("topP", it) }
                    config.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    
                    // 添加 thinkingConfig 支持思考过程
                    // 🔥 修复：当启用 code_execution 时，强制禁用 thinkingConfig，避免参数冲突导致 400 INVALID_ARGUMENT
                    // Thinking 模式和 Code Execution 模式在某些模型/API版本下可能互斥
                    if (!enableCodeExecution) {
                        config.thinkingConfig?.let { thinkingConfig ->
                            putJsonObject("thinkingConfig") {
                                thinkingConfig.includeThoughts?.let { put("includeThoughts", it) }
                                thinkingConfig.thinkingBudget?.let { put("thinkingBudget", it) }
                                thinkingConfig.thinkingLevel?.let { put("thinkingLevel", it) }
                            }
                        }
                    } else {
                         Log.i(TAG, "🚫 已禁用 Thinking Config (与 Code Execution 互斥)")
                    }
                }
            }

            put("safetySettings", AiContentSafetyPolicy.geminiSafetySettings())
            
            // 添加工具（Web 搜索、代码执行、MCP 工具等）
            val mcpTools = PromptCachePolicy.normalizeTools(request.tools).orEmpty()
            val hasMcpTools = mcpTools.isNotEmpty()
            runCatching {
                Log.d(
                    TAG,
                    "Prompt prefix system=${PromptCachePolicy.systemFingerprint(messagesWithSystemPrompt)} " +
                        "profile=${PromptCachePolicy.toolProfile(mcpTools)} " +
                        "tools=${PromptCachePolicy.toolSchemaHash(mcpTools).take(16)}",
                )
            }
            if (enableWebSearch || enableCodeExecution || hasMcpTools) {
                putJsonArray("tools") {
                    // Google Search 工具 - 使用 Gemini 原生 google_search (REST API 标准)
                    if (enableWebSearch) {
                        addJsonObject { putJsonObject("google_search") {} }
                        Log.i(TAG, "🔍 启用 Google Search 工具 (google_search)")
                    }
                    // 代码执行工具
                    if (enableCodeExecution) {
                        // 🔥 修复：Gemini REST API 使用 snake_case (code_execution)
                        addJsonObject { putJsonObject("code_execution") {} }
                        Log.i(TAG, "💻 启用代码执行工具")
                    }
                    // MCP 工具 - 转换为 Gemini functionDeclarations 格式
                    if (hasMcpTools) {
                        addJsonObject {
                            putJsonArray("functionDeclarations") {
                                mcpTools.forEach { tool ->
                                    val toolMap = tool as? Map<*, *> ?: return@forEach
                                    val functionMap = toolMap["function"] as? Map<*, *> ?: return@forEach
                                    val name = functionMap["name"] as? String ?: return@forEach
                                    val description = functionMap["description"] as? String ?: ""
                                    val parameters = functionMap["parameters"]
                                    
                                    addJsonObject {
                                        put("name", name)
                                        put("description", description)
                                        // parametersJsonSchema 接受完整 JSON Schema，保留可选字段、
                                        // anyOf 和 additionalProperties，避免有损转换后改变工具含义。
                                        if (parameters != null) put("parametersJsonSchema", convertToJsonElement(parameters))
                                    }
                                    Log.d(TAG, "🔧 添加 MCP 工具: $name")
                                }
                            }
                        }
                        Log.i(TAG, "🔧 注入 ${mcpTools.size} 个 MCP 工具到 functionDeclarations")
                    }
                }
                if (hasMcpTools) {
                    geminiFunctionCallingMode(request.toolChoice)?.let { mode ->
                        putJsonObject("toolConfig") {
                            putJsonObject("functionCallingConfig") { put("mode", mode) }
                        }
                    }
                }
            }
        }
        return PiGeminiMessageAdapter.normalizePayload(payload).toString()
    }

    /** Pi Google Adapter 的 toolChoice 映射，只接受三种官方模式。 */
    private fun geminiFunctionCallingMode(toolChoice: Any?): String? = when ((toolChoice as? String)?.lowercase()) {
        "auto" -> "AUTO"
        "none" -> "NONE"
        "any", "required" -> "ANY"
        else -> null
    }
    
    /**
     * 判断是否应该启用代码执行工具
     */
    private fun shouldEnableCodeExecution(request: ChatRequest): Boolean {
        // 如果显式启用，返回 true
        if (request.enableCodeExecution == true) return true
        // 如果显式禁用，返回 false
        if (request.enableCodeExecution == false) return false
        
        // 自动检测：基于用户意图关键词
        val intentKeywords = listOf(
            "计算", "求解", "运行代码", "执行代码", "画图", "绘制", "plot",
            "matplotlib", "数据分析", "统计", "csv", "pandas", "numpy",
            "calculate", "compute", "run code", "execute", "draw", "chart",
            "可视化", "visualization", "seaborn", "scipy"
        )
        
        val userText = extractLastUserText(request)?.lowercase() ?: return false
        return intentKeywords.any { it in userText }
    }
    
    /**
     * 提取最后一条用户消息的文本
     */
    private fun extractLastUserText(request: ChatRequest): String? {
        val lastUserMessage = request.messages.lastOrNull { it.role == "user" } ?: return null
        return when (lastUserMessage) {
            is SimpleTextApiMessage -> lastUserMessage.content
            is PartsApiMessage -> {
                lastUserMessage.parts.filterIsInstance<com.android.everytalk.data.DataClass.ApiContentPart.Text>()
                    .firstOrNull()?.text
            }
            else -> null
        }
    }
    
    private fun convertToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) ->
                if (k is String) put(k, convertToJsonElement(v))
            }
        }
        is List<*> -> buildJsonArray {
            value.forEach { add(convertToJsonElement(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }

    /**
     * 解析结果，用于在工具循环中传递信息
     */
    private data class ParseResult(
        val hasToolCalls: Boolean,
        val fullText: String,
        /** 原样保留模型 content，下一轮需要其中的 thoughtSignature。 */
        val assistantContent: JsonObject? = null,
        val finishReason: String = "stop",
    )

    private fun parseGeminiTokenUsage(usage: JsonObject): TokenUsage? {
        val totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.longOrNull
        val parsed = TokenUsage(
            inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.longOrNull,
            outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.longOrNull,
            reasoningTokens = usage["thoughtsTokenCount"]?.jsonPrimitive?.longOrNull,
            cachedInputTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.longOrNull,
            totalTokens = totalTokens,
            isFinal = totalTokens != null,
            source = TokenUsageSource.GEMINI,
        )
        return parsed.takeIf {
            it.inputTokens != null || it.outputTokens != null || it.reasoningTokens != null ||
                it.cachedInputTokens != null || it.totalTokens != null
        }
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun parseGeminiSSEStreamWithToolCapture(
        channel: ByteReadChannel,
        onToolCall: (GeminiToolCall) -> Unit,
        emitEvent: suspend (AppStreamEvent) -> Unit
    ): ParseResult {
        val boundedChannel = BoundedSseLineReader(channel)
        val lineBuffer = StringBuilder()
        val fullText = StringBuilder()
        val fullReasoning = StringBuilder()
        var lineCount = 0
        var eventCount = 0
        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false
        var hasToolCalls = false
        var safetyBlocked = false
        var rawFinishReason: String? = null
        var latestAssistantContent: JsonObject? = null
        val seenToolCallIds = mutableSetOf<String>()
        val thinkRouter = ThinkTagStreamRouter()
        
        try {
            Log.d(TAG, "开始解析 SSE 流（支持工具捕获）...")
            
            while (true) {
                val line = boundedChannel.readLine() ?: break
                lineCount++
                
                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            if (chunk.equals("[DONE]", ignoreCase = true)) break
                            
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject
                                ProviderSafetyResponse.geminiBlockReason(jsonChunk)?.let { reason ->
                                    safetyBlocked = true
                                    emitEvent(ProviderSafetyResponse.error(reason))
                                }
                                if (safetyBlocked) break

                                NativeWebSearchResultExtractor.extract(jsonChunk)
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { sources ->
                                        emitEvent(AppStreamEvent.WebSearchResults(sources))
                                    }
                                (jsonChunk["usageMetadata"] as? JsonObject)
                                    ?.let(::parseGeminiTokenUsage)
                                    ?.let { usage -> emitEvent(AppStreamEvent.Usage(usage)) }
                                jsonChunk["candidates"]?.jsonArray?.firstOrNull()?.let { candidate ->
                                    val candidateObj = candidate.jsonObject
                                    val candidateContent = candidateObj["content"] as? JsonObject
                                    candidateContent?.let { content ->
                                        latestAssistantContent = mergeGeminiAssistantContent(
                                            current = latestAssistantContent,
                                            incoming = content,
                                        )
                                    }
                                    candidateContent?.get("parts")?.jsonArray?.forEach { part ->
                                        val partObj = part.jsonObject
                                        val thoughtSignature = partObj["thoughtSignature"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                        
                                        val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                                        val textContent = partObj["text"]?.jsonPrimitive?.contentOrNull
                                        
                                        if (isThought && (!textContent.isNullOrEmpty() || thoughtSignature != null)) {
                                            if (!reasoningStarted) reasoningStarted = true
                                            fullReasoning.append(textContent.orEmpty())
                                            emitEvent(AppStreamEvent.Reasoning(textContent.orEmpty(), thoughtSignature))
                                        } else if (thoughtSignature != null && textContent != null) {
                                            if (reasoningStarted && !reasoningFinished) {
                                                emitEvent(AppStreamEvent.ReasoningFinish(null))
                                                reasoningFinished = true
                                            }
                                            if (!contentStarted) contentStarted = true
                                            eventCount++
                                            fullText.append(textContent)
                                            emitEvent(AppStreamEvent.Content(textContent, thoughtSignature = thoughtSignature))
                                        } else if (!textContent.isNullOrEmpty()) {
                                            val routed = thinkRouter.feed(textContent)
                                            for (routedChunk in routed) {
                                                if (routedChunk.isReasoning) {
                                                    if (!reasoningStarted) reasoningStarted = true
                                                    fullReasoning.append(routedChunk.text)
                                                    emitEvent(AppStreamEvent.Reasoning(routedChunk.text))
                                                } else {
                                                    if (reasoningStarted && !reasoningFinished) {
                                                        emitEvent(AppStreamEvent.ReasoningFinish(null))
                                                        reasoningFinished = true
                                                    }
                                                    if (!contentStarted) contentStarted = true
                                                    eventCount++
                                                    fullText.append(routedChunk.text)
                                                    emitEvent(AppStreamEvent.Content(routedChunk.text, null, null))
                                                }
                                            }
                                        }
                                        
                                        partObj["functionCall"]?.jsonObject?.let { fcObj ->
                                            val name = fcObj["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                                            val args = fcObj["args"]?.jsonObject ?: JsonObject(emptyMap())
                                            hasToolCalls = true
                                            // Gemini 3 要求 FunctionResponse 原样带回服务端 FunctionCall.id。
                                            // 旧模型可能没有 id，本地 ID 只供 AgentLoop 关联，不能伪装成服务端 ID 回传。
                                            val providedId = fcObj["id"]?.jsonPrimitive?.contentOrNull
                                                ?.takeIf(String::isNotBlank)
                                            // Pi 会替换缺失或重复的 ID，避免同一批 ToolResult 互相覆盖。
                                            val toolCallId = providedId
                                                ?.takeIf(seenToolCallIds::add)
                                                ?: generateSequence {
                                                    "$LOCAL_TOOL_CALL_ID_PREFIX${UUID.randomUUID()}"
                                                }.first(seenToolCallIds::add)
                                            onToolCall(GeminiToolCall(toolCallId, name, args))
                                            emitEvent(AppStreamEvent.ToolCall(
                                                id = toolCallId,
                                                name = name,
                                                argumentsObj = args,
                                                thoughtSignature = thoughtSignature,
                                            ))
                                            Log.i(TAG, "🔧 捕获 functionCall: $name")
                                        }
                                    }
                                    
                                    candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                                        rawFinishReason = reason
                                        Log.d(TAG, "Finish reason: $reason")
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "解析数据块失败", e)
                                throw e
                            }
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotEmpty() && !data.equals("[DONE]", ignoreCase = true)) {
                            lineBuffer.append(data)
                        } else if (data.equals("[DONE]", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            if (safetyBlocked) {
                return ParseResult(hasToolCalls = false, fullText = "")
            }
            
            // 冲刷 thinkRouter 剩余内容
            val routerRemaining = thinkRouter.flush()
            for (routedChunk in routerRemaining) {
                if (routedChunk.isReasoning) {
                    if (!reasoningStarted) reasoningStarted = true
                    fullReasoning.append(routedChunk.text)
                    emitEvent(AppStreamEvent.Reasoning(routedChunk.text))
                } else {
                    if (reasoningStarted && !reasoningFinished) {
                        emitEvent(AppStreamEvent.ReasoningFinish(null))
                        reasoningFinished = true
                    }
                    fullText.append(routedChunk.text)
                    emitEvent(AppStreamEvent.Content(routedChunk.text, null, null))
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SSE 流解析错误", e)
            throw e
        }

        if (rawFinishReason == null) {
            throw IllegalStateException("Gemini stream ended before finishReason")
        }

        val completedText = fullText.toString()
        if (completedText.isNotEmpty() && !hasToolCalls) {
            emitEvent(AppStreamEvent.ContentFinal(completedText))
        }

        val mappedFinishReason = mapGeminiFinishReason(rawFinishReason, hasToolCalls)
        if (mappedFinishReason == "error") {
            emitEvent(
                AppStreamEvent.Error(
                    message = "Gemini 响应异常终止：$rawFinishReason",
                    code = rawFinishReason.lowercase(),
                    type = "provider_error",
                ),
            )
        }

        return ParseResult(
            hasToolCalls = hasToolCalls,
            fullText = completedText,
            assistantContent = latestAssistantContent,
            finishReason = mappedFinishReason,
        )
    }

    /** 与 Pi Google Adapter 的 stopReason 映射一致。 */
    private fun mapGeminiFinishReason(rawReason: String?, hasToolCalls: Boolean): String =
        when (rawReason?.uppercase()) {
            "MAX_TOKENS" -> "length"
            "STOP" -> if (hasToolCalls) "tool_use" else "stop"
            else -> "error"
        }

    /**
     * Gemini 会把同一条 model content 分成多个 SSE candidate。
     * parts 按到达顺序合并，完整保留 thoughtSignature 和 functionCall 等协议字段。
     */
    private fun mergeGeminiAssistantContent(
        current: JsonObject?,
        incoming: JsonObject,
    ): JsonObject {
        val role = incoming["role"] ?: current?.get("role") ?: JsonPrimitive("model")
        val parts = (current?.get("parts") as? JsonArray).orEmpty().toMutableList()
        (incoming["parts"] as? JsonArray).orEmpty().forEach { incomingPart ->
            val incomingObject = incomingPart as? JsonObject
            val previousObject = parts.lastOrNull() as? JsonObject
            if (incomingObject != null && previousObject != null &&
                incomingObject.isGeminiTextDelta() && previousObject.isGeminiTextDelta() &&
                incomingObject.isThoughtPart() == previousObject.isThoughtPart()
            ) {
                // Google 可能只在首个分片发送签名。连续同类文本按 Pi 的 currentBlock 规则合并，
                // 新的非空签名覆盖旧值，缺失签名则保留旧值。
                parts[parts.lastIndex] = JsonObject(previousObject.toMutableMap().apply {
                    put(
                        "text",
                        JsonPrimitive(
                            previousObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() +
                                incomingObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ),
                    )
                    incomingObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotEmpty)
                        ?.let { put("thoughtSignature", JsonPrimitive(it)) }
                    if (incomingObject.isThoughtPart()) put("thought", JsonPrimitive(true))
                })
            } else {
                parts += incomingPart
            }
        }
        return buildJsonObject {
            put("role", role)
            put("parts", JsonArray(parts))
        }
    }

    /** 只有恰好一个 text data 字段的 Part 才能合并；签名-only Part 必须保持独立。 */
    private fun JsonObject.isGeminiTextDelta(): Boolean =
        PART_DATA_FIELDS.count { field -> this[field]?.let { it !is JsonNull } == true } == 1 &&
            this["text"]?.let { it !is JsonNull } == true

    private fun JsonObject.isThoughtPart(): Boolean =
        this["thought"]?.jsonPrimitive?.booleanOrNull == true

}

private data class GeminiToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

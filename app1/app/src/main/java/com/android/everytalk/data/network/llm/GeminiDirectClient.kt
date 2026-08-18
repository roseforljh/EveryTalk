package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
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
            .normalizeAgentToolHistory()
        
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
        }.joinToString("\n\n")
        
        val enableWebSearch = request.useWebSearch == true
        val enableCodeExecution = shouldEnableCodeExecution(request)

        return buildJsonObject {
            // 转换消息格式
            // 🔥 修复：合并连续的相同角色消息，防止 Gemini API 报错 400 (INVALID_ARGUMENT)
            // Gemini 要求 user 和 model 必须交替出现，不能有连续的 user 或 model
            val mergedMessages = mutableListOf<com.android.everytalk.data.DataClass.AbstractApiMessage>()
            
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

            messagesWithSystemPrompt.forEach { message ->
                if (message.role == "system") return@forEach // 跳过系统消息（已处理或降级）

                val lastMsg = mergedMessages.lastOrNull()
                val currentRole = if (message.role == "assistant") "model" else message.role
                val lastRole = if (lastMsg?.role == "assistant") "model" else lastMsg?.role

                if (lastMsg != null && currentRole == lastRole) {
                    if (message is AgentAssistantApiMessage || message is AgentToolResultApiMessage ||
                        lastMsg is AgentAssistantApiMessage || lastMsg is AgentToolResultApiMessage
                    ) {
                        mergedMessages.add(message)
                        return@forEach
                    }
                    // 合并到上一条消息
                    val mergedParts = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart>()
                    
                    // 提取上一条消息的内容
                    when (lastMsg) {
                        is SimpleTextApiMessage -> mergedParts.add(com.android.everytalk.data.DataClass.ApiContentPart.Text(lastMsg.content))
                        is PartsApiMessage -> mergedParts.addAll(lastMsg.parts)
                        else -> Unit
                    }
                    
                    // 提取当前消息的内容
                    when (message) {
                        is SimpleTextApiMessage -> mergedParts.add(com.android.everytalk.data.DataClass.ApiContentPart.Text(message.content))
                        is PartsApiMessage -> mergedParts.addAll(message.parts)
                        else -> Unit
                    }
                    
                    // 替换上一条消息为合并后的 PartsApiMessage
                    mergedMessages[mergedMessages.lastIndex] = PartsApiMessage(
                        id = lastMsg.id, // 保持 ID 不变
                        role = lastMsg.role,
                        parts = mergedParts,
                        name = lastMsg.name
                    )
                } else {
                    mergedMessages.add(message)
                }
            }

            put("contents", buildGeminiContents(mergedMessages, request.localProviderContinuation))
            
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
            }
        }.toString()
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

    private fun stripDataUriPrefix(value: String): String {
        if (!value.startsWith("data:", ignoreCase = true)) return value
        val marker = ";base64,"
        val markerIndex = value.indexOf(marker, ignoreCase = true)
        return if (markerIndex >= 0) value.substring(markerIndex + marker.length) else value
    }
    
    private fun isLocalGeminiToolCallId(id: String): Boolean = id.startsWith(LOCAL_TOOL_CALL_ID_PREFIX)

    /**
     * 按 Gemini 官方协议组装工具历史。
     * 当前工具回合原样回传服务端 Content；缺少签名的旧工具回合整组移除。
     */
    private fun buildGeminiContents(
        messages: List<AbstractApiMessage>,
        continuation: ProviderTurnContinuation?,
    ): JsonArray {
        val latestAssistant = messages.filterIsInstance<AgentAssistantApiMessage>().lastOrNull()
        val nativeAssistant = continuation
            ?.takeIf { it.protocol == ModelParameterProtocol.GEMINI }
            ?.takeIf { it.assistantMessageId == null || it.assistantMessageId == latestAssistant?.id }
            ?.payloadJson
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
            ?.takeIf { content ->
                latestAssistant != null && content.matchesGeminiAssistant(latestAssistant)
            }
        val nativeAssistantId = latestAssistant?.id?.takeIf { nativeAssistant != null }
        val legacyAssistantId = latestAssistant
            ?.takeIf { assistant ->
                continuation == null &&
                    assistant.toolCalls.isNotEmpty() &&
                    assistant.toolCalls.all { isLocalGeminiToolCallId(it.id) }
            }
            ?.id
        val protocolAssistantId = nativeAssistantId ?: legacyAssistantId
        val protocolCallIds = latestAssistant
            ?.takeIf { it.id == protocolAssistantId }
            ?.toolCalls
            ?.mapTo(mutableSetOf()) { it.id }
            .orEmpty()

        val contents = mutableListOf<JsonObject>()
        var messageIndex = 0
        while (messageIndex < messages.size) {
            val message = messages[messageIndex]
            if (message is AgentToolResultApiMessage) {
                val results = messages.drop(messageIndex)
                    .takeWhile { it is AgentToolResultApiMessage }
                    .filterIsInstance<AgentToolResultApiMessage>()
                if (results.all { it.toolCallId in protocolCallIds }) {
                    appendGeminiContent(contents, geminiFunctionResponseContent(results))
                }
                messageIndex += results.size
                continue
            }

            val content: JsonObject? = when {
                message is AgentAssistantApiMessage && message.id == nativeAssistantId -> checkNotNull(nativeAssistant)
                message is AgentAssistantApiMessage &&
                    message.toolCalls.isNotEmpty() && message.id != legacyAssistantId -> null
                else -> message.toGeminiContent(message.id == legacyAssistantId)
            }
            content?.let { appendGeminiContent(contents, it) }
            messageIndex++
        }
        return JsonArray(contents)
    }

    /** 原生 Content 只允许替换同一个工具回合，名称和服务端 ID 都要完全对应。 */
    private fun JsonObject.matchesGeminiAssistant(assistant: AgentAssistantApiMessage): Boolean {
        if (assistant.toolCalls.isEmpty()) return false
        if (this["role"]?.jsonPrimitive?.contentOrNull != "model") return false
        val nativeCalls = (this["parts"] as? JsonArray).orEmpty().mapNotNull { part ->
            (part as? JsonObject)?.get("functionCall") as? JsonObject
        }
        if (nativeCalls.size != assistant.toolCalls.size) return false
        return nativeCalls.zip(assistant.toolCalls).all { (nativeCall, call) ->
            val nativeName = nativeCall["name"]?.jsonPrimitive?.contentOrNull
            val nativeId = nativeCall["id"]?.jsonPrimitive?.contentOrNull
            nativeName == call.name && if (isLocalGeminiToolCallId(call.id)) {
                nativeId.isNullOrBlank()
            } else {
                nativeId == call.id
            }
        }
    }

    private fun AbstractApiMessage.toGeminiContent(useLegacyFunctionCalls: Boolean): JsonObject = buildJsonObject {
        put("role", if (role == "assistant") "model" else role)
        putJsonArray("parts") {
            when (this@toGeminiContent) {
                is SimpleTextApiMessage -> content.takeIf(String::isNotEmpty)?.let { text ->
                    addJsonObject { put("text", text) }
                }
                is PartsApiMessage -> parts.forEach { part ->
                    when (part) {
                        is com.android.everytalk.data.DataClass.ApiContentPart.Text ->
                            addJsonObject { put("text", part.text) }
                        is com.android.everytalk.data.DataClass.ApiContentPart.InlineData -> addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", part.mimeType)
                                put("data", part.base64Data)
                            }
                        }
                        is com.android.everytalk.data.DataClass.ApiContentPart.FileUri ->
                            addJsonObject { put("text", "[Image: ${part.uri}]") }
                    }
                }
                is AgentAssistantApiMessage -> {
                    reasoning.takeIf(String::isNotBlank)?.let { addJsonObject { put("text", it) } }
                    text.takeIf(String::isNotBlank)?.let { addJsonObject { put("text", it) } }
                    if (useLegacyFunctionCalls) toolCalls.forEach { call ->
                        addJsonObject {
                            putJsonObject("functionCall") {
                                put("name", call.name)
                                put("args", call.arguments)
                            }
                        }
                    }
                }
                is AgentToolResultApiMessage -> error("工具结果应按连续批次处理")
            }
        }
    }

    private fun geminiFunctionResponseContent(results: List<AgentToolResultApiMessage>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") {
            results.forEach { result ->
                addJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", result.toolName)
                        result.toolCallId.takeUnless(::isLocalGeminiToolCallId)?.let { put("id", it) }
                        putJsonObject("response") {
                            if (result.isError) put("error", result.content)
                            else put("result", result.content)
                        }
                    }
                }
            }
        }
    }

    /** 只合并普通文本 Content，带签名或工具协议的 Part 必须保持原位置。 */
    private fun appendGeminiContent(contents: MutableList<JsonObject>, content: JsonObject) {
        val parts = content["parts"] as? JsonArray ?: return
        if (parts.isEmpty()) return
        val previous = contents.lastOrNull()
        if (
            previous != null &&
            previous["role"] == content["role"] &&
            previous.isMergeableGeminiTextContent() &&
            content.isMergeableGeminiTextContent()
        ) {
            contents[contents.lastIndex] = buildJsonObject {
                put("role", checkNotNull(content["role"]))
                putJsonArray("parts") {
                    (previous["parts"] as? JsonArray).orEmpty().forEach(::add)
                    parts.forEach(::add)
                }
            }
        } else {
            contents += content
        }
    }

    private fun JsonObject.isMergeableGeminiTextContent(): Boolean =
        (this["parts"] as? JsonArray).orEmpty().all { part ->
            val objectPart = part as? JsonObject ?: return@all false
            objectPart["functionCall"] == null &&
                objectPart["functionResponse"] == null &&
                objectPart["thoughtSignature"] == null
        }
    
    /**
     * 解析结果，用于在工具循环中传递信息
     */
    private data class ParseResult(
        val hasToolCalls: Boolean,
        val fullText: String,
        /** 原样保留模型 content，下一轮需要其中的 thoughtSignature。 */
        val assistantContent: JsonObject? = null,
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
        var latestAssistantContent: JsonObject? = null
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
                                        
                                        val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                                        val textContent = partObj["text"]?.jsonPrimitive?.contentOrNull
                                        
                                        if (isThought && !textContent.isNullOrEmpty()) {
                                            if (!reasoningStarted) reasoningStarted = true
                                            fullReasoning.append(textContent)
                                            emitEvent(AppStreamEvent.Reasoning(textContent))
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
                                            val toolCallId = fcObj["id"]?.jsonPrimitive?.contentOrNull
                                                ?.takeIf(String::isNotBlank)
                                                ?: "$LOCAL_TOOL_CALL_ID_PREFIX${UUID.randomUUID()}"
                                            onToolCall(GeminiToolCall(toolCallId, name, args))
                                            emitEvent(AppStreamEvent.ToolCall(
                                                id = toolCallId,
                                                name = name,
                                                argumentsObj = args
                                            ))
                                            Log.i(TAG, "🔧 捕获 functionCall: $name")
                                        }
                                    }
                                    
                                    candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
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

        val completedText = fullText.toString()
        if (completedText.isNotEmpty() && !hasToolCalls) {
            emitEvent(AppStreamEvent.ContentFinal(completedText))
        }

        return ParseResult(
            hasToolCalls = hasToolCalls,
            fullText = completedText,
            assistantContent = latestAssistantContent,
        )
    }

    /**
     * Gemini 会把同一条 model content 分成多个 SSE candidate。
     * parts 按到达顺序合并，完整保留 thoughtSignature 和 functionCall 等协议字段。
     */
    private fun mergeGeminiAssistantContent(
        current: JsonObject?,
        incoming: JsonObject,
    ): JsonObject {
        if (current == null) return incoming
        val role = incoming["role"] ?: current["role"] ?: JsonPrimitive("model")
        val parts = buildJsonArray {
            (current["parts"] as? JsonArray)?.forEach(::add)
            (incoming["parts"] as? JsonArray)?.forEach(::add)
        }
        return buildJsonObject {
            put("role", role)
            put("parts", parts)
        }
    }

}

private data class GeminiToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

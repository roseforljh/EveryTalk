package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.network.NetworkUtils.configureSSERequest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

object GeminiDirectClient {
    private const val TAG = "GeminiDirectClient"
    private const val MAX_TOOL_LOOPS = 5
    
    private var mcpToolExecutor: (suspend (String, JsonObject) -> JsonElement)? = null
    
    fun setMcpToolExecutor(executor: (suspend (String, JsonObject) -> JsonElement)?) {
        mcpToolExecutor = executor
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
        try {
            Log.i(TAG, "🔄 启动 Gemini 直连模式")
            
            val baseUrl = request.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?: com.android.everytalk.BuildConfig.GOOGLE_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
                ?: "https://generativelanguage.googleapis.com"
            val model = request.model.trim()
            val url = "$baseUrl/v1beta/models/$model:streamGenerateContent?key=${request.apiKey}&alt=sse"
            
            Log.d(TAG, "直连 URL: ${url.substringBefore("?key=")}")
            
            val conversationHistory = mutableListOf<JsonObject>()
            var currentRequest = request
            var loopCount = 0
            
            while (loopCount < MAX_TOOL_LOOPS) {
                loopCount++
                Log.i(TAG, "🔄 开始循环 #$loopCount, 历史记录数: ${conversationHistory.size}")
                val payload = buildGeminiPayloadWithHistory(currentRequest, conversationHistory)
                Log.d(TAG, "请求 payload 长度: ${payload.length}")
                
                var pendingToolCalls = mutableListOf<Pair<String, JsonObject>>()
                var hasContent = false
                
                var parseResult: ParseResult? = null
                
                client.preparePost(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    configureSSERequest()
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { null }
                        val result = NetworkUtils.handleApiError(response.status, errorBody, "Gemini")
                        send(result.error)
                        send(result.finish)
                        return@execute
                    }

                    Log.i(TAG, "✅ Gemini 直连成功 (loop $loopCount)，开始接收流")

                    parseResult = parseGeminiSSEStreamWithToolCapture(
                        channel = response.bodyAsChannel(),
                        onToolCall = { toolName, args ->
                            Log.d(TAG, "回调捕获工具: $toolName")
                            pendingToolCalls.add(toolName to args)
                        },
                        emitEvent = { event ->
                            when (event) {
                                is AppStreamEvent.Content -> {
                                    hasContent = true
                                    Log.d(TAG, "收到内容: ${event.text.take(50)}...")
                                }
                                is AppStreamEvent.ToolCall -> {
                                    Log.d(TAG, "流中收到 ToolCall: ${event.name}")
                                }
                                is AppStreamEvent.Error -> {
                                    Log.e(TAG, "收到错误事件: ${event.message}")
                                }
                                else -> {}
                            }
                            send(event)
                            kotlinx.coroutines.yield()
                        }
                    )
                }
                
                Log.i(TAG, "循环 #$loopCount 结束, pendingToolCalls=${pendingToolCalls.size}, hasContent=$hasContent, hasToolCalls=${parseResult?.hasToolCalls}")
                
                if (pendingToolCalls.isEmpty()) {
                    Log.i(TAG, "🏁 没有待处理的工具调用，结束循环")
                    break
                }
                
                if (mcpToolExecutor == null) {
                    Log.w(TAG, "⚠️ 有工具调用但没有设置执行器，跳过")
                    break
                }
                
                Log.i(TAG, "🔧 处理 ${pendingToolCalls.size} 个工具调用")
                
                val toolResponses = mutableListOf<JsonObject>()
                for ((toolName, args) in pendingToolCalls) {
                    try {
                        val result = withContext(NonCancellable) {
                            Log.d(TAG, "🔧 开始执行工具: $toolName")
                            mcpToolExecutor!!.invoke(toolName, args)
                        }
                        Log.i(TAG, "🔧 工具 $toolName 执行成功: ${result.toString().take(100)}")
                        toolResponses.add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", toolName)
                                put("response", buildJsonObject {
                                    put("result", result)
                                })
                            })
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "🔧 工具 $toolName 执行失败", e)
                        toolResponses.add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", toolName)
                                put("response", buildJsonObject {
                                    put("error", e.message ?: "Unknown error")
                                })
                            })
                        })
                    }
                }
                
                conversationHistory.add(buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        pendingToolCalls.forEach { (name, args) ->
                            addJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("name", name)
                                    put("args", args)
                                })
                            }
                        }
                    }
                })
                
                conversationHistory.add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        toolResponses.forEach { add(it) }
                    }
                })
                
                pendingToolCalls.clear()
            }
            
            Log.i(TAG, "🏁 工具循环完成，发送 Finish 事件")
            send(AppStreamEvent.Finish("stop"))
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = NetworkUtils.handleConnectionError(e, "Gemini")
            send(result.error)
            send(result.finish)
        }
        
        return@channelFlow
    }
    
    /**
     * 构建 Gemini API 请求体
     */
    private fun buildGeminiPayload(request: ChatRequest): String {
        // 首先注入系统提示词（如果消息中没有系统消息，则自动注入）
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
        
        // 提取系统消息内容用于 systemInstruction
        val systemMessages = messagesWithSystemPrompt.filter { it.role == "system" }
        val systemContent = systemMessages.mapNotNull { msg ->
            when (msg) {
                is SimpleTextApiMessage -> msg.content.takeIf { it.isNotBlank() }
                is PartsApiMessage -> msg.parts.filterIsInstance<com.android.everytalk.data.DataClass.ApiContentPart.Text>()
                    .joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
                else -> null
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
                    // 合并到上一条消息
                    val mergedParts = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart>()
                    
                    // 提取上一条消息的内容
                    when (lastMsg) {
                        is SimpleTextApiMessage -> mergedParts.add(com.android.everytalk.data.DataClass.ApiContentPart.Text(lastMsg.content))
                        is PartsApiMessage -> mergedParts.addAll(lastMsg.parts)
                    }
                    
                    // 提取当前消息的内容
                    when (message) {
                        is SimpleTextApiMessage -> mergedParts.add(com.android.everytalk.data.DataClass.ApiContentPart.Text(message.content))
                        is PartsApiMessage -> mergedParts.addAll(message.parts)
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

            putJsonArray("contents") {
                mergedMessages.forEach { message ->
                    addJsonObject {
                        put("role", if (message.role == "assistant") "model" else message.role)
                        putJsonArray("parts") {
                            // 处理 content
                            when (message) {
                                is SimpleTextApiMessage -> {
                                    if (message.content.isNotEmpty()) {
                                        addJsonObject {
                                            put("text", message.content)
                                        }
                                    }
                                }
                                is PartsApiMessage -> {
                                    message.parts.forEach { part ->
                                        when (part) {
                                            is com.android.everytalk.data.DataClass.ApiContentPart.Text -> {
                                                addJsonObject {
                                                    put("text", part.text)
                                                }
                                            }
                                            is com.android.everytalk.data.DataClass.ApiContentPart.InlineData -> {
                                                addJsonObject {
                                                    putJsonObject("inlineData") {
                                                        put("mimeType", part.mimeType)
                                                        put("data", part.base64Data)
                                                    }
                                                }
                                            }
                                            is com.android.everytalk.data.DataClass.ApiContentPart.FileUri -> {
                                                addJsonObject {
                                                    put("text", "[Image: ${part.uri}]")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
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
                            }
                        }
                    } else {
                         Log.i(TAG, "🚫 已禁用 Thinking Config (与 Code Execution 互斥)")
                    }
                }
            }
            
            // 添加工具（Web 搜索、代码执行、MCP 工具等）
            val hasMcpTools = !request.tools.isNullOrEmpty()
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
                                request.tools!!.forEach { tool ->
                                    val toolMap = tool as? Map<*, *> ?: return@forEach
                                    val functionMap = toolMap["function"] as? Map<*, *> ?: return@forEach
                                    val name = functionMap["name"] as? String ?: return@forEach
                                    val description = functionMap["description"] as? String ?: ""
                                    val parameters = functionMap["parameters"]
                                    
                                    addJsonObject {
                                        put("name", name)
                                        put("description", description)
                                        if (parameters != null) {
                                            put("parameters", convertToolParametersForGemini(parameters))
                                        }
                                    }
                                    Log.d(TAG, "🔧 添加 MCP 工具: $name")
                                }
                            }
                        }
                        Log.i(TAG, "🔧 注入 ${request.tools!!.size} 个 MCP 工具到 functionDeclarations")
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
    
    // Gemini 完全不支持、需要直接移除的字段
    private val REMOVE_SCHEMA_KEYS = setOf(
        "propertyNames", "dependentSchemas", "dependentRequired", 
        "unevaluatedProperties", "unevaluatedItems", 
        "contentMediaType", "contentEncoding",
        "\$schema", "\$id", "\$anchor", "\$dynamicRef",
        "\$dynamicAnchor", "\$vocabulary", "\$comment",
        "not", "if", "then", "else"
    )
    
    // 需要特殊转换的字段
    private val CONVERT_SCHEMA_KEYS = setOf(
        "const", "anyOf", "any_of", "oneOf", "one_of", 
        "allOf", "all_of", "\$ref", "\$defs"
    )
    
    /**
     * 转换 JSON Schema 为 Gemini 兼容格式
     * - const -> enum
     * - anyOf/oneOf -> 取第一个有效类型
     * - allOf -> 合并所有 schema
     * - $ref/$defs -> 尝试内联解析
     */
    private fun sanitizeSchemaForGemini(element: JsonElement, defs: JsonObject? = null): JsonElement = when (element) {
        is JsonObject -> transformSchemaObject(element, defs ?: element["\$defs"]?.jsonObjectOrNull)
        is JsonArray -> buildJsonArray { element.forEach { add(sanitizeSchemaForGemini(it, defs)) } }
        else -> element
    }
    
    private fun transformSchemaObject(obj: JsonObject, defs: JsonObject?): JsonObject {
        val addedKeys = mutableSetOf<String>()
        
        return buildJsonObject {
            // 先处理 $ref 引用
            obj["\$ref"]?.jsonPrimitive?.contentOrNull?.let { ref ->
                val resolved = resolveRef(ref, defs)
                if (resolved != null) {
                    val sanitized = sanitizeSchemaForGemini(resolved, defs).jsonObject
                    sanitized.forEach { (k, v) -> put(k, v); addedKeys.add(k) }
                    return@buildJsonObject
                }
            }
            
            // 处理 const -> enum
            obj["const"]?.let { constVal ->
                put("enum", buildJsonArray { add(constVal) })
                addedKeys.add("enum")
                Log.d(TAG, "转换 const -> enum: $constVal")
            }
            
            // 处理 anyOf/oneOf -> 取第一个有效 schema
            val anyOfKey = listOf("anyOf", "any_of", "oneOf", "one_of").firstOrNull { obj.containsKey(it) }
            if (anyOfKey != null) {
                obj[anyOfKey]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull?.let { first ->
                    val sanitized = sanitizeSchemaForGemini(first, defs).jsonObject
                    sanitized.forEach { (k, v) -> 
                        if (k !in addedKeys) { put(k, v); addedKeys.add(k) }
                    }
                    Log.d(TAG, "转换 $anyOfKey -> 使用第一个 schema")
                }
            }
            
            // 处理 allOf -> 合并所有 schema
            val allOfKey = listOf("allOf", "all_of").firstOrNull { obj.containsKey(it) }
            if (allOfKey != null) {
                obj[allOfKey]?.jsonArrayOrNull?.forEach { item ->
                    item.jsonObjectOrNull?.let { subSchema ->
                        val sanitized = sanitizeSchemaForGemini(subSchema, defs).jsonObject
                        sanitized.forEach { (k, v) -> 
                            if (k !in addedKeys) { put(k, v); addedKeys.add(k) }
                        }
                    }
                }
                Log.d(TAG, "转换 $allOfKey -> 合并 schema")
            }
            
            // 复制其他字段，递归处理嵌套
            obj.forEach { (key, value) ->
                if (key in REMOVE_SCHEMA_KEYS || key in CONVERT_SCHEMA_KEYS || key in addedKeys) return@forEach
                put(key, sanitizeSchemaForGemini(value, defs))
            }
        }
    }
    
    private fun resolveRef(ref: String, defs: JsonObject?): JsonElement? {
        if (defs == null) return null
        // 格式: "#/$defs/SomeName" 或 "#/definitions/SomeName"
        val parts = ref.removePrefix("#/").split("/")
        if (parts.size >= 2 && (parts[0] == "\$defs" || parts[0] == "definitions")) {
            return defs[parts[1]]
        }
        return null
    }
    
    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject
    
    private val JsonElement.jsonArrayOrNull: JsonArray?
        get() = this as? JsonArray
    
    private fun convertToolParametersForGemini(value: Any?): JsonElement {
        val rawElement = convertToJsonElement(value)
        return sanitizeSchemaForGemini(rawElement)
    }
    
    private fun buildGeminiPayloadWithHistory(
        request: ChatRequest, 
        toolHistory: List<JsonObject>
    ): String {
        val basePayload = Json.parseToJsonElement(buildGeminiPayload(request)).jsonObject.toMutableMap()
        
        if (toolHistory.isNotEmpty()) {
            val existingContents = basePayload["contents"]?.jsonArray?.toMutableList() ?: mutableListOf()
            toolHistory.forEach { historyItem ->
                existingContents.add(historyItem)
            }
            basePayload["contents"] = JsonArray(existingContents)
            Log.d(TAG, "添加 ${toolHistory.size} 条工具历史到 contents")
        }
        
        return JsonObject(basePayload).toString()
    }
    
    /**
     * 解析结果，用于在工具循环中传递信息
     */
    private data class ParseResult(
        val hasToolCalls: Boolean,
        val fullText: String
    )
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun parseGeminiSSEStreamWithToolCapture(
        channel: ByteReadChannel,
        onToolCall: (String, JsonObject) -> Unit,
        emitEvent: suspend (AppStreamEvent) -> Unit
    ): ParseResult {
        val lineBuffer = StringBuilder()
        var fullText = ""
        var fullReasoning = ""
        var lineCount = 0
        var eventCount = 0
        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false
        var hasToolCalls = false
        
        try {
            Log.d(TAG, "开始解析 SSE 流（支持工具捕获）...")
            
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                lineCount++
                
                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            if (chunk.equals("[DONE]", ignoreCase = true)) break
                            
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject
                                jsonChunk["candidates"]?.jsonArray?.firstOrNull()?.let { candidate ->
                                    val candidateObj = candidate.jsonObject
                                    candidateObj["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                                        val partObj = part.jsonObject
                                        
                                        val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                                        val textContent = partObj["text"]?.jsonPrimitive?.contentOrNull
                                        
                                        if (isThought && !textContent.isNullOrEmpty()) {
                                            if (!reasoningStarted) reasoningStarted = true
                                            fullReasoning += textContent
                                            emitEvent(AppStreamEvent.Reasoning(textContent))
                                        } else if (!textContent.isNullOrEmpty()) {
                                            if (reasoningStarted && !reasoningFinished) {
                                                emitEvent(AppStreamEvent.ReasoningFinish(null))
                                                reasoningFinished = true
                                            }
                                            if (!contentStarted) contentStarted = true
                                            eventCount++
                                            fullText += textContent
                                            emitEvent(AppStreamEvent.Content(textContent, null, null))
                                        }
                                        
                                        partObj["functionCall"]?.jsonObject?.let { fcObj ->
                                            val name = fcObj["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                                            val args = fcObj["args"]?.jsonObject ?: JsonObject(emptyMap())
                                            hasToolCalls = true
                                            onToolCall(name, args)
                                            emitEvent(AppStreamEvent.ToolCall(
                                                id = "fc_${System.currentTimeMillis()}",
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
                            } catch (e: Exception) {
                                Log.e(TAG, "解析数据块失败", e)
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
            
            // 只有当没有工具调用时才发送 ContentFinal
            // 有工具调用时，等待整个循环完成后再发送最终内容
            if (fullText.isNotEmpty() && !hasToolCalls) {
                emitEvent(AppStreamEvent.ContentFinal(fullText))
            }
            
            // 关键修复：不在这里发送 Finish 事件
            // Finish 事件由 streamChatDirect 在整个工具循环完成后统一发送
            // 这样就不会触发 ApiHandler 的 CancellationException
            
        } catch (e: Exception) {
            Log.e(TAG, "SSE 流解析错误", e)
            emitEvent(AppStreamEvent.Error(e.message ?: "Unknown error"))
            // 错误时也不发送 Finish，让上层处理
        }
        
        return ParseResult(hasToolCalls = hasToolCalls, fullText = fullText)
    }
    
    /**
     * 解析 Gemini SSE 流 - 实时流式输出，支持思考过程
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun parseGeminiSSEStream(channel: ByteReadChannel): Flow<AppStreamEvent> = channelFlow {
        val lineBuffer = StringBuilder()
        var fullText = ""
        var fullReasoning = ""
        var lineCount = 0
        var eventCount = 0
        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false
        
        // 用于存储 Grounding Metadata 以便最后添加引用
        var capturedGroundingMetadata: JsonObject? = null
        
        try {
            Log.d(TAG, "开始解析 SSE 流（支持思考过程）...")
            
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                lineCount++
                
                if (lineCount <= 10) {
                    Log.d(TAG, "读取行 #$lineCount: '${line.take(100)}'")
                }
                
                when {
                    line.isEmpty() -> {
                        // 空行表示一个 SSE 事件结束，解析累积的 data
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            Log.d(TAG, "处理数据块 (长度=${chunk.length}): '${chunk.take(100)}'")
                            
                            if (chunk.equals("[DONE]", ignoreCase = true)) {
                                Log.d(TAG, "收到 [DONE] 标记")
                                break
                            }
                            
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject
                                
                                // 解析 candidates - 和后端一样的逻辑
                                jsonChunk["candidates"]?.jsonArray?.firstOrNull()?.let { candidate ->
                                    val candidateObj = candidate.jsonObject
                                    
                                    // 提取内容（包括思考和正文）
                                    candidateObj["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                                        val partObj = part.jsonObject
                                        
                                        // 检查是否为思考内容（thought 字段）
                                        val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                                        val textContent = partObj["text"]?.jsonPrimitive?.contentOrNull
                                        
                                        if (isThought && !textContent.isNullOrEmpty()) {
                                            // 这是思考内容
                                            if (!reasoningStarted) {
                                                reasoningStarted = true
                                                Log.i(TAG, "🧠 开始接收思考过程")
                                            }
                                            fullReasoning += textContent
                                            send(AppStreamEvent.Reasoning(textContent))
                                            Log.d(TAG, "🧠 思考片段 (${textContent.length}字): ${textContent.take(50)}...")
                                        } else if (!textContent.isNullOrEmpty()) {
                                            // 这是正文内容
                                            // 如果之前有思考过程但还没结束，先发送思考结束事件
                                            if (reasoningStarted && !reasoningFinished) {
                                                send(AppStreamEvent.ReasoningFinish(null))
                                                reasoningFinished = true
                                                Log.i(TAG, "🧠 思考过程结束，开始输出正文")
                                            }
                                            
                                            if (!contentStarted) {
                                                contentStarted = true
                                            }
                                            
                                            eventCount++
                                            fullText += textContent
                                            // 立即发送 Content 事件
                                            send(AppStreamEvent.Content(textContent, null, null))
                                            Log.i(TAG, "✓ 流式输出 #$eventCount (${textContent.length}字): ${textContent.take(50)}...")
                                        }
                                        
                                        // 检查代码执行相关内容
                                        partObj["executableCode"]?.jsonObject?.let { codeObj ->
                                            val code = codeObj["code"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val language = codeObj["language"]?.jsonPrimitive?.contentOrNull ?: "python"
                                            if (code.isNotEmpty()) {
                                                send(AppStreamEvent.CodeExecutable(code, language))
                                                Log.i(TAG, "💻 收到可执行代码 ($language): ${code.take(50)}...")
                                            }
                                        }
                                        
                                        partObj["codeExecutionResult"]?.jsonObject?.let { resultObj ->
                                            val output = resultObj["output"]?.jsonPrimitive?.contentOrNull
                                            val outcome = resultObj["outcome"]?.jsonPrimitive?.contentOrNull
                                            val outcomeNormalized = when (outcome?.uppercase()) {
                                                "OUTCOME_OK", "SUCCESS", "OK" -> "success"
                                                else -> if (outcome != null) "error" else null
                                            }
                                            send(AppStreamEvent.CodeExecutionResult(output, outcomeNormalized, null))
                                            Log.i(TAG, "💻 代码执行结果: outcome=$outcomeNormalized, output=${output?.take(50)}...")
                                        }
                                        
                                        // 检查内联图片（代码执行生成的图表）
                                        partObj["inlineData"]?.jsonObject?.let { inlineData ->
                                            val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull
                                            val data = inlineData["data"]?.jsonPrimitive?.contentOrNull
                                            if (mimeType != null && data != null && mimeType.startsWith("image/")) {
                                                val imageUrl = "data:$mimeType;base64,$data"
                                                send(AppStreamEvent.CodeExecutionResult(null, "success", imageUrl))
                                                Log.i(TAG, "📊 收到代码执行生成的图片: $mimeType")
                                            }
                                        }
                                        
                                        partObj["functionCall"]?.jsonObject?.let { fcObj ->
                                            val name = fcObj["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                                            val args = fcObj["args"]?.jsonObject ?: JsonObject(emptyMap())
                                            send(AppStreamEvent.ToolCall(
                                                id = "fc_${System.currentTimeMillis()}",
                                                name = name,
                                                argumentsObj = args
                                            ))
                                            Log.i(TAG, "🔧 收到 functionCall: $name, args=$args")
                                        }
                                    }
                                    
                                    // 检查搜索结果（grounding metadata）
                                    candidateObj["groundingMetadata"]?.jsonObject?.let { groundingMeta ->
                                        capturedGroundingMetadata = groundingMeta // 捕获元数据供后续处理引用
                                        
                                        groundingMeta["groundingChunks"]?.jsonArray?.let { chunks ->
                                            val webResults = chunks.mapNotNull { chunkElement ->
                                                try {
                                                    val chunkObj = chunkElement.jsonObject
                                                    val webObj = chunkObj["web"]?.jsonObject ?: return@mapNotNull null
                                                    com.android.everytalk.data.DataClass.WebSearchResult(
                                                        index = 0,
                                                        title = webObj["title"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                                                        href = webObj["uri"]?.jsonPrimitive?.contentOrNull ?: "#",
                                                        snippet = ""
                                                    )
                                                } catch (e: Exception) {
                                                    null
                                                }
                                            }
                                            if (webResults.isNotEmpty()) {
                                                send(AppStreamEvent.WebSearchResults(webResults))
                                                Log.i(TAG, "🔍 收到 ${webResults.size} 个搜索结果")
                                            }
                                        }
                                    }
                                    
                                    // 检查结束原因
                                    candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                                        Log.d(TAG, "Finish reason: $reason")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "解析数据块失败: '$chunk'", e)
                            }
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith(":") -> {
                        // SSE 注释/心跳，忽略
                        Log.d(TAG, "SSE 注释行（忽略）: '$line'")
                    }
                    line.startsWith("data:") -> {
                        // 累积 data 内容
                        val dataContent = line.substring(5).trim()
                        Log.d(TAG, "SSE data 行: '$dataContent'")
                        if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                        lineBuffer.append(dataContent)
                    }
                    line.startsWith("event:") -> {
                        // 事件类型
                        Log.d(TAG, "SSE event 行: '${line.substring(6).trim()}'")
                    }
                    else -> {
                        // 其他格式，尝试直接解析 JSON
                        val trimmed = line.trim()
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            Log.d(TAG, "非 SSE 格式行（JSON 回退）: '$trimmed'")
                        }
                    }
                }
            }
            
            Log.i(TAG, "SSE 流读取完成，共 $lineCount 行，$eventCount 个事件")
            
            // 如果思考过程开始了但还没结束，发送结束事件
            if (reasoningStarted && !reasoningFinished) {
                send(AppStreamEvent.ReasoningFinish(null))
                Log.i(TAG, "🧠 思考过程结束（流结束时）")
            }
            
            // 发送最终结果
            if (fullText.isNotEmpty()) {
                // 尝试添加引用
                val finalText = if (capturedGroundingMetadata != null) {
                    try {
                        addCitations(fullText, capturedGroundingMetadata!!)
                    } catch (e: Exception) {
                        Log.e(TAG, "添加引用失败", e)
                        fullText
                    }
                } else {
                    fullText
                }
                
                send(AppStreamEvent.ContentFinal(finalText, null, null))
                Log.d(TAG, "发送最终内容，总长度: ${finalText.length} (原长度: ${fullText.length})")
            }
            send(AppStreamEvent.Finish("stop"))
            Log.d(TAG, "流结束")
            
        } catch (e: Exception) {
            Log.e(TAG, "解析 Gemini 流失败", e)
            send(AppStreamEvent.Error("流解析失败: ${e.message}", null))
        }
        
        // 结束解析子流（返回即可完成 channelFlow）
        return@channelFlow
    }

    /**
     * 根据 Grounding Metadata 为文本添加行内引用
     * 参考官方 Python/JS 示例实现
     */
    private fun addCitations(text: String, metadata: JsonObject): String {
        val supports = metadata["groundingSupports"]?.jsonArray ?: return text
        val chunks = metadata["groundingChunks"]?.jsonArray ?: return text
        
        if (supports.isEmpty() || chunks.isEmpty()) return text
        
        val sb = StringBuilder(text)
        
        // 按照 endIndex 降序排序，以便从后往前插入，避免索引偏移
        val sortedSupports = supports.mapNotNull { supportElement ->
            try {
                val support = supportElement.jsonObject
                val segment = support["segment"]?.jsonObject
                val endIndex = segment?.get("endIndex")?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val chunkIndices = support["groundingChunkIndices"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.intOrNull
                } ?: emptyList()
                
                if (chunkIndices.isEmpty()) return@mapNotNull null
                
                Triple(endIndex, chunkIndices, support)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.first }
        
        // 插入引用
        for ((endIndex, chunkIndices, _) in sortedSupports) {
            if (endIndex > sb.length) continue // 索引越界保护
            
            val citationLinks = chunkIndices.mapNotNull { idx ->
                if (idx >= 0 && idx < chunks.size) {
                    val chunk = chunks[idx].jsonObject
                    val uri = chunk["web"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                    if (uri != null) {
                        // 生成 Markdown 链接格式的引用: [n](url)
                        // 或者仅生成数字: [n] - 取决于 UI 需求，这里使用 Markdown 链接以便点击
                        "[${idx + 1}]($uri)" // 注意：这里使用 1-based 索引，符合用户习惯
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            
            if (citationLinks.isNotEmpty()) {
                val citationString = " " + citationLinks.joinToString(" ")
                sb.insert(endIndex, citationString)
            }
        }
        
        return sb.toString()
    }
}
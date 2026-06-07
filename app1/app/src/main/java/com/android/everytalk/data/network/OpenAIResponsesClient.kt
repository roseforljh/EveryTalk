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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object OpenAIResponsesClient {
    private const val TAG = "OpenAIResponsesClient"
    private const val MAX_TOOL_LOOPS = 50

    private var mcpToolExecutor: (suspend (String, JsonObject, suspend (String?) -> Unit) -> JsonElement)? = null

    fun setMcpToolExecutor(executor: (suspend (String, JsonObject, suspend (String?) -> Unit) -> JsonElement)?) {
        mcpToolExecutor = executor
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatResponses(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
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

            val conversationInput = mutableListOf<JsonElement>()
            var loopCount = 0

            while (loopCount < MAX_TOOL_LOOPS) {
                loopCount++
                Log.i(TAG, "循环 #$loopCount, 历史输入数: ${conversationInput.size}")

                val payload = buildResponsesPayload(request, conversationInput)

                var pendingToolCalls = mutableListOf<ResponsesToolCallInfo>()
                var parseResult: ResponsesParseResult? = null

                client.preparePost(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
                    configureSSERequest()
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { null }
                        val result = NetworkUtils.handleApiError(response.status, errorBody, "OpenAI-Responses")
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
                            send(event)
                            kotlinx.coroutines.yield()
                        }
                    )
                }

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

                        val result = withContext(NonCancellable) {
                            mcpToolExecutor!!.invoke(toolInfo.name, argsJson) { status ->
                                send(AppStreamEvent.ExecutionStatusUpdate(status))
                            }
                        }
                        Log.i(TAG, "工具 ${toolInfo.name} 执行成功")

                        val webResults = WebSearchToolResultExtractor.extract(toolInfo.name, result)
                        if (webResults.isNotEmpty()) {
                            send(AppStreamEvent.WebSearchResults(webResults))
                        }

                        // Responses API 工具结果格式：function_call_output item
                        conversationInput.add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", toolInfo.callId)
                            put("output", result.toString())
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "工具 ${toolInfo.name} 执行失败", e)
                        conversationInput.add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", toolInfo.callId)
                            put("output", "Error: ${e.message ?: "Unknown error"}")
                        })
                    }
                }

                pendingToolCalls.clear()
            }

            Log.i(TAG, "工具循环完成，发送 Finish")
            send(AppStreamEvent.Finish("stop"))

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = NetworkUtils.handleConnectionError(e, "OpenAI-Responses")
            send(result.error)
            send(result.finish)
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
        val reasoningContent: String = ""
    )

    private fun buildResponsesPayload(
        request: ChatRequest,
        previousOutput: List<JsonElement>
    ): String {
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)

        return buildJsonObject {
            put("model", request.model)
            put("stream", true)

            // instructions: 提取 system message
            val systemMsg = messagesWithSystemPrompt.firstOrNull { it.role == "system" }
            if (systemMsg != null) {
                val systemText = when (systemMsg) {
                    is SimpleTextApiMessage -> systemMsg.content
                    is PartsApiMessage -> systemMsg.parts.filterIsInstance<ApiContentPart.Text>()
                        .joinToString("\n") { it.text }
                    else -> ""
                }
                if (systemText.isNotBlank()) {
                    put("instructions", systemText)
                }
            }

            // input: 非 system 消息 + 之前的工具输出
            putJsonArray("input") {
                messagesWithSystemPrompt.filter { it.role != "system" }.forEach { message ->
                    when (message) {
                        is SimpleTextApiMessage -> {
                            addJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            }
                        }
                        is PartsApiMessage -> {
                            val parts = message.parts.filterNot {
                                it is ApiContentPart.FileUri && it.mimeType == "qwen-file-id"
                            }
                            val allText = parts.all { it is ApiContentPart.Text }
                            if (allText) {
                                addJsonObject {
                                    put("role", message.role)
                                    put("content", parts.joinToString("\n") {
                                        (it as ApiContentPart.Text).text
                                    })
                                }
                            } else {
                                addJsonObject {
                                    put("role", message.role)
                                    putJsonArray("content") {
                                        parts.forEach { part ->
                                            when (part) {
                                                is ApiContentPart.Text -> {
                                                    addJsonObject {
                                                        put("type", "input_text")
                                                        put("text", part.text)
                                                    }
                                                }
                                                is ApiContentPart.InlineData -> {
                                                    val dataUri = "data:${part.mimeType};base64,${part.base64Data}"
                                                    addJsonObject {
                                                        put("type", "input_image")
                                                        putJsonObject("image_url") {
                                                            put("url", dataUri)
                                                        }
                                                    }
                                                }
                                                is ApiContentPart.FileUri -> {
                                                    addJsonObject {
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
                        else -> {
                            addJsonObject {
                                put("role", message.role)
                                put("content", "")
                            }
                        }
                    }
                }

                // 追加之前的工具调用输出
                previousOutput.forEach { add(it) }
            }

            // 参数
            request.generationConfig?.let { config ->
                config.temperature?.let { put("temperature", it) }
                config.topP?.let { put("top_p", it) }
                config.maxOutputTokens?.let { put("max_output_tokens", it) }
            }

            // reasoning 参数（Responses API 专用）
            putJsonObject("reasoning") {
                put("effort", "medium")
                put("summary", "auto")
            }

            // 工具注入 (Responses API 扁平格式)
            request.tools?.let { tools ->
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

    private suspend fun parseResponsesSSEStream(
        channel: ByteReadChannel,
        onToolCall: (ResponsesToolCallInfo) -> Unit,
        emitEvent: suspend (AppStreamEvent) -> Unit
    ): ResponsesParseResult {
        val lineBuffer = StringBuilder()
        var fullText = ""
        var fullReasoningContent = ""
        var hasToolCalls = false

        // 聚合 function_call 参数
        val toolCallsMap = mutableMapOf<String, Triple<String, String, StringBuilder>>() // callId -> (callId, name, args)

        try {
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            if (chunk == "[DONE]") break
                            try {
                                val event = Json.parseToJsonElement(chunk).jsonObject
                                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: ""

                                when (type) {
                                    "response.output_text.delta" -> {
                                        val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (delta.isNotEmpty()) {
                                            fullText += delta
                                            emitEvent(AppStreamEvent.Content(delta, null, ""))
                                        }
                                    }
                                    "response.output_text.done" -> {
                                        // 文本完成，不需要额外处理
                                    }
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
                                            fullReasoningContent += delta
                                            emitEvent(AppStreamEvent.Reasoning(delta))
                                        }
                                    }
                                    "response.reasoning_summary_text.done" -> {
                                        emitEvent(AppStreamEvent.ReasoningFinish(null))
                                    }
                                    "response.output_item.added" -> {
                                        val item = event["item"]?.jsonObject
                                        val itemType = item?.get("type")?.jsonPrimitive?.contentOrNull
                                        if (itemType == "function_call") {
                                            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                ?: item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (callId.isNotBlank() && !toolCallsMap.containsKey(callId)) {
                                                toolCallsMap[callId] = Triple(callId, name, StringBuilder())
                                            }
                                            hasToolCalls = true
                                        } else if (itemType == "reasoning") {
                                            // 推理输出项开始
                                            val summary = item["summary"]
                                            if (summary is JsonArray) {
                                                summary.forEach { s ->
                                                    val text = (s as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                                                    if (!text.isNullOrEmpty()) {
                                                        fullReasoningContent += text
                                                        emitEvent(AppStreamEvent.Reasoning(text))
                                                    }
                                                }
                                                emitEvent(AppStreamEvent.ReasoningFinish(null))
                                            }
                                        }
                                    }
                                    "response.completed" -> {
                                        // 响应完成
                                    }
                                    "error" -> {
                                        val errorMsg = event["message"]?.jsonPrimitive?.contentOrNull
                                            ?: event["error"]?.let { errEl ->
                                                (errEl as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                                            } ?: "Unknown error"
                                        emitEvent(AppStreamEvent.Error(errorMsg, null))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "解析 Responses SSE 事件失败: ${e.message}")
                            }
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val dataContent = line.substring(5).trim()
                        if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
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

            if (fullText.isNotEmpty() && !hasToolCalls) {
                emitEvent(AppStreamEvent.ContentFinal(fullText, null, null))
            }

        } catch (e: Exception) {
            emitEvent(AppStreamEvent.Error("Responses 流解析失败: ${NetworkUtils.sanitizeMessage(e.message)}", null))
        }

        return ResponsesParseResult(hasToolCalls = hasToolCalls, fullText = fullText, reasoningContent = fullReasoningContent)
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
}

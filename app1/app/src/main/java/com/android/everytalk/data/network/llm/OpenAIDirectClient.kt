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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import android.util.Base64
import kotlinx.coroutines.CancellationException
import java.util.UUID

object OpenAIDirectClient {
    private const val TAG = "OpenAIDirectClient"
    private const val MAX_QWEN_UPLOAD_FILE_BYTES = 10L * 1024L * 1024L

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun streamSingleTurn(
        client: HttpClient,
        request: ChatRequest,
    ): Flow<AppStreamEvent> = channelFlow {
        var effectiveRequest = if (request.model.contains("qwen-long", ignoreCase = true)) {
            handleQwenUploads(client, request)
        } else {
            request
        }
        val baseUrl = effectiveRequest.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: "https://api.openai.com"
        val url = LlmEndpointResolver.resolve(
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = baseUrl,
            model = effectiveRequest.model,
        )
        var terminalSent = false
        try {
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                setBody(buildOpenAIPayload(effectiveRequest))
                header(HttpHeaders.Authorization, "Bearer ${effectiveRequest.apiKey}")
                configureSSERequest()
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val result = NetworkUtils.handleApiError(
                        response.status,
                        response.readErrorTextAtMost(),
                        "OpenAI",
                    )
                    terminalSent = true
                    send(result.error)
                    send(result.finish)
                    return@execute
                }
                val parsed = parseOpenAISSEStreamWithTools(
                    channel = response.bodyAsChannel(),
                    onToolCall = {},
                    emitEvent = { send(it) },
                )
                if (!terminalSent) {
                    terminalSent = true
                    send(AppStreamEvent.Finish(parsed.stopReason))
                }
            }
            if (!terminalSent) send(AppStreamEvent.Finish("stop"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val result = NetworkUtils.handleConnectionError(error, "OpenAI")
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
     * 构建 OpenAI API 请求体
     */
    internal fun buildOpenAIPayload(request: ChatRequest): String {
        // 首先注入系统提示词（如果消息中没有系统消息，则自动注入）
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
            .let { messages ->
                PiMessageTransformer.transform(messages, request) { id, _ ->
                    PiOpenAIChatMessageAdapter.normalizeToolCallId(id, request.provider)
                }
            }
        val normalizedTools = PromptCachePolicy.normalizeTools(request.tools)
        val officialOpenAIEndpoint = PromptCachePolicy.isOfficialOpenAIEndpoint(resolvedOpenAIApiAddress(request))
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
        Log.i(TAG, "📝 已注入系统提示词，消息数量: ${messagesWithSystemPrompt.size}")

        fun audioFormatFromMime(mime: String): String {
            return when (mime.lowercase()) {
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/aac" -> "aac"
                "audio/ogg" -> "ogg"
                "audio/opus" -> "opus"
                "audio/flac" -> "flac"
                "audio/3gpp" -> "3gp"
                "audio/amr" -> "amr"
                "audio/aiff" -> "aiff"
                "audio/x-m4a" -> "m4a"
                "audio/midi" -> "midi"
                "audio/webm" -> "webm"
                else -> mime.substringAfter("/", mime)
            }
        }

        fun isAudioMime(mime: String?) = mime?.lowercase()?.startsWith("audio/") == true
        fun isVideoMime(mime: String?) = mime?.lowercase()?.startsWith("video/") == true

        return buildJsonObject {
            put("model", request.model)
            put("stream", true)
            if (officialOpenAIEndpoint) {
                // Pi 的官方 OpenAI 适配器明确关闭服务端存储，并始终请求最终 usage。
                put("store", false)
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            promptCacheKey?.let { cacheKey ->
                put("prompt_cache_key", cacheKey)
            }

            // 转换消息（支持多模态：text + image_url(data URI) + input_audio）
            putJsonArray("messages") {
                // 1. 提取 Qwen 文件 ID
                val qwenFileIds = mutableListOf<String>()
                messagesWithSystemPrompt.forEach { msg ->
                    if (msg is PartsApiMessage) {
                        msg.parts.forEach { part ->
                            if (part is ApiContentPart.FileUri && part.mimeType == "qwen-file-id") {
                                qwenFileIds.add(part.uri)
                            }
                        }
                    }
                }

                // 2. 处理消息
                val pendingToolResultImages = mutableListOf<com.android.everytalk.data.DataClass.AgentToolResultContentApiPart.Image>()
                messagesWithSystemPrompt.forEachIndexed { messageIndex, message ->
                    // 如果是系统消息，且是第一个系统消息，则在其后注入文件 ID 系统消息
                    // 或者如果还没有注入过文件 ID，且当前不是系统消息，则在当前消息前注入（针对没有系统消息的情况）
                    
                    // 简化策略：
                    // 1. 如果是系统消息 -> 输出系统消息 -> 输出所有文件 ID 系统消息 (仅一次)
                    // 2. 如果不是系统消息 ->
                    //    如果还没输出过文件 ID (即没有系统消息) -> 输出所有文件 ID 系统消息 -> 输出当前消息
                    //    如果已输出过 -> 输出当前消息

                    // 但由于 messagesWithSystemPrompt 必定包含系统消息（SystemPromptInjector 保证），
                    // 我们只需要在遇到第一个系统消息时，在其后追加文件 ID 消息即可。
                    // 如果有多个系统消息，我们只在第一个后面追加。
                    
                    // 实际上 SystemPromptInjector 会把系统消息放在最前面。
                    
                    when (message) {
                        is SimpleTextApiMessage -> {
                            if (message.role == "system" && qwenFileIds.isNotEmpty()) {
                                // 尝试将 fileid 合并到系统消息中，或者紧跟其后
                                // 官方文档建议单独消息，但为了兼容性，这里保持单独消息策略
                                addJsonObject {
                                    put("role", message.role)
                                    put("content", message.content)
                                }
                                qwenFileIds.forEach { fileId ->
                                    addJsonObject {
                                        put("role", "system")
                                        put("content", "fileid://$fileId")
                                    }
                                }
                                qwenFileIds.clear()
                            } else {
                                addJsonObject {
                                    put("role", message.role)
                                    put("content", message.content)
                                }
                            }
                        }
                        is com.android.everytalk.data.DataClass.PartsApiMessage -> {
                            // 过滤掉 qwen-file-id 部分，因为它们已经作为系统消息注入了
                            val parts = message.parts.filterNot {
                                it is ApiContentPart.FileUri && it.mimeType == "qwen-file-id"
                            }

                            if (parts.isEmpty()) {
                                // 如果过滤后为空（例如只包含文件的消息），且不是系统消息，可能需要跳过或发空内容
                                // 但为了保持对话结构，发送空内容比较安全，或者如果原意是"请分析此文件"，用户通常会附带文本。
                                // 如果用户只发了文件，前端通常会生成一个"Sent a file"之类的文本，或者这里发空字符串。
                                if (message.role != "system") {
                                     addJsonObject {
                                        put("role", message.role)
                                        put("content", "")
                                    }
                                }
                            } else {
                                // Check if we can simplify to string content (preferred by some models like qwen-long)
                                val allText = parts.all { it is com.android.everytalk.data.DataClass.ApiContentPart.Text }
                                if (allText) {
                                    val textContent = parts.joinToString("\n") { (it as com.android.everytalk.data.DataClass.ApiContentPart.Text).text }
                                    addJsonObject {
                                        put("role", message.role)
                                        put("content", textContent)
                                    }
                                } else {
                                    addJsonObject {
                                        put("role", message.role)
                                        putJsonArray("content") {
                                            parts.forEach { part ->
                                                when (part) {
                                                    is com.android.everytalk.data.DataClass.ApiContentPart.Text -> {
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", part.text)
                                                        }
                                                    }
                                                    is com.android.everytalk.data.DataClass.ApiContentPart.InlineData -> {
                                                        val mime = part.mimeType
                                                        if (isAudioMime(mime)) {
                                                            // OpenAI-compat input_audio
                                                            addJsonObject {
                                                                put("type", "input_audio")
                                                                putJsonObject("input_audio") {
                                                                    put("data", part.base64Data)
                                                                    put("format", audioFormatFromMime(mime))
                                                                }
                                                            }
                                                        } else if (isVideoMime(mime)) {
                                                            // 视频按后端策略：仍使用 image_url data URI（多数网关接受）
                                                            val dataUri = "data:${mime};base64,${part.base64Data}"
                                                            addJsonObject {
                                                                put("type", "image_url")
                                                                putJsonObject("image_url") {
                                                                    put("url", dataUri)
                                                                }
                                                            }
                                                        } else {
                                                            // 图片/其他 → image_url data URI
                                                            val dataUri = "data:${mime};base64,${part.base64Data}"
                                                            addJsonObject {
                                                                put("type", "image_url")
                                                                putJsonObject("image_url") {
                                                                    put("url", dataUri)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    is com.android.everytalk.data.DataClass.ApiContentPart.FileUri -> {
                                                        // 其他类型的文件引用（非 Qwen ID）
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "[Attachment: ${part.uri}]")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 如果 PartsMessage 也是 system role (不太常见但可能)，也需要注入
                            if (message.role == "system" && qwenFileIds.isNotEmpty()) {
                                qwenFileIds.forEach { fileId ->
                                    addJsonObject {
                                        put("role", "system")
                                        put("content", "fileid://$fileId")
                                    }
                                }
                                qwenFileIds.clear()
                            }
                        }
                        is AgentAssistantApiMessage -> addAgentAssistantMessage(
                            message = message,
                            includeReasoningContent = !officialOpenAIEndpoint,
                        )
                        is AgentToolResultApiMessage -> {
                            pendingToolResultImages += addAgentToolResultMessage(message)
                            val nextIsToolResult = messagesWithSystemPrompt.getOrNull(messageIndex + 1) is AgentToolResultApiMessage
                            if (!nextIsToolResult) {
                                if (pendingToolResultImages.isNotEmpty() && "image" in request.localInputModalities) {
                                    addJsonObject {
                                        put("role", "user")
                                        putJsonArray("content") {
                                            addJsonObject {
                                                put("type", "text")
                                                put("text", "Attached image(s) from tool result:")
                                            }
                                            pendingToolResultImages.forEach { image -> addJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    put(
                                                        "url",
                                                        "data:${image.mimeType};base64,${image.data.substringAfter(";base64,", image.data)}",
                                                    )
                                                }
                                            } }
                                        }
                                    }
                                }
                                pendingToolResultImages.clear()
                            }
                        }
                    }
                }
                
                // 防御性编程：如果遍历完所有消息后 qwenFileIds 仍不为空（例如没有系统消息），则追加到最前（但这很难做到因为是流式写入 array）
                // 或者追加到最后？不，系统消息应该在前。
                // 由于 SystemPromptInjector.smartInjectSystemPrompt 保证了第一条是 system 消息，所以上面的逻辑应该是覆盖了绝大多数情况。
                // 唯一例外是 smartInjectSystemPrompt 返回空列表（不可能）或没有 system 消息（如果 forceInject=false 且检测失败？但 smartInject 默认逻辑会注入）。
                // 假设总有 system 消息。
            }

            // 添加参数
            request.generationConfig?.let { config ->
                config.temperature?.let { put("temperature", it) }
                config.topP?.let { put("top_p", it) }
                config.maxOutputTokens?.let { maxOutputTokens ->
                    val parameterName = if (officialOpenAIEndpoint) {
                        "max_completion_tokens"
                    } else {
                        "max_tokens"
                    }
                    put(parameterName, maxOutputTokens)
                }
            }

            // Qwen 联网搜索支持
            val isQwenSearchEnabled = request.qwenEnableSearch == true
            
            // 尝试直接在顶层注入 Qwen 搜索参数（针对某些非严格的兼容接口或 DashScope 原生行为）
            if (isQwenSearchEnabled) {
                put("enable_search", true)
                putJsonObject("search_options") {
                    put("forced_search", true)
                    put("search_strategy", "max")
                }
            }

            if (isQwenSearchEnabled) {
                 putJsonObject("extra_body") {
                    put("enable_search", true)
                    putJsonObject("search_options") {
                        put("forced_search", true)
                        put("search_strategy", "max")
                    }
                }
            }

            request.customModelParameters.orEmpty().forEach { (name, value) ->
                put(name, anyToJsonElement(value))
            }

            // MCP 工具注入 (OpenAI function calling 格式)
            normalizedTools?.let { tools ->
                if (tools.isNotEmpty()) {
                    Log.d(TAG, "注入 ${tools.size} 个 MCP 工具到请求")
                    putJsonArray("tools") {
                        tools.forEach { toolDef ->
                            add(mapToJsonElement(toolDef))
                        }
                    }
                    put("tool_choice", request.toolChoice?.let(::anyToJsonElement) ?: JsonPrimitive("auto"))
                }
            }
        }.toString()
    }

    /**
     * 处理 Qwen 模型的文件上传
     */
    private suspend fun handleQwenUploads(
        client: HttpClient,
        request: ChatRequest
    ): ChatRequest {
        val newMessages = request.messages.map { msg ->
            if (msg is PartsApiMessage) {
                val newParts = msg.parts.map { part ->
                    if (part is ApiContentPart.InlineData && part.mimeType.startsWith("file_upload_marker|")) {
                        // Format: file_upload_marker|mime|filename
                        val segments = part.mimeType.split("|")
                        val fileName = segments.getOrNull(2) ?: "unknown_file"
                        
                        try {
                            ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit(
                                part.base64Data,
                                maxBytes = MAX_QWEN_UPLOAD_FILE_BYTES,
                            )
                            val bytes = Base64.decode(part.base64Data, Base64.NO_WRAP)
                            val fileId = uploadFileToDashScope(client, request.apiKey, fileName, bytes)
                            Log.i(TAG, "Uploaded $fileName, id=$fileId")
                            
                            ApiContentPart.FileUri(uri = fileId, mimeType = "qwen-file-id")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to upload file for Qwen", e)
                            ApiContentPart.Text("[Upload Failed: ${e.message}]")
                        }
                    } else {
                        part
                    }
                }
                msg.copy(parts = newParts)
            } else {
                msg
            }
        }
        
        return request.copy(messages = newMessages)
    }

    /**
     * 解析 OpenAI SSE 流并支持工具调用
     */
    internal suspend fun parseOpenAISSEStreamWithTools(
        channel: ByteReadChannel,
        onToolCall: (OpenAiToolCallInfo) -> Unit,
        emitEvent: suspend (AppStreamEvent) -> Unit
    ): OpenAIParseResult {
        val boundedChannel = BoundedSseLineReader(channel)
        val lineBuffer = StringBuilder()
        val fullText = StringBuilder()
        val fullReasoningContent = StringBuilder()

        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false
        val thinkRouter = ThinkTagStreamRouter()
        var hasToolCalls = false
        var safetyBlocked = false
        var latestUsage: TokenUsage? = null
        val reasoningDetails = mutableListOf<JsonObject>()
        var rawFinishReason: String? = null
        var sawFinishReason = false

        // 兼容端点可能把 id、name、arguments 拆到不同 chunk，必须按 index 更新同一个调用。
        val toolCallsMap = mutableMapOf<Int, OpenAIStreamingToolCall>()

        try {
            while (true) {
                val line = boundedChannel.readLine() ?: break

                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            if (chunk == "[DONE]") {
                                if (reasoningStarted && !reasoningFinished) {
                                    emitEvent(AppStreamEvent.ReasoningFinish(null))
                                    reasoningFinished = true
                                }
                                break
                            }
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject
                                NativeWebSearchResultExtractor.extract(jsonChunk)
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { sources ->
                                        emitEvent(AppStreamEvent.WebSearchResults(sources))
                                    }
                                (jsonChunk["usage"] as? JsonObject)?.let { usage ->
                                    parseOpenAIChatTokenUsage(usage)?.let { parsedUsage ->
                                        latestUsage = parsedUsage
                                        emitEvent(AppStreamEvent.Usage(parsedUsage))
                                    }
                                }
                                val choicesElement = jsonChunk["choices"]
                                val choice = (choicesElement as? JsonArray)
                                    ?.firstOrNull()
                                    ?.jsonObject

                                if (choice != null) {
                                    val delta = choice["delta"]?.jsonObject

                                    // 处理推理内容
                                    val reasoningText =
                                        delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("thinking")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("thoughts")?.jsonPrimitive?.contentOrNull

                                    if (!reasoningText.isNullOrEmpty()) {
                                        if (!reasoningStarted) reasoningStarted = true
                                        fullReasoningContent.append(reasoningText)
                                        emitEvent(AppStreamEvent.Reasoning(reasoningText))
                                    }

                                    (delta?.get("reasoning_details") as? JsonArray)?.forEach { detail ->
                                        val objectDetail = detail as? JsonObject ?: return@forEach
                                        appendReasoningDetail(reasoningDetails, objectDetail)
                                    }

                                    // 处理正文内容（支持 <think> 标签检测）
                                    val contentText = delta?.get("content")?.jsonPrimitive?.contentOrNull
                                    if (!contentText.isNullOrEmpty()) {
                                        val routed = thinkRouter.feed(contentText)
                                        for (routedChunk in routed) {
                                            if (routedChunk.isReasoning) {
                                                if (!reasoningStarted) reasoningStarted = true
                                                fullReasoningContent.append(routedChunk.text)
                                                emitEvent(AppStreamEvent.Reasoning(routedChunk.text))
                                            } else {
                                                if (reasoningStarted && !reasoningFinished) {
                                                    emitEvent(AppStreamEvent.ReasoningFinish(null))
                                                    reasoningFinished = true
                                                }
                                                if (!contentStarted) contentStarted = true
                                                fullText.append(routedChunk.text)
                                                emitEvent(AppStreamEvent.Content(routedChunk.text, null, ""))
                                            }
                                        }
                                    }

                                    // 处理工具调用 (OpenAI 格式: delta.tool_calls)
                                    val toolCallsElement = delta?.get("tool_calls")
                                    if (toolCallsElement is JsonArray) {
                                        toolCallsElement.forEach { tcElement ->
                                            val tcObj = tcElement.jsonObject
                                            val index = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                            val id = tcObj["id"]?.jsonPrimitive?.contentOrNull
                                            val function = tcObj["function"]?.jsonObject
                                            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                                            val argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""

                                            val state = toolCallsMap.getOrPut(index) { OpenAIStreamingToolCall() }
                                            id?.takeIf(String::isNotBlank)?.let { state.id = it }
                                            name?.takeIf(String::isNotBlank)?.let { state.name = it }
                                            state.arguments.append(argumentsDelta)
                                            hasToolCalls = true
                                        }
                                    }

                                    // 检查 finish_reason
                                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                    if (finishReason != null) {
                                        rawFinishReason = finishReason
                                        sawFinishReason = true
                                    }
                                    if (finishReason == "tool_calls") {
                                        Log.d(TAG, "Finish reason: tool_calls, 准备处理工具调用")
                                    } else if (ProviderSafetyResponse.isSafetyReason(finishReason)) {
                                        safetyBlocked = true
                                        emitEvent(ProviderSafetyResponse.error(finishReason))
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "解析 SSE chunk 失败: ${e.message}")
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
                    line.startsWith(":") -> {
                        // SSE 注释/心跳，忽略
                    }
                }
            }

            if (safetyBlocked) {
                return OpenAIParseResult(
                    hasToolCalls = false,
                    fullText = "",
                    reasoningContent = "",
                    stopReason = "error",
                )
            }

            if (!sawFinishReason) {
                throw IllegalStateException("OpenAI Chat stream ended before a finish_reason")
            }

            // 冲刷 thinkRouter 剩余内容
            val routerRemaining = thinkRouter.flush()
            for (routedChunk in routerRemaining) {
                if (routedChunk.isReasoning) {
                    if (!reasoningStarted) reasoningStarted = true
                    fullReasoningContent.append(routedChunk.text)
                    emitEvent(AppStreamEvent.Reasoning(routedChunk.text))
                } else {
                    if (reasoningStarted && !reasoningFinished) {
                        emitEvent(AppStreamEvent.ReasoningFinish(null))
                        reasoningFinished = true
                    }
                    fullText.append(routedChunk.text)
                    emitEvent(AppStreamEvent.Content(routedChunk.text, null, ""))
                }
            }

            // 处理完成后，发送聚合的工具调用
            if (reasoningDetails.isNotEmpty()) {
                emitEvent(
                    AppStreamEvent.Reasoning(
                        text = "",
                        thoughtSignature = JsonArray(reasoningDetails).toString(),
                        signatureOnlyUpdate = true,
                    ),
                )
            }
            if (toolCallsMap.isNotEmpty()) {
                toolCallsMap.toSortedMap().forEach { (index, state) ->
                    val (id, name, argsBuilder) = state.finalize(index)
                    if (name.isNotBlank()) {
                        val toolInfo = OpenAiToolCallInfo(id, name, argsBuilder.toString())
                        onToolCall(toolInfo)
                        emitEvent(AppStreamEvent.ToolCall(
                            id = id,
                            name = name,
                            argumentsObj = try {
                                Json.parseToJsonElement(argsBuilder.toString()).jsonObject
                            } catch (e: Exception) {
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
        if (reasoningStarted && !reasoningFinished) {
            emitEvent(AppStreamEvent.ReasoningFinish(null))
        }

        return OpenAIParseResult(
            hasToolCalls = hasToolCalls,
            fullText = completedText,
            reasoningContent = completedReasoning,
            usage = latestUsage,
            toolCalls = toolCallsMap.toSortedMap().mapNotNull { (index, state) ->
                val (id, name, argsBuilder) = state.finalize(index)
                name.takeIf(String::isNotBlank)?.let { OpenAiToolCallInfo(id, it, argsBuilder.toString()) }
            },
            stopReason = mapOpenAIChatStopReason(rawFinishReason, hasToolCalls),
        )
    }

    /** 同一流式工具块的聚合状态，id/name 晚到时覆盖空占位。 */
    private data class OpenAIStreamingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun finalize(index: Int): Triple<String, String, StringBuilder> {
            if (id.isBlank()) id = "call_local_${UUID.randomUUID()}_$index"
            return Triple(id, name, arguments)
        }
    }

    /** Pi OpenAI Chat Completions stopReason 映射。 */
    private fun mapOpenAIChatStopReason(reason: String?, hasToolCalls: Boolean): String = when (reason) {
        null -> if (hasToolCalls) "tool_use" else "stop"
        "stop", "end" -> "stop"
        "length" -> "length"
        "function_call", "tool_calls" -> "tool_use"
        "content_filter" -> "error"
        "network_error" -> throw IllegalStateException("Provider finish_reason: network_error")
        else -> throw IllegalStateException("Provider finish_reason: $reason")
    }

    private fun parseOpenAIChatTokenUsage(usage: JsonObject): TokenUsage? {
        val promptDetails = usage["prompt_tokens_details"] as? JsonObject
        val completionDetails = usage["completion_tokens_details"] as? JsonObject
        val parsed = TokenUsage(
            inputTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull,
            outputTokens = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull,
            reasoningTokens = (completionDetails?.get("reasoning_tokens") as? JsonPrimitive)?.longOrNull,
            cachedInputTokens = (promptDetails?.get("cached_tokens") as? JsonPrimitive)?.longOrNull,
            cacheWriteTokens = (promptDetails?.get("cache_write_tokens") as? JsonPrimitive)?.longOrNull,
            totalTokens = (usage["total_tokens"] as? JsonPrimitive)?.longOrNull,
            isFinal = true,
            source = TokenUsageSource.OPENAI_CHAT,
        )
        return parsed.takeIf {
            it.inputTokens != null || it.outputTokens != null || it.reasoningTokens != null ||
                it.cachedInputTokens != null || it.cacheWriteTokens != null || it.totalTokens != null
        }
    }

    /** OpenRouter 等端点会把 reasoning_details 分片发送，按 Pi 规则合并相邻文本和摘要。 */
    private fun appendReasoningDetail(target: MutableList<JsonObject>, detail: JsonObject) {
        val type = detail["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type !in setOf("reasoning.text", "reasoning.summary", "reasoning.encrypted")) return
        val last = target.lastOrNull()
        val field = when (type) {
            "reasoning.text" -> "text"
            "reasoning.summary" -> "summary"
            else -> null
        }
        if (field != null && last?.get("type")?.jsonPrimitive?.contentOrNull == type) {
            target[target.lastIndex] = JsonObject(last.toMutableMap().apply {
                val previous = last[field]?.jsonPrimitive?.contentOrNull.orEmpty()
                val next = detail[field]?.jsonPrimitive?.contentOrNull.orEmpty()
                put(field, JsonPrimitive(previous + next))
                if (get("id") == null) detail["id"]?.let { put("id", it) }
                if (get("format") == null) detail["format"]?.let { put("format", it) }
                if (get("index") == null) detail["index"]?.let { put("index", it) }
                if (get("signature") == null) detail["signature"]?.let { put("signature", it) }
            })
        } else {
            target += detail
        }
    }

    private fun JsonArrayBuilder.addAgentAssistantMessage(
        message: AgentAssistantApiMessage,
        includeReasoningContent: Boolean,
    ) {
        // PiMessageTransformer 已把跨协议 reasoning 降级成 Text，并剥离不可回放签名。
        // 这里只认规范块，不能再从旧 summary 字段把 reasoning 私自塞回目标 Provider。
        val text = message.contentParts
            .filterIsInstance<com.android.everytalk.data.DataClass.AgentAssistantContentApiPart.Text>()
            .filter { it.text.isNotBlank() }
            .joinToString("") { it.text }
        val reasoningBlocks = message.contentParts
            .filterIsInstance<com.android.everytalk.data.DataClass.AgentAssistantContentApiPart.Reasoning>()
        val reasoning = reasoningBlocks
            .filter { !it.redacted && it.text.isNotBlank() }
            .joinToString("\n") { it.text }
        val toolCalls = message.contentParts.mapNotNull { part ->
            (part as? com.android.everytalk.data.DataClass.AgentAssistantContentApiPart.ToolCall)?.call
        }
        if (text.isEmpty() && toolCalls.isEmpty() && (!includeReasoningContent || reasoning.isEmpty())) return

        addJsonObject {
            put("role", "assistant")
            if (text.isNotEmpty()) {
                put("content", text)
            } else if (includeReasoningContent) {
                // 兼容端点沿用旧行为；OpenAI 官方在纯 Tool Call 消息中使用 null。
                put("content", "")
            } else {
                put("content", JsonNull)
            }
            // DeepSeek、Kimi 等 OpenAI 兼容推理模型要求工具下一轮回传 reasoning_content。
            // OpenAI 官方 Chat Completions 没有该字段，发送过去会触发参数校验错误。
            reasoning.takeIf { includeReasoningContent && it.isNotBlank() }
                ?.let { put("reasoning_content", it) }
            reasoningBlocks
                .mapNotNull { part ->
                    part.thoughtSignature?.let { raw ->
                        runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
                    }
                }
                .firstOrNull()
                ?.let { put("reasoning_details", it) }
            if (toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    toolCalls.forEach { call ->
                        addJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun JsonArrayBuilder.addAgentToolResultMessage(
        message: AgentToolResultApiMessage,
    ): List<com.android.everytalk.data.DataClass.AgentToolResultContentApiPart.Image> {
        val blocks = message.canonicalContentBlocks()
        val text = blocks.filterIsInstance<com.android.everytalk.data.DataClass.AgentToolResultContentApiPart.Text>()
            .joinToString("\n") { it.text }
        val images = blocks.filterIsInstance<com.android.everytalk.data.DataClass.AgentToolResultContentApiPart.Image>()
        addJsonObject {
            put("role", "tool")
            put("tool_call_id", message.toolCallId)
            put(
                "content",
                when {
                    text.isNotEmpty() -> text
                    images.isNotEmpty() -> "(see attached image)"
                    else -> "(no tool output)"
                },
            )
        }
        return images
    }
}

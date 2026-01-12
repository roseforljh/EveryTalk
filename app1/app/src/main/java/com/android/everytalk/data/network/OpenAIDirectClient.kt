package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.WebSearchResult
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object OpenAIDirectClient {
    private const val TAG = "OpenAIDirectClient"
    private const val MAX_TOOL_LOOPS = 5

    private var mcpToolExecutor: (suspend (String, JsonObject) -> JsonElement)? = null

    fun setMcpToolExecutor(executor: (suspend (String, JsonObject) -> JsonElement)?) {
        mcpToolExecutor = executor
    }

    private data class SearchHit(val title: String, val href: String, val snippet: String)
    
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
        try {
            Log.i(TAG, "🔄 启动 OpenAI 兼容直连模式")

            var effectiveRequest = request

            if (request.model.contains("qwen-long", ignoreCase = true)) {
                effectiveRequest = handleQwenUploads(client, effectiveRequest) { status ->
                    send(AppStreamEvent.StatusUpdate(status))
                }
            }

            if (request.useWebSearch == true && request.qwenEnableSearch != true) {
                val userQuery = extractLastUserText(request).let { it ?: "" }.trim()

                if (userQuery.isNotBlank()) {
                    val endpoint = (request.customExtraBody?.get("webSearchEndpoint") as? String)?.trim()
                    val customKey = (request.customExtraBody?.get("webSearchKey") as? String)?.trim()
                    val googleCseId = com.android.everytalk.BuildConfig.GOOGLE_CSE_ID
                    val googleApiKey = com.android.everytalk.BuildConfig.GOOGLE_SEARCH_API_KEY

                    var searchResults: List<SearchHit> = emptyList()
                    var searchSource = "None"

                    try {
                        if (!endpoint.isNullOrBlank()) {
                            searchSource = "Custom Endpoint"
                            send(AppStreamEvent.StatusUpdate("Searching web (Custom)..."))
                            searchResults = tryFetchWebSearch(client, endpoint, customKey, userQuery)
                        } else if (googleCseId.isNotBlank() && googleApiKey.isNotBlank()) {
                            searchSource = "Google CSE"
                            send(AppStreamEvent.StatusUpdate("Searching Google..."))
                            val results = WebSearchClient.search(client, userQuery, googleApiKey, googleCseId)
                            searchResults = results.map { SearchHit(it.title, it.href, it.snippet) }
                        } else {
                            send(AppStreamEvent.StatusUpdate("Web search skipped (no configuration)..."))
                        }

                        if (searchResults.isNotEmpty()) {
                            val listForUi = searchResults.mapIndexed { idx, hit ->
                                WebSearchResult(index = idx + 1, title = hit.title, snippet = hit.snippet, href = hit.href)
                            }
                            send(AppStreamEvent.WebSearchResults(listForUi))
                            effectiveRequest = injectSearchResultsIntoRequest(request, userQuery, searchResults)
                            send(AppStreamEvent.StatusUpdate("Answering with search results..."))
                        } else if (searchSource != "None") {
                            send(AppStreamEvent.StatusUpdate("No search results, answering directly..."))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Web search failed ($searchSource), skip injection: ${e.message}")
                        send(AppStreamEvent.StatusUpdate("Search failed, answering directly..."))
                    }
                }
            }

            var baseUrl = effectiveRequest.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
                ?: "https://api.openai.com"

            if (baseUrl.contains("bigmodel.cn") && !baseUrl.contains("/api/paas/v4")) {
                baseUrl = "https://open.bigmodel.cn/api/paas/v4"
            }

            val url = when {
                baseUrl.endsWith("/chat/completions") -> baseUrl
                baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
                else -> "$baseUrl/v1/chat/completions"
            }

            Log.d(TAG, "直连 URL: $url")

            // 会话历史用于工具调用的多轮对话
            val conversationHistory = mutableListOf<JsonObject>()
            var currentRequest = effectiveRequest
            var loopCount = 0

            while (loopCount < MAX_TOOL_LOOPS) {
                loopCount++
                Log.i(TAG, "🔄 开始循环 #$loopCount, 历史记录数: ${conversationHistory.size}")

                val payload = if (conversationHistory.isEmpty()) {
                    buildOpenAIPayload(currentRequest)
                } else {
                    buildOpenAIPayloadWithHistory(currentRequest, conversationHistory)
                }

                var pendingToolCalls = mutableListOf<OpenAiToolCallInfo>()
                var hasContent = false
                var parseResult: OpenAIParseResult? = null

                client.preparePost(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    header(HttpHeaders.Authorization, "Bearer ${currentRequest.apiKey}")
                    configureSSERequest()
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { null }
                        val result = NetworkUtils.handleApiError(response.status, errorBody, "OpenAI")
                        send(result.error)
                        send(result.finish)
                        return@execute
                    }

                    Log.i(TAG, "✅ 直连成功 (loop $loopCount)，开始接收流")

                    parseResult = parseOpenAISSEStreamWithTools(
                        channel = response.bodyAsChannel(),
                        onToolCall = { toolInfo ->
                            Log.d(TAG, "回调捕获工具: ${toolInfo.name}")
                            pendingToolCalls.add(toolInfo)
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

                Log.i(TAG, "循环 #$loopCount 结束, pendingToolCalls=${pendingToolCalls.size}, hasContent=$hasContent")

                if (pendingToolCalls.isEmpty()) {
                    Log.i(TAG, "🏁 没有待处理的工具调用，结束循环")
                    break
                }

                if (mcpToolExecutor == null) {
                    Log.w(TAG, "⚠️ 有工具调用但没有设置执行器，跳过")
                    break
                }

                Log.i(TAG, "🔧 处理 ${pendingToolCalls.size} 个工具调用")

                // 构建 assistant 消息（包含 tool_calls）
                conversationHistory.add(buildJsonObject {
                    put("role", "assistant")
                    put("content", parseResult?.fullText ?: "")
                    putJsonArray("tool_calls") {
                        pendingToolCalls.forEach { toolInfo ->
                            addJsonObject {
                                put("id", toolInfo.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", toolInfo.name)
                                    put("arguments", toolInfo.arguments)
                                }
                            }
                        }
                    }
                })

                // 执行每个工具并构建 tool 消息
                for (toolInfo in pendingToolCalls) {
                    try {
                        val argsJson = try {
                            Json.parseToJsonElement(toolInfo.arguments).jsonObject
                        } catch (e: Exception) {
                            Log.w(TAG, "解析工具参数失败: ${toolInfo.arguments}", e)
                            JsonObject(emptyMap())
                        }

                        val result = withContext(NonCancellable) {
                            Log.d(TAG, "🔧 开始执行工具: ${toolInfo.name}")
                            mcpToolExecutor!!.invoke(toolInfo.name, argsJson)
                        }
                        Log.i(TAG, "🔧 工具 ${toolInfo.name} 执行成功: ${result.toString().take(100)}")

                        conversationHistory.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", toolInfo.id)
                            put("content", result.toString())
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "🔧 工具 ${toolInfo.name} 执行失败", e)
                        conversationHistory.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", toolInfo.id)
                            put("content", "Error: ${e.message ?: "Unknown error"}")
                        })
                    }
                }

                pendingToolCalls.clear()
            }

            Log.i(TAG, "🏁 工具循环完成，发送 Finish 事件")
            send(AppStreamEvent.Finish("stop"))

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "流被取消: ${e.message}")
            throw e
        } catch (e: Exception) {
            val result = NetworkUtils.handleConnectionError(e, "OpenAI")
            send(result.error)
            send(result.finish)
        }

        return@channelFlow
    }

    private data class OpenAiToolCallInfo(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class OpenAIParseResult(
        val hasToolCalls: Boolean,
        val fullText: String
    )
    
    /**
     * 构建 OpenAI API 请求体
     */
    private fun buildOpenAIPayload(request: ChatRequest): String {
        // 首先注入系统提示词（如果消息中没有系统消息，则自动注入）
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
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
                messagesWithSystemPrompt.forEach { message ->
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
                        else -> {
                            addJsonObject {
                                put("role", message.role)
                                put("content", "")
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
                config.maxOutputTokens?.let { put("max_tokens", it) }
            }

            // Gemini-in-OpenAI 格式支持 (Gemini 通过 OpenAI 兼容接口调用)
            val isGemini = request.channel.contains("gemini", ignoreCase = true) ||
                           request.model.contains("gemini", ignoreCase = true)

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

            if (isGemini || isQwenSearchEnabled) {
                 putJsonObject("extra_body") {
                    if (isGemini) {
                        putJsonObject("google") {
                            // 工具配置
                            val toolsToAdd = mutableListOf<String>()
                            if (request.useWebSearch == true) {
                                toolsToAdd.add("google_search")
                            }
                            // 代码执行工具
                            if (request.enableCodeExecution == true) {
                                 toolsToAdd.add("code_execution")
                            }
                            
                            if (toolsToAdd.isNotEmpty()) {
                                putJsonArray("tools") {
                                    toolsToAdd.forEach { toolName ->
                                        addJsonObject { putJsonObject(toolName) {} }
                                    }
                                }
                            }
                            
                            // thinking_config 支持
                            request.generationConfig?.thinkingConfig?.let { tc ->
                                putJsonObject("thinking_config") {
                                    tc.includeThoughts?.let { put("include_thoughts", it) }
                                    tc.thinkingBudget?.let { put("thinking_budget", it) }
                                }
                            }
                        }
                    }

                    if (isQwenSearchEnabled) {
                        put("enable_search", true)
                        putJsonObject("search_options") {
                            put("forced_search", true)
                            put("search_strategy", "max")
                        }
                    }
                }
            }

            // MCP 工具注入 (OpenAI function calling 格式)
            request.tools?.let { tools ->
                if (tools.isNotEmpty()) {
                    Log.d(TAG, "注入 ${tools.size} 个 MCP 工具到请求")
                    putJsonArray("tools") {
                        tools.forEach { toolDef ->
                            add(mapToJsonElement(toolDef))
                        }
                    }
                    put("tool_choice", "auto")
                }
            }
        }.toString()
    }

    private fun mapToJsonElement(map: Map<String, Any>): JsonElement {
        return buildJsonObject {
            map.forEach { (key, value) ->
                put(key, anyToJsonElement(value))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> mapToJsonElement(value as Map<String, Any>)
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    // -------------------- Helper: Extract last user text -------------------- 
    private fun extractLastUserText(req: ChatRequest): String? {
        val lastUser = req.messages.lastOrNull { it.role == "user" } ?: return null
        return when (lastUser) {
            is SimpleTextApiMessage -> lastUser.content
            is PartsApiMessage -> {
                lastUser.parts.firstOrNull { it is ApiContentPart.Text }?.let { (it as ApiContentPart.Text).text }
            }
            else -> null
        }?.trim()
    }

    // -------------------- Helper: Fetch web search results -------------------- 
    // Endpoint should return JSON with a top-level array under one of ["results","items","data"] or be an array.
    // Each item ideally contains {title, href|url|link, snippet|description|abstract}
    private suspend fun tryFetchWebSearch(
        client: HttpClient,
        endpoint: String,
        apiKey: String?,
        query: String
    ): List<SearchHit> {
        val responseText = client.get(endpoint) {
            url {
                parameters.append("q", query)
                parameters.append("count", "5")
            }
            header(HttpHeaders.Accept, "application/json")
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }.bodyAsText()

        val root = Json.parseToJsonElement(responseText)
        val arr = when {
            root is JsonObject && root["results"] is JsonArray -> root["results"]!!.jsonArray
            root is JsonObject && root["items"] is JsonArray -> root["items"]!!.jsonArray
            root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
            root is JsonArray -> root
            else -> JsonArray(emptyList())
        }

        return arr.mapNotNull { el ->
            try {
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["heading"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val href = obj["href"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["link"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull
                    ?: obj["description"]?.jsonPrimitive?.contentOrNull
                    ?: obj["abstract"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                SearchHit(title = title, href = href, snippet = snippet)
            } catch (_: Exception) {
                null
            }
        }.take(5)
    }

    // -------------------- Helper: Inject search results into last user message -------------------- 
    private fun injectSearchResultsIntoRequest(
        req: ChatRequest,
        query: String,
        results: List<SearchHit>
    ): ChatRequest {
        if (results.isEmpty()) return req
        val formatted = buildString {
            append("Search results for \"").append(query).append("\":\n\n")
            results.forEachIndexed { idx, hit ->
                append(idx + 1).append(". ").append(hit.title).append("\n")
                if (hit.snippet.isNotBlank()) append(hit.snippet).append("\n")
                if (hit.href.isNotBlank()) append(hit.href).append("\n\n")
            }
            append("Please answer based on the search results above.\n")
        }

        val msgs = req.messages.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == "user" }
        if (lastIdx < 0) return req

        val last = msgs[lastIdx]
        val newLast = when (last) {
            is SimpleTextApiMessage -> last.copy(content = formatted + "\n\n" + last.content)
            is PartsApiMessage -> {
                val parts = last.parts.toMutableList()
                val firstTextIdx = parts.indexOfFirst { it is ApiContentPart.Text }
                if (firstTextIdx >= 0) {
                    val t = parts[firstTextIdx] as ApiContentPart.Text
                    parts[firstTextIdx] = ApiContentPart.Text(formatted + "\n\n" + t.text)
                } else {
                    parts.add(0, ApiContentPart.Text(formatted))
                }
                last.copy(parts = parts)
            }
            else -> last
        }
        msgs[lastIdx] = newLast
        return req.copy(messages = msgs)
    }
    
    /**
     * 处理 Qwen 模型的文件上传
     */
    private suspend fun handleQwenUploads(
        client: HttpClient,
        request: ChatRequest,
        onStatus: suspend (String) -> Unit
    ): ChatRequest {
        var hasUploads = false
        val newMessages = request.messages.map { msg ->
            if (msg is PartsApiMessage) {
                val newParts = msg.parts.map { part ->
                    if (part is ApiContentPart.InlineData && part.mimeType.startsWith("file_upload_marker|")) {
                        hasUploads = true
                        // Format: file_upload_marker|mime|filename
                        val segments = part.mimeType.split("|")
                        val fileName = segments.getOrNull(2) ?: "unknown_file"
                        
                        try {
                            onStatus("Uploading $fileName to DashScope...")
                            val bytes = Base64.decode(part.base64Data, Base64.NO_WRAP)
                            val fileId = uploadFileToDashScope(client, request.apiKey, fileName, bytes)
                            Log.i(TAG, "Uploaded $fileName, id=$fileId")
                            
                            ApiContentPart.FileUri(uri = fileId, mimeType = "qwen-file-id")
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
        
        if (hasUploads) {
            onStatus("File upload complete, starting generation...")
        }
        
        return request.copy(messages = newMessages)
    }

    private suspend fun uploadFileToDashScope(
        client: HttpClient,
        apiKey: String,
        fileName: String,
        fileBytes: ByteArray
    ): String {
        // https://dashscope.aliyuncs.com/compatible-mode/v1/files
        val response = client.submitFormWithBinaryData(
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1/files",
            formData = formData {
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
                        "txt" -> "text/plain"
                        "pdf" -> "application/pdf"
                        "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        else -> "application/octet-stream"
                    }
                    append(HttpHeaders.ContentType, mimeType)
                })
                append("purpose", "file-extract")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }

        if (!response.status.isSuccess()) {
            throw Exception("Upload failed: ${response.status}")
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return json["id"]?.jsonPrimitive?.content ?: throw Exception("No file id in response")
    }

    /**
     * 解析 OpenAI SSE 流
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun parseOpenAISSEStream(channel: ByteReadChannel): Flow<AppStreamEvent> = channelFlow {
        val lineBuffer = StringBuilder()
        var fullText = ""
        var eventCount = 0

        // 推理/正文阶段状态，用于驱动思考框
        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false

        try {
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                when {
                    line.isEmpty() -> {
                        // 空行 = 一个 SSE 事件结束
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty()) {
                            if (chunk == "[DONE]") {
                                // 若仍未发出推理完成，先发
                                if (reasoningStarted && !reasoningFinished) {
                                    send(AppStreamEvent.ReasoningFinish(null))
                                    reasoningFinished = true
                                }
                                break
                            }
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject

                                // 解析 OpenAI-compat choices[].delta
                                val choice = jsonChunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                if (choice != null) {
                                    val delta = choice["delta"]?.jsonObject

                                    // 兼容可能的推理字段（与后端一致）
                                    val reasoningText =
                                        delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("thinking")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("thoughts")?.jsonPrimitive?.contentOrNull

                                    if (!reasoningText.isNullOrEmpty()) {
                                        if (!reasoningStarted) {
                                            reasoningStarted = true
                                        }
                                        send(AppStreamEvent.Reasoning(reasoningText))
                                    }

                                    val contentText = delta?.get("content")?.jsonPrimitive?.contentOrNull
                                    if (!contentText.isNullOrEmpty()) {
                                        // 第一段正文到来，先收起思考框
                                        if (reasoningStarted && !reasoningFinished) {
                                            send(AppStreamEvent.ReasoningFinish(null))
                                            reasoningFinished = true
                                        }
                                        if (!contentStarted) contentStarted = true

                                        eventCount++
                                        fullText += contentText
                                        send(AppStreamEvent.Content(contentText, null, null))
                                    }

                                    // 结束原因
                                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                    if (!finishReason.isNullOrBlank() && finishReason != "null") {
                                        Log.d(TAG, "Finish reason: $finishReason")
                                    }
                                }
                            } catch (_: Exception) {
                                // 忽略解析错误，继续读取后续帧
                            }
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val dataContent = line.substring(5).trim()
                        if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                        lineBuffer.append(dataContent)
                    }
                    line.startsWith(":") -> {
                        // SSE 注释/心跳，忽略
                    }
                }
            }

            // 发送结束事件（补尾）
            if (fullText.isNotEmpty()) {
                send(AppStreamEvent.ContentFinal(fullText, null, null))
            }
            if (reasoningStarted && !reasoningFinished) {
                send(AppStreamEvent.ReasoningFinish(null))
            }
            send(AppStreamEvent.Finish("stop"))

        } catch (e: Exception) {
            send(AppStreamEvent.Error("流解析失败: ${e.message}", null))
        }

        awaitClose {
            Log.d(TAG, "SSE stream channel closed")
        }
    }

    /**
     * 构建带有工具调用历史的 OpenAI API 请求体
     */
    private fun buildOpenAIPayloadWithHistory(
        request: ChatRequest,
        conversationHistory: List<JsonObject>
    ): String {
        val messagesWithSystemPrompt = SystemPromptInjector.smartInjectSystemPrompt(request.messages)
        Log.i(TAG, "📝 已注入系统提示词，消息数量: ${messagesWithSystemPrompt.size}, 历史数量: ${conversationHistory.size}")

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

            putJsonArray("messages") {
                // 原始消息
                messagesWithSystemPrompt.forEach { message ->
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
                                val textContent = parts.joinToString("\n") { (it as ApiContentPart.Text).text }
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
                                                is ApiContentPart.Text -> {
                                                    addJsonObject {
                                                        put("type", "text")
                                                        put("text", part.text)
                                                    }
                                                }
                                                is ApiContentPart.InlineData -> {
                                                    val mime = part.mimeType
                                                    if (isAudioMime(mime)) {
                                                        addJsonObject {
                                                            put("type", "input_audio")
                                                            putJsonObject("input_audio") {
                                                                put("data", part.base64Data)
                                                                put("format", audioFormatFromMime(mime))
                                                            }
                                                        }
                                                    } else {
                                                        val dataUri = "data:${mime};base64,${part.base64Data}"
                                                        addJsonObject {
                                                            put("type", "image_url")
                                                            putJsonObject("image_url") {
                                                                put("url", dataUri)
                                                            }
                                                        }
                                                    }
                                                }
                                                is ApiContentPart.FileUri -> {
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
                        else -> {
                            addJsonObject {
                                put("role", message.role)
                                put("content", "")
                            }
                        }
                    }
                }

                // 添加工具调用历史
                conversationHistory.forEach { historyMsg ->
                    add(historyMsg)
                }
            }

            // 添加参数
            request.generationConfig?.let { config ->
                config.temperature?.let { put("temperature", it) }
                config.topP?.let { put("top_p", it) }
                config.maxOutputTokens?.let { put("max_tokens", it) }
            }

            // MCP 工具注入
            request.tools?.let { tools ->
                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        tools.forEach { toolDef ->
                            add(mapToJsonElement(toolDef))
                        }
                    }
                    put("tool_choice", "auto")
                }
            }
        }.toString()
    }

    /**
     * 解析 OpenAI SSE 流并支持工具调用
     */
    private suspend fun parseOpenAISSEStreamWithTools(
        channel: ByteReadChannel,
        onToolCall: (OpenAiToolCallInfo) -> Unit,
        emitEvent: suspend (AppStreamEvent) -> Unit
    ): OpenAIParseResult {
        val lineBuffer = StringBuilder()
        var fullText = ""

        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false
        var hasToolCalls = false

        // 用于聚合流式的 tool_calls（OpenAI 会分多个 chunk 发送）
        val toolCallsMap = mutableMapOf<Int, Triple<String, String, StringBuilder>>() // index -> (id, name, arguments)

        try {
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

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
                                val choice = jsonChunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject

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
                                        emitEvent(AppStreamEvent.Reasoning(reasoningText))
                                    }

                                    // 处理正文内容
                                    val contentText = delta?.get("content")?.jsonPrimitive?.contentOrNull
                                    if (!contentText.isNullOrEmpty()) {
                                        if (reasoningStarted && !reasoningFinished) {
                                            emitEvent(AppStreamEvent.ReasoningFinish(null))
                                            reasoningFinished = true
                                        }
                                        if (!contentStarted) contentStarted = true
                                        fullText += contentText
                                        emitEvent(AppStreamEvent.Content(contentText, null, null))
                                    }

                                    // 处理工具调用 (OpenAI 格式: delta.tool_calls)
                                    delta?.get("tool_calls")?.jsonArray?.forEach { tcElement ->
                                        val tcObj = tcElement.jsonObject
                                        val index = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val id = tcObj["id"]?.jsonPrimitive?.contentOrNull
                                        val function = tcObj["function"]?.jsonObject
                                        val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                                        val argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""

                                        val existing = toolCallsMap[index]
                                        if (existing != null) {
                                            existing.third.append(argumentsDelta)
                                        } else {
                                            toolCallsMap[index] = Triple(
                                                id ?: "call_${System.currentTimeMillis()}_$index",
                                                name ?: "",
                                                StringBuilder(argumentsDelta)
                                            )
                                        }
                                        hasToolCalls = true
                                    }

                                    // 检查 finish_reason
                                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                    if (finishReason == "tool_calls") {
                                        Log.d(TAG, "Finish reason: tool_calls, 准备处理工具调用")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "解析 SSE chunk 失败: ${e.message}")
                            }
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val dataContent = line.substring(5).trim()
                        if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                        lineBuffer.append(dataContent)
                    }
                    line.startsWith(":") -> {
                        // SSE 注释/心跳，忽略
                    }
                }
            }

            // 处理完成后，发送聚合的工具调用
            if (toolCallsMap.isNotEmpty()) {
                toolCallsMap.values.forEach { (id, name, argsBuilder) ->
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

            // 发送结束事件
            if (fullText.isNotEmpty() && !hasToolCalls) {
                emitEvent(AppStreamEvent.ContentFinal(fullText, null, null))
            }
            if (reasoningStarted && !reasoningFinished) {
                emitEvent(AppStreamEvent.ReasoningFinish(null))
            }
            // 注意：不在这里发送 Finish，由调用方决定（可能还有工具循环）

        } catch (e: Exception) {
            emitEvent(AppStreamEvent.Error("流解析失败: ${e.message}", null))
        }

        return OpenAIParseResult(hasToolCalls = hasToolCalls, fullText = fullText)
    }
}
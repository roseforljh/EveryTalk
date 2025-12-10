package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.WebSearchResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import android.util.Base64

/**
 * OpenAI 兼容格式直连客户端
 * 用于在后端服务器被 Cloudflare 拦截时自动降级到直连模式
 */
object OpenAIDirectClient {
    private const val TAG = "OpenAIDirectClient"

    // 直连联网搜索的内部数据模型，避免使用 Triple 导致的类型推断/属性名冲突
    private data class SearchHit(val title: String, val href: String, val snippet: String)
    
    /**
     * 直连 OpenAI 兼容 API 发送聊天请求
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
        try {
            Log.i(TAG, "🔄 启动 OpenAI 兼容直连模式")

           
            // 通过 request.customExtraBody 配置搜索端点与密钥：
            //   customExtraBody = {"webSearchEndpoint":"https://<your-search>/api","webSearchKey":"<key>"}
            var effectiveRequest = request

            // Qwen 文档上传处理
            if (request.model.contains("qwen", ignoreCase = true)) {
                effectiveRequest = handleQwenUploads(client, effectiveRequest) { status ->
                    send(AppStreamEvent.StatusUpdate(status))
                }
            }

            // 如果启用了 Qwen 原生搜索，则跳过客户端侧的 Google 搜索
            if (request.useWebSearch == true && request.qwenEnableSearch != true) {
                val userQuery = extractLastUserText(request).let { it ?: "" }.trim()

                if (userQuery.isNotBlank()) {
                    // 1. 优先检查自定义搜索端点
                    val endpoint = (request.customExtraBody?.get("webSearchEndpoint") as? String)?.trim()
                    val customKey = (request.customExtraBody?.get("webSearchKey") as? String)?.trim()

                    // 2. 检查 Google CSE 配置
                    val googleCseId = com.android.everytalk.BuildConfig.GOOGLE_CSE_ID
                    val googleApiKey = com.android.everytalk.BuildConfig.GOOGLE_SEARCH_API_KEY

                    var searchResults: List<SearchHit> = emptyList()
                    var searchSource = "None"

                    try {
                        if (!endpoint.isNullOrBlank()) {
                            // 使用自定义端点
                            searchSource = "Custom Endpoint"
                            send(AppStreamEvent.StatusUpdate("Searching web (Custom)..."))
                            searchResults = tryFetchWebSearch(client, endpoint, customKey, userQuery)
                        } else if (googleCseId.isNotBlank() && googleApiKey.isNotBlank()) {
                            // 使用 Google CSE
                            searchSource = "Google CSE"
                            send(AppStreamEvent.StatusUpdate("Searching Google..."))
                            val results = WebSearchClient.search(client, userQuery, googleApiKey, googleCseId)
                            searchResults = results.map {
                                SearchHit(it.title, it.href, it.snippet)
                            }
                        } else {
                            send(AppStreamEvent.StatusUpdate("Web search skipped (no configuration)..."))
                        }

                        if (searchResults.isNotEmpty()) {
                            // 发送结果事件（UI 可展示来源弹窗）
                            val listForUi = searchResults.mapIndexed { idx, hit ->
                                WebSearchResult(
                                    index = idx + 1,
                                    title = hit.title,
                                    snippet = hit.snippet,
                                    href = hit.href
                                )
                            }
                            send(AppStreamEvent.WebSearchResults(listForUi))

                            // 注入到最后一条 user 消息（与跳板注入策略一致，作为前置上下文）
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

            // ——— 2) 构建 API URL 与请求体（使用可能被注入后的 effectiveRequest） ———
            var baseUrl = effectiveRequest.apiAddress?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
                ?: "https://api.openai.com"

            // 智谱 BigModel 特殊处理
            if (baseUrl.contains("bigmodel.cn")) {
                // 如果用户填写的 URL 不包含 API 路径，尝试自动修正
                if (!baseUrl.contains("/api/paas/v4")) {
                     baseUrl = "https://open.bigmodel.cn/api/paas/v4"
                }
            }

            // 构建最终 URL
            val url = if (baseUrl.endsWith("/chat/completions")) {
                baseUrl
            } else if (baseUrl.endsWith("/v1")) {
                "$baseUrl/chat/completions"
            } else {
                "$baseUrl/v1/chat/completions"
            }
            
            Log.d(TAG, "直连 URL: $url")

            val payload = buildOpenAIPayload(effectiveRequest)
            Log.d(TAG, "Payload: $payload")

            // 发送请求（流式执行，禁缓冲/禁压缩）
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)

                header(HttpHeaders.Authorization, "Bearer ${effectiveRequest.apiKey}")
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                accept(ContentType.Text.EventStream)
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.AcceptEncoding, "identity")
                header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0, must-revalidate")
                header(HttpHeaders.Pragma, "no-cache")
                header(HttpHeaders.Connection, "keep-alive")
                header("X-Accel-Buffering", "no")

                timeout {
                    requestTimeoutMillis = Long.MAX_VALUE
                    connectTimeoutMillis = 60_000
                    socketTimeoutMillis = Long.MAX_VALUE
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                    Log.e(TAG, "API 错误 ${response.status}: $errorBody")
                    send(AppStreamEvent.Error("API 错误: ${response.status}", response.status.value))
                    send(AppStreamEvent.Finish("api_error"))
                    return@execute
                }

                Log.i(TAG, "✅ 直连成功，开始接收流")

                // 解析 SSE 流
                parseOpenAISSEStream(response.bodyAsChannel())
                    .collect { event ->
                        send(event)
                        kotlinx.coroutines.yield()
                    }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "流被取消: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "直连失败", e)
            send(AppStreamEvent.Error("直连失败: ${e.message}", null))
            send(AppStreamEvent.Finish("direct_connection_failed"))
        }

        // 结束 channelFlow（不要挂起等待外部关闭，否则上层 onCompletion 不会触发）
        return@channelFlow
    }
    
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
        }.toString()
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

        awaitClose { }
    }
}


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

            // ——— 1) 可选：直连前进行“客户端侧联网搜索注入”，与跳板策略对齐 ———
            // 通过 request.customExtraBody 配置搜索端点与密钥：
            //   customExtraBody = {"webSearchEndpoint":"https://<your-search>/api","webSearchKey":"<key>"}
            var effectiveRequest = request
            if (request.useWebSearch == true) {
                val endpoint = (request.customExtraBody?.get("webSearchEndpoint") as? String)?.trim()
                val apiKey = (request.customExtraBody?.get("webSearchKey") as? String)?.trim()
                val userQuery = extractLastUserText(request).let { it ?: "" }.trim()

                if (!endpoint.isNullOrBlank() && userQuery.isNotBlank()) {
                    try {
                        // 提示 UI：开始联网
                        send(AppStreamEvent.StatusUpdate("Searching web..."))
                        val results = tryFetchWebSearch(client, endpoint, apiKey, userQuery)
                        if (results.isNotEmpty()) {
                            // 发送结果事件（UI 可展示来源弹窗）
                            val listForUi = results.mapIndexed { idx, hit ->
                                WebSearchResult(
                                    index = idx + 1,
                                    title = hit.title,
                                    snippet = hit.snippet,
                                    href = hit.href
                                )
                            }
                            send(AppStreamEvent.WebSearchResults(listForUi))

                            // 注入到最后一条 user 消息（与跳板注入策略一致，作为前置上下文）
                            effectiveRequest = injectSearchResultsIntoRequest(request, userQuery, results)
                            send(AppStreamEvent.StatusUpdate("Answering with search results..."))
                        } else {
                            send(AppStreamEvent.StatusUpdate("No search results, answering directly..."))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Web search failed, skip injection: ${e.message}")
                        send(AppStreamEvent.StatusUpdate("Search failed, answering directly..."))
                    }
                } else {
                    send(AppStreamEvent.StatusUpdate("Web search skipped (no endpoint configured)..."))
                }
            }

            // ——— 2) 构建 API URL 与请求体（使用可能被注入后的 effectiveRequest） ———
            val baseUrl = effectiveRequest.apiAddress?.trimEnd('/') ?: "https://api.openai.com"
            val url = "$baseUrl/v1/chat/completions"
            Log.d(TAG, "直连 URL: $url")

            val payload = buildOpenAIPayload(effectiveRequest)

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
                request.messages.forEach { message ->
                    when (message) {
                        is SimpleTextApiMessage -> {
                            addJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            }
                        }
                        is com.android.everytalk.data.DataClass.PartsApiMessage -> {
                            val parts = message.parts
                            if (parts.isEmpty()) {
                                addJsonObject {
                                    put("role", message.role)
                                    put("content", "")
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
            }

            // 添加参数
            request.generationConfig?.let { config ->
                config.temperature?.let { put("temperature", it) }
                config.topP?.let { put("top_p", it) }
                config.maxOutputTokens?.let { put("max_tokens", it) }
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


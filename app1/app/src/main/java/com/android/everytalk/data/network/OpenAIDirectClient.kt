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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import android.util.Base64
import kotlinx.coroutines.CancellationException

object OpenAIDirectClient {
    private const val TAG = "OpenAIDirectClient"
    private const val MAX_TOOL_LOOPS = 50
    private const val MAX_FILE_UPLOAD_RESPONSE_BYTES = 1L * 1024L * 1024L
    private const val MAX_QWEN_UPLOAD_FILE_BYTES = 10L * 1024L * 1024L

    private class StreamingContentAggregator {
        private val buffer = StringBuilder()
        private var pendingShortHeading = false
        private var lastEmitAt = System.currentTimeMillis()
        private val maxWaitMs = 450L
        private val maxPendingChars = 40

        fun append(delta: String): List<String> {
            if (delta.isEmpty()) return emptyList()
            buffer.append(delta)
            updatePendingShortHeadingFlag()
            val out = mutableListOf<String>()
            while (true) {
                val flushLen = findFlushablePrefixLength(buffer.toString())
                if (flushLen <= 0) break
                out += buffer.substring(0, flushLen)
                buffer.delete(0, flushLen)
                lastEmitAt = System.currentTimeMillis()
                updatePendingShortHeadingFlag()
            }
            return out
        }

        fun flushRemaining(): String {
            val remaining = buffer.toString()
            buffer.setLength(0)
            pendingShortHeading = false
            lastEmitAt = System.currentTimeMillis()
            return remaining
        }

        private fun findFlushablePrefixLength(text: String): Int {
            if (text.isEmpty()) return -1

            val now = System.currentTimeMillis()
            val waitedTooLong = now - lastEmitAt >= maxWaitMs
            val bufferedTooMuch = text.length >= maxPendingChars

            if ((waitedTooLong || bufferedTooMuch) && !isInsideUnclosedFence(text)) {
                val forcedCut = findForcedFlushCut(text)
                if (forcedCut > 0) return forcedCut
            }

            if (text.length < 12 && !text.contains('\n')) return -1

            val shortHeadingCut = findShortHeadingWithBodyBoundary(text)
            if (shortHeadingCut > 0) return shortHeadingCut

            val lastLineStart = text.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            val lastLine = text.substring(lastLineStart).trimStart()
            if (pendingShortHeading) {
                if (isHeadingBodyArrived(text)) {
                    val pendingCut = flushPendingShortHeadingWithBody(text)
                    if (pendingCut > 0) return pendingCut
                }
                if (!waitedTooLong && !bufferedTooMuch) {
                    return -1
                }
            }
            if (isHeadingLikeLine(lastLine) && !text.endsWith("\n") && !text.contains("\n\n")) {
                return -1
            }
            if (isListLikeLine(lastLine) && !text.endsWith("\n") && !text.contains("\n\n")) {
                return -1
            }

            val fenceCut = findFenceLineBoundary(text)
            if (fenceCut > 0) return fenceCut

            val codeLineCut = findCodeLineBoundary(text)
            if (codeLineCut > 0) return codeLineCut

            val headingCut = findHeadingLineBoundary(text)
            if (headingCut > 0) return headingCut

            val listCut = findListItemBoundary(text)
            if (listCut > 0) return listCut

            if (text.length >= 96) return findSafeCut(text, 96)

            val doubleNewline = text.lastIndexOf("\n\n")
            if (doubleNewline >= 0) {
                val cut = doubleNewline + 2
                return if (isSafePrefix(text.substring(0, cut))) cut else -1
            }

            val singleNewline = text.lastIndexOf('\n')
            if (singleNewline >= 0) {
                val cut = singleNewline + 1
                return if (isSafePrefix(text.substring(0, cut))) cut else -1
            }

            val punctuationCut = findLastPunctuationBoundary(text)
            if (punctuationCut > 0) {
                return if (isSafePrefix(text.substring(0, punctuationCut))) punctuationCut else -1
            }

            val whitespaceCut = text.lastIndexOf(' ')
            if (whitespaceCut > 0) {
                val cut = whitespaceCut + 1
                return if (isSafePrefix(text.substring(0, cut))) cut else -1
            }

            return -1
        }

        private fun findForcedFlushCut(text: String): Int {
            val newlineCut = text.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: -1
            if (newlineCut > 0 && isSafePrefix(text.substring(0, newlineCut))) return newlineCut

            val punctuationCut = findLastPunctuationBoundary(text)
            if (punctuationCut > 0 && isSafePrefix(text.substring(0, punctuationCut))) return punctuationCut

            val whitespaceCut = text.lastIndexOf(' ').takeIf { it >= 0 }?.plus(1) ?: -1
            if (whitespaceCut > 0 && isSafePrefix(text.substring(0, whitespaceCut))) return whitespaceCut

            return if (text.length >= 12 && isSafePrefix(text)) text.length else -1
        }

        private fun findShortHeadingWithBodyBoundary(text: String): Int {
            val lines = text.split('\n')
            if (lines.size < 2) return -1

            val headingLine = lines[0].trimEnd()
            if (!isHeadingLikeLine(headingLine.trimStart())) return -1

            val headingText = headingLine.trimStart().replaceFirst(Regex("^#{1,6}\\s+"), "")
            if (headingText.length > 8) return -1

            var consumedChars = headingLine.length
            var bodyCollected = StringBuilder()
            var sawMeaningfulBody = false

            for (i in 1 until lines.size) {
                consumedChars += 1 // newline
                val line = lines[i]
                val trimmed = line.trim()

                if (trimmed.isEmpty()) {
                    if (sawMeaningfulBody) {
                        consumedChars += line.length
                        return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                    }
                    consumedChars += line.length
                    continue
                }

                if (isHeadingLikeLine(trimmed) || isListLikeLine(trimmed)) {
                    return if (sawMeaningfulBody && consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                }

                bodyCollected.append(trimmed).append('\n')
                consumedChars += line.length
                sawMeaningfulBody = true

                val bodyText = bodyCollected.toString().trim()
                val bodyLooksComplete = bodyText.endsWith("。") || bodyText.endsWith("！") || bodyText.endsWith("？") ||
                    bodyText.endsWith("：") || bodyText.endsWith(":") || bodyText.length >= 24
                if (bodyLooksComplete) {
                    return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                }
            }

            return -1
        }

        private fun updatePendingShortHeadingFlag() {
            val text = buffer.toString()
            val lines = text.split('\n')
            if (lines.isEmpty()) {
                pendingShortHeading = false
                return
            }

            val headingLine = lines.first().trimEnd()
            val headingTrimmed = headingLine.trimStart()
            if (!isHeadingLikeLine(headingTrimmed)) {
                pendingShortHeading = false
                return
            }

            val headingText = headingTrimmed.replaceFirst(Regex("^#{1,6}\\s+"), "")
            pendingShortHeading = headingText.length <= 8
        }

        private fun isHeadingBodyArrived(text: String): Boolean {
            val lines = text.split('\n')
            if (lines.size < 2) return false
            val bodyLines = lines.drop(1)
            return bodyLines.any { it.trim().isNotEmpty() }
        }

        private fun flushPendingShortHeadingWithBody(text: String): Int {
            val lines = text.split('\n')
            if (lines.size < 2) return -1

            val headingLine = lines[0].trimEnd()
            val headingTrimmed = headingLine.trimStart()
            if (!isHeadingLikeLine(headingTrimmed)) return -1

            val bodyLines = mutableListOf<String>()
            var consumedChars = headingLine.length
            var bodyLength = 0

            for (i in 1 until lines.size) {
                consumedChars += 1 // newline
                val line = lines[i]
                val trimmed = line.trim()

                if (trimmed.isEmpty()) {
                    if (bodyLines.isNotEmpty()) {
                        consumedChars += line.length
                        return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                    }
                    consumedChars += line.length
                    continue
                }

                if (isHeadingLikeLine(trimmed) || isListLikeLine(trimmed)) {
                    return if (bodyLines.isNotEmpty() && consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                }

                bodyLines += line
                consumedChars += line.length
                bodyLength += trimmed.length

                val bodyJoined = bodyLines.joinToString("\n").trim()
                val bodyLooksComplete = bodyJoined.endsWith("。") || bodyJoined.endsWith("！") || bodyJoined.endsWith("？") ||
                    bodyJoined.endsWith("：") || bodyJoined.endsWith(":") || bodyLength >= 20
                if (bodyLooksComplete) {
                    return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                }
            }

            return -1
        }

        private fun findHeadingLineBoundary(text: String): Int {
            val lineStart = text.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            val line = text.substring(lineStart)
            val trimmed = line.trimStart()
            if (!isHeadingLikeLine(trimmed)) return -1

            val headingText = trimmed.replaceFirst(Regex("^#{1,6}\\s+"), "")
            if (headingText.length <= 8) return -1

            val hasLineTerminator = text.endsWith("\n") || text.contains("\n\n")
            if (!hasLineTerminator) return -1

            return if (isSafePrefix(text)) text.length else -1
        }

        private fun findListItemBoundary(text: String): Int {
            val lineStart = text.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            val line = text.substring(lineStart)
            val trimmed = line.trimStart()
            if (!isListLikeLine(trimmed)) return -1

            val hasLineTerminator = text.endsWith("\n") || text.contains("\n\n")
            val hasSentenceEnd = line.endsWith("。") || line.endsWith("！") || line.endsWith("？") ||
                line.endsWith("；") || line.endsWith(":") || line.endsWith("：")
            val bodyLength = trimmed.length

            if (!hasLineTerminator && !hasSentenceEnd) return -1
            if (bodyLength < 12) return -1

            return if (isSafePrefix(text)) text.length else -1
        }

        private fun isHeadingLikeLine(trimmed: String): Boolean {
            if (!trimmed.startsWith("#")) return false
            val marker = trimmed.takeWhile { it == '#' }
            if (marker.isEmpty() || marker.length > 6) return false
            if (trimmed.length <= marker.length) return false
            if (trimmed[marker.length] != ' ') return false
            return true
        }

        private fun isListLikeLine(trimmed: String): Boolean {
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) return true
            if (Regex("^\\d+\\.\\s+").containsMatchIn(trimmed)) return true
            return false
        }

        private fun findFenceLineBoundary(text: String): Int {
            val trimmed = text.trimEnd()
            if (trimmed.endsWith("```") || Regex("```[A-Za-z0-9_-]+$").containsMatchIn(trimmed)) {
                return if (isSafePrefix(text)) text.length else -1
            }
            return -1
        }

        private fun findCodeLineBoundary(text: String): Int {
            if (!isInsideUnclosedFence(text)) return -1
            val newline = text.lastIndexOf('\n')
            if (newline <= 0) return -1
            val lineStart = text.substring(0, newline).lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            val candidate = text.substring(0, newline + 1)
            val line = candidate.substring(lineStart, candidate.length - 1)
            if (line.isBlank()) return candidate.length
            if (line.length < 12 && !line.trimEnd().endsWith("{") && !line.trimEnd().endsWith(";") && !line.trimEnd().endsWith(">")) {
                return -1
            }
            return candidate.length
        }

        private fun findSafeCut(text: String, preferred: Int): Int {
            val safeRegion = text.take(preferred)
            val punctuationCut = findLastPunctuationBoundary(safeRegion)
            if (punctuationCut > 0 && isSafePrefix(text.substring(0, punctuationCut))) return punctuationCut

            val newlineCut = safeRegion.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: -1
            if (newlineCut > 0 && isSafePrefix(text.substring(0, newlineCut))) return newlineCut

            val whitespaceCut = safeRegion.lastIndexOf(' ').takeIf { it >= 0 }?.plus(1) ?: -1
            if (whitespaceCut > 0 && isSafePrefix(text.substring(0, whitespaceCut))) return whitespaceCut

            return if (isSafePrefix(safeRegion)) safeRegion.length else -1
        }

        private fun findLastPunctuationBoundary(text: String): Int {
            for (i in text.indices.reversed()) {
                val ch = text[i]
                if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == ':' || ch == '：' || ch == ',' || ch == '，') {
                    return i + 1
                }
            }
            return -1
        }

        private fun isSafePrefix(prefix: String): Boolean {
            return !endsWithDangerousFragment(prefix) && !isInsideUnclosedFence(prefix)
        }

        private fun endsWithDangerousFragment(text: String): Boolean {
            val line = text.substringAfterLast('\n')
            val trimmed = line.trimEnd()
            val startTrimmed = trimmed.trimStart()

            if (startTrimmed == "#" || startTrimmed == "##" || startTrimmed == "###" ||
                startTrimmed == "####" || startTrimmed == "#####" || startTrimmed == "######") return true
            if (startTrimmed == "-" || startTrimmed == "+" || startTrimmed == "*" || startTrimmed == ">") return true
            if (Regex("^\\d+\\.$").matches(startTrimmed)) return true
            if (trimmed == "`" || trimmed == "``" || trimmed == "```") return true
            if (trimmed == "**") return true
            if (trimmed == "'" || trimmed == "\"") return true
            if (trimmed.endsWith("</") || trimmed.endsWith("<")) return true
            if (trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) return true
            return false
        }

        private fun isInsideUnclosedFence(text: String): Boolean {
            var idx = 0
            var count = 0
            while (true) {
                val pos = text.indexOf("```", idx)
                if (pos < 0) break
                count++
                idx = pos + 3
            }
            return (count % 2) == 1
        }
    }

    private var mcpToolExecutor: (suspend (String, JsonObject, suspend (String?) -> Unit) -> JsonElement)? = null
    private var mcpToolExecutorOwner: Any? = null

    @Synchronized
    fun setMcpToolExecutor(
        owner: Any,
        executor: (suspend (String, JsonObject, suspend (String?) -> Unit) -> JsonElement)?,
    ) {
        mcpToolExecutorOwner = owner
        mcpToolExecutor = executor
    }

    @Synchronized
    fun setMcpToolExecutor(
        executor: (suspend (String, JsonObject, suspend (String?) -> Unit) -> JsonElement)?,
    ) {
        mcpToolExecutorOwner = null
        mcpToolExecutor = executor
    }

    @Synchronized
    fun clearMcpToolExecutor(owner: Any) {
        if (mcpToolExecutorOwner === owner) {
            mcpToolExecutorOwner = null
            mcpToolExecutor = null
        }
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
        var terminalSent = false
        try {
            Log.i(TAG, "🔄 启动 OpenAI 兼容直连模式")

            var effectiveRequest = request

            if (request.model.contains("qwen-long", ignoreCase = true)) {
                effectiveRequest = handleQwenUploads(client, effectiveRequest)
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
                var shouldRetryWithoutImages = false
                var shouldFallbackResponses = false

                client.preparePost(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    header(HttpHeaders.Authorization, "Bearer ${currentRequest.apiKey}")
                    configureSSERequest()
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = response.readErrorTextAtMost()
                        if (response.status.value == 400 && errorBody?.contains("image_url") == true) {
                            Log.w(TAG, "模型不支持 image_url，移除图片消息后重试")
                            conversationHistory.removeAll { msg ->
                                val content = msg["content"]
                                content is kotlinx.serialization.json.JsonArray &&
                                    content.toString().contains("image_url")
                            }
                            shouldRetryWithoutImages = true
                            return@execute
                        }
                        // 403 + HTML/Cloudflare 响应 → 自动 fallback 到 /v1/responses
                        if (response.status.value == 403 && shouldFallbackToResponses(errorBody)) {
                            Log.w(TAG, "403 Cloudflare/HTML 响应，fallback 到 /v1/responses")
                            shouldFallbackResponses = true
                            return@execute
                        }
                        val result = NetworkUtils.handleApiError(response.status, errorBody, "OpenAI")
                        terminalSent = true
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

                if (terminalSent) return@channelFlow

                if (shouldFallbackResponses) {
                    OpenAIResponsesClient.streamChatResponses(client, currentRequest).collect { event ->
                        send(event)
                    }
                    return@channelFlow
                }

                if (shouldRetryWithoutImages) {
                    loopCount--
                    continue
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
                    parseResult?.reasoningContent?.takeIf { it.isNotEmpty() }
                        ?.let { put("reasoning_content", it) }
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

                        Log.d(TAG, "🔧 开始执行工具: ${toolInfo.name}")
                        val result = mcpToolExecutor!!.invoke(toolInfo.name, argsJson) { status ->
                            send(AppStreamEvent.ExecutionStatusUpdate(status))
                        }
                        Log.i(TAG, "🔧 工具 ${toolInfo.name} 执行成功: resultChars=${result.toString().length}")

                        val webResults = WebSearchToolResultExtractor.extract(toolInfo.name, result)
                        if (webResults.isNotEmpty()) {
                            send(AppStreamEvent.WebSearchResults(webResults))
                        }

                        val resultObject = result as? JsonObject
                        val images = resultObject?.get("_images") as? JsonArray
                        if (images != null && images.isNotEmpty()) {
                            val textOnly = buildJsonObject {
                                resultObject.entries.forEach { (k, v) ->
                                    if (k != "_images") put(k, v)
                                }
                            }
                            conversationHistory.add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", toolInfo.id)
                                put("content", textOnly.toString())
                            })
                            val imageParts = buildJsonArray {
                                images.forEach { imgElement ->
                                    val imgObj = imgElement as? JsonObject ?: return@forEach
                                    val b64 = imgObj["base64"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                    val mime = imgObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/jpeg"
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:$mime;base64,$b64")
                                        }
                                    }
                                }
                            }
                            if (imageParts.isNotEmpty()) {
                                conversationHistory.add(buildJsonObject {
                                    put("role", "user")
                                    put("content", buildJsonArray {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "以上是从网页中提取的图片，请结合网页文本内容一起分析。")
                                        }
                                        imageParts.forEach { add(it) }
                                    })
                                })
                            }
                        } else {
                            conversationHistory.add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", toolInfo.id)
                                put("content", result.toString())
                            })
                        }
                    } catch (e: CancellationException) {
                        throw e
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
            if (!terminalSent) {
                terminalSent = true
                send(AppStreamEvent.Finish("stop"))
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "流被取消: ${e.message}")
            throw e
        } catch (e: Exception) {
            if (!terminalSent) {
                val result = NetworkUtils.handleConnectionError(e, "OpenAI")
                terminalSent = true
                send(result.error)
                send(result.finish)
            }
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
        val fullText: String,
        val reasoningContent: String = ""
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
                WebSearchSupport.isGeminiModelName(request.model)
            if (isGemini) {
                val reasoningEffort = request.customModelParameters?.get("reasoning_effort")?.toString()
                    ?: defaultGeminiReasoningEffort(request.model)
                put("reasoning_effort", reasoningEffort)
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

    private fun defaultGeminiReasoningEffort(model: String): String {
        return when {
            model.contains("flash", ignoreCase = true) -> "low"
            model.contains("pro", ignoreCase = true) -> "medium"
            else -> "high"
        }
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
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> mapToJsonElement(value as Map<String, Any>)
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
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

    private suspend fun uploadFileToDashScope(
        client: HttpClient,
        apiKey: String,
        fileName: String,
        fileBytes: ByteArray
    ): String {
        // https://dashscope.aliyuncs.com/compatible-mode/v1/files
        return client.preparePost("https://dashscope.aliyuncs.com/compatible-mode/v1/files") {
            setBody(MultiPartFormDataContent(formData {
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
            }))
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw Exception("Upload failed: ${response.status}")
            }

            val json = Json.parseToJsonElement(
                response.readTextAtMost(MAX_FILE_UPLOAD_RESPONSE_BYTES)
            ).jsonObject
            json["id"]?.jsonPrimitive?.content ?: throw Exception("No file id in response")
        }
    }

    /** 构建包含会话历史的 OpenAI 请求载荷。 */
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

            val isGemini = request.channel.contains("gemini", ignoreCase = true) ||
                WebSearchSupport.isGeminiModelName(request.model)
            if (isGemini) {
                val reasoningEffort = request.customModelParameters?.get("reasoning_effort")?.toString()
                    ?: defaultGeminiReasoningEffort(request.model)
                put("reasoning_effort", reasoningEffort)
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
        val boundedChannel = BoundedSseLineReader(channel)
        val lineBuffer = StringBuilder()
        val fullText = StringBuilder()
        val fullReasoningContent = StringBuilder()

        var reasoningStarted = false
        var reasoningFinished = false
        var contentStarted = false
        val contentAggregator = StreamingContentAggregator()
        val thinkRouter = ThinkTagStreamRouter()
        var hasToolCalls = false

        // 用于聚合流式的 tool_calls（OpenAI 会分多个 chunk 发送）
        val toolCallsMap = mutableMapOf<Int, Triple<String, String, StringBuilder>>() // index -> (id, name, arguments)

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
                                                val aggregatedChunks = contentAggregator.append(routedChunk.text)
                                                aggregatedChunks.forEach { aggregated ->
                                                    emitEvent(AppStreamEvent.Content(aggregated, null, ""))
                                                }
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
                                    }

                                    // 检查 finish_reason
                                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                    if (finishReason == "tool_calls") {
                                        Log.d(TAG, "Finish reason: tool_calls, 准备处理工具调用")
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "解析 SSE chunk 失败: ${e.message}")
                                throw e
                            }
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
                    contentAggregator.append(routedChunk.text)
                }
            }

            // 冲刷正文缓冲
            val remainingContent = contentAggregator.flushRemaining()
            if (remainingContent.isNotEmpty()) {
                emitEvent(AppStreamEvent.Content(remainingContent, null, ""))
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
            reasoningContent = completedReasoning
        )
    }

    private fun shouldFallbackToResponses(errorBody: String?): Boolean {
        if (errorBody.isNullOrBlank()) return false
        val lower = errorBody.lowercase()
        // HTML 页面（Cloudflare 拦截）或非 JSON 响应
        return lower.contains("<html") ||
            lower.contains("cloudflare") ||
            lower.contains("<!doctype") ||
            (lower.contains("403 forbidden") && !lower.startsWith("{"))
    }
}

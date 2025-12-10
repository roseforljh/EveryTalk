package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.network.NetworkUtils.configureSSERequest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

object GeminiDirectClient {
    private const val TAG = "GeminiDirectClient"
    
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
            
            val payload = buildGeminiPayload(request)
            
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
                configureSSERequest()
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { null }
                    val (error, finish) = NetworkUtils.handleApiError(response.status, errorBody, "Gemini")
                    send(error)
                    send(finish)
                    return@execute
                }

                Log.i(TAG, "✅ Gemini 直连成功，开始接收流")

                parseGeminiSSEStream(response.bodyAsChannel())
                    .collect { event ->
                        send(event)
                        kotlinx.coroutines.yield()
                    }
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val (error, finish) = NetworkUtils.handleConnectionError(e, "Gemini")
            send(error)
            send(finish)
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
            
            // 添加工具（Web 搜索、代码执行等）
            if (enableWebSearch || enableCodeExecution) {
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
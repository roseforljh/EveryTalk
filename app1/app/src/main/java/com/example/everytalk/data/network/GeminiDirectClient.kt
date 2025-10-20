package com.example.everytalk.data.network

import android.util.Log
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.SimpleTextApiMessage
import com.example.everytalk.data.DataClass.PartsApiMessage
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
 * 直连 Gemini API 的客户端
 * 用于在后端服务器被 Cloudflare 拦截时自动降级到直连模式
 */
object GeminiDirectClient {
    private const val TAG = "GeminiDirectClient"
    
    /**
     * 直连 Gemini API 发送聊天请求
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamChatDirect(
        client: HttpClient,
        request: ChatRequest
    ): Flow<AppStreamEvent> = channelFlow {
        try {
            Log.i(TAG, "🔄 启动 Gemini 直连模式")
            
            // 构建 Gemini API URL
            val baseUrl = request.apiAddress?.trimEnd('/') 
                ?: "https://generativelanguage.googleapis.com"
            val model = request.model
            val url = "$baseUrl/v1beta/models/$model:streamGenerateContent?key=${request.apiKey}&alt=sse"
            
            Log.d(TAG, "直连 URL: ${url.substringBefore("?key=")}")
            
            // 构建 Gemini 请求体
            val payload = buildGeminiPayload(request)
            
            // 发送请求（流式执行，避免中间层攒包/缓冲）
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)

                // 接受 SSE 并禁用透明压缩/缓冲
                accept(ContentType.Text.EventStream)
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.AcceptEncoding, "identity")
                header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0, must-revalidate")
                header(HttpHeaders.Pragma, "no-cache")
                header(HttpHeaders.Connection, "keep-alive")
                header("X-Accel-Buffering", "no")

                // 浏览器特征头，提升推流概率
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )

                // 与代理流一致的超时配置：保持长连接与持续读取
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 60_000
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                    Log.e(TAG, "Gemini API 错误 ${response.status}: $errorBody")
                    send(AppStreamEvent.Error("Gemini API 错误: ${response.status}", response.status.value))
                    send(AppStreamEvent.Finish("api_error"))
                    return@execute
                }

                Log.i(TAG, "✅ Gemini 直连成功，开始接收流")

                // 按行即时解析与转发
                parseGeminiSSEStream(response.bodyAsChannel())
                    .collect { event ->
                        send(event)
                        // 让出调度，促进 UI 及时刷新
                        kotlinx.coroutines.yield()
                    }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "直连 Gemini 失败", e)
            send(AppStreamEvent.Error("直连失败: ${e.message}", null))
            send(AppStreamEvent.Finish("direct_connection_failed"))
        }
        
        // 结束 channelFlow（不要挂起等待外部关闭，否则上层 onCompletion 不会触发）
        return@channelFlow
    }
    
    /**
     * 构建 Gemini API 请求体
     */
    private fun buildGeminiPayload(request: ChatRequest): String {
        return buildJsonObject {
            // 转换消息格式
            putJsonArray("contents") {
                request.messages.forEach { message ->
                    when {
                        message.role == "system" -> {
                            // 系统消息特殊处理
                        }
                        else -> {
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
                                                    is com.example.everytalk.data.DataClass.ApiContentPart.Text -> {
                                                        addJsonObject {
                                                            put("text", part.text)
                                                        }
                                                    }
                                                    is com.example.everytalk.data.DataClass.ApiContentPart.InlineData -> {
                                                        addJsonObject {
                                                            putJsonObject("inline_data") {
                                                                put("mime_type", part.mimeType)
                                                                put("data", part.base64Data)
                                                            }
                                                        }
                                                    }
                                                    is com.example.everytalk.data.DataClass.ApiContentPart.FileUri -> {
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
                }
            }
            
            // 添加生成配置
            request.generationConfig?.let { config ->
                putJsonObject("generationConfig") {
                    config.temperature?.let { put("temperature", it) }
                    config.topP?.let { put("topP", it) }
                    config.maxOutputTokens?.let { put("maxOutputTokens", it) }
                }
            }
            
            // 添加 Web 搜索工具（如果启用）
            if (request.useWebSearch == true) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonObject("googleSearch") {}
                    }
                }
            }
        }.toString()
    }
    
    /**
     * 解析 Gemini SSE 流 - 实时流式输出
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun parseGeminiSSEStream(channel: ByteReadChannel): Flow<AppStreamEvent> = channelFlow {
        val lineBuffer = StringBuilder()
        var fullText = ""
        var lineCount = 0
        var eventCount = 0
        
        try {
            Log.d(TAG, "开始解析 SSE 流（使用跳板模式相同逻辑）...")
            
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
                                    
                                    // 提取文本内容
                                    candidateObj["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                                        val partObj = part.jsonObject
                                        partObj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                                            if (text.isNotEmpty()) {
                                                eventCount++
                                                fullText += text
                                                // 立即发送 Content 事件
                                                send(AppStreamEvent.Content(text, null, null))
                                                Log.i(TAG, "✓ 流式输出 #$eventCount (${text.length}字): ${text.take(50)}...")
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
                            // 可以尝试直接解析，但通常 Gemini 用标准 SSE
                        }
                    }
                }
            }
            
            Log.i(TAG, "SSE 流读取完成，共 $lineCount 行，$eventCount 个事件")
            
            // 发送最终结果
            if (fullText.isNotEmpty()) {
                send(AppStreamEvent.ContentFinal(fullText, null, null))
                Log.d(TAG, "发送最终内容，总长度: ${fullText.length}")
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
}


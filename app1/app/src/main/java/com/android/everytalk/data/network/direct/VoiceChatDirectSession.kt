package com.android.everytalk.data.network.direct

import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * 语音对话直连会话
 * 
 * 实现完整的 STT → Chat → TTS 直连流程，无需经过后端服务器。
 * 
 * 特点：
 * - 直连 API，消除服务器跳转延迟
 * - 预测性 TTS，LLM 输出与 TTS 合成并行
 * - 智能分句，提前触发 TTS 减少首字延迟
 */
class VoiceChatDirectSession(
    private val httpClient: HttpClient,
    
    // STT 配置
    private val sttConfig: SttDirectClient.SttConfig,
    
    // Chat 配置
    private val chatPlatform: String,          // "Gemini", "OpenAI"
    private val chatApiKey: String,
    private val chatApiUrl: String?,
    private val chatModel: String,
    private val systemPrompt: String = "",
    private val chatHistory: List<Pair<String, String>> = emptyList(),  // List of (role, content)
    
    // TTS 配置
    private val ttsConfig: TtsDirectClient.TtsConfig,
    
    // 回调
    private val onTranscription: ((String) -> Unit)? = null,
    private val onResponseDelta: ((String, String) -> Unit)? = null,  // (delta, fullText)
    private val onAudioChunk: (suspend (ByteArray) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onComplete: ((String, String) -> Unit)? = null  // (userText, assistantText)
) {
    companion object {
        private const val TAG = "VoiceChatDirectSession"
    }
    
    // 智能分句器
    private val splitter = SmartSentenceSplitter()
    
    // 文本缓冲
    private var sentenceBuffer = ""
    private var fullAssistantText = ""
    
    // 取消控制
    private var isCancelled = false
    private var currentJob: Job? = null
    
    /**
     * 处理语音输入，执行完整的 STT → Chat → TTS 流程
     * 
     * @param audioData 录音数据
     * @param mimeType 音频 MIME 类型
     * @return VoiceChatResult 包含识别文本、回复文本、音频数据
     */
    suspend fun process(
        audioData: ByteArray,
        mimeType: String
    ): VoiceChatResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Direct voice chat processing started")
        
        // 重置状态
        splitter.reset()
        sentenceBuffer = ""
        fullAssistantText = ""
        isCancelled = false
        
        var userText = ""
        val audioChunks = mutableListOf<ByteArray>()
        
        try {
            // 1. STT - 语音转文字
            val sttStartTime = System.currentTimeMillis()
            
            userText = SttDirectClient.transcribe(
                client = httpClient,
                config = sttConfig,
                audioData = audioData,
                mimeType = mimeType
            )
            
            val sttElapsed = System.currentTimeMillis() - sttStartTime
            Log.i(TAG, "STT completed (${sttElapsed}ms): $userText")
            
            if (userText.isBlank()) {
                throw Exception("语音识别失败：未能识别出文字")
            }
            
            // 回调识别结果
            withContext(Dispatchers.Main) {
                onTranscription?.invoke(userText)
            }
            
            // 2. Chat + TTS (并行处理)
            Log.i(TAG, "Starting Chat + TTS parallel processing...")
            val chatStartTime = System.currentTimeMillis()
            
            // 创建 TTS 执行器
            val ttsExecutor: suspend (String) -> Flow<ByteArray> = { text ->
                TtsDirectClient.synthesizeStream(httpClient, ttsConfig, text)
            }
            
            // 创建预测性 TTS 处理器
            val ttsProcessor = PredictiveTTSProcessor(
                ttsExecutor = ttsExecutor,
                maxConcurrent = 5,
                maxRetry = 2,
                taskTimeout = 30_000L,
                firstTaskTimeout = 15_000L
            )
            
            var sequenceId = 0
            
            // 启动 TTS 音频收集协程
            val audioCollectorJob = launch {
                ttsProcessor.yieldAudioInOrder().collect { chunk ->
                    if (!isCancelled && chunk.isNotEmpty()) {
                        audioChunks.add(chunk)
                        // 直接在当前协程中调用 suspend 回调，避免切换到主线程导致 ANR
                        onAudioChunk?.invoke(chunk)
                    }
                }
            }
            
            // 流式处理 Chat 响应
            try {
                streamChatResponse(userText).collect { token ->
                    if (isCancelled) {
                        return@collect
                    }
                    
                    sentenceBuffer += token
                    fullAssistantText += token
                    
                    // 回调增量文本
                    withContext(Dispatchers.Main) {
                        onResponseDelta?.invoke(token, fullAssistantText)
                    }
                    
                    // 使用智能分割器分割文本
                    val result = splitter.split(sentenceBuffer)
                    
                    // 处理每个可发送的片段
                    for (segment in result.segments) {
                        if (segment.isNotBlank()) {
                            // 提交 TTS 任务（非阻塞）
                            val cleanSegment = stripMarkdownForTts(segment)
                            ttsProcessor.submitTask(sequenceId, cleanSegment)
                            sequenceId++
                        }
                    }
                    
                    // 更新 buffer 为剩余内容
                    sentenceBuffer = result.remainder
                }
                
                // 处理剩余 buffer
                if (sentenceBuffer.isNotBlank()) {
                    val cleanSegment = stripMarkdownForTts(sentenceBuffer)
                    ttsProcessor.submitTask(sequenceId, cleanSegment)
                    sequenceId++
                }
                
                // 标记输入完成
                ttsProcessor.markInputComplete()
                
            } catch (e: Exception) {
                Log.e(TAG, "Chat 流处理错误", e)
                ttsProcessor.markInputComplete()
                throw e
            }
            
            // 等待 TTS 音频收集完成
            audioCollectorJob.join()
            
            // 清理 TTS 处理器
            ttsProcessor.cleanup()
            
            val chatElapsed = System.currentTimeMillis() - chatStartTime
            Log.i(TAG, "Chat + TTS completed (${chatElapsed}ms)")
            
            // 回调完成
            withContext(Dispatchers.Main) {
                onComplete?.invoke(userText, fullAssistantText)
            }
            
            // 获取音频格式
            val audioFormat = TtsDirectClient.getAudioFormat(ttsConfig.platform)
            
            VoiceChatResult(
                userText = userText,
                assistantText = fullAssistantText,
                audioData = mergeAudioChunks(audioChunks),
                audioFormat = audioFormat.format,
                sampleRate = audioFormat.sampleRate
            )
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "语音对话被取消")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "语音对话处理失败", e)
            withContext(Dispatchers.Main) {
                onError?.invoke(e.message ?: "未知错误")
            }
            throw e
        }
    }
    
    /**
     * 流式 Chat 响应
     */
    private fun streamChatResponse(userText: String): Flow<String> = channelFlow {
        when (chatPlatform.lowercase()) {
            "gemini", "google" -> {
                streamGeminiChat(userText).collect { send(it) }
            }
            "openai", "siliconflow" -> {
                streamOpenAIChat(userText).collect { send(it) }
            }
            else -> {
                throw IllegalArgumentException("Unsupported chat platform: $chatPlatform")
            }
        }
    }
    
    /**
     * Gemini Chat 流式响应
     */
    private fun streamGeminiChat(userText: String): Flow<String> = channelFlow {
        val baseUrl = chatApiUrl?.trimEnd('/') ?: "https://generativelanguage.googleapis.com"
        val url = "$baseUrl/v1beta/models/$chatModel:streamGenerateContent?key=$chatApiKey&alt=sse"
        
        Log.d(TAG, "Gemini Chat URL: ${url.substringBefore("?key=")}")
        
        // 构建请求体
        val payload = buildJsonObject {
            putJsonArray("contents") {
                // 历史消息
                for ((role, content) in chatHistory) {
                    addJsonObject {
                        put("role", if (role == "assistant") "model" else role)
                        putJsonArray("parts") {
                            addJsonObject { put("text", content) }
                        }
                    }
                }
                // 当前用户消息
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", userText) }
                    }
                }
            }
            // 系统提示
            if (systemPrompt.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemPrompt) }
                    }
                }
            }
        }.toString()
        
        httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(payload)
            accept(ContentType.Text.EventStream)
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                throw Exception("Gemini Chat failed: ${response.status} - $errorBody")
            }
            
            val channel = response.bodyAsChannel()
            val lineBuffer = StringBuilder()
            
            while (!channel.isClosedForRead && !isCancelled) {
                val line = channel.readUTF8Line() ?: break
                
                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty() && chunk != "[DONE]") {
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject
                                jsonChunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                                    ?.get("content")?.jsonObject
                                    ?.get("parts")?.jsonArray?.forEach { part ->
                                        part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                                            if (text.isNotEmpty()) {
                                                send(text)
                                            }
                                        }
                                    }
                            } catch (_: Exception) {}
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val dataContent = line.substring(5).trim()
                        if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                        lineBuffer.append(dataContent)
                    }
                }
            }
        }
    }
    
    /**
     * OpenAI 兼容 Chat 流式响应
     */
    private fun streamOpenAIChat(userText: String): Flow<String> = channelFlow {
        val baseUrl = chatApiUrl?.trimEnd('/') ?: "https://api.openai.com"
        val url = "$baseUrl/v1/chat/completions"
        
        Log.d(TAG, "OpenAI Chat URL: $url")
        
        // 构建消息列表
        val messages = buildJsonArray {
            // 系统提示
            if (systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            }
            // 历史消息
            for ((role, content) in chatHistory) {
                addJsonObject {
                    put("role", role)
                    put("content", content)
                }
            }
            // 当前用户消息
            addJsonObject {
                put("role", "user")
                put("content", userText)
            }
        }
        
        val payload = buildJsonObject {
            put("model", chatModel)
            put("messages", messages)
            put("stream", true)
        }.toString()
        
        httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $chatApiKey")
            setBody(payload)
            accept(ContentType.Text.EventStream)
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                throw Exception("OpenAI Chat failed: ${response.status} - $errorBody")
            }
            
            val channel = response.bodyAsChannel()
            val lineBuffer = StringBuilder()
            
            while (!channel.isClosedForRead && !isCancelled) {
                val line = channel.readUTF8Line() ?: break
                
                when {
                    line.isEmpty() -> {
                        val chunk = lineBuffer.toString().trim()
                        if (chunk.isNotEmpty() && chunk != "[DONE]") {
                            try {
                                val jsonChunk = Json.parseToJsonElement(chunk).jsonObject
                                val delta = jsonChunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                    ?.get("delta")?.jsonObject
                                delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { text ->
                                    if (text.isNotEmpty()) {
                                        send(text)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        lineBuffer.clear()
                    }
                    line.startsWith("data:") -> {
                        val dataContent = line.substring(5).trim()
                        if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                        lineBuffer.append(dataContent)
                    }
                }
            }
        }
    }
    
    /**
     * 取消当前会话
     */
    fun cancel() {
        isCancelled = true
        currentJob?.cancel()
        Log.i(TAG, "语音对话会话已取消")
    }
    
    /**
     * 合并音频块
     */
    private fun mergeAudioChunks(chunks: List<ByteArray>): ByteArray {
        if (chunks.isEmpty()) return ByteArray(0)
        
        val totalSize = chunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        
        return result
    }
    
    /**
     * 去除 Markdown 格式（TTS 不需要）
     */
    private fun stripMarkdownForTts(text: String): String {
        var result = text
        
        // 移除代码块
        result = result.replace(Regex("```[\\s\\S]*?```"), "")
        result = result.replace(Regex("`[^`]+`"), "")
        
        // 移除链接
        result = result.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        
        // 移除加粗和斜体
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        result = result.replace(Regex("\\*([^*]+)\\*"), "$1")
        result = result.replace(Regex("__([^_]+)__"), "$1")
        result = result.replace(Regex("_([^_]+)_"), "$1")
        
        // 移除标题标记
        result = result.replace(Regex("^#+\\s+", RegexOption.MULTILINE), "")
        
        // 移除列表标记
        result = result.replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "")
        result = result.replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
        
        return result.trim()
    }
    
    /**
     * 语音对话结果
     */
    data class VoiceChatResult(
        val userText: String,          // 用户说的话（识别结果）
        val assistantText: String,     // AI 回复
        val audioData: ByteArray,      // 音频数据
        val audioFormat: String,       // 音频格式
        val sampleRate: Int            // 采样率
    )
}
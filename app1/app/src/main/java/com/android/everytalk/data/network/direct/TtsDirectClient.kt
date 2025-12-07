package com.android.everytalk.data.network.direct

import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*

/**
 * TTS 直连客户端
 * 
 * 支持直接从 Android 客户端调用各种 TTS API，无需经过后端代理。
 * 
 * 支持的平台：
 * - Gemini (Google)
 * - OpenAI
 * - SiliconFlow (流式)
 * - Minimax
 */
object TtsDirectClient {
    private const val TAG = "TtsDirectClient"
    
    /**
     * TTS 配置
     */
    data class TtsConfig(
        val platform: String,    // "Gemini", "OpenAI", "SiliconFlow", "Minimax"
        val apiKey: String,
        val apiUrl: String?,     // 可选，使用自定义 API 地址
        val model: String,
        val voiceName: String
    )
    
    /**
     * 音频格式配置
     */
    data class AudioFormat(
        val format: String,      // "pcm", "opus", "mp3", "wav"
        val sampleRate: Int      // 采样率，如 24000, 32000
    )
    
    // 平台默认音频格式
    private val AUDIO_FORMAT_CONFIG = mapOf(
        "gemini" to AudioFormat("pcm", 24000),
        "openai" to AudioFormat("opus", 24000),
        "siliconflow" to AudioFormat("pcm", 32000),
        "minimax" to AudioFormat("pcm", 32000),
        "aliyun" to AudioFormat("pcm", 24000)
    )
    
    /**
     * 获取平台对应的音频格式
     */
    fun getAudioFormat(platform: String): AudioFormat {
        return AUDIO_FORMAT_CONFIG[platform.lowercase()] ?: AudioFormat("pcm", 24000)
    }
    
    /**
     * 执行 TTS 合成（非流式）
     * 
     * @param client HTTP 客户端
     * @param config TTS 配置
     * @param text 待合成文本
     * @return 音频数据
     */
    suspend fun synthesize(
        client: HttpClient,
        config: TtsConfig,
        text: String
    ): ByteArray {
        return when (config.platform.lowercase()) {
            "gemini", "google" -> synthesizeWithGemini(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voiceName = config.voiceName,
                model = config.model,
                apiUrl = config.apiUrl
            )
            "openai" -> synthesizeWithOpenAI(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voiceName = config.voiceName,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.openai.com/v1"
            )
            "siliconflow" -> synthesizeWithSiliconFlow(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voice = config.voiceName,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.siliconflow.cn/v1/audio/speech"
            )
            "minimax" -> synthesizeWithMinimax(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voiceId = config.voiceName,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.minimax.chat/v1/text_to_speech"
            )
            "aliyun" -> synthesizeWithAliyun(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voice = config.voiceName,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
            )
            else -> throw IllegalArgumentException("Unsupported TTS platform: ${config.platform}")
        }
    }
    
    /**
     * 执行 TTS 合成（流式）
     * 
     * 仅 SiliconFlow 支持真正的流式输出。
     * 其他平台会将完整音频作为单个块返回。
     * 
     * @param client HTTP 客户端
     * @param config TTS 配置
     * @param text 待合成文本
     * @return 音频数据流
     */
    fun synthesizeStream(
        client: HttpClient,
        config: TtsConfig,
        text: String
    ): Flow<ByteArray> = channelFlow {
        when (config.platform.lowercase()) {
            "siliconflow" -> {
                synthesizeWithSiliconFlowStream(
                    client = client,
                    text = text,
                    apiKey = config.apiKey,
                    voice = config.voiceName,
                    model = config.model,
                    apiUrl = config.apiUrl ?: "https://api.siliconflow.cn/v1/audio/speech"
                ).collect { chunk ->
                    send(chunk)
                }
            }
            "aliyun" -> {
                // Aliyun 也支持流式
                synthesizeWithAliyunStream(
                    client = client,
                    text = text,
                    apiKey = config.apiKey,
                    voice = config.voiceName,
                    model = config.model,
                    apiUrl = config.apiUrl ?: "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
                ).collect { chunk ->
                    send(chunk)
                }
            }
            else -> {
                // 其他平台使用非流式接口，一次性返回
                val audioData = synthesize(client, config, text)
                if (audioData.isNotEmpty()) {
                    send(audioData)
                }
            }
        }
    }
    
    /**
     * Minimax TTS
     */
    suspend fun synthesizeWithMinimax(
        client: HttpClient,
        text: String,
        apiKey: String,
        voiceId: String,
        model: String,
        apiUrl: String
    ): ByteArray {
        Log.i(TAG, "Minimax TTS: $model, voice=$voiceId at $apiUrl")
        
        val payload = buildJsonObject {
            put("model", model)
            put("text", text)
            put("stream", false)
            putJsonObject("voice_setting") {
                put("voice_id", voiceId)
                put("speed", 1.0)
                put("vol", 1.0)
                put("pitch", 0)
            }
            putJsonObject("audio_setting") {
                put("sample_rate", 32000)
                put("bitrate", 128000)
                put("format", "pcm")
                put("channel", 1)
            }
        }.toString()
        
        // Minimax API:
        // 1. 如果用户在 URL 中指定了 GroupId，则直接使用
        // 2. 如果未指定，尝试直接调用（新版 API 可能仅需 Bearer Token）
        // 注意：不再强制追加硬编码的 GroupId，这会导致 "token not match group" 错误
        
        try {
            val response = client.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(payload)
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "Minimax TTS failed: ${response.status} - $errorBody")
                throw Exception("Minimax TTS failed: ${response.status}")
            }
            
            // Minimax 返回的是音频流，直接读取字节
            val audioData = response.readBytes()
            
            // Minimax API 返回的是 JSON，里面包含 hex 编码的音频数据
            val responseText = String(audioData)
            try {
                val json = Json.parseToJsonElement(responseText).jsonObject
                
                // 检查错误
                val baseResp = json["base_resp"]?.jsonObject
                val statusCode = baseResp?.get("status_code")?.jsonPrimitive?.intOrNull
                
                if (statusCode != null && statusCode != 0) {
                    val statusMsg = baseResp?.get("status_msg")?.jsonPrimitive?.contentOrNull
                    Log.e(TAG, "Minimax TTS API error: $statusCode - $statusMsg")
                    throw Exception("Minimax TTS API error: $statusMsg")
                }
                
                // 提取音频数据 (hex string)
                val audioHex = json["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.contentOrNull
                
                if (audioHex.isNullOrEmpty()) {
                    Log.e(TAG, "Minimax TTS returned no audio data")
                    throw Exception("Minimax TTS returned no audio data")
                }
                
                // Hex string to ByteArray
                val pcmData = hexStringToByteArray(audioHex)
                Log.i(TAG, "Minimax TTS completed: ${pcmData.size} bytes (from hex)")
                return pcmData
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Minimax response", e)
                throw e
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Minimax TTS error", e)
            throw e
        }
    }
    
    /**
     * Gemini TTS
     * 
     * 使用 Gemini 的语音合成能力
     */
    suspend fun synthesizeWithGemini(
        client: HttpClient,
        text: String,
        apiKey: String,
        voiceName: String,
        model: String,
        apiUrl: String? = null
    ): ByteArray {
        val baseUrl = apiUrl?.trimEnd('/') ?: "https://generativelanguage.googleapis.com"
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"
        
        Log.i(TAG, "Gemini TTS: $model, voice=$voiceName, text=${text.take(50)}...")
        
        // 构建请求体
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", text)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add("AUDIO")
                }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceName)
                        }
                    }
                }
            }
        }.toString()
        
        try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "Gemini TTS failed: ${response.status} - $errorBody")
                throw Exception("Gemini TTS failed: ${response.status}")
            }
            
            val responseText = response.bodyAsText()
            val jsonResponse = Json.parseToJsonElement(responseText).jsonObject
            
            // 提取音频数据
            val audioBase64 = jsonResponse["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("inlineData")?.jsonObject
                ?.get("data")?.jsonPrimitive?.contentOrNull
            
            if (audioBase64.isNullOrEmpty()) {
                Log.w(TAG, "Gemini TTS returned no audio data")
                return ByteArray(0)
            }
            
            val pcmData = Base64.decode(audioBase64, Base64.DEFAULT)
            Log.i(TAG, "Gemini TTS completed: ${pcmData.size} bytes")
            return pcmData
            
        } catch (e: Exception) {
            Log.e(TAG, "Gemini TTS error", e)
            throw e
        }
    }
    
    /**
     * OpenAI TTS
     */
    suspend fun synthesizeWithOpenAI(
        client: HttpClient,
        text: String,
        apiKey: String,
        voiceName: String,
        model: String,
        apiUrl: String,
        responseFormat: String = "opus"
    ): ByteArray {
        // 智能补全 URL
        val targetUrl = if (apiUrl.endsWith("/speech")) {
            apiUrl
        } else {
            "${apiUrl.trimEnd('/')}/audio/speech"
        }
        
        Log.i(TAG, "OpenAI TTS: $model, voice=$voiceName at $targetUrl")
        
        val payload = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voiceName)
            put("response_format", responseFormat)
        }.toString()
        
        try {
            val response = client.post(targetUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(payload)
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "OpenAI TTS failed: ${response.status} - $errorBody")
                throw Exception("OpenAI TTS failed: ${response.status}")
            }
            
            val audioData = response.readBytes()
            Log.i(TAG, "OpenAI TTS completed: ${audioData.size} bytes")
            return audioData
            
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI TTS error", e)
            throw e
        }
    }
    
    /**
     * SiliconFlow TTS（非流式）
     */
    suspend fun synthesizeWithSiliconFlow(
        client: HttpClient,
        text: String,
        apiKey: String,
        voice: String,
        model: String,
        apiUrl: String,
        responseFormat: String = "pcm",
        sampleRate: Int = 32000
    ): ByteArray {
        Log.i(TAG, "SiliconFlow TTS: $model, voice=$voice at $apiUrl")
        
        // 处理 voice 参数，如果未包含模型前缀则自动添加
        val finalVoice = if (model.isNotEmpty() && voice.isNotEmpty() && !voice.contains(":")) {
            "$model:$voice"
        } else {
            voice
        }
        
        val payload = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", finalVoice)
            put("response_format", responseFormat)
            put("sample_rate", sampleRate)
            put("stream", false)
        }.toString()
        
        try {
            val response = client.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(payload)
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "SiliconFlow TTS failed: ${response.status} - $errorBody")
                throw Exception("SiliconFlow TTS failed: ${response.status}")
            }
            
            val audioData = response.readBytes()
            Log.i(TAG, "SiliconFlow TTS completed: ${audioData.size} bytes")
            return audioData
            
        } catch (e: Exception) {
            Log.e(TAG, "SiliconFlow TTS error", e)
            throw e
        }
    }
    
    /**
     * SiliconFlow TTS（流式）
     */
    fun synthesizeWithSiliconFlowStream(
        client: HttpClient,
        text: String,
        apiKey: String,
        voice: String,
        model: String,
        apiUrl: String,
        responseFormat: String = "pcm",
        sampleRate: Int = 32000
    ): Flow<ByteArray> = channelFlow {
        Log.i(TAG, "SiliconFlow Stream TTS: $model, voice=$voice")
        
        // 处理 voice 参数
        val finalVoice = if (model.isNotEmpty() && voice.isNotEmpty() && !voice.contains(":")) {
            "$model:$voice"
        } else {
            voice
        }
        
        // IndexTTS-2 模型特殊处理
        val finalSampleRate = if (model.contains("IndexTTS-2")) 24000 else sampleRate
        val enableStream = !model.contains("IndexTTS-2")
        
        val payload = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", finalVoice)
            put("response_format", responseFormat)
            put("sample_rate", finalSampleRate)
            put("stream", enableStream)
        }.toString()
        
        try {
            client.preparePost(apiUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(payload)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                    Log.e(TAG, "SiliconFlow Stream TTS failed: ${response.status} - $errorBody")
                    throw Exception("SiliconFlow Stream TTS failed: ${response.status}")
                }
                
                val channel = response.bodyAsChannel()
                var chunkCount = 0
                var totalBytes = 0
                
                while (!channel.isClosedForRead) {
                    val buffer = ByteArray(8192)
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        chunkCount++
                        totalBytes += bytesRead
                        send(chunk)
                    }
                }
                
                Log.i(TAG, "SiliconFlow Stream TTS completed: $chunkCount chunks, $totalBytes bytes")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "SiliconFlow Stream TTS error", e)
            throw e
        }
    }
    /**
     * Aliyun TTS (非流式)
     *
     * 根据阿里云 DashScope TTS API 文档，请求格式需要将 voice 等参数放在顶层
     * 参考: https://help.aliyun.com/zh/model-studio/developer-reference/qwen-tts
     */
    suspend fun synthesizeWithAliyun(
        client: HttpClient,
        text: String,
        apiKey: String,
        voice: String,
        model: String,
        apiUrl: String
    ): ByteArray {
        // 智能补全 Aliyun URL
        val targetUrl = if (apiUrl.endsWith("/generation")) {
            apiUrl
        } else {
            "${apiUrl.trimEnd('/')}/services/aigc/multimodal-generation/generation"
        }

        val actualVoice = voice.ifEmpty { "Cherry" }
        val actualModel = model.ifEmpty { "qwen3-tts-flash" }
        
        Log.i(TAG, "Aliyun TTS: model=$actualModel, voice=$actualVoice at $targetUrl")
        
        // 按照阿里云 DashScope CosyVoice TTS API 格式构建请求
        // 参考: https://help.aliyun.com/zh/model-studio/developer-reference/cosyvoice-large-model-api
        // voice 需要放在 input 对象内部
        val payload = buildJsonObject {
            put("model", actualModel)
            putJsonObject("input") {
                put("text", text)
                put("voice", actualVoice)  // voice 必须在 input 内部
            }
            putJsonObject("parameters") {
                put("sample_rate", 24000)
                put("format", "pcm")
            }
        }.toString()
        
        Log.d(TAG, "Aliyun TTS request payload: $payload")
        
        try {
            val response = client.post(targetUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header("X-DashScope-Data-Inspection", "enable")
                setBody(payload)
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "Aliyun TTS failed: ${response.status} - $errorBody")
                throw Exception("Aliyun TTS failed: ${response.status}")
            }
            
            val responseText = response.bodyAsText()
            val json = Json.parseToJsonElement(responseText).jsonObject
            
            // 检查 output.audio
            val output = json["output"]?.jsonObject
            // 根据官方文档，audio 是一个对象，包含 data 和 url
            val audioObj = output?.get("audio")?.jsonObject
            val audioData = audioObj?.get("data")?.jsonPrimitive?.contentOrNull
            
            if (!audioData.isNullOrEmpty()) {
                // Base64 decode
                val pcmData = Base64.decode(audioData, Base64.DEFAULT)
                Log.i(TAG, "Aliyun TTS completed: ${pcmData.size} bytes")
                return pcmData
            }
            
            // 检查是否有错误信息
            val code = json["code"]?.jsonPrimitive?.contentOrNull
            val message = json["message"]?.jsonPrimitive?.contentOrNull
            if (code != null) {
                Log.e(TAG, "Aliyun TTS API error: $code - $message")
                throw Exception("Aliyun TTS API error: $message")
            }
            
            throw Exception("Aliyun TTS returned no audio data")
            
        } catch (e: Exception) {
            Log.e(TAG, "Aliyun TTS error", e)
            throw e
        }
    }

    /**
     * Aliyun TTS (流式)
     * 使用 SSE (Server-Sent Events)
     *
     * 根据阿里云 DashScope TTS API 文档，请求格式需要将 voice 等参数放在顶层
     * 参考: https://help.aliyun.com/zh/model-studio/developer-reference/qwen-tts
     */
    fun synthesizeWithAliyunStream(
        client: HttpClient,
        text: String,
        apiKey: String,
        voice: String,
        model: String,
        apiUrl: String
    ): Flow<ByteArray> = channelFlow {
        // 智能补全 Aliyun URL
        val targetUrl = if (apiUrl.endsWith("/generation")) {
            apiUrl
        } else {
            "${apiUrl.trimEnd('/')}/services/aigc/multimodal-generation/generation"
        }

        val actualVoice = voice.ifEmpty { "Cherry" }
        val actualModel = model.ifEmpty { "qwen3-tts-flash" }
        
        Log.i(TAG, "Aliyun Stream TTS: model=$actualModel, voice=$actualVoice at $targetUrl")
        
        // 按照阿里云 DashScope CosyVoice TTS API 格式构建请求
        // 参考: https://help.aliyun.com/zh/model-studio/developer-reference/cosyvoice-large-model-api
        // voice 需要放在 input 对象内部
        val payload = buildJsonObject {
            put("model", actualModel)
            putJsonObject("input") {
                put("text", text)
                put("voice", actualVoice)  // voice 必须在 input 内部
            }
            putJsonObject("parameters") {
                put("sample_rate", 24000)
                put("format", "pcm")
            }
        }.toString()
        
        Log.d(TAG, "Aliyun Stream TTS request payload: $payload")
        
        try {
            client.preparePost(targetUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header("X-DashScope-Data-Inspection", "enable")
                header("X-DashScope-SSE", "enable") // 强制开启 SSE
                header(HttpHeaders.Accept, "text/event-stream") // 明确指定接收 SSE
                setBody(payload)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                    Log.e(TAG, "Aliyun Stream TTS failed: ${response.status} - $errorBody")
                    
                    // 尝试解析错误信息
                    try {
                        val json = Json.parseToJsonElement(errorBody).jsonObject
                        val code = json["code"]?.jsonPrimitive?.contentOrNull
                        val message = json["message"]?.jsonPrimitive?.contentOrNull
                        if (code != null) {
                            throw Exception("Aliyun TTS Error: $message ($code)")
                        }
                    } catch (_: Exception) {}
                    
                    throw Exception("Aliyun Stream TTS failed: ${response.status}")
                }
                
                val channel = response.bodyAsChannel()
                var chunkCount = 0
                var totalBytes = 0
                
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    
                    if (line.startsWith("data:")) {
                        val jsonStr = line.substring(5).trim()
                        if (jsonStr.isEmpty()) continue
                        
                        try {
                            val json = Json.parseToJsonElement(jsonStr).jsonObject
                            
                            // 检查错误
                            val code = json["code"]?.jsonPrimitive?.contentOrNull
                            if (code != null) {
                                val msg = json["message"]?.jsonPrimitive?.contentOrNull
                                Log.e(TAG, "Aliyun Stream TTS error in stream: $code - $msg")
                                throw Exception("Aliyun TTS Error: $msg ($code)")
                            }
                            
                            // 检查 output.audio
                            val output = json["output"]?.jsonObject
                            // 官方文档: output.audio.data
                            val audioObj = output?.get("audio")?.jsonObject
                            val audioData = audioObj?.get("data")?.jsonPrimitive?.contentOrNull
                                            ?: audioObj?.get("audio")?.jsonPrimitive?.contentOrNull // 尝试兼容旧格式
                                            ?: output?.get("audio")?.jsonPrimitive?.contentOrNull // 尝试直接获取
                            
                            if (!audioData.isNullOrEmpty()) {
                                val chunk = Base64.decode(audioData, Base64.DEFAULT)
                                if (chunk.isNotEmpty()) {
                                    send(chunk)
                                    chunkCount++
                                    totalBytes += chunk.size
                                }
                            }
                            
                            // 检查结束
                            val finishReason = output?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                            if (finishReason == "stop") {
                                break
                            }
                            
                        } catch (e: Exception) {
                            if (e.message?.startsWith("Aliyun TTS Error") == true) throw e
                            // 忽略解析错误，继续处理下一行
                            Log.w(TAG, "Failed to parse Aliyun stream line: ${jsonStr.take(100)}...", e)
                        }
                    } else if (line.startsWith("id:") || line.startsWith("event:")) {
                        // 忽略 SSE 元数据
                    } else if (line.isNotEmpty()) {
                        // 尝试直接解析 JSON (有些响应可能不带 data: 前缀)
                        try {
                            val json = Json.parseToJsonElement(line).jsonObject
                            val code = json["code"]?.jsonPrimitive?.contentOrNull
                            if (code != null) {
                                val msg = json["message"]?.jsonPrimitive?.contentOrNull
                                Log.e(TAG, "Aliyun Stream TTS error: $code - $msg")
                                throw Exception("Aliyun TTS Error: $msg ($code)")
                            }
                        } catch (_: Exception) {}
                    }
                }
                
                Log.i(TAG, "Aliyun Stream TTS completed: $chunkCount chunks, $totalBytes bytes")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Aliyun Stream TTS error", e)
            throw e
        }
    }

    /**
     * Helper function to convert hex string to byte array
     */
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
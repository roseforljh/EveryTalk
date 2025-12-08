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

    // 平台默认音色列表 (作为 Fallback 数据源)
    // 如果 UI 层没有传递有效的音色 ID，或者传递了其他平台的 ID，则使用此列表的第一个作为默认值
    private val VOICE_LISTS = mapOf(
        "minimax" to listOf(
            "female-shaonv",      // 少女 (默认)
            "male-qn-qingse",     // 青涩青年
            "male-qn-jingying",   // 精英青年
            "female-yujie",       // 御姐
            "presenter_male",     // 男主持人
            "presenter_female",   // 女主持人
            "audiobook_male_1",   // 有声书男1
            "audiobook_male_2",   // 有声书男2
            "audiobook_female_1", // 有声书女1
            "audiobook_female_2"  // 有声书女2
        ),
        "aliyun" to listOf(
            "Cherry",             // 知性女声 (默认)
            "Harry",              // 磁性男声
            "NuoNuo",             // 可爱童声
            "Farui",              // 活力女声
            "Maxwell"             // 浑厚男声
        ),
        "gemini" to listOf(
            "Puck",               // 默认
            "Charon",
            "Kore",
            "Fenrir",
            "Aoede"
        ),
        "openai" to listOf(
            "alloy",              // 默认
            "echo",
            "fable",
            "onyx",
            "nova",
            "shimmer"
        ),
        "siliconflow" to listOf(
            "fishaudio/fish-speech-1.5:alex", // 默认
            "fishaudio/fish-speech-1.5:benjamin",
            "fishaudio/fish-speech-1.5:charles",
            "fishaudio/fish-speech-1.5:david",
            "fishaudio/fish-speech-1.5:anna",
            "fishaudio/fish-speech-1.5:bella",
            "fishaudio/fish-speech-1.5:claire",
            "fishaudio/fish-speech-1.5:diana"
        )
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
                voiceName = resolveVoice(config.voiceName, "gemini"),
                model = config.model,
                apiUrl = config.apiUrl
            )
            "openai" -> synthesizeWithOpenAI(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voiceName = resolveVoice(config.voiceName, "openai"),
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.openai.com/v1"
            )
            "siliconflow" -> synthesizeWithSiliconFlow(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voice = resolveVoice(config.voiceName, "siliconflow"),
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.siliconflow.cn/v1/audio/speech"
            )
            "minimax" -> synthesizeWithMinimax(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voiceId = resolveVoice(config.voiceName, "minimax"),
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.minimax.chat/v1/text_to_speech"
            )
            "aliyun" -> synthesizeWithAliyun(
                client = client,
                text = text,
                apiKey = config.apiKey,
                voice = resolveVoice(config.voiceName, "aliyun"),
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
                    voice = resolveVoice(config.voiceName, "siliconflow"),
                    model = config.model,
                    apiUrl = config.apiUrl ?: "https://api.siliconflow.cn/v1/audio/speech"
                ).collect { chunk ->
                    send(chunk)
                }
            }
            "minimax" -> {
                synthesizeWithMinimaxStream(
                    client = client,
                    text = text,
                    apiKey = config.apiKey,
                    voiceId = resolveVoice(config.voiceName, "minimax"),
                    model = config.model,
                    apiUrl = config.apiUrl ?: "https://api.minimaxi.com/v1/t2a_v2"
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
                    voice = resolveVoice(config.voiceName, "aliyun"),
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
     * Minimax TTS (非流式)
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
            
            val responseText = response.bodyAsText()
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
     * Minimax TTS (流式)
     */
    fun synthesizeWithMinimaxStream(
        client: HttpClient,
        text: String,
        apiKey: String,
        voiceId: String,
        model: String,
        apiUrl: String
    ): Flow<ByteArray> = channelFlow {
        Log.i(TAG, "Minimax Stream TTS: $model, voice=$voiceId at $apiUrl")
        
        val payload = buildJsonObject {
            put("model", model)
            put("text", text)
            put("stream", true) // 开启流式
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
        
        try {
            client.preparePost(apiUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(payload)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                    Log.e(TAG, "Minimax Stream TTS failed: ${response.status} - $errorBody")
                    throw Exception("Minimax Stream TTS failed: ${response.status}")
                }
                
                val channel = response.bodyAsChannel()
                var chunkCount = 0
                var totalBytes = 0
                
                // Minimax 流式响应是连续的 JSON 对象，可能没有换行符分隔，也可能有
                // 这里我们需要逐个解析 JSON 对象
                // 简单的做法是读取直到遇到 '}' 且括号平衡，或者按行读取（如果服务端有换行）
                // 观察示例，通常是 SSE 风格或者 JSON Lines
                // 假设是 JSON Lines 或者连续 JSON
                
                // 使用 StringBuilder 缓冲数据
                val buffer = StringBuilder()
                val readBuffer = ByteArray(4096)
                
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(readBuffer)
                    if (bytesRead > 0) {
                        val chunkText = String(readBuffer, 0, bytesRead)
                        buffer.append(chunkText)
                        
                        // 尝试解析缓冲区中的 JSON 对象
                        // Minimax 返回的是 SSE 格式: data: {...}
                        // 我们需要处理 "data: " 前缀
                        
                        var startIndex = 0
                        var braceCount = 0
                        var inString = false
                        var escape = false
                        var jsonStartIndex = -1 // 记录 JSON 开始的 '{' 位置
                        
                        for (i in 0 until buffer.length) {
                            val char = buffer[i]
                            if (escape) {
                                escape = false
                            } else if (char == '\\') {
                                escape = true
                            } else if (char == '"') {
                                inString = !inString
                            } else if (!inString) {
                                if (char == '{') {
                                    if (braceCount == 0) {
                                        jsonStartIndex = i
                                    }
                                    braceCount++
                                } else if (char == '}') {
                                    braceCount--
                                    if (braceCount == 0 && jsonStartIndex != -1) {
                                        // 找到一个完整的 JSON 对象
                                        val jsonStr = buffer.substring(jsonStartIndex, i + 1)
                                        
                                        // 更新 startIndex 到当前位置之后，准备处理下一个
                                        startIndex = i + 1
                                        jsonStartIndex = -1
                                        
                                        try {
                                            val json = Json.parseToJsonElement(jsonStr).jsonObject
                                            
                                            // 检查错误
                                            val baseResp = json["base_resp"]?.jsonObject
                                            val statusCode = baseResp?.get("status_code")?.jsonPrimitive?.intOrNull
                                            if (statusCode != null && statusCode != 0) {
                                                val statusMsg = baseResp?.get("status_msg")?.jsonPrimitive?.contentOrNull
                                                Log.e(TAG, "Minimax Stream TTS API error: $statusCode - $statusMsg")
                                                throw Exception("Minimax TTS API error: $statusMsg")
                                            }
                                            
                                            // 提取音频
                                            val dataObj = json["data"]?.jsonObject
                                            val audioHex = dataObj?.get("audio")?.jsonPrimitive?.contentOrNull
                                            val status = dataObj?.get("status")?.jsonPrimitive?.intOrNull // 1=continue, 2=end
                                            
                                            // Minimax 流式 API 坑点：status=2 的包可能包含全量音频
                                            // 策略：如果 status=2 且之前已经收到过数据，则忽略当前包的音频，防止重复播放
                                            // 除非 audioHex 很短（可能是最后一个分片），但为了保险起见，
                                            // 如果用户反馈重复，极有可能是 status=2 包含了全量数据。
                                            // 观察示例，status=2 的包带有 extra_info，很可能是汇总包。
                                            
                                            val isEndPacket = (status == 2)
                                            val shouldSkipAudio = isEndPacket && chunkCount > 0
                                            
                                            if (!audioHex.isNullOrEmpty()) {
                                                if (shouldSkipAudio) {
                                                    Log.w(TAG, "Skipping audio in Minimax status=2 packet to avoid duplication")
                                                } else {
                                                    val pcmData = hexStringToByteArray(audioHex)
                                                    if (pcmData.isNotEmpty()) {
                                                        send(pcmData)
                                                        chunkCount++
                                                        totalBytes += pcmData.size
                                                    }
                                                }
                                            }
                                            
                                            if (isEndPacket) {
                                                // 结束
                                            }
                                            
                                        } catch (e: Exception) {
                                            // 如果是 API 错误，重新抛出以便上层处理（触发重试）
                                            if (e.message?.contains("Minimax TTS API error") == true) {
                                                throw e
                                            }
                                            Log.w(TAG, "Failed to parse Minimax stream chunk: ${jsonStr.take(50)}...", e)
                                        }
                                    }
                                }
                            }
                        }
                        
                        // 移除已处理的部分
                        if (startIndex > 0) {
                            buffer.delete(0, startIndex)
                        }
                    }
                }
                
                Log.i(TAG, "Minimax Stream TTS completed: $chunkCount chunks, $totalBytes bytes")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Minimax Stream TTS error", e)
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
    /**
     * 解析并验证音色 ID
     *
     * 1. 检查传入的 voiceId 是否属于当前 platform 的有效列表
     * 2. 如果是，直接返回
     * 3. 如果不是（例如切换平台后残留了旧平台的音色），则返回当前 platform 的第一个默认音色
     */
    private fun resolveVoice(voiceId: String, platform: String): String {
        val platformKey = platform.lowercase()
        val validVoices = VOICE_LISTS[platformKey] ?: return voiceId // 未知平台不处理
        
        // 1. 精确匹配
        if (validVoices.any { it.equals(voiceId, ignoreCase = true) }) {
            return voiceId
        }
        
        // 2. 模糊匹配/特殊映射 (保留之前的 Minimax 映射逻辑作为增强)
        if (platformKey == "minimax") {
             val mapped = when (voiceId.lowercase()) {
                "cherry" -> "female-shaonv"
                "harry" -> "male-qn-qingse"
                "kore" -> "female-yujie"
                else -> null
            }
            if (mapped != null) return mapped
            
            // Minimax ID 通常较长或包含连字符，如果看起来像有效ID也放行
            if (voiceId.contains("-") || voiceId.length > 10) return voiceId
        }
        
        // 3. 默认回退：使用列表第一个
        val defaultVoice = validVoices.first()
        Log.w(TAG, "Voice '$voiceId' is invalid for platform '$platform', falling back to default: '$defaultVoice'")
        return defaultVoice
    }
}
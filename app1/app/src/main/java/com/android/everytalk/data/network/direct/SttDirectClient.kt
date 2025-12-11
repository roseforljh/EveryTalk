package com.android.everytalk.data.network.direct

import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * STT 直连客户端
 *
 * 支持直接从 Android 客户端调用各种 STT API，无需经过后端代理。
 *
 * 支持的平台：
 * - Gemini (Google)
 * - OpenAI (Whisper)
 * - SiliconFlow
 * - Aliyun (阿里云 Fun-ASR 实时语音识别)
 */
object SttDirectClient {
    private const val TAG = "SttDirectClient"
    
    /**
     * STT 配置
     */
    data class SttConfig(
        val platform: String,    // "Gemini", "OpenAI", "SiliconFlow", "Aliyun"
        val apiKey: String,
        val apiUrl: String?,     // 可选，使用自定义 API 地址
        val model: String,
        val sampleRate: Int = 16000,  // 采样率，默认 16000
        val format: String = "pcm"    // 音频格式，默认 pcm
    )
    
    /**
     * 执行 STT 识别
     *
     * @param client HTTP 客户端
     * @param config STT 配置
     * @param audioData 音频数据
     * @param mimeType 音频 MIME 类型 (如 "audio/wav", "audio/opus")
     * @return 识别的文本
     */
    suspend fun transcribe(
        client: HttpClient,
        config: SttConfig,
        audioData: ByteArray,
        mimeType: String
    ): String {
        return when (config.platform.lowercase()) {
            "gemini", "google" -> transcribeWithGemini(
                client = client,
                audioData = audioData,
                mimeType = mimeType,
                apiKey = config.apiKey,
                model = config.model,
                apiUrl = config.apiUrl
            )
            "openai" -> transcribeWithOpenAI(
                client = client,
                audioData = audioData,
                apiKey = config.apiKey,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.openai.com/v1"
            )
            "siliconflow" -> transcribeWithSiliconFlow(
                client = client,
                audioData = audioData,
                mimeType = mimeType,
                apiKey = config.apiKey,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://api.siliconflow.cn/v1/audio/transcriptions"
            )
            "aliyun" -> transcribeWithAliyun(
                client = client,
                audioData = audioData,
                mimeType = mimeType,
                apiKey = config.apiKey,
                model = config.model,
                apiUrl = config.apiUrl ?: "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"
            )
            else -> throw IllegalArgumentException("Unsupported STT platform: ${config.platform}")
        }
    }
    
    /**
     * Gemini STT
     * 
     * 使用 Gemini 的多模态能力进行语音识别
     */
    suspend fun transcribeWithGemini(
        client: HttpClient,
        audioData: ByteArray,
        mimeType: String,
        apiKey: String,
        model: String,
        apiUrl: String? = null
    ): String {
        val baseUrl = apiUrl?.trimEnd('/') ?: "https://generativelanguage.googleapis.com"
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"
        
        Log.i(TAG, "Gemini STT: $model, audio size=${audioData.size}, mimeType=$mimeType")
        
        // 构建请求体
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        // 音频数据
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", mimeType)
                                put("data", Base64.encodeToString(audioData, Base64.NO_WRAP))
                            }
                        }
                        // 提示词
                        addJsonObject {
                            put("text", "转录：")
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
                Log.e(TAG, "Gemini STT failed: ${response.status} - $errorBody")
                throw Exception("Gemini STT failed: ${response.status}")
            }
            
            val responseText = response.bodyAsText()
            val jsonResponse = Json.parseToJsonElement(responseText).jsonObject
            
            // 提取文本
            val text = jsonResponse["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            
            Log.i(TAG, "Gemini STT result: ${text.take(100)}...")
            return text
            
        } catch (e: Exception) {
            Log.e(TAG, "Gemini STT error", e)
            throw e
        }
    }
    
    /**
     * OpenAI Whisper STT
     */
    suspend fun transcribeWithOpenAI(
        client: HttpClient,
        audioData: ByteArray,
        apiKey: String,
        model: String,
        apiUrl: String
    ): String {
        // 智能补全 URL
        val targetUrl = if (apiUrl.endsWith("/transcriptions")) {
            apiUrl
        } else {
            "${apiUrl.trimEnd('/')}/audio/transcriptions"
        }
        
        Log.i(TAG, "OpenAI STT: $model at $targetUrl, audio size=${audioData.size}")
        
        try {
            val response = client.submitFormWithBinaryData(
                url = targetUrl,
                formData = formData {
                    append("model", model)
                    append("file", "audio.wav", ContentType.Audio.Any) {
                        writeFully(audioData)
                    }
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "OpenAI STT failed: ${response.status} - $errorBody")
                throw Exception("OpenAI STT failed: ${response.status}")
            }
            
            val responseText = response.bodyAsText()
            val jsonResponse = Json.parseToJsonElement(responseText).jsonObject
            val text = jsonResponse["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            
            Log.i(TAG, "OpenAI STT result: ${text.take(100)}...")
            return text
            
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI STT error", e)
            throw e
        }
    }
    
    /**
     * SiliconFlow STT
     */
    suspend fun transcribeWithSiliconFlow(
        client: HttpClient,
        audioData: ByteArray,
        mimeType: String,
        apiKey: String,
        model: String,
        apiUrl: String
    ): String {
        Log.i(TAG, "SiliconFlow STT: $model at $apiUrl, audio size=${audioData.size}")
        
        // 根据 mimeType 确定文件扩展名
        val ext = mimeType.substringAfter("/", "wav")
        val filename = "audio.$ext"
        
        try {
            val response = client.submitFormWithBinaryData(
                url = apiUrl,
                formData = formData {
                    append("model", model)
                    append("file", filename, ContentType.parse(mimeType)) {
                        writeFully(audioData)
                    }
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                Log.e(TAG, "SiliconFlow STT failed: ${response.status} - $errorBody")
                throw Exception("SiliconFlow STT failed: ${response.status}")
            }
            
            val responseText = response.bodyAsText()
            val jsonResponse = Json.parseToJsonElement(responseText).jsonObject
            val text = jsonResponse["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            
            if (text.isEmpty()) {
                Log.w(TAG, "SiliconFlow STT returned empty text")
            } else {
                Log.i(TAG, "SiliconFlow STT result: ${text.take(100)}...")
            }
            
            return text
            
        } catch (e: Exception) {
            Log.e(TAG, "SiliconFlow STT error", e)
            throw e
        }
    }
    
    /**
     * 阿里云 Fun-ASR 实时语音识别
     *
     * 使用阿里云 DashScope WebSocket API 进行实时流式语音识别。
     * 支持 fun-asr-realtime 系列模型。
     *
     * 参考文档：https://help.aliyun.com/zh/model-studio/developer-reference/funasr-real-time-speech-recognition-api
     *
     * @param client HTTP 客户端（需要支持 WebSocket）
     * @param audioData 完整的音频数据
     * @param mimeType 音频 MIME 类型
     * @param apiKey 阿里云 API Key
     * @param model 模型名称，如 "fun-asr-realtime-2025-11-07"
     * @param apiUrl WebSocket URL（可选）
     * @param sampleRate 采样率，默认 16000
     * @return 识别的文本
     */
    suspend fun transcribeWithAliyun(
        client: HttpClient,
        audioData: ByteArray,
        mimeType: String,
        apiKey: String,
        model: String,
        apiUrl: String,
        sampleRate: Int = 16000
    ): String {
        Log.i(TAG, "Aliyun STT START: model=$model, audio size=${audioData.size}")
        
        // 处理音频数据：如果是 WAV 格式，需要剥离 44 字节头信息
        // 阿里云 Fun-ASR WebSocket API 期望的是纯 PCM 数据，不是 WAV 文件
        val actualAudioData: ByteArray
        val format: String
        
        if (mimeType.contains("wav") && audioData.size > 44) {
            // 验证 WAV 头签名 (RIFF....WAVE)
            val isWav = audioData[0] == 'R'.code.toByte() &&
                audioData[1] == 'I'.code.toByte() &&
                audioData[2] == 'F'.code.toByte() &&
                audioData[3] == 'F'.code.toByte() &&
                audioData[8] == 'W'.code.toByte() &&
                audioData[9] == 'A'.code.toByte() &&
                audioData[10] == 'V'.code.toByte() &&
                audioData[11] == 'E'.code.toByte()
            
            if (isWav) {
                actualAudioData = audioData.copyOfRange(44, audioData.size)
                format = "pcm"  // WAV 内部是 PCM 编码
            } else {
                actualAudioData = audioData
                format = "pcm"
            }
        } else if (mimeType.contains("opus") || mimeType.contains("ogg")) {
            actualAudioData = audioData
            format = "opus"
        } else if (mimeType.contains("mp3")) {
            actualAudioData = audioData
            format = "mp3"
        } else {
            // PCM 或其他格式，直接使用
            actualAudioData = audioData
            format = "pcm"
        }
        
        // WebSocket URL
        val wsUrl = if (apiUrl != null && (apiUrl.startsWith("wss://") || apiUrl.startsWith("ws://"))) {
            apiUrl
        } else {
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        }
        
        try {
            return transcribeWithAliyunWebSocket(
                client = client,
                audioData = actualAudioData,  // 使用处理后的音频数据
                format = format,
                sampleRate = sampleRate,
                apiKey = apiKey,
                model = model.ifEmpty { "fun-asr-realtime" },
                wsUrl = wsUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Aliyun STT error: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 使用 WebSocket 进行阿里云实时语音识别
     */
    private suspend fun transcribeWithAliyunWebSocket(
        client: HttpClient,
        audioData: ByteArray,
        format: String,
        sampleRate: Int,
        apiKey: String,
        model: String,
        wsUrl: String
    ): String = coroutineScope {
        val resultChannel = Channel<String>(Channel.UNLIMITED)
        val finalTextBuilder = StringBuilder()
        var taskId = UUID.randomUUID().toString()
        
        try {
            client.wss(
                urlString = wsUrl,
                request = {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header("X-DashScope-DataInspection", "enable")
                }
            ) {
                Log.i(TAG, "Aliyun STT WebSocket connected")
                
                // 发送 run-task 指令启动识别
                val runTaskMessage = buildJsonObject {
                    putJsonObject("header") {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    }
                    putJsonObject("payload") {
                        put("model", model)
                        put("task_group", "audio")
                        put("task", "asr")
                        put("function", "recognition")
                        putJsonObject("input") {}
                        putJsonObject("parameters") {
                            put("format", format)
                            put("sample_rate", sampleRate)
                            put("enable_intermediate_result", true) // 启用中间结果
                            put("enable_punctuation", true)         // 启用标点
                            put("language_hints", buildJsonArray { add("zh"); add("en") })
                        }
                    }
                }.toString()
                
                send(Frame.Text(runTaskMessage))
                
                // 等待 task-started 事件
                var taskStarted = false
                val startTimeout = withTimeoutOrNull(10_000L) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            
                            val json = Json.parseToJsonElement(text).jsonObject
                            val event = json["header"]?.jsonObject?.get("event")?.jsonPrimitive?.contentOrNull
                            
                            if (event == "task-started") {
                                taskStarted = true
                                break
                            } else if (event == "task-failed") {
                                val errorMsg = json["header"]?.jsonObject?.get("error_message")?.jsonPrimitive?.contentOrNull
                                    ?: "Unknown error"
                                throw Exception("Aliyun STT task failed: $errorMsg")
                            }
                        }
                    }
                }
                
                if (!taskStarted) {
                    throw Exception("Aliyun STT: Timeout waiting for task-started")
                }
                
                // 启动接收协程
                val receiveJob = launch {
                    try {
                        var resultCount = 0
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    
                                    val json = Json.parseToJsonElement(text).jsonObject
                                    val event = json["header"]?.jsonObject?.get("event")?.jsonPrimitive?.contentOrNull
                                    
                                    when (event) {
                                        "result-generated" -> {
                                            resultCount++
                                            // 提取识别结果
                                            val output = json["payload"]?.jsonObject?.get("output")?.jsonObject
                                            val sentence = output?.get("sentence")?.jsonObject
                                            
                                            if (sentence != null) {
                                                val sentenceText = sentence["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val isEnd = sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull ?: false
                                                
                                                // 阿里云实时流式返回的是整个句子的结果，如果 isEnd=true，则该句子已固定
                                                // 我们只需要收集 isEnd=true 的句子
                                                if (isEnd && sentenceText.isNotEmpty()) {
                                                    synchronized(finalTextBuilder) {
                                                        finalTextBuilder.append(sentenceText)
                                                    }
                                                }
                                            }
                                        }
                                        "task-finished" -> {
                                            resultChannel.send(finalTextBuilder.toString())
                                            return@launch
                                        }
                                        "task-failed" -> {
                                            val errorCode = json["header"]?.jsonObject?.get("error_code")?.jsonPrimitive?.contentOrNull
                                            val errorMsg = json["header"]?.jsonObject?.get("error_message")?.jsonPrimitive?.contentOrNull
                                                ?: "Unknown error"
                                            Log.e(TAG, "Aliyun STT task-failed: code=$errorCode, message=$errorMsg")
                                            throw Exception("Aliyun STT task failed: [$errorCode] $errorMsg")
                                        }
                                        else -> {
                                            // 忽略其他事件
                                        }
                                    }
                                }
                                is Frame.Close -> {
                                    resultChannel.send(finalTextBuilder.toString())
                                    return@launch
                                }
                                else -> {
                                    // 忽略非文本帧
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Aliyun STT receive error: ${e.message}", e)
                        resultChannel.send(finalTextBuilder.toString())
                    }
                }
                
                // 分块发送音频数据
                val chunkSize = 3200  // 100ms @ 16kHz 16bit mono = 3200 bytes
                var offset = 0
                var chunkCount = 0
                
                while (offset < audioData.size) {
                    val end = minOf(offset + chunkSize, audioData.size)
                    val chunk = audioData.copyOfRange(offset, end)
                    chunkCount++
                    
                    // 根据阿里云 DashScope WebSocket API 文档：
                    // 音频数据应该通过**二进制帧 (Binary Frame)** 发送，而不是 JSON 消息中的 Base64
                    // 参考: https://help.aliyun.com/zh/model-studio/developer-reference/funasr-real-time-speech-recognition-api
                    send(Frame.Binary(true, chunk))
                    offset = end
                    
                    // 模拟实时发送，每块间隔约 100ms（对应 3200 字节 @ 16kHz 16bit mono）
                    // 但由于是一次性录音后发送，我们加快速度
                    delay(10)
                }
                
                // 发送 finish-task 指令结束识别
                val finishTaskMessage = buildJsonObject {
                    putJsonObject("header") {
                        put("action", "finish-task")
                        put("task_id", taskId)
                    }
                    putJsonObject("payload") {
                        putJsonObject("input") {}
                    }
                }.toString()
                
                send(Frame.Text(finishTaskMessage))
                
                // 等待接收完成 (长语音可能需要更长时间)
                withTimeout(60_000L) {
                    receiveJob.join()
                }
            }
            
            // 从通道获取结果
            val result = resultChannel.tryReceive().getOrNull() ?: finalTextBuilder.toString()
            Log.i(TAG, "Aliyun STT final result: ${result.take(100)}...")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Aliyun STT WebSocket error", e)
            throw e
        } finally {
            resultChannel.close()
        }
    }
}
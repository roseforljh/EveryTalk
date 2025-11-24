package com.android.everytalk.data.network

import android.media.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.io.BufferedReader
import com.android.everytalk.security.RequestSignatureUtil

/**
 * 语音对话会话（新流程）：STT → Chat → TTS
 * 
 * 流程：
 * 1. 用户录音（16kHz PCM）
 * 2. 发送到后端进行语音识别（STT）
 * 3. 将识别的文字发送给AI获取回复
 * 4. 将AI回复转换为语音（TTS）
 * 5. 播放语音
 * 
 * 返回：识别的文字、AI回复文字、语音数据
 */
class VoiceChatSession(
    private val chatHistory: List<Pair<String, String>> = emptyList(), // List of (role, content)
    private val systemPrompt: String = "",
    
    // STT Config
    private val sttPlatform: String = "Google",
    private val sttApiKey: String = "",
    private val sttApiUrl: String = "",
    private val sttModel: String = "",
    
    // Chat Config
    private val chatPlatform: String = "Google",
    private val chatApiKey: String = "",
    private val chatApiUrl: String = "",
    private val chatModel: String = "",
    
    // TTS Config
    private val ttsPlatform: String = "Gemini",
    private val ttsApiKey: String = "",
    private val ttsApiUrl: String = "",
    private val ttsModel: String = "",
    private val voiceName: String = "Kore",
    
    private val onVolumeChanged: ((Float) -> Unit)? = null,
    private val onTranscriptionReceived: ((String) -> Unit)? = null, // 识别到的文字回调
    private val onResponseReceived: ((String) -> Unit)? = null  // AI回复文字回调
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording: Boolean = false
    private val pcmBuffer = ByteArrayOutputStream(256 * 1024)

    // 录音参数（16k / 16-bit / mono）
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    companion object {
        private const val TAG = "VoiceChatSession"
        // 接口路径（编译时混淆）
        private val API_PATH = buildString {
            append("/voice")
            append("-chat")
            append("/complete")
        }

        // 全局单例 OkHttpClient，复用连接池以减少握手延迟
        private val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .connectionPool(
                    okhttp3.ConnectionPool(
                        maxIdleConnections = 5,
                        keepAliveDuration = 5,
                        TimeUnit.MINUTES
                    )
                )
                .build()
        }
    }

    /**
     * 开始录音
     */
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext

        val recorder = createAudioRecord()
            ?: throw IllegalStateException("AudioRecord init failed. Check RECORD_AUDIO permission.")

        audioRecord = recorder
        pcmBuffer.reset()

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            audioRecord = null
            throw e
        }

        isRecording = true

        // 启动读取循环
        val readBuf = ByteArray(2048)
        var lastVolumeUpdateTime = 0L

        try {
            while (isRecording) {
                val n = recorder.read(readBuf, 0, readBuf.size)
                
                if (n > 0) {
                    pcmBuffer.write(readBuf, 0, n)

                    // 计算音量（RMS）并回调给UI
                    val currentTime = System.currentTimeMillis()
                    if (onVolumeChanged != null && currentTime - lastVolumeUpdateTime >= 50) {
                        lastVolumeUpdateTime = currentTime

                        var sum = 0.0
                        var i = 0
                        while (i < n - 1) {
                            val sample = (readBuf[i].toInt() and 0xFF) or (readBuf[i + 1].toInt() shl 8)
                            val shortValue = sample.toShort()
                            sum += shortValue * shortValue
                            i += 2
                        }
                        val sampleCount = n / 2
                        if (sampleCount > 0) {
                            val rms = kotlin.math.sqrt(sum / sampleCount)
                            val normalizedVolume = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()

                            withContext(Dispatchers.Main) {
                                onVolumeChanged?.invoke(normalizedVolume)
                            }
                        }
                    }
                } else if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord read error: $n")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Recording loop error", t)
        }
    }

    /**
     * 取消录音
     * 停止录音并丢弃数据，不进行后续处理
     */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext
        
        isRecording = false
        
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
        
        pcmBuffer.reset()
        Log.i(TAG, "Recording cancelled and data discarded")
    }

    /**
     * 强制释放资源（非协程版本，用于生命周期销毁时）
     */
    fun forceRelease() {
        if (!isRecording && audioRecord == null) return
        
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
        Log.i(TAG, "Force released audio recorder")
    }

    /**
     * 停止录音并处理完整的语音对话流程
     */
    suspend fun stopRecordingAndProcess(): VoiceChatResult = withContext(Dispatchers.IO) {
        isRecording = false

        // 停止录音
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null

        val pcmData = pcmBuffer.toByteArray()
        if (pcmData.isEmpty()) {
            throw IllegalStateException("No audio data recorded")
        }

        Log.i(TAG, "Recorded ${pcmData.size} bytes of PCM data")

        // 将PCM保存为临时WAV文件（OkHttp上传需要文件）
        val tempWavFile = File.createTempFile("voice_chat_", ".wav")
        try {
            tempWavFile.outputStream().use { out ->
                // 写入WAV头部
                writeWavHeader(out, pcmData.size, sampleRate, 1, 16)
                // 写入PCM数据
                out.write(pcmData)
            }

            // 构建对话历史JSON
            val historyJson = JSONArray()
            chatHistory.forEach { (role, content) ->
                historyJson.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }

            // 使用全局单例 client 复用连接
            val client = okHttpClient

            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "recording.wav",
                    tempWavFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("chat_history", historyJson.toString())
                .addFormDataPart("system_prompt", systemPrompt)
                
                // STT
                .addFormDataPart("stt_platform", sttPlatform)
                .addFormDataPart("stt_api_key", sttApiKey)
                .addFormDataPart("stt_api_url", sttApiUrl)
                .addFormDataPart("stt_model", sttModel)
                
                // Chat
                .addFormDataPart("chat_platform", chatPlatform)
                .addFormDataPart("chat_api_key", chatApiKey)
                .addFormDataPart("chat_api_url", chatApiUrl)
                .addFormDataPart("chat_model", chatModel)
                
                // TTS
                .addFormDataPart("voice_platform", ttsPlatform)
                .addFormDataPart("voice_name", voiceName)
                .addFormDataPart("tts_api_key", ttsApiKey)
                .addFormDataPart("tts_api_url", ttsApiUrl)
                .addFormDataPart("tts_model", ttsModel)

            // 针对 Minimax 和 SiliconFlow 启用流式
            if (ttsPlatform == "Minimax" || ttsPlatform == "SiliconFlow") {
                requestBodyBuilder.addFormDataPart("stream", "true")
            }

            val requestBody = requestBodyBuilder.build()

            // 使用配置的URL和混淆的路径
            val baseUrl = com.android.everytalk.BuildConfig.VOICE_BACKEND_URL
            val apiUrl = baseUrl + API_PATH
            
            // 生成签名头
            // 注意：MultipartBody 的内容签名比较复杂，这里简化处理，只对空 body 进行签名
            // 后端中间件应该配置为对 multipart/form-data 请求放宽 body 校验，或者只校验 path/method/timestamp
            // 根据 RequestSignatureUtil 的实现，body 为 null 时 bodyHash 为空字符串
            val signatureHeaders = RequestSignatureUtil.generateSignatureHeaders(
                method = "POST",
                path = API_PATH,
                body = null // MultipartBody 难以在此处获取完整字符串，且包含二进制文件
            )
            
            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .post(requestBody)
            
            // 添加签名头
            signatureHeaders.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            
            val request = requestBuilder.build()

            Log.i(TAG, "Sending voice chat request to: $apiUrl")

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Voice chat request failed: ${response.code} - $errorBody")
                throw Exception("Voice chat failed: ${response.code} - $errorBody")
            }

            val contentType = response.header("Content-Type")
            
            // 判断是否为流式响应 (NDJSON)
            if (contentType?.contains("application/x-ndjson") == true) {
                return@withContext handleStreamingResponse(response)
            }

            // 原有非流式逻辑
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            val jsonResponse = JSONObject(responseBody)

            val userText = jsonResponse.getString("user_text")
            val assistantText = jsonResponse.getString("assistant_text")
            val audioBase64 = jsonResponse.optString("audio_base64", "")
            val audioFormat = jsonResponse.optString("audio_format", "wav")
            val sampleRate = jsonResponse.optInt("sample_rate", 24000)

            Log.i(TAG, "Voice chat completed successfully")
            Log.i(TAG, "User: $userText")
            Log.i(TAG, "Assistant: $assistantText")

            // 回调识别和回复文字
            withContext(Dispatchers.Main) {
                onTranscriptionReceived?.invoke(userText)
                onResponseReceived?.invoke(assistantText)
            }

            // 解码并播放音频数据（如果有）
            val audioData = if (audioBase64.isNotEmpty()) {
                try {
                    val decoded = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                    // 播放音频
                    playAudio(decoded, sampleRate)
                    decoded
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode/play audio (TTS quota exhausted?)", e)
                    ByteArray(0)
                }
            } else {
                Log.w(TAG, "No audio data (TTS quota exhausted), text-only mode")
                ByteArray(0)
            }

            VoiceChatResult(
                userText = userText,
                assistantText = assistantText,
                audioData = audioData,
                audioFormat = audioFormat,
                sampleRate = sampleRate
            )
        } finally {
            // 清理临时文件
            try {
                tempWavFile.delete()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 播放音频数据
     */
    private suspend fun playAudio(audioData: ByteArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Playing audio: ${audioData.size} bytes at ${sampleRate}Hz")

        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufSize, 8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()

            var offset = 0
            val chunkSize = 4096
            while (offset < audioData.size) {
                val toWrite = minOf(chunkSize, audioData.size - offset)
                val written = track.write(audioData, offset, toWrite)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack write error: $written")
                    break
                }
                offset += written
            }

            // 等待播放完成 (防止尾部截断)
            // 计算总帧数 (16-bit = 2 bytes per frame)
            val totalFrames = audioData.size / 2
            var waitedMs = 0
            val timeoutMs = (totalFrames.toFloat() / sampleRate * 1000).toLong() + 2000 // 理论时长 + 2秒缓冲
            
            while (waitedMs < timeoutMs) {
                if (track.playState == AudioTrack.PLAYSTATE_STOPPED) break
                
                // getPlaybackHeadPosition() 返回的是帧数
                val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                
                if (currentPosition >= totalFrames) {
                    Log.i(TAG, "Standard playback completed ($currentPosition / $totalFrames)")
                    break
                }
                
                kotlinx.coroutines.delay(50)
                waitedMs += 50
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Audio playback error", t)
        } finally {
            try {
                track.stop()
            } catch (_: Throwable) {}
            try {
                track.release()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 创建AudioRecord
     */
    private fun createAudioRecord(): AudioRecord? {
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = (if (minBufSize > 0) minBufSize else 2048) * 2

        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        for (src in sources) {
            try {
                val recorder = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufSize)
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord initialized with source: $src")
                    return recorder
                }
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AudioRecord with source $src: ${e.message}")
            }
        }

        return null
    }

    /**
     * 写入WAV文件头部
     */
    private fun writeWavHeader(
        out: java.io.OutputStream,
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36 + dataSize

        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToBytes(chunkSize))
        out.write("WAVE".toByteArray())

        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToBytes(16)) // Subchunk1Size
        out.write(shortToBytes(1)) // AudioFormat (1 = PCM)
        out.write(shortToBytes(channels))
        out.write(intToBytes(sampleRate))
        out.write(intToBytes(byteRate))
        out.write(shortToBytes(blockAlign))
        out.write(shortToBytes(bitsPerSample))

        // data chunk
        out.write("data".toByteArray())
        out.write(intToBytes(dataSize))
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
    /**
     * 处理流式响应
     */
    private suspend fun handleStreamingResponse(response: okhttp3.Response): VoiceChatResult {
        Log.i(TAG, "Starting streaming response processing")
        
        var userText = ""
        var assistantText = ""
        val fullAudioData = ByteArrayOutputStream()
        var sampleRate = 24000
        var audioPlayer: StreamAudioPlayer? = null
        
        try {
            response.body?.byteStream()?.use { inputStream ->
                val reader = BufferedReader(java.io.InputStreamReader(inputStream))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) continue
                    
                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type")
                        
                        when (type) {
                            "meta" -> {
                                userText = json.optString("user_text", "")
                                assistantText = json.optString("assistant_text", "")
                                // 立即回调文字
                                withContext(Dispatchers.Main) {
                                    if (userText.isNotEmpty()) onTranscriptionReceived?.invoke(userText)
                                    if (assistantText.isNotEmpty()) onResponseReceived?.invoke(assistantText)
                                }
                            }
                            "audio" -> {
                                val b64Data = json.optString("data", "")
                                if (b64Data.isNotEmpty()) {
                                    val chunk = android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT)
                                    if (chunk.isNotEmpty()) {
                                        fullAudioData.write(chunk)
                                        
                                        // 初始化播放器（收到第一包音频时）
                                        if (audioPlayer == null) {
                                            // Minimax 流式通常是 24000 或 32000，这里假设 24000，后续可优化
                                            audioPlayer = StreamAudioPlayer(sampleRate)
                                            audioPlayer?.start()
                                        }
                                        
                                        // 写入播放器
                                        audioPlayer?.write(chunk)
                                    }
                                }
                            }
                            "error" -> {
                                val msg = json.optString("message", "Unknown error")
                                Log.e(TAG, "Stream error: $msg")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse stream line: $line", e)
                    }
                }
            }
        } finally {
            // 等待播放完成并释放
            audioPlayer?.close()
        }
        
        return VoiceChatResult(
            userText = userText,
            assistantText = assistantText,
            audioData = fullAudioData.toByteArray(),
            audioFormat = "pcm",
            sampleRate = sampleRate
        )
    }

    /**
     * 流式音频播放器
     */
    private class StreamAudioPlayer(private val sampleRate: Int) {
        private var audioTrack: AudioTrack? = null
        private val bufferSize: Int
        
        // 预缓冲配置
        private var totalWrittenBytes = 0
        private var isBuffering = true
        // 预缓冲 0.3 秒的数据量，平衡首字延迟与抗抖动能力
        private var nextPlayThreshold = (sampleRate * 2 * 0.3).toInt()

        init {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            // 大幅增大缓冲区至 4 秒，从根本上减少 Underrun 概率
            bufferSize = maxOf(minBufSize * 8, sampleRate * 2 * 4)
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
        
        fun start() {
            // 不再立即播放，由 write() 中的预缓冲逻辑触发
            // audioTrack?.play()
        }
        
        fun write(data: ByteArray) {
            val track = audioTrack ?: return
            val written = track.write(data, 0, data.size)
            
            if (written > 0) {
                totalWrittenBytes += written
                
                // 检查播放状态，处理 Underrun 自动恢复
                if (isBuffering) {
                    // 缓冲阶段：达到阈值才播放
                    if (totalWrittenBytes >= nextPlayThreshold) {
                        try {
                            track.play()
                            isBuffering = false
                            Log.i("StreamAudioPlayer", "Buffering complete ($totalWrittenBytes bytes), starting playback")
                        } catch (e: Exception) {
                            Log.e("StreamAudioPlayer", "Failed to start playback", e)
                        }
                    }
                } else {
                    // 播放阶段：检查是否意外停止 (Underrun)
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        Log.w("StreamAudioPlayer", "Underrun detected (state=${track.playState}), pausing to re-buffer")
                        isBuffering = true
                        // 重新缓冲 1.5 秒数据后再继续，避免频繁启停 (从 0.5 增加到 1.5)
                        nextPlayThreshold = totalWrittenBytes + (sampleRate * 2 * 1.5).toInt()
                    }
                }
            }
        }
        
        fun close() {
            try {
                val track = audioTrack ?: return
                
                // 1. 确保处于播放状态
                if (totalWrittenBytes > 0 && (isBuffering || track.playState != AudioTrack.PLAYSTATE_PLAYING)) {
                    track.play()
                    Log.i("StreamAudioPlayer", "Force starting playback in close()")
                }
                
                // 2. 阻塞等待播放完成 (防止尾部截断)
                val totalFrames = totalWrittenBytes / 2 // 16-bit = 2 bytes per frame
                var waitedMs = 0
                val timeoutMs = 15000 // 最多等15秒
                
                // 只有当实际写入了数据才等待
                if (totalFrames > 0) {
                    while (waitedMs < timeoutMs) {
                        // 检查 Track 状态
                        if (track.playState == AudioTrack.PLAYSTATE_STOPPED) break
                        
                        // 检查播放进度 (HeadPosition 可能会溢出，但短对话通常没事，严谨可用 mask)
                        // 注意：getPlaybackHeadPosition() 返回的是帧数
                        val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        
                        if (currentPosition >= totalFrames) {
                            Log.i("StreamAudioPlayer", "Playback completed naturally ($currentPosition / $totalFrames)")
                            break
                        }
                        
                        // 简单的超时退出：如果进度长时间不动(如AudioTrack卡死)，这里会有timeoutMs保护
                        Thread.sleep(50)
                        waitedMs += 50
                    }
                }
                
                if (waitedMs >= timeoutMs) {
                    Log.w("StreamAudioPlayer", "Playback wait timed out")
                }

                // 3. 释放资源
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.w("StreamAudioPlayer", "Error closing AudioTrack", e)
            } finally {
                audioTrack = null
            }
        }
    }
}

/**
 * 语音对话结果
 */
data class VoiceChatResult(
    val userText: String,          // 用户说的话（识别结果）
    val assistantText: String,     // AI回复
    val audioData: ByteArray,      // 音频数据
    val audioFormat: String,       // 音频格式
    val sampleRate: Int            // 采样率
)


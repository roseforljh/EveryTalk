package com.android.everytalk.data.network

import android.media.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import com.android.everytalk.config.PerformanceConfig
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
    
    // 网络请求控制
    @Volatile
    private var currentCall: okhttp3.Call? = null
    
    // 音频播放控制
    @Volatile
    private var currentAudioTrack: AudioTrack? = null
    @Volatile
    private var streamAudioPlayer: StreamAudioPlayer? = null
    @Volatile
    private var shouldStopPlayback = false

    // 录音参数（采样率 / 16-bit / mono）
    private val sampleRate = PerformanceConfig.VOICE_RECORD_SAMPLE_RATE_HZ
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

                    // 计算音量（RMS）并回调给UI（降低频率 + 抽样以减少CPU开销）
                    val currentTime = System.currentTimeMillis()
                    if (onVolumeChanged != null &&
                        currentTime - lastVolumeUpdateTime >= PerformanceConfig.VOICE_RECORD_VOLUME_UPDATE_INTERVAL_MS
                    ) {
                        lastVolumeUpdateTime = currentTime

                        var sum = 0.0
                        val step = PerformanceConfig.VOICE_RECORD_VOLUME_SAMPLE_STEP.coerceAtLeast(1)
                        var i = 0
                        val limit = n - 1
                        while (i < limit) {
                            val sample = (readBuf[i].toInt() and 0xFF) or (readBuf[i + 1].toInt() shl 8)
                            val shortValue = sample.toShort()
                            sum += shortValue * shortValue
                            i += 2 * step
                        }
                        val sampleCount = (n / 2) / step
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
     * 停止当前播放的音频
     */
    fun stopPlayback() {
        shouldStopPlayback = true
        
        // 取消网络请求
        try {
            currentCall?.cancel()
        } catch (_: Throwable) {}
        currentCall = null
        
        // 停止标准播放
        currentAudioTrack?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (_: Throwable) {}
        }
        currentAudioTrack = null
        
        // 停止流式播放
        streamAudioPlayer?.let { player ->
            try {
                player.forceStop()
            } catch (_: Throwable) {}
        }
        streamAudioPlayer = null
        
        Log.i(TAG, "Audio playback stopped by user")
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
        
        // 同时停止播放
        stopPlayback()
        
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

        // 基于 PCM 数据估算录音时长（毫秒）
        val totalSamples = pcmData.size / 2 // 16-bit PCM: 2 bytes per sample
        val recordDurationMs = if (sampleRate > 0) {
            (totalSamples * 1000L) / sampleRate
        } else {
            0L
        }
        val isShortUtterance =
            recordDurationMs in 1..PerformanceConfig.VOICE_SHORT_UTTERANCE_MAX_DURATION_MS

        // 尝试将 PCM 编码为 AAC (m4a) 以减小上传体积
        // 短语音直接走 WAV 上传以降低首包延迟；长语音保留 AAC 压缩策略
        var uploadFile: File? = null
        var mimeType = "audio/wav"
        
        if (!isShortUtterance) {
            try {
                val tempAacFile = File.createTempFile("voice_chat_", ".m4a")
                val success = encodePcmToAac(pcmData, tempAacFile, sampleRate)
                if (success && tempAacFile.length() > 0) {
                    uploadFile = tempAacFile
                    mimeType = "audio/mp4" // m4a
                    Log.i(TAG, "Encoded PCM to AAC: ${pcmData.size} -> ${uploadFile.length()} bytes (duration=${recordDurationMs}ms)")
                } else {
                    tempAacFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "AAC encoding failed, falling back to WAV", e)
            }
        } else {
            Log.i(TAG, "Short utterance ($recordDurationMs ms), skip AAC and upload as WAV")
        }

        // 如果 AAC 编码失败或未执行，使用 WAV
        if (uploadFile == null) {
            val tempWavFile = File.createTempFile("voice_chat_", ".wav")
            tempWavFile.outputStream().use { out ->
                writeWavHeader(out, pcmData.size, sampleRate, 1, 16)
                out.write(pcmData)
            }
            uploadFile = tempWavFile
            mimeType = "audio/wav"
            Log.i(TAG, "Saved PCM as WAV: ${uploadFile.length()} bytes")
        }

        try {
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
                    "recording.${if (mimeType == "audio/mp4") "m4a" else "wav"}",
                    uploadFile.asRequestBody(mimeType.toMediaType())
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
            
            val call = client.newCall(request)
            currentCall = call
            
            val response = try {
                call.execute()
            } catch (e: Exception) {
                if (call.isCanceled()) {
                    throw java.util.concurrent.CancellationException("Request cancelled")
                }
                throw e
            } finally {
                currentCall = null
            }

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
                uploadFile?.delete()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 使用 MediaCodec 将 PCM 编码为 AAC (M4A)
     */
    private fun encodePcmToAac(pcmData: ByteArray, outputFile: File, sampleRate: Int): Boolean {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32000) // 32kbps for STT is sufficient and faster
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            
            val bufferInfo = MediaCodec.BufferInfo()
            var inputOffset = 0
            var outputDone = false
            
            val inputBuffers = encoder.inputBuffers
            // 注意: outputBuffers 在 API 21+ 已废弃，但为了兼容性使用 getOutputBuffer(index)
            
            while (!outputDone) {
                // 1. 写入输入数据
                if (inputOffset < pcmData.size) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            encoder.getInputBuffer(inputBufferIndex)
                        } else {
                            inputBuffers[inputBufferIndex]
                        }
                        
                        inputBuffer?.clear()
                        val chunkSize = minOf(inputBuffer?.capacity() ?: 0, pcmData.size - inputOffset)
                        inputBuffer?.put(pcmData, inputOffset, chunkSize)
                        
                        inputOffset += chunkSize
                        
                        val flags = if (inputOffset >= pcmData.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(inputBufferIndex, 0, chunkSize, System.nanoTime() / 1000, flags)
                    }
                }
                
                // 2. 读取输出数据
                var encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputOffset >= pcmData.size) {
                        // 输入已结束，但这只是暂时没有输出，继续循环等待 EOS
                    }
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw IllegalStateException("format changed twice")
                    val newFormat = encoder.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encoderStatus < 0) {
                    // ignore
                } else {
                    // 获取编码后的数据
                    val outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        encoder.getOutputBuffer(encoderStatus)
                    } else {
                        encoder.outputBuffers[encoderStatus]
                    }
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    
                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) throw IllegalStateException("muxer hasn't started")
                        outputBuffer?.position(bufferInfo.offset)
                        outputBuffer?.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer!!, bufferInfo)
                    }
                    
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
            
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec exception", e)
            return false
        }
    }

    /**
     * 播放音频数据
     */
    private suspend fun playAudio(audioData: ByteArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Playing audio: ${audioData.size} bytes at ${sampleRate}Hz")
        
        shouldStopPlayback = false

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

        currentAudioTrack = track

        try {
            track.play()

            var offset = 0
            val chunkSize = 4096
            while (offset < audioData.size && !shouldStopPlayback) {
                val toWrite = minOf(chunkSize, audioData.size - offset)
                val written = track.write(audioData, offset, toWrite)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack write error: $written")
                    break
                }
                offset += written
            }
            
            if (shouldStopPlayback) {
                Log.i(TAG, "Playback interrupted by user")
                return@withContext
            }

            // 等待播放完成 (防止尾部截断)
            // 计算总帧数 (16-bit = 2 bytes per frame)
            val totalFrames = audioData.size / 2
            var waitedMs = 0
            val timeoutMs = (totalFrames.toFloat() / sampleRate * 1000).toLong() + 2000 // 理论时长 + 2秒缓冲
            
            while (waitedMs < timeoutMs && !shouldStopPlayback) {
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
            currentAudioTrack = null
            
            // 播放结束后，将音量归零
            withContext(Dispatchers.Main) {
                onVolumeChanged?.invoke(0f)
            }
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
        shouldStopPlayback = false
        
        try {
            response.body?.byteStream()?.use { inputStream ->
                val reader = BufferedReader(java.io.InputStreamReader(inputStream))
                var line: String?
                
                while (reader.readLine().also { line = it } != null && !shouldStopPlayback) {
                    // 检查协程是否已取消，如果取消则抛出 CancellationException
                    kotlin.coroutines.coroutineContext.ensureActive()

                    if (line.isNullOrBlank()) continue
                    
                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type")
                        
                        when (type) {
                            "meta" -> {
                                userText = json.optString("user_text", "")
                                assistantText = json.optString("assistant_text", "")
                                
                                // 更新采样率 (如果后端下发)
                                val sr = json.optInt("sample_rate", 0)
                                if (sr > 0) {
                                    sampleRate = sr
                                    Log.i(TAG, "Updated sample rate from stream meta: $sampleRate")
                                }
                                
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
                                            streamAudioPlayer = audioPlayer
                                            audioPlayer?.start()
                                        }
                                        
                                        // 写入播放器
                                        audioPlayer?.write(chunk)
                                    }
                                }
                            }
                            "ping" -> {
                                // 保持连接活跃，忽略
                            }
                            "error" -> {
                                val msg = json.optString("message", "Unknown error")
                                Log.e(TAG, "Stream error: $msg")
                            }
                        }
                    } catch (e: Exception) {
                        // 取消类异常（包括 JobCancellationException）直接向上抛出，避免当成解析错误刷屏
                        if (e is java.util.concurrent.CancellationException ||
                            e.javaClass.name.contains("CancellationException")) {
                            throw e
                        }
                        // 截断日志，防止刷屏
                        val logLine = if (line != null && line!!.length > 200) line!!.substring(0, 200) + "..." else line
                        Log.w(TAG, "Failed to parse stream line: $logLine", e)
                    }
                }
            }
        } finally {
            // 等待播放完成并释放
            audioPlayer?.close()
            streamAudioPlayer = null
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
    private inner class StreamAudioPlayer(private val sampleRate: Int) {
        private var audioTrack: AudioTrack? = null
        private val bufferSize: Int
        
        // 预缓冲配置
        private var totalWrittenBytes = 0
        private var isBuffering = true
        // 按配置的预缓冲时长计算需要写入的字节数
        private var nextPlayThreshold =
            ((sampleRate * 2L * PerformanceConfig.VOICE_STREAM_PREBUFFER_MS) / 1000L).toInt()
        
        // 字节对齐缓冲 (处理奇数包)
        private var leftoverByte: Byte? = null

        // 音量计算
        private var lastVolumeUpdateTime = 0L

        init {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            // 缓冲区大小调整：1秒左右，兼顾稳定性和延迟
            bufferSize = maxOf(minBufSize * 2, sampleRate * 2 * 1)
            
            Log.i("StreamAudioPlayer", "Init AudioTrack: sampleRate=$sampleRate, minBuf=$minBufSize, actualBuf=$bufferSize")

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
        
        @Volatile
        private var forceStop = false
        
        fun start() {
            // 不再立即播放，由 write() 中的预缓冲逻辑触发
            // audioTrack?.play()
        }
        
        fun forceStop() {
            forceStop = true
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
            } catch (_: Throwable) {}
        }
        
        suspend fun write(data: ByteArray) {
            if (forceStop) return
            val track = audioTrack ?: return
            
            // 计算音量并回调
            val currentTime = System.currentTimeMillis()
            if (onVolumeChanged != null && currentTime - lastVolumeUpdateTime >= 50) {
                lastVolumeUpdateTime = currentTime
                
                // 简单的 RMS 计算 (只取一部分样本以提高性能)
                var sum = 0.0
                val step = 4 // 步长，减少计算量
                var i = 0
                while (i < data.size - 1) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    val shortValue = sample.toShort()
                    sum += shortValue * shortValue
                    i += 2 * step
                }
                
                val sampleCount = data.size / (2 * step)
                if (sampleCount > 0) {
                    val rms = kotlin.math.sqrt(sum / sampleCount)
                    // 调整归一化系数，让波形更明显
                    val normalizedVolume = (rms / 2000.0).coerceIn(0.0, 1.0).toFloat()
                    
                    withContext(Dispatchers.Main) {
                        onVolumeChanged?.invoke(normalizedVolume)
                    }
                }
            }

            // 处理字节对齐 (16-bit PCM 必须偶数写入)
            var dataToWrite = data
            
            // 1. 如果有上次剩余的字节，拼接到开头
            if (leftoverByte != null) {
                val newData = ByteArray(data.size + 1)
                newData[0] = leftoverByte!!
                System.arraycopy(data, 0, newData, 1, data.size)
                dataToWrite = newData
                leftoverByte = null
            }
            
            // 2. 如果当前数据长度是奇数，剥离最后一个字节留给下次
            if (dataToWrite.size % 2 != 0) {
                leftoverByte = dataToWrite[dataToWrite.size - 1]
                dataToWrite = dataToWrite.copyOfRange(0, dataToWrite.size - 1)
            }
            
            if (dataToWrite.isEmpty()) return

            val written = track.write(dataToWrite, 0, dataToWrite.size)
            
            if (written > 0) {
                totalWrittenBytes += written
                
                // 检查播放状态，处理 Underrun 自动恢复
                if (isBuffering) {
                    // 缓冲阶段：达到阈值才播放
                    if (totalWrittenBytes >= nextPlayThreshold) {
                        try {
                            track.play()
                            isBuffering = false
                            Log.i("StreamAudioPlayer", "Buffering complete ($totalWrittenBytes bytes), starting playback. Head: ${track.playbackHeadPosition}")
                        } catch (e: Exception) {
                            Log.e("StreamAudioPlayer", "Failed to start playback", e)
                        }
                    }
                } else {
                    // 播放阶段：检查是否意外停止 (Underrun)
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        Log.w(
                            "StreamAudioPlayer",
                            "Underrun detected (state=${track.playState}, head=${track.playbackHeadPosition}, written=$totalWrittenBytes), pausing to re-buffer"
                        )
                        isBuffering = true
                        // 重新缓冲指定时长的数据
                        nextPlayThreshold =
                            totalWrittenBytes + ((sampleRate * 2L * PerformanceConfig.VOICE_STREAM_PREBUFFER_MS) / 1000L).toInt()
                    }
                }
            } else {
                Log.w("StreamAudioPlayer", "AudioTrack.write returned $written")
            }
        }
        
        fun close() {
            try {
                val track = audioTrack ?: return
                
                if (forceStop) {
                    Log.i("StreamAudioPlayer", "Playback force stopped by user")
                    // track.stop() // Already handled in forceStop
                    track.release()
                    return
                }
                
                // 1. 确保处于播放状态
                if (totalWrittenBytes > 0 && (isBuffering || track.playState != AudioTrack.PLAYSTATE_PLAYING)) {
                    track.play()
                    Log.i("StreamAudioPlayer", "Force starting playback in close()")
                }
                
                // 2. 阻塞等待播放完成 (防止尾部截断)
                val totalFrames = totalWrittenBytes / 2 // 16-bit = 2 bytes per frame
                var waitedMs = 0
                val timeoutMs = PerformanceConfig.VOICE_STREAM_CLOSE_TIMEOUT_MS.toInt()
                var lastPosition = -1L
                var positionStuckCount = 0
                
                Log.i("StreamAudioPlayer", "Waiting for playback completion. Total frames: $totalFrames")

                // 只有当实际写入了数据才等待
                if (totalFrames > 0) {
                    while (waitedMs < timeoutMs && !forceStop) {
                        // 检查 Track 状态
                        if (track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                            Log.i("StreamAudioPlayer", "AudioTrack stopped unexpectedly")
                            break
                        }
                        
                        // 检查播放进度
                        val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        
                        if (currentPosition >= totalFrames) {
                            Log.i("StreamAudioPlayer", "Playback completed naturally ($currentPosition / $totalFrames)")
                            break
                        }
                        
                        // 检测卡死：如果进度条长时间不动
                        if (currentPosition == lastPosition) {
                            positionStuckCount++
                            if (positionStuckCount > 20) { // 1秒不动
                                Log.w("StreamAudioPlayer", "Playback stuck at $currentPosition / $totalFrames for 1s, aborting wait")
                                break
                            }
                        } else {
                            lastPosition = currentPosition
                            positionStuckCount = 0
                        }
                        
                        Thread.sleep(50)
                        waitedMs += 50
                    }
                }
                
                if (waitedMs >= timeoutMs) {
                    Log.w("StreamAudioPlayer", "Playback wait timed out. Final pos: ${track.playbackHeadPosition} / $totalFrames")
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


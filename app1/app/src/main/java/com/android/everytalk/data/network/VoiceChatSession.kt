package com.android.everytalk.data.network

import android.media.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import com.android.everytalk.util.audio.AudioTestUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.network.direct.SttDirectClient
import com.android.everytalk.data.network.direct.TtsDirectClient
import com.android.everytalk.data.network.direct.VoiceChatDirectSession
import com.android.everytalk.data.network.direct.AliyunRealtimeSttClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
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
import java.util.concurrent.atomic.AtomicBoolean

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
    
    // 实时 STT 模式（仅阿里云支持）
    private val useRealtimeStt: Boolean = false,
    
    private val onVolumeChanged: ((Float) -> Unit)? = null,
    private val onTranscriptionReceived: ((String) -> Unit)? = null, // 识别到的文字回调
    private val onResponseReceived: ((String) -> Unit)? = null,  // AI回复文字回调
    private val onWebSocketStateChanged: ((WebSocketState) -> Unit)? = null  // WebSocket 状态回调
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording: Boolean = false
    private val pcmBuffer = ByteArrayOutputStream(256 * 1024)
    
    // 直连会话
    private var directSession: VoiceChatDirectSession? = null
    
    // 阿里云实时 STT 客户端（每次录音创建新的客户端）
    private var aliyunRealtimeSttClient: AliyunRealtimeSttClient? = null
    private var realtimeSttJob: Job? = null
    private val realtimeSttScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 零等待录音：音频缓冲区（用于在 WebSocket 连接建立前缓存音频数据）
    private val pendingAudioBuffer = mutableListOf<ByteArray>()
    private val pendingBufferMutex = Mutex()
    private val isWebSocketReady = AtomicBoolean(false)
    
    // 最大缓冲大小：5 秒音频 @ 16kHz 16bit mono = 160KB
    private val maxPendingBufferSize = 5 * 16000 * 2
    
    // Ktor HTTP 客户端（用于直连模式）
    private val ktorClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(300, TimeUnit.SECONDS)
                    writeTimeout(120, TimeUnit.SECONDS)
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = Long.MAX_VALUE
            }
            install(WebSockets) {
                pingIntervalMillis = 30_000
            }
        }
    }
    
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

    /**
     * WebSocket 连接状态
     */
    enum class WebSocketState {
        DISCONNECTED,   // 未连接
        CONNECTING,     // 正在连接
        CONNECTED,      // 已连接
        ERROR           // 连接错误
    }
    
    companion object {
        private const val TAG = "VoiceChatSession"
    }

    /**
     * 开始录音
     *
     * 如果启用了实时 STT 模式且平台是阿里云，会同时启动实时语音识别。
     *
     * 【零等待录音优化】
     * 按下录音按钮后立即启动 AudioRecord，不等待 WebSocket 连接。
     * 在 WebSocket 连接建立前，音频数据会被缓存到本地。
     * 连接建立后，先发送缓冲的音频，再实时发送后续音频。
     * 这样用户按下按钮后立即看到录音动画，无需等待网络。
     */
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext
        
        val recorder = createAudioRecord()
            ?: throw IllegalStateException("AudioRecord init failed. Check RECORD_AUDIO permission.")

        audioRecord = recorder
        pcmBuffer.reset()
        
        // 重置零等待录音状态
        pendingBufferMutex.withLock {
            pendingAudioBuffer.clear()
        }
        isWebSocketReady.set(false)

        // 如果是阿里云且启用实时 STT
        val shouldUseRealtimeStt = useRealtimeStt && sttPlatform.equals("Aliyun", ignoreCase = true)

        // 【关键优化】先启动录音，再启动 WebSocket 连接（并行）
        try {
            recorder.startRecording()
            Log.i(TAG, "AudioRecord started immediately (zero-wait mode)")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            audioRecord = null
            throw e
        }

        isRecording = true

        // 移除实时 STT 逻辑，强制使用直连模式

        // 启动读取循环
        val readBuf = ByteArray(3200) // 100ms @ 16kHz 16bit mono = 3200 bytes
        var lastVolumeUpdateTime = 0L

        try {
            while (isRecording) {
                val n = recorder.read(readBuf, 0, readBuf.size)
                
                if (n > 0) {
                    // 始终写入 pcmBuffer（用于后续可能的离线 STT 或备份）
                    pcmBuffer.write(readBuf, 0, n)
                    
                    // 移除实时 STT 发送逻辑

                    // 计算音量（RMS）并回调给UI
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
     * 将音频块添加到待发送缓冲区
     * 如果缓冲区已满（超过 5 秒音频），丢弃最旧的数据
     */
    private suspend fun bufferAudioChunk(chunk: ByteArray) {
        pendingBufferMutex.withLock {
            // 计算当前缓冲区大小
            val currentSize = pendingAudioBuffer.sumOf { it.size }
            
            if (currentSize + chunk.size > maxPendingBufferSize) {
                // 缓冲区即将溢出，丢弃最旧的数据
                var sizeToRemove = chunk.size
                while (sizeToRemove > 0 && pendingAudioBuffer.isNotEmpty()) {
                    val oldest = pendingAudioBuffer.removeAt(0)
                    sizeToRemove -= oldest.size
                }
                Log.w(TAG, "Pending audio buffer overflow, dropped oldest chunks")
            }
            
            pendingAudioBuffer.add(chunk)
            
            if (pendingAudioBuffer.size % 10 == 0) {
                Log.d(TAG, "Buffering audio: ${pendingAudioBuffer.size} chunks, ~${currentSize / 1024}KB")
            }
        }
    }
    
    /**
     * 发送缓冲区中的所有音频数据
     * 在 WebSocket 连接建立后调用
     */
    private suspend fun flushPendingAudioBuffer() {
        val chunksToSend: List<ByteArray>
        
        pendingBufferMutex.withLock {
            chunksToSend = pendingAudioBuffer.toList()
            pendingAudioBuffer.clear()
        }
        
        if (chunksToSend.isNotEmpty()) {
            val totalBytes = chunksToSend.sumOf { it.size }
            val durationMs = (totalBytes.toFloat() / (sampleRate * 2) * 1000).toLong()
            Log.i(TAG, "Flushing ${chunksToSend.size} buffered audio chunks (${totalBytes / 1024}KB, ~${durationMs}ms)")
            
            try {
                for (chunk in chunksToSend) {
                    // 检查是否仍在录音状态，避免在停止后继续发送
                    if (!isRecording && !isWebSocketReady.get()) {
                        Log.w(TAG, "Recording stopped during flush, aborting")
                        break
                    }
                    aliyunRealtimeSttClient?.sendAudio(chunk)
                }
                Log.i(TAG, "Buffered audio flushed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing audio buffer: ${e.message}")
                // 不抛出异常，避免崩溃
            }
        }
    }
    
    /**
     * 取消录音
     * 停止录音并丢弃数据，不进行后续处理
     */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext
        
        isRecording = false
        
        // 清理零等待录音缓冲区
        pendingBufferMutex.withLock {
            pendingAudioBuffer.clear()
        }
        isWebSocketReady.set(false)
        
        // 通知 UI WebSocket 已断开
        withContext(Dispatchers.Main) {
            onWebSocketStateChanged?.invoke(WebSocketState.DISCONNECTED)
        }
        
        // 取消实时 STT
        aliyunRealtimeSttClient?.cancel()
        aliyunRealtimeSttClient = null
        realtimeSttJob?.cancel()
        realtimeSttJob = null
        
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
        // 优先停止播放，确保退出时不会继续发声
        stopPlayback()
        
        // 清理零等待录音缓冲区（非协程版本，直接清理）
        pendingAudioBuffer.clear()
        isWebSocketReady.set(false)
        
        // 取消实时 STT
        aliyunRealtimeSttClient?.cancel()
        aliyunRealtimeSttClient = null
        realtimeSttJob?.cancel()
        realtimeSttJob = null

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
        
        // 强制使用直连模式
        
        // 直连模式处理
        Log.i(TAG, "Using Direct Mode")
        return@withContext stopRecordingAndProcessDirect()
    }
    
    /**
     * 使用实时 STT 模式处理：获取实时 STT 的最终结果，然后执行 Chat + TTS
     */
    private suspend fun stopRecordingAndProcessWithRealtimeStt(): VoiceChatResult {
        // 停止录音
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
        
        // 【关键修复】给音频系统时间从录音模式切换到播放模式
        // 某些 OPPO/Realme 设备在录音结束后立即播放可能导致 AudioTrack 无法正常工作
        // 根据设备类型动态调整等待时间
        val isProblematicDevice = AudioTestUtil.isKnownProblematicDevice()
        val delayMs = if (isProblematicDevice) {
            PerformanceConfig.VOICE_RECORD_TO_PLAYBACK_DELAY_OPPO_MS
        } else {
            PerformanceConfig.VOICE_RECORD_TO_PLAYBACK_DELAY_MS
        }
        Log.i(TAG, "Waiting for audio system mode switch... (${delayMs}ms, isProblematicDevice=$isProblematicDevice)")
        kotlinx.coroutines.delay(delayMs)
        
        // 等待实时 STT 完成并获取最终文本
        val userText = aliyunRealtimeSttClient?.finishAndWait() ?: ""

        // 清理实时 STT 客户端
        aliyunRealtimeSttClient = null
        realtimeSttJob?.cancel()
        realtimeSttJob = null
        
        // 通知 UI WebSocket 已断开
        withContext(Dispatchers.Main) {
            onWebSocketStateChanged?.invoke(WebSocketState.DISCONNECTED)
        }
        
        Log.i(TAG, "Realtime STT final result: $userText")
        
        if (userText.isBlank()) {
            throw IllegalStateException("语音识别失败：未能识别出文字")
        }
        
        // 通知 UI 最终的识别结果
        withContext(Dispatchers.Main) {
            onTranscriptionReceived?.invoke(userText)
        }
        
        // 使用已有的 userText 执行 Chat + TTS（跳过 STT 步骤）
        return processWithExistingUserText(userText)
    }
    
    /**
     * 使用已有的用户文本执行 Chat + TTS（跳过 STT）
     */
    private suspend fun processWithExistingUserText(userText: String): VoiceChatResult {
        val audioChunks = mutableListOf<ByteArray>()
        var assistantText = ""
        
        directSession = VoiceChatDirectSession(
            httpClient = ktorClient,
            sttConfig = SttDirectClient.SttConfig(
                platform = sttPlatform,
                apiKey = sttApiKey,
                apiUrl = sttApiUrl.ifBlank { null },
                model = sttModel
            ),
            chatPlatform = chatPlatform,
            chatApiKey = chatApiKey,
            chatApiUrl = chatApiUrl.ifBlank { null },
            chatModel = chatModel,
            systemPrompt = systemPrompt,
            chatHistory = chatHistory,
            ttsConfig = TtsDirectClient.TtsConfig(
                platform = ttsPlatform,
                apiKey = ttsApiKey,
                apiUrl = ttsApiUrl.ifBlank { null },
                model = ttsModel,
                voiceName = voiceName
            ),
            onTranscription = { _ ->
                // 已经有 userText，忽略 STT 回调
            },
            onResponseDelta = { _, full ->
                assistantText = full
                onResponseReceived?.invoke(full)
            },
            onAudioChunk = { chunk ->
                audioChunks.add(chunk)
                // 实时播放音频块
                if (streamAudioPlayer == null) {
                    val audioFormat = TtsDirectClient.getAudioFormat(ttsPlatform)
                    streamAudioPlayer = StreamAudioPlayer(audioFormat.sampleRate)
                    streamAudioPlayer?.start()
                }
                streamAudioPlayer?.write(chunk)
            },
            onError = { error ->
                Log.e(TAG, "Direct mode error: $error")
            },
            onComplete = { user, assistant ->
                Log.i(TAG, "Direct mode completed: user='${user.take(50)}...', assistant='${assistant.take(50)}...'")
            }
        )
        
        // 执行 Chat + TTS（使用已有的 userText）
        val result = directSession?.processWithUserText(userText)
            ?: throw Exception("Direct session process failed")
        
        // 等待播放完成
        streamAudioPlayer?.close()
        streamAudioPlayer = null
        
        directSession = null
        
        return VoiceChatResult(
            userText = userText,
            assistantText = result.assistantText,
            audioData = result.audioData,
            audioFormat = result.audioFormat,
            sampleRate = result.sampleRate,
            isRealtimeMode = true
        )
    }

    /**
     * 直连模式处理：停止录音并使用直连 API 处理
     */
    private suspend fun stopRecordingAndProcessDirect(): VoiceChatResult {
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

        Log.i(TAG, "Recorded ${pcmData.size} bytes of PCM data for direct mode")

        // 创建 WAV 文件用于 STT
        val tempWavFile = File.createTempFile("voice_chat_direct_", ".wav")
        try {
            tempWavFile.outputStream().use { out ->
                writeWavHeader(out, pcmData.size, sampleRate, 1, 16)
                out.write(pcmData)
            }
            
            val wavData = tempWavFile.readBytes()
            Log.i(TAG, "Created WAV file: ${wavData.size} bytes")
            
            // 创建直连会话
            val audioChunks = mutableListOf<ByteArray>()
            var userText = ""
            var assistantText = ""
            
            directSession = VoiceChatDirectSession(
                httpClient = ktorClient,
                sttConfig = SttDirectClient.SttConfig(
                    platform = sttPlatform,
                    apiKey = sttApiKey,
                    apiUrl = sttApiUrl.ifBlank { null },
                    model = sttModel
                ),
                chatPlatform = chatPlatform,
                chatApiKey = chatApiKey,
                chatApiUrl = chatApiUrl.ifBlank { null },
                chatModel = chatModel,
                systemPrompt = systemPrompt,
                chatHistory = chatHistory,
                ttsConfig = TtsDirectClient.TtsConfig(
                    platform = ttsPlatform,
                    apiKey = ttsApiKey,
                    apiUrl = ttsApiUrl.ifBlank { null },
                    model = ttsModel,
                    voiceName = voiceName
                ),
                onTranscription = { text ->
                    userText = text
                    onTranscriptionReceived?.invoke(text)
                },
                onResponseDelta = { _, full ->
                    assistantText = full
                    onResponseReceived?.invoke(full)
                },
                onAudioChunk = { chunk ->
                    audioChunks.add(chunk)
                    // 实时播放音频块
                    if (streamAudioPlayer == null) {
                        val audioFormat = TtsDirectClient.getAudioFormat(ttsPlatform)
                        streamAudioPlayer = StreamAudioPlayer(audioFormat.sampleRate)
                        streamAudioPlayer?.start()
                    }
                    // onAudioChunk 现在是 suspend 函数，直接调用即可，无需 runBlocking
                    streamAudioPlayer?.write(chunk)
                },
                onError = { error ->
                    Log.e(TAG, "Direct mode error: $error")
                },
                onComplete = { user, assistant ->
                    Log.i(TAG, "Direct mode completed: user='${user.take(50)}...', assistant='${assistant.take(50)}...'")
                }
            )
            
            // 执行直连处理
            val result = directSession?.process(wavData, "audio/wav")
                ?: throw Exception("Direct session process failed")
            
            // 等待播放完成
            streamAudioPlayer?.close()
            streamAudioPlayer = null
            
            directSession = null
            
            return VoiceChatResult(
                userText = result.userText,
                assistantText = result.assistantText,
                audioData = result.audioData,
                audioFormat = result.audioFormat,
                sampleRate = result.sampleRate,
                isRealtimeMode = false
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
     * 流式音频播放器
     *
     * 【增强版】包含以下优化：
     * - 静音预热：在正式播放前写入静音数据唤醒音频通路
     * - 详细日志：记录 AudioTrack 状态变化，便于调试
     * - 设备适配：针对 OPPO/Realme 等设备的特殊处理
     */
    private inner class StreamAudioPlayer(private val inputSampleRate: Int) {
        private var audioTrack: AudioTrack? = null
        private val bufferSize: Int
        
        // 目标采样率：强制使用 48000Hz (Android 原生最安全采样率)
        // 解决部分设备 (OnePlus/OPPO) 在非标准采样率 (如 32kHz) 下 AudioTrack 卡死的问题
        private val targetSampleRate = 48000
        
        // 预缓冲配置
        private var totalWrittenBytes = 0
        private var isBuffering = true // 初始状态为缓冲
        
        // 字节对齐缓冲 (处理奇数包)
        private var leftoverByte: Byte? = null

        // 音量计算
        private var lastVolumeUpdateTime = 0L
        
        // 设备信息
        private val isProblematicDevice = AudioTestUtil.isKnownProblematicDevice()

        init {
            // 【关键修复 1】强制使用双声道输出 (Stereo)
            // 解决 Mono 模式下部分设备驱动不工作的问题
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
            // 【关键修复 2】强制使用 48kHz 采样率
            // 解决 32kHz/24kHz 在部分设备上导致 playbackHeadPosition 不动的问题
            
            // 缓冲区计算：Stereo (2ch) * 16bit (2bytes) = 4 bytes/frame
            val minBufSize = AudioTrack.getMinBufferSize(targetSampleRate, channelConfig, audioFormat)
            // 缓冲区大小调整：使用较小的倍数 (2x minBuf) 以减少延迟并避免某些设备的 buffer 协商问题
            // 之前使用 1秒 (targetSampleRate * 4) 可能过大导致驱动行为异常
            bufferSize = maxOf(minBufSize * 2, targetSampleRate * 4 / 2)
            
            Log.i("StreamAudioPlayer", "=== AudioTrack 初始化 (Resample: $inputSampleRate -> $targetSampleRate, Stereo) ===")
            Log.i("StreamAudioPlayer", "参数: targetSampleRate=$targetSampleRate, minBuf=$minBufSize, actualBuf=$bufferSize")
            Log.i("StreamAudioPlayer", "设备: ${Build.MANUFACTURER} ${Build.MODEL}, isProblematic=$isProblematicDevice")

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        // 【关键修复】使用 CONTENT_TYPE_MUSIC 而非 SPEECH
                        // 某些设备 (如 OnePlus/OPPO) 对 SPEECH 类型的流有特殊的路由或处理逻辑
                        // 可能导致 AudioTrack 写入成功但无法播放 (head stuck at 0)
                        // MUSIC 类型通常走标准媒体通道，兼容性最好
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(targetSampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            val state = audioTrack?.state
            val actualSampleRate = audioTrack?.sampleRate
            val playState = audioTrack?.playState
            
            if (state != AudioTrack.STATE_INITIALIZED) {
                Log.e("StreamAudioPlayer", "AudioTrack 初始化失败! state=$state, playState=$playState")
            } else {
                Log.i("StreamAudioPlayer", "AudioTrack 初始化成功: state=$state, sampleRate=$actualSampleRate, playState=$playState")
            }
        }
        
        @Volatile
        private var forceStop = false
        
        fun start() {
            // 【关键修复】立即启动播放
            // 不要等待数据写入后再 play()，这在某些设备上会导致死锁或状态异常
            // AudioTrack 在没有数据时会自动处于 Underrun 状态，写入数据后会立即发声
            try {
                audioTrack?.play()
                Log.i("StreamAudioPlayer", "start() 调用，AudioTrack 已设置为 PLAYING 状态")
            } catch (e: Exception) {
                Log.e("StreamAudioPlayer", "start() 播放失败", e)
            }
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
            
            // 计算音量并回调 (使用原始 Mono 数据计算)
            val currentTime = System.currentTimeMillis()
            if (onVolumeChanged != null && currentTime - lastVolumeUpdateTime >= 50) {
                lastVolumeUpdateTime = currentTime
                
                var sum = 0.0
                val step = 4
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
                    val normalizedVolume = (rms / 2000.0).coerceIn(0.0, 1.0).toFloat()
                    withContext(Dispatchers.Main) {
                        onVolumeChanged?.invoke(normalizedVolume)
                    }
                }
            }

            // 处理字节对齐
            var dataToProcess = data
            
            // 1. 如果有上次剩余的字节，拼接到开头
            if (leftoverByte != null) {
                val newData = ByteArray(data.size + 1)
                newData[0] = leftoverByte!!
                System.arraycopy(data, 0, newData, 1, data.size)
                dataToProcess = newData
                leftoverByte = null
            }
            
            // 2. 如果当前数据长度是奇数，剥离最后一个字节
            if (dataToProcess.size % 2 != 0) {
                leftoverByte = dataToProcess[dataToProcess.size - 1]
                dataToProcess = dataToProcess.copyOfRange(0, dataToProcess.size - 1)
            }
            
            if (dataToProcess.isEmpty()) return

            // 【关键处理】重采样 + 声道扩展
            // 1. Resample: inputSampleRate -> 48000Hz
            // 2. Mono -> Stereo
            
            val resampledData = if (inputSampleRate != targetSampleRate) {
                resample(dataToProcess, inputSampleRate, targetSampleRate)
            } else {
                dataToProcess
            }

            // Mono (16-bit) -> Stereo (16-bit)
            // 输入: [L-low, L-high]
            // 输出: [L-low, L-high, L-low, L-high] (即 Left=Right)
            val stereoData = ByteArray(resampledData.size * 2)
            for (i in 0 until resampledData.size / 2) {
                val low = resampledData[i * 2]
                val high = resampledData[i * 2 + 1]
                
                // Left channel
                stereoData[i * 4] = low
                stereoData[i * 4 + 1] = high
                
                // Right channel (duplicated)
                stereoData[i * 4 + 2] = low
                stereoData[i * 4 + 3] = high
            }

            // 循环写入 Stereo 数据
            var offset = 0
            val totalToWrite = stereoData.size
            
            while (offset < totalToWrite && !forceStop) {
                val remaining = totalToWrite - offset
                val written = track.write(stereoData, offset, remaining)
                
                if (written > 0) {
                    offset += written
                    totalWrittenBytes += written
                    
                    // 检查播放状态，如果处于停止状态则重新启动
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        try {
                            track.play()
                            Log.i("StreamAudioPlayer", "AudioTrack restarted from non-playing state")
                        } catch (e: Exception) {
                            Log.e("StreamAudioPlayer", "Failed to restart playback", e)
                        }
                    }
                } else if (written == 0) {
                    kotlinx.coroutines.delay(10)
                } else {
                    Log.e("StreamAudioPlayer", "AudioTrack.write error: $written")
                    break
                }
            }
        }
        
        fun close() {
            try {
                val track = audioTrack ?: return
                
                if (forceStop) {
                    Log.i("StreamAudioPlayer", "Playback force stopped by user")
                    track.release()
                    return
                }

                // 【关键修复】写入静音 Padding
                // 在停止前写入约 200ms 的静音数据，将硬件缓冲区中的有效音频"挤"出来
                // 这能解决部分设备尾音被吞或 playbackHeadPosition 不更新到最后的问题
                try {
                    val paddingMs = 200
                    val paddingBytes = (targetSampleRate * 4 * paddingMs) / 1000 // Stereo: 4 bytes/frame
                    val paddingData = ByteArray(paddingBytes) // 全 0 即静音
                    track.write(paddingData, 0, paddingBytes)
                    Log.i("StreamAudioPlayer", "Written ${paddingBytes} bytes of silence padding")
                    
                    // 计入总写入量，以便后续等待逻辑包含这段 Padding
                    totalWrittenBytes += paddingBytes
                } catch (e: Exception) {
                    Log.w("StreamAudioPlayer", "Failed to write padding", e)
                }
                
                // 阻塞等待播放完成
                // Stereo 模式下: 1 frame = 2 channels * 16bit = 4 bytes
                val totalFrames = totalWrittenBytes / 4
                var waitedMs = 0
                val timeoutMs = PerformanceConfig.VOICE_STREAM_CLOSE_TIMEOUT_MS.toInt()
                var lastPosition = -1L
                var positionStuckCount = 0
                
                // 启动等待
                val startupGraceMs = 1000
                var startupWaitMs = 0
                
                Log.i("StreamAudioPlayer", "Waiting for playback completion. Total frames: $totalFrames")

                if (totalFrames > 0) {
                    // 1. 等待播放启动 (head > 0)
                    while (startupWaitMs < startupGraceMs && !forceStop) {
                        val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (currentPosition > 0) break
                        
                        // 如果还没开始且不在播放状态，尝试再次 play
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        
                        Thread.sleep(50)
                        startupWaitMs += 50
                    }
                    
                    // 2. 等待播放结束
                    while (waitedMs < timeoutMs && !forceStop) {
                        val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        
                        // 允许 5% 的误差，加上 Padding 后通常能完整播放有效音频
                        if (currentPosition >= totalFrames * 0.95) {
                            Log.i("StreamAudioPlayer", "Playback completed naturally ($currentPosition / $totalFrames)")
                            break
                        }
                        
                        // 卡死检测
                        if (currentPosition == lastPosition) {
                            positionStuckCount++
                            if (positionStuckCount > 30) { // 1.5s 不动则超时
                                Log.w("StreamAudioPlayer", "Playback stuck at $currentPosition / $totalFrames, aborting wait")
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

    /**
     * 简单的线性插值重采样 (16-bit PCM Mono)
     */
    private fun resample(input: ByteArray, inRate: Int, outRate: Int): ByteArray {
        if (inRate == outRate) return input
        
        val inputShorts = ShortArray(input.size / 2)
        java.nio.ByteBuffer.wrap(input).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputShorts)
        
        val ratio = inRate.toDouble() / outRate
        val outputLength = (inputShorts.size / ratio).toInt()
        val outputShorts = ShortArray(outputLength)
        
        for (i in 0 until outputLength) {
            val inputIndex = i * ratio
            val index1 = inputIndex.toInt()
            val index2 = minOf(index1 + 1, inputShorts.size - 1)
            val fraction = inputIndex - index1
            
            val val1 = inputShorts[index1]
            val val2 = inputShorts[index2]
            
            // 线性插值
            val interpolated = (val1 + fraction * (val2 - val1)).toInt().toShort()
            outputShorts[i] = interpolated
        }
        
        val outputBytes = ByteArray(outputShorts.size * 2)
        java.nio.ByteBuffer.wrap(outputBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outputShorts)
        
        return outputBytes
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
    val sampleRate: Int,           // 采样率
    val isRealtimeMode: Boolean = false  // 是否为实时流式模式（实时模式下音频已边播边放，audioData 为空是正常的）
)


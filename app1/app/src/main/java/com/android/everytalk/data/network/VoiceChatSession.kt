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
import com.android.everytalk.data.network.direct.SttDirectClient
import com.android.everytalk.data.network.direct.TtsDirectClient
import com.android.everytalk.data.network.direct.VoiceChatDirectSession
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
    
    // 直连模式强制开启，不再支持后端中转
    private val useDirectMode: Boolean = true,
    
    private val onVolumeChanged: ((Float) -> Unit)? = null,
    private val onTranscriptionReceived: ((String) -> Unit)? = null, // 识别到的文字回调
    private val onResponseReceived: ((String) -> Unit)? = null  // AI回复文字回调
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording: Boolean = false
    private val pcmBuffer = ByteArrayOutputStream(256 * 1024)
    
    // 直连会话
    private var directSession: VoiceChatDirectSession? = null
    
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

    companion object {
        private const val TAG = "VoiceChatSession"
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
        
        // 直连模式处理（强制）
        Log.i(TAG, "Using Direct Mode (no backend proxy)")
        return@withContext stopRecordingAndProcessDirect()
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
    val sampleRate: Int,           // 采样率
    val isRealtimeMode: Boolean = false  // 是否为实时流式模式（实时模式下音频已边播边放，audioData 为空是正常的）
)


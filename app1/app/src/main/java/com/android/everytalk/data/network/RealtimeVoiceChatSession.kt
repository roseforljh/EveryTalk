package com.android.everytalk.data.network

import android.media.*
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.android.everytalk.config.PerformanceConfig

/**
 * 实时语音对话会话
 * 
 * 实现端到端流式语音对话：
 * 1. 边录音边通过 WebSocket 发送音频块
 * 2. 实时接收 STT 识别结果
 * 3. 实时接收 Chat 回复
 * 4. 实时播放 TTS 音频
 * 
 * 协议说明：
 * - 客户端发送 init 消息进行初始化
 * - 客户端发送 audio 消息传输音频块（每100ms一次）
 * - 客户端发送 end 消息结束录音
 * - 客户端发送 cancel 消息取消会话
 */
class RealtimeVoiceChatSession(
    private val wsUrl: String,
    private val chatHistory: List<Pair<String, String>> = emptyList(),
    private val systemPrompt: String = "",
    
    // STT Config
    private val sttPlatform: String = "Aliyun",
    private val sttApiKey: String = "",
    private val sttApiUrl: String = "",
    private val sttModel: String = "fun-asr-realtime",
    
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
    
    // Callbacks
    private val onVolumeChanged: ((Float) -> Unit)? = null,
    private val onPartialTranscription: ((String) -> Unit)? = null,
    private val onFinalTranscription: ((String) -> Unit)? = null,
    private val onChatDelta: ((String, String) -> Unit)? = null,
    private val onAudioReceived: ((ByteArray, Int) -> Unit)? = null,
    private val onComplete: ((String, String) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    // 新增：用于显示状态信息的回调
    private val onResponseReceived: ((String) -> Unit)? = null
) {
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    // 新增：是否已收到服务端就绪信号
    private var isServerReady = false
    private var isCancelled = false
    // 新增：会话是否已完成（收到 complete 消息）
    @Volatile
    private var isCompleted = false
    
    // 音频播放
    private var streamAudioPlayer: StreamAudioPlayer? = null
    private var currentSampleRate = 24000
    private var currentAudioFormat = "pcm"
    
    // 音频写入队列，避免阻塞 WebSocket 消息处理线程
    // 使用 UNLIMITED 容量防止突发数据导致丢包（TTS 可能瞬间返回大量数据块）
    private val audioQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private var audioWriterJob: Job? = null
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    
    // 用于等待会话完成的 CompletableDeferred
    private val completionDeferred = CompletableDeferred<Pair<String, String>>()
    
    // 录音参数
    private val sampleRate = PerformanceConfig.VOICE_RECORD_SAMPLE_RATE_HZ
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // 音频发送间隔（毫秒）
    private val audioSendIntervalMs = 100L
    
    companion object {
        private const val TAG = "RealtimeVoiceChat"
        
        // 全局 OkHttpClient
        private val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 无超时
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS) // 禁用协议层 Ping，使用应用层心跳
                .build()
        }
    }
    
    /**
     * 开始实时语音对话
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (webSocket != null) {
            Log.w(TAG, "Session already started")
            return@withContext
        }
        
        isCancelled = false
        
        // 创建 WebSocket 连接
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, WebSocketListenerImpl())
        
        Log.i(TAG, "WebSocket 连接请求已发送: $wsUrl")
        
        // 通知 UI 正在连接
        withContext(Dispatchers.Main) {
            onResponseReceived?.invoke("正在建立实时连接...")
        }
        
        // 立即开始录音（为了音量反馈），但暂时不发送数据
        startRecording()
    }
    
    /**
     * 停止录音并等待完成
     */
    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext
        
        isRecording = false
        recordingJob?.join()
        
        // 发送结束信号
        sendMessage(JSONObject().apply {
            put("type", "end")
        })
        
        Log.i(TAG, "录音已停止，等待处理完成")
    }
    
    /**
     * 等待会话完成并返回结果
     * @param timeoutMs 超时时间（毫秒），默认 120 秒（阿里云TTS串行处理可能较慢）
     * @return 用户文本和助手文本的 Pair，如果超时或取消则返回空字符串
     */
    suspend fun awaitCompletion(timeoutMs: Long = 120000L): Pair<String, String> {
        return try {
            withTimeout(timeoutMs) {
                completionDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "等待会话完成超时 (${timeoutMs}ms)")
            Pair("", "")
        } catch (e: Exception) {
            Log.w(TAG, "等待会话完成时出错: ${e.message}")
            Pair("", "")
        }
    }
    
    /**
     * 检查会话是否已完成
     */
    fun isSessionCompleted(): Boolean = isCompleted
    
    /**
     * 取消会话
     */
    fun cancel() {
        isCancelled = true
        isRecording = false
        
        // 发送取消信号
        try {
            webSocket?.send(JSONObject().apply {
                put("type", "cancel")
            }.toString())
        } catch (_: Exception) {}
        
        // 停止录音
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        
        // 关闭音频队列
        audioQueue.close()
        audioWriterJob?.cancel()
        audioWriterJob = null
        
        // 停止播放
        streamAudioPlayer?.forceStop()
        streamAudioPlayer = null
        
        // 关闭 WebSocket
        try {
            webSocket?.close(1000, "Cancelled")
        } catch (_: Exception) {}
        webSocket = null
        
        // 取消协程
        scope.cancel()
        
        Log.i(TAG, "会话已取消")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        cancel()
    }
    
    private fun sendMessage(json: JSONObject) {
        try {
            val ws = webSocket
            if (ws == null) {
                Log.e(TAG, "发送消息失败: WebSocket 实例为空")
                return
            }
            
            val sent = ws.send(json.toString())
            if (!sent) {
                Log.e(TAG, "发送消息失败: WebSocket 未连接或已关闭")
                scope.launch(Dispatchers.Main) {
                    onError?.invoke("发送失败: 连接已断开")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败: ${e.message}")
            scope.launch(Dispatchers.Main) {
                onError?.invoke("发送失败: ${e.message}")
            }
        }
    }
    
    private fun sendInit() {
        try {
            val historyJson = JSONArray()
            chatHistory.forEach { (role, content) ->
                historyJson.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
            
            val initMsg = JSONObject().apply {
                put("type", "init")
                put("stt", JSONObject().apply {
                put("platform", sttPlatform)
                put("api_key", sttApiKey)
                put("api_url", sttApiUrl)
                put("model", sttModel)
                put("sample_rate", sampleRate)
                put("format", "pcm")
            })
            put("chat", JSONObject().apply {
                put("platform", chatPlatform)
                put("api_key", chatApiKey)
                put("api_url", chatApiUrl)
                put("model", chatModel)
                put("system_prompt", systemPrompt)
                put("history", historyJson)
            })
            put("tts", JSONObject().apply {
                put("platform", ttsPlatform)
                put("api_key", ttsApiKey)
                put("api_url", ttsApiUrl)
                put("model", ttsModel)
                put("voice_name", voiceName)
            })
        }
        
            sendMessage(initMsg)
            Log.i(TAG, "初始化消息已发送")
        } catch (e: Exception) {
            Log.e(TAG, "构建初始化消息失败: ${e.message}", e)
            scope.launch(Dispatchers.Main) {
                onError?.invoke("初始化失败: ${e.message}")
            }
        }
    }
    
    private fun startRecording() {
        if (isRecording) return
        
        val recorder = createAudioRecord() ?: run {
            onError?.invoke("无法初始化录音设备")
            return
        }
        
        audioRecord = recorder
        isRecording = true
        
        recordingJob = scope.launch {
            try {
                recorder.startRecording()
                Log.i(TAG, "录音已开始")
                
                // 每 100ms 读取一次音频数据并发送
                // 16kHz * 2 bytes * 0.1s = 3200 bytes
                val bufferSize = (sampleRate * 2 * audioSendIntervalMs / 1000).toInt()
                val buffer = ByteArray(bufferSize)
                var lastVolumeUpdateTime = 0L
                
                while (isRecording && !isCancelled) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    
                    if (bytesRead > 0) {
                        // 提取音频数据
                        val audioData = if (bytesRead < buffer.size) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer
                        }
                        
                        // 只有在服务端就绪后才发送音频数据
                        if (isServerReady) {
                            val audioMsg = JSONObject().apply {
                                put("type", "audio")
                                put("data", Base64.encodeToString(audioData, Base64.NO_WRAP))
                            }
                            sendMessage(audioMsg)
                        }
                        
                        // 始终计算音量，保证UI反馈
                        val currentTime = System.currentTimeMillis()
                        if (onVolumeChanged != null &&
                            currentTime - lastVolumeUpdateTime >= PerformanceConfig.VOICE_RECORD_VOLUME_UPDATE_INTERVAL_MS) {
                            lastVolumeUpdateTime = currentTime
                            
                            var sum = 0.0
                            val step = PerformanceConfig.VOICE_RECORD_VOLUME_SAMPLE_STEP.coerceAtLeast(1)
                            var i = 0
                            val limit = bytesRead - 1
                            while (i < limit) {
                                val sample = (audioData[i].toInt() and 0xFF) or (audioData[i + 1].toInt() shl 8)
                                val shortValue = sample.toShort()
                                sum += shortValue * shortValue
                                i += 2 * step
                            }
                            val sampleCount = (bytesRead / 2) / step
                            if (sampleCount > 0) {
                                val rms = kotlin.math.sqrt(sum / sampleCount)
                                val normalizedVolume = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
                                
                                withContext(Dispatchers.Main) {
                                    onVolumeChanged?.invoke(normalizedVolume)
                                }
                            }
                        }
                    }
                    
                    delay(audioSendIntervalMs)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "录音错误: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("录音错误: ${e.message}")
                }
            } finally {
                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Exception) {}
                audioRecord = null
                Log.i(TAG, "录音资源已释放")
            }
        }
    }
    
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
                    Log.i(TAG, "AudioRecord 初始化成功，音频源: $src")
                    return recorder
                }
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord 初始化失败 (音频源 $src): ${e.message}")
            }
        }
        
        return null
    }
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            
            when (type) {
                "ready" -> {
                    Log.i(TAG, "服务端就绪，开始发送音频")
                    isServerReady = true
                    scope.launch(Dispatchers.Main) {
                        onResponseReceived?.invoke("连接已建立，请说话")
                        // 1秒后清空提示，以免干扰识别结果显示
                        delay(1000)
                        onResponseReceived?.invoke("")
                    }
                }
                
                "stt_partial" -> {
                    val sttText = json.optString("text", "")
                    val isFinal = json.optBoolean("is_final", false)
                    
                    scope.launch(Dispatchers.Main) {
                        if (isFinal) {
                            onFinalTranscription?.invoke(sttText)
                        } else {
                            onPartialTranscription?.invoke(sttText)
                        }
                    }
                }
                
                "stt_final" -> {
                    val sttText = json.optString("text", "")
                    scope.launch(Dispatchers.Main) {
                        onFinalTranscription?.invoke(sttText)
                    }
                }
                
                "meta" -> {
                    // 音频格式信息
                    val format = json.optString("audio_format", "pcm")
                    val sampleRate = json.optInt("sample_rate", 24000)
                    currentAudioFormat = format
                    currentSampleRate = sampleRate
                    Log.i(TAG, "音频格式: $format, 采样率: $sampleRate")
                }
                
                "chat_delta" -> {
                    val delta = json.optString("text", "")
                    val fullText = json.optString("full_text", "")
                    scope.launch(Dispatchers.Main) {
                        onChatDelta?.invoke(delta, fullText)
                    }
                }
                
                "audio" -> {
                    // 如果已取消，忽略所有音频消息
                    if (isCancelled) return
                    
                    val audioData = json.optString("data", "")
                    if (audioData.isNotEmpty()) {
                        val decoded = Base64.decode(audioData, Base64.DEFAULT)
                        
                        // 初始化播放器和音频写入协程（如果需要）
                        if (streamAudioPlayer == null && !isCancelled) {
                            streamAudioPlayer = StreamAudioPlayer(currentSampleRate)
                            streamAudioPlayer?.start()
                            
                            // 启动音频写入协程，从队列中读取音频数据并写入播放器
                            audioWriterJob = scope.launch(Dispatchers.IO) {
                                try {
                                    for (chunk in audioQueue) {
                                        if (isCancelled) break
                                        streamAudioPlayer?.writeBlocking(chunk)
                                    }
                                } catch (e: Exception) {
                                    if (e !is kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                                        Log.w(TAG, "音频写入协程错误: ${e.message}")
                                    }
                                }
                            }
                        }
                        
                        // 将音频数据放入队列，不阻塞 WebSocket 消息处理
                        // 使用 trySend 放入无限容量队列，确保不丢包
                        val result = audioQueue.trySend(decoded)
                        if (result.isFailure) {
                            Log.e(TAG, "音频写入队列失败，这不应该发生 (UNLIMITED channel)")
                        }
                        
                        // 回调（仅在未取消时）
                        if (!isCancelled) {
                            scope.launch(Dispatchers.Main) {
                                onAudioReceived?.invoke(decoded, currentSampleRate)
                            }
                        }
                    }
                }
                
                "complete" -> {
                    val userText = json.optString("user_text", "")
                    val assistantText = json.optString("assistant_text", "")
                    
                    Log.i(TAG, "对话完成")
                    Log.i(TAG, "用户: $userText")
                    Log.i(TAG, "助手: $assistantText")
                    
                    isCompleted = true
                    
                    // 关闭音频队列
                    audioQueue.close()
                    
                    // 启动协程等待音频写入和播放完成
                    // 必须在协程中等待，因为 join() 是 suspend 函数
                    scope.launch(Dispatchers.IO) {
                        try {
                            // 等待所有音频数据写入播放器
                            audioWriterJob?.join()
                            
                            // 等待音频播放完成
                            streamAudioPlayer?.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "关闭音频播放流程出错: ${e.message}")
                        } finally {
                            streamAudioPlayer = null
                            Log.i(TAG, "音频播放完成，发送回调")
                            
                            // 安全地调用回调
                            withContext(Dispatchers.Main) {
                                try {
                                    if (!isCancelled) {
                                        onComplete?.invoke(userText, assistantText)
                                        onVolumeChanged?.invoke(0f)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "回调执行时出错: ${e.message}")
                                }
                            }
                            
                            // 通知等待者会话已完成
                            completionDeferred.complete(Pair(userText, assistantText))
                        }
                    }
                }
                
                "error" -> {
                    val message = json.optString("message", "未知错误")
                    Log.e(TAG, "服务端错误: $message")
                    scope.launch(Dispatchers.Main) {
                        onError?.invoke(message)
                    }
                }
                
                "ping" -> {
                    // 心跳响应
                    sendMessage(JSONObject().apply {
                        put("type", "pong")
                    })
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败: ${e.message}", e)
        }
    }
    
    /**
     * 流式音频播放器
     * 线程安全：使用 synchronized 和 volatile 保护对 audioTrack 的访问
     */
    private inner class StreamAudioPlayer(private val sampleRate: Int) {
        @Volatile
        private var audioTrack: AudioTrack? = null
        private val bufferSize: Int
        private var totalWrittenBytes = 0
        private var isBuffering = true
        private var nextPlayThreshold = ((sampleRate * 2L * PerformanceConfig.VOICE_STREAM_PREBUFFER_MS) / 1000L).toInt()
        private var leftoverByte: Byte? = null
        private var lastVolumeUpdateTime = 0L
        
        @Volatile
        private var forceStop = false
        
        // 标记播放器是否已关闭，防止 close() 后还有 write() 调用
        @Volatile
        private var isClosed = false
        
        // 同步锁，保护对 audioTrack 的访问
        private val trackLock = Any()
        
        init {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            bufferSize = maxOf(minBufSize * 2, sampleRate * 2 * 1)
            
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
            // 预缓冲后再播放
        }
        
        fun forceStop() {
            forceStop = true
            isClosed = true
            synchronized(trackLock) {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.stop()
                } catch (_: Throwable) {}
            }
        }
        
        /**
         * 阻塞式写入音频数据（用于 WebSocket 消息处理线程）
         * 避免与 close() 的竞态条件
         */
        fun writeBlocking(data: ByteArray) {
            // 快速检查：如果已关闭或强制停止，直接返回
            if (isClosed || forceStop) return
            
            // 计算音量（不需要锁，因为不访问 audioTrack）
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
                    
                    // 使用 post 到主线程而不是协程（因为这是阻塞方法）
                    try {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (!isClosed && !isCancelled) {
                                onVolumeChanged?.invoke(normalizedVolume)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            
            // 处理字节对齐
            var dataToWrite = data
            
            if (leftoverByte != null) {
                val newData = ByteArray(data.size + 1)
                newData[0] = leftoverByte!!
                System.arraycopy(data, 0, newData, 1, data.size)
                dataToWrite = newData
                leftoverByte = null
            }
            
            if (dataToWrite.size % 2 != 0) {
                leftoverByte = dataToWrite[dataToWrite.size - 1]
                dataToWrite = dataToWrite.copyOfRange(0, dataToWrite.size - 1)
            }
            
            if (dataToWrite.isEmpty()) return
            
            // 再次检查（在处理数据后）
            if (isClosed || forceStop) return
            
            // 同步访问 audioTrack
            synchronized(trackLock) {
                if (isClosed || forceStop) return
                val track = audioTrack ?: return
                
                try {
                    val written = track.write(dataToWrite, 0, dataToWrite.size)
                    
                    if (written > 0) {
                        totalWrittenBytes += written
                        
                        if (isBuffering) {
                            if (totalWrittenBytes >= nextPlayThreshold) {
                                try {
                                    track.play()
                                    isBuffering = false
                                    Log.i(TAG, "预缓冲完成，开始播放")
                                } catch (e: Exception) {
                                    Log.e(TAG, "播放失败: ${e.message}")
                                }
                            }
                        } else {
                            try {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    Log.w(TAG, "检测到 Underrun，重新缓冲")
                                    isBuffering = true
                                    nextPlayThreshold = totalWrittenBytes +
                                        ((sampleRate * 2L * PerformanceConfig.VOICE_STREAM_PREBUFFER_MS) / 1000L).toInt()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "写入音频数据时出错: ${e.message}")
                }
            }
        }
        
        /**
         * 协程版本的写入（保留兼容性）
         */
        suspend fun write(data: ByteArray) {
            writeBlocking(data)
        }
        
        fun close() {
            // 立即标记为已关闭，阻止新的 write() 调用
            isClosed = true
            
            synchronized(trackLock) {
                try {
                    val track = audioTrack ?: return
                    
                    if (forceStop) {
                        try {
                            track.release()
                        } catch (_: Exception) {}
                        audioTrack = null
                        return
                    }
                    
                    // 确保播放
                    if (totalWrittenBytes > 0 && (isBuffering || track.playState != AudioTrack.PLAYSTATE_PLAYING)) {
                        try {
                            track.play()
                        } catch (_: Exception) {}
                    }
                    
                    // 等待播放完成
                    val totalFrames = totalWrittenBytes / 2
                    var waitedMs = 0
                    val timeoutMs = PerformanceConfig.VOICE_STREAM_CLOSE_TIMEOUT_MS.toInt()
                    var lastPosition = -1L
                    var positionStuckCount = 0
                    
                    // 剩余帧数阈值：当剩余不足 100ms 的音频时，认为已完成
                    // 这样可以避免因为轮询延迟导致的额外等待
                    val remainingThresholdFrames = sampleRate / 10  // 100ms worth of frames
                    
                    if (totalFrames > 0) {
                        while (waitedMs < timeoutMs && !forceStop) {
                            try {
                                if (track.playState == AudioTrack.PLAYSTATE_STOPPED) break
                                
                                val currentPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                                
                                // 已完成或剩余不足阈值时直接退出
                                if (currentPosition >= totalFrames) break
                                val remainingFrames = totalFrames - currentPosition
                                if (remainingFrames <= remainingThresholdFrames) {
                                    Log.i(TAG, "剩余帧数 ($remainingFrames) 低于阈值，认为播放完成")
                                    break
                                }
                                
                                if (currentPosition == lastPosition) {
                                    positionStuckCount++
                                    // 减少卡死检测次数（从20次降到10次，即200ms）
                                    if (positionStuckCount > 10) break
                                } else {
                                    lastPosition = currentPosition
                                    positionStuckCount = 0
                                }
                            } catch (_: Exception) {
                                break
                            }
                            
                            // 减少轮询间隔以更快响应（从50ms降到20ms）
                            Thread.sleep(20)
                            waitedMs += 20
                        }
                    }
                    
                    try {
                        track.stop()
                    } catch (_: Exception) {}
                    try {
                        track.release()
                    } catch (_: Exception) {}
                    
                } catch (e: Exception) {
                    Log.w(TAG, "关闭播放器错误: ${e.message}")
                } finally {
                    audioTrack = null
                }
            }
        }
    }
    
    /**
     * WebSocket 监听器实现
     */
    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket 连接已建立")
            // 确保成员变量引用最新的 WebSocket 实例
            this@RealtimeVoiceChatSession.webSocket = webSocket
            sendInit()
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }
        
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(bytes.utf8())
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 正在关闭: $code $reason")
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 已关闭: $code $reason")
            // 如果会话尚未完成，通知等待者连接已关闭
            // 这样 awaitCompletion 就不会一直等待直到超时
            if (!isCompleted && !completionDeferred.isCompleted) {
                Log.i(TAG, "连接关闭时会话尚未完成，通知等待者")
                completionDeferred.complete(Pair("", ""))
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket 错误: ${t.message}", t)
            scope.launch(Dispatchers.Main) {
                onError?.invoke("连接错误: ${t.message}")
            }
            // 如果会话尚未完成，通知等待者连接已失败
            // 这样 awaitCompletion 就不会一直等待直到超时
            if (!isCompleted && !completionDeferred.isCompleted) {
                Log.i(TAG, "连接失败时会话尚未完成，通知等待者")
                completionDeferred.complete(Pair("", ""))
            }
        }
    }
}
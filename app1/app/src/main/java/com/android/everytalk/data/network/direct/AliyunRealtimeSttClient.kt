package com.android.everytalk.data.network.direct

import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阿里云实时 STT 客户端
 * 
 * 支持边录音边识别，实时返回 partial 和 final 结果。
 * 使用阿里云 DashScope WebSocket API (Fun-ASR)。
 * 
 * 参考文档：https://help.aliyun.com/zh/model-studio/developer-reference/funasr-real-time-speech-recognition-api
 */
class AliyunRealtimeSttClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "fun-asr-realtime",
    private val sampleRate: Int = 16000,
    private val format: String = "pcm"
) {
    companion object {
        private const val TAG = "AliyunRealtimeStt"
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        internal const val MAX_QUEUED_AUDIO_CHUNKS = 50
    }
    
    // 会话状态
    private var webSocketSession: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null
    private var sendJob: Job? = null
    private var taskId: String = ""
    private val isConnected = AtomicBoolean(false)
    private val isTaskStarted = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val errorNotified = AtomicBoolean(false)
    
    // 音频数据队列
    private val audioQueue = Channel<ByteArray>(MAX_QUEUED_AUDIO_CHUNKS)
    
    // 结果回调
    private var onReady: (() -> Unit)? = null
    private var onPartialResult: ((String) -> Unit)? = null
    private var onFinalResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    
    // 累积的最终文本
    private val finalTextBuilder = StringBuilder()
    
    // 用于通知 task-finished 的完成信号
    private var finalResultDeferred: CompletableDeferred<String>? = null
    
    /**
     * 启动实时 STT 会话
     *
     * @param onReady WebSocket 连接成功且服务端就绪时的回调
     * @param onPartial 收到 partial 结果时的回调（句子还在识别中）
     * @param onFinal 收到 final 结果时的回调（句子已确定）
     * @param onError 发生错误时的回调
     */
    suspend fun start(
        onReady: (() -> Unit)? = null,
        onPartial: ((String) -> Unit)? = null,
        onFinal: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (isConnected.get()) {
            Log.w(TAG, "Session already started")
            return@withContext
        }
        
        this@AliyunRealtimeSttClient.onReady = onReady
        this@AliyunRealtimeSttClient.onPartialResult = onPartial
        this@AliyunRealtimeSttClient.onFinalResult = onFinal
        this@AliyunRealtimeSttClient.onError = onError
        
        taskId = UUID.randomUUID().toString()
        isClosed.set(false)
        errorNotified.set(false)
        finalTextBuilder.clear()
        finalResultDeferred = CompletableDeferred()
        
        try {
            Log.i(TAG, "Connecting to Aliyun STT WebSocket...")
            
            httpClient.wss(
                urlString = WS_URL,
                request = {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header("X-DashScope-DataInspection", "enable")
                }
            ) {
                webSocketSession = this
                isConnected.set(true)
                Log.i(TAG, "WebSocket connected")
                
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
                            put("enable_intermediate_result", true)
                            put("enable_punctuation", true)
                            put("language_hints", buildJsonArray { add("zh"); add("en") })
                        }
                    }
                }.toString()
                
                send(Frame.Text(runTaskMessage))
                Log.i(TAG, "Sent run-task message")
                
                // 等待 task-started 事件
                withTimeoutOrNull(10_000L) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val event = parseAliyunSttEvent(frame.readText())
                            
                            when (event.name) {
                                "task-started" -> {
                                    isTaskStarted.set(true)
                                    Log.i(TAG, "Task started successfully")
                                    withContext(Dispatchers.Main) {
                                        onReady?.invoke()
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
                
                if (!isTaskStarted.get()) {
                    throw Exception("Timeout waiting for task-started")
                }
                
                // 启动发送协程
                sendJob = launch {
                    processAudioQueue()
                }

                // 当前 WebSocket 会话协程直接接收，确保原始异常不会被 join 吞掉。
                receiveJob = currentCoroutineContext()[Job]
                processIncomingMessages()
                if (finalResultDeferred?.isCompleted != true && !isClosed.get()) {
                    throw IllegalStateException("阿里云 STT 连接在任务完成前结束")
                }
            }
            
        } catch (e: CancellationException) {
            finalResultDeferred?.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "STT session error: ${e.message}", e)
            finalResultDeferred?.completeExceptionally(e)
            notifyErrorOnce(e.message ?: "Unknown error")
            throw e
        } finally {
            cleanup()
        }
    }
    
    /**
     * 发送音频数据
     *
     * @param audioData PCM 音频数据块
     */
    suspend fun sendAudio(audioData: ByteArray) {
        if (!isConnected.get() || isClosed.get()) {
            return
        }
        try {
            audioQueue.send(audioData)
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            // 通道已关闭，忽略此音频块
            Log.d(TAG, "Audio queue closed, ignoring audio chunk")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send audio: ${e.message}")
            throw e
        }
    }
    
    /**
     * 结束音频输入并等待最终结果
     *
     * 只等待阿里云返回 task-finished 事件，而不是等待整个接收协程完全退出，
     * 避免在 stop 后额外等待很长时间。
     */
    suspend fun finishAndWait(): String = withContext(Dispatchers.IO) {
        if (!isConnected.get()) {
            val deferred = finalResultDeferred
            return@withContext if (deferred?.isCompleted == true) {
                deferred.await()
            } else {
                throw IllegalStateException("阿里云 STT 尚未连接或已提前断开")
            }
        }
        
        try {
            // 关闭音频队列，停止继续发送音频
            audioQueue.close()
            sendJob?.join()
            
            // 发送 finish-task 指令
            val finishTaskMessage = buildJsonObject {
                putJsonObject("header") {
                    put("action", "finish-task")
                    put("task_id", taskId)
                }
                putJsonObject("payload") {
                    putJsonObject("input") {}
                }
            }.toString()
            
            webSocketSession?.send(Frame.Text(finishTaskMessage))
            Log.i(TAG, "Sent finish-task message")
            
            // 等待阿里云返回 task-finished 信号（通常在几百毫秒内）
            val deferred = finalResultDeferred
            if (deferred != null) {
                val result = withTimeoutOrNull(5_000L) {
                    deferred.await()
                }
                if (result == null && !deferred.isCompleted) {
                    throw IllegalStateException("等待阿里云 STT 最终结果超时")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing STT: ${e.message}", e)
            throw e
        } finally {
            // 标记结束，不再处理后续消息
            isClosed.set(true)
        }
        
        return@withContext finalTextBuilder.toString()
    }
    
    /**
     * 取消会话
     */
    fun cancel() {
        val cancellation = CancellationException("Aliyun realtime STT cancelled")
        isClosed.set(true)
        audioQueue.cancel(cancellation)
        finalResultDeferred?.cancel(cancellation)
        sendJob?.cancel(cancellation)
        receiveJob?.cancel(cancellation)
        webSocketSession?.cancel(cancellation)
        cleanup()

        Log.i(TAG, "Session cancelled")
    }
    
    /**
     * 获取当前累积的最终文本
     */
    fun getCurrentFinalText(): String = finalTextBuilder.toString()
    
    private suspend fun processIncomingMessages() {
        val session = webSocketSession ?: return
        
        try {
            for (frame in session.incoming) {
                if (isClosed.get()) break
                
                when (frame) {
                    is Frame.Text -> {
                        if (handleMessage(frame.readText())) break
                    }
                    is Frame.Close -> {
                        Log.i(TAG, "WebSocket closed by server")
                        break
                    }
                    else -> {}
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            Log.i(TAG, "Receive channel closed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving messages: ${e.message}", e)
            finalResultDeferred?.completeExceptionally(e)
            notifyErrorOnce(e.message ?: "Receive error")
            throw e
        }
    }
    
    private suspend fun handleMessage(text: String): Boolean {
        try {
            val event = parseAliyunSttEvent(text)
            val json = event.payload
            
            when (event.name) {
                "result-generated" -> {
                    val output = json["payload"]?.jsonObject?.get("output")?.jsonObject
                    val sentence = output?.get("sentence")?.jsonObject
                    
                    if (sentence != null) {
                        val sentenceText = sentence["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val isEnd = sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull ?: false
                        
                        if (sentenceText.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (isEnd) {
                                    // 句子已确定，追加到最终文本
                                    finalTextBuilder.append(sentenceText)
                                    onFinalResult?.invoke(finalTextBuilder.toString())
                                    Log.d(TAG, "Final sentence: $sentenceText")
                                } else {
                                    // 句子还在识别中，显示当前累积 + 正在识别的部分
                                    val currentText = finalTextBuilder.toString() + sentenceText
                                    onPartialResult?.invoke(currentText)
                                    Log.d(TAG, "Partial: $sentenceText")
                                }
                            }
                        }
                    }
                }
                "task-finished" -> {
                    Log.i(TAG, "Task finished, finalTextChars=${finalTextBuilder.length}")
                    // 任务完成，发送最终结果
                    withContext(Dispatchers.Main) {
                        onFinalResult?.invoke(finalTextBuilder.toString())
                    }
                    // 通知 finishAndWait() 可以返回了
                    finalResultDeferred?.complete(finalTextBuilder.toString())
                    return true
                }
            }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
            throw e
        }
    }
    
    private suspend fun processAudioQueue() {
        val session = webSocketSession ?: return
        
        try {
            for (audioData in audioQueue) {
                if (isClosed.get()) break
                
                // 发送音频数据（二进制帧）
                session.send(Frame.Binary(true, audioData))
            }
        } catch (e: ClosedReceiveChannelException) {
            // 队列已关闭，正常退出
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio: ${e.message}", e)
            finalResultDeferred?.completeExceptionally(e)
            notifyErrorOnce(e.message ?: "Audio send error")
            throw e
        }
    }

    private suspend fun notifyErrorOnce(message: String) {
        if (!errorNotified.compareAndSet(false, true)) return
        withContext(Dispatchers.Main) {
            onError?.invoke(message)
        }
    }
    
    private fun cleanup() {
        isConnected.set(false)
        isTaskStarted.set(false)
        webSocketSession = null
        receiveJob = null
        sendJob = null
        onReady = null
        onPartialResult = null
        onFinalResult = null
        onError = null
    }
}

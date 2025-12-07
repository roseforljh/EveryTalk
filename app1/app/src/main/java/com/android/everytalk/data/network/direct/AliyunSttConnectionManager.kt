package com.android.everytalk.data.network.direct

import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 阿里云实时 STT 长连接管理器
 *
 * 目标：
 * - 在进入语音页时预先建立 WebSocket 连接（预热）
 * - 多轮语音对话尽量复用同一个物理连接，减少冷启动
 * - 空闲一段时间后自动断开，避免资源长期占用
 *
 * 注意：
 * - 这是一个纯客户端层的长连管理器，只管理阿里云 STT；
 * - 真正的音频发送与结果回调依然通过 [AliyunRealtimeSttClient] 完成；
 * - 每一轮会话通过 [SessionHandle] 间接访问底层 client。
 */
object AliyunSttConnectionManager {

    private const val TAG = "AliyunSttConnManager"

    /**
     * 连接状态
     */
    enum class State {
        Idle,          // 未连接
        Connecting,    // 正在建立连接 / 等待 task-started
        Ready,         // 已建立连接，可用于新的识别任务
        Error          // 发生错误，需外部决定是否重试
    }

    /**
     * 单轮会话句柄
     *
     * VoiceChatSession 通过该句柄与底层 STT 交互，而不直接持有 client。
     */
    class SessionHandle internal constructor(
        private val client: AliyunRealtimeSttClient?
    ) {
        suspend fun sendAudio(data: ByteArray) {
            client?.sendAudio(data)
        }

        suspend fun finishAndWait(): String {
            return client?.finishAndWait() ?: ""
        }

        fun getCurrentFinalText(): String {
            return client?.getCurrentFinalText() ?: ""
        }

        fun isValid(): Boolean = client != null
    }

    // Ktor 客户端由外部注入（与当前 VoiceChatSession 保持一致）
    private var httpClient: HttpClient? = null

    // 当前复用的 Aliyun STT 客户端
    @Volatile
    private var client: AliyunRealtimeSttClient? = null

    // 当前状态
    @Volatile
    private var state: State = State.Idle

    // 最近一次活跃时间（发送音频 / 完成任务）
    private val lastActiveAt = AtomicLong(0L)

    // 管理协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 状态锁，避免并发修改
    private val mutex = Mutex()

    // 空闲检测 Job
    private var idleJob: Job? = null

    /**
     * 初始化管理器（在应用启动或语音模块初始化时调用一次）
     */
    fun initialize(httpClient: HttpClient) {
        this.httpClient = httpClient
    }

    // 后台连接 Job
    private var connectJob: Job? = null

    /**
     * 进入语音页或开启语音模式时调用，尝试在后台预连阿里云 STT。
     *
     * 预期：
     * - 如果当前已经 Ready，则什么也不做；
     * - 如果正在 Connecting，则等待连接完成；
     * - 如果 Idle/Error，则启动一次后台连接任务。
     *
     * 注意：此函数会等待连接完成（最多 10 秒），确保返回时连接已就绪。
     */
    suspend fun ensureConnected(apiKey: String, model: String, sampleRate: Int) {
        val clientSnapshot = httpClient ?: run {
            Log.w(TAG, "HttpClient not initialized, cannot ensureConnected")
            return
        }

        mutex.withLock {
            when (state) {
                State.Ready -> {
                    touchActive()
                    Log.i(TAG, "ensureConnected: already Ready, skipping")
                    return
                }
                State.Connecting -> {
                    Log.i(TAG, "ensureConnected: already Connecting, will wait for ready")
                    // 不启动新连接，后面等待
                }
                State.Idle, State.Error -> {
                    Log.i(TAG, "ensureConnected: starting new Aliyun STT connection (async)")
                    state = State.Connecting

                    val newClient = AliyunRealtimeSttClient(
                        httpClient = clientSnapshot,
                        apiKey = apiKey,
                        model = if (model.isNotBlank()) model else "fun-asr-realtime",
                        sampleRate = sampleRate,
                        format = "pcm"
                    )
                    client = newClient

                    // 在后台启动连接，不阻塞当前协程
                    // start() 是一个长时间运行的函数，会在 WebSocket 会话结束时才返回
                    // 我们通过 onReady 回调来知道连接何时就绪
                    connectJob = scope.launch {
                        try {
                            newClient.start(
                                onReady = {
                                    Log.i(TAG, "Aliyun STT preconnect ready (callback)")
                                    // 在回调中更新状态
                                    state = State.Ready
                                    touchActive()
                                    startIdleWatcher()
                                },
                                onPartial = { /* 预连阶段不关心 partial */ },
                                onFinal = { /* 预连阶段不关心 final */ },
                                onError = { error ->
                                    Log.e(TAG, "Aliyun STT preconnect error: $error")
                                    state = State.Error
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Aliyun STT preconnect failed: ${e.message}", e)
                            state = State.Error
                        }
                    }
                }
            }
        }

        // 等待连接就绪（最多 10 秒）
        if (state == State.Connecting) {
            val handle = waitForReadyAndGetHandle(timeoutMs = 10_000L)
            if (handle != null) {
                Log.i(TAG, "ensureConnected: connection ready after waiting")
            } else {
                Log.w(TAG, "ensureConnected: timeout waiting for connection")
            }
        }
    }

    /**
     * VoiceChatSession 在开始录音前调用，获取一个可用的会话句柄。
     *
     * 行为：
     * - 若当前为 Ready，直接返回句柄；
     * - 若为 Connecting，则等待连接完成（最多 waitTimeoutMs），若 Ready 则返回，否则返回 null；
     * - 若为 Idle/Error，直接返回 null，由调用方决定是否退回旧逻辑。
     */
    suspend fun acquireSessionHandle(
        waitTimeoutMs: Long = 10_000L  // 增加默认超时到 10 秒，确保有足够时间等待连接
    ): SessionHandle? {
        // 先检查当前状态（不加锁，快速路径）
        when (state) {
            State.Ready -> {
                touchActive()
                Log.i(TAG, "acquireSessionHandle: already Ready, returning handle")
                return SessionHandle(client)
            }
            State.Connecting -> {
                Log.i(TAG, "acquireSessionHandle: Connecting, waiting for ready...")
                // 等待连接完成
                val handle = waitForReadyAndGetHandle(waitTimeoutMs)
                if (handle != null) {
                    Log.i(TAG, "acquireSessionHandle: got handle after waiting")
                }
                return handle
            }
            State.Idle, State.Error -> {
                Log.w(TAG, "acquireSessionHandle: state=$state, no hot connection available")
                return null
            }
        }
    }

    /**
     * 手动关闭连接（例如语音页销毁 / 应用退出时）
     */
    fun shutdown() {
        scope.launch {
            mutex.withLock {
                Log.i(TAG, "shutdown: closing Aliyun STT connection")
                idleJob?.cancel()
                idleJob = null
                
                connectJob?.cancel()
                connectJob = null

                client?.cancel()
                client = null
                state = State.Idle
            }
        }
    }

    /**
     * 标记一轮会话活动（发送数据 / 完成任务）
     */
    fun touchActive() {
        lastActiveAt.set(System.currentTimeMillis())
    }

    /**
     * 在应用进入后台时调用，可选择立即断开连接或缩短 idle 超时。
     *
     * 当前实现仅简单调用 shutdown，后续可根据需要调整策略。
     */
    fun onAppBackground() {
        Log.i(TAG, "App moved to background, shutting down Aliyun STT connection")
        shutdown()
    }

    // 等待 Ready 状态，并返回 SessionHandle
    private suspend fun waitForReadyAndGetHandle(timeoutMs: Long): SessionHandle? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (state == State.Ready) {
                touchActive()
                return SessionHandle(client)
            }
            delay(30)
        }
        Log.w(TAG, "waitForReadyAndGetHandle: timeout while waiting for Ready")
        return null
    }

    // STT 连接空闲超时（毫秒）- 30秒无活动则断开
    // 注意：这与 VOICE_STREAM_CLOSE_TIMEOUT_MS（用于音频播放）不同
    private const val STT_CONNECTION_IDLE_TIMEOUT_MS = 30_000L

    // 启动空闲检测协程
    private fun startIdleWatcher() {
        if (idleJob != null) return

        idleJob = scope.launch {
            while (true) {
                delay(5_000L) // 每5秒检查一次
                val last = lastActiveAt.get()
                if (last == 0L) continue

                val diff = System.currentTimeMillis() - last
                if (diff > STT_CONNECTION_IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "Aliyun STT idle for $diff ms (threshold: ${STT_CONNECTION_IDLE_TIMEOUT_MS}ms), shutting down connection")
                    shutdown()
                    break
                }
            }
        }
    }
}
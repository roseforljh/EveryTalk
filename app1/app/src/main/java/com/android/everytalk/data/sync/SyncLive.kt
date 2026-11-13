package com.android.everytalk.data.sync

import android.content.Context
import android.util.Base64
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.auth.AuthRuntime
import com.android.everytalk.data.auth.AuthState
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.network.BoundedSseLineReader
import com.android.everytalk.data.network.OwnedServerRequestPurpose
import com.android.everytalk.data.network.ownedServerRequestGuard
import com.android.everytalk.service.SyncScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 其他设备正在运行的回复。messageUuid 与该回复定稿后同步过来的消息一致。
 * running=false 表示已结束、定稿消息还在路上：继续显示最后快照，避免回复短暂消失。
 */
internal data class LiveRemoteRun(
    val messageUuid: String,
    val message: Message,
    val running: Boolean = true,
    val runId: String = messageUuid,
)

@Serializable
internal data class LiveSnapshot(
    val messageUuid: String,
    val message: Message,
    val running: Boolean = true,
)

/** 同步身份在发送端转为 UUID、接收端转为本机 ID；正文、边界和用量卡片使用同一映射。 */
internal suspend fun mapLiveMessageReferences(message: Message, resolve: suspend (String) -> String): Message = message.copy(
    id = resolve(message.id),
    executionTrace = message.executionTrace.map { event ->
        if (event is ExecutionTraceEvent.UserMessageBoundary) event.copy(messageId = resolve(event.messageId)) else event
    },
    contextUsageSnapshot = message.contextUsageSnapshot?.let { it.copy(messageId = resolve(it.messageId)) },
)

/**
 * 同账号多设备的实时状态：运行端上报回复快照，前台设备通过 SSE 接收其他设备的快照、
 * 结束通知和"云端有新数据"通知。快照只经过服务器内存，不写入同步库。
 */
internal object LiveSync {
    /** 每个进程一个设备身份，服务器据此不把自己的快照和变更推回给自己。 */
    val deviceId: String = UUID.randomUUID().toString()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val base = BuildConfig.EVERYTALK_SYNC_BASE_URL.trim().trimEnd('/')
    @Volatile private var app: Context? = null

    private val _remote = MutableStateFlow<Map<String, LiveRemoteRun>>(emptyMap())
    /** key 是会话同步 UUID。 */
    val remote = _remote.asStateFlow()
    private val _cancelRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 其他设备请求停止的会话同步 UUID。 */
    val cancelRequests = _cancelRequests.asSharedFlow()

    private val client by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000 }
            install(ownedServerRequestGuard({ !signedIn() }, { true }, { OwnedServerRequestPurpose.SYNC }))
        }
    }

    private fun signedIn() = app?.let { AuthRuntime.get(it).auth.state.value is AuthState.SignedIn } == true

    private suspend fun allowed(): Boolean = runCatching { permitted() }.getOrDefault(false)

    private suspend fun permitted(): Boolean {
        val context = app ?: return false
        if (!syncServerConfigured(base)) return false
        val runtime = AuthRuntime.get(context)
        val user = (runtime.auth.state.value as? AuthState.SignedIn)?.user ?: return false
        return !runtime.account.state.value.sessionExpired && runtime.binding.readBoundAccountId() == user.id &&
            SyncStateStore(AppDatabase.getDatabase(context)).isEnabled()
    }

    /** 401 时强制刷新一次 token 重试。 */
    private suspend fun authorized(block: suspend (String) -> HttpResponse): HttpResponse {
        val account = AuthRuntime.get(requireNotNull(app)).account
        val first = block(account.accessTokenForOwnedServer(false) ?: error("缺少登录会话"))
        if (first.status.value != 401) return first
        return block(account.accessTokenForOwnedServer(true) ?: error("缺少登录会话"))
    }

    // ---------- 运行端：上报 ----------

    private class Publisher(val messageUuid: String) {
        @Volatile var snapshot: LiveSnapshot? = null
        @Volatile var stillRunning: () -> Boolean = { true }
        @Volatile var cancelForwarded = false
        val wake = Channel<Unit>(Channel.CONFLATED)
    }

    /** 远程停止可能同时经快照响应和实时通道到达，每次运行只转交一次。 */
    private fun forwardCancel(session: String) {
        val publisher = publishers[session] ?: return
        if (publisher.cancelForwarded) return
        publisher.cancelForwarded = true
        _cancelRequests.tryEmit(session)
    }
    private val publishers = ConcurrentHashMap<String, Publisher>()

    /**
     * 每次本地保存流式快照时调用；被其他设备观看时每秒最多上报一次，否则 5 秒一次心跳。
     * stillRunning 兜底：任务没走到终态就结束时，停止心跳并通知结束。
     */
    fun publish(context: Context, sessionUuid: String, messageUuid: String, message: Message, terminal: Boolean, stillRunning: () -> Boolean) {
        app = context.applicationContext
        val publisher = synchronized(publishers) {
            val existing = publishers[sessionUuid]?.takeIf { it.messageUuid == messageUuid }
            // 终态可能先于缓冲中的旧增量到达，不能让旧增量重新启动已结束的回复。
            if (existing?.snapshot?.running == false && !terminal) return
            val publisher = existing ?: Publisher(messageUuid)
            val finalMessage = if (terminal) message.copy(
                executionStatus = null,
                executionFinishedAt = message.executionFinishedAt ?: System.currentTimeMillis(),
            ) else message
            publisher.snapshot = LiveSnapshot(messageUuid, finalMessage, running = !terminal)
            publisher.stillRunning = stillRunning
            if (existing == null) {
                publishers[sessionUuid] = publisher
                scope.launch { publishLoop(sessionUuid, publisher) }
            }
            publisher
        }
        // 后台运行时也保持实时通道，远程停止立即送达。
        ensureListening()
        publisher.wake.trySend(Unit)
    }

    private suspend fun publishLoop(session: String, publisher: Publisher) {
        var peers = 0
        var lastSentAt = 0L
        var lastSent: LiveSnapshot? = null
        try {
            while (publishers[session] === publisher) {
                val snapshot = publisher.snapshot ?: break
                if (snapshot.running && !publisher.stillRunning()) break
                val now = System.currentTimeMillis()
                val interval = if (peers > 0) 1_000L else 5_000L
                // 唤醒只表示有新内容，不允许高频增量绕过限频；失败保留同一快照重试。
                if (now - lastSentAt < interval) delay(interval - (now - lastSentAt))
                if (snapshot === lastSent && now - lastSentAt < 10_000L) {
                    withTimeoutOrNull(interval) { publisher.wake.receive() }
                    continue
                }
                if (!allowed()) { delay(5_000L); continue }
                if (publishers[session] !== publisher) break
                val current = publisher.snapshot ?: break
                lastSentAt = System.currentTimeMillis()
                try {
                    val wireMessage = mapLiveMessageReferences(current.message, AppDatabase.getDatabase(requireNotNull(app)).chatDao()::resolveMessageSyncUuid)
                    val body = encode(current.copy(message = wireMessage.copy(id = current.messageUuid, conversationId = session)))
                    val response = authorized { token ->
                        client.put("$base/live/sessions/$session") {
                            bearerAuth(token); header("X-EveryTalk-Device", deviceId)
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"$body","runId":"${publisher.messageUuid}"}""")
                        }
                    }
                    if (!response.status.isSuccess()) continue
                    val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
                    if (result["accepted"]?.jsonPrimitive?.booleanOrNull == false) break
                    lastSent = current
                    peers = result["peers"]?.jsonPrimitive?.intOrNull ?: 0
                    if (result["cancel"]?.jsonPrimitive?.booleanOrNull == true) forwardCancel(session)
                    // 先确认最后一帧已被接收，才能发 end；发送期间到来的终态留给下一轮。
                    if (!current.running && publisher.snapshot === current) break
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 不清除待发快照，下一个限频周期重试。
                }
            }
        } finally {
            publishers.remove(session, publisher)
            // 结束带运行身份，旧任务的 finally 不能结束同会话的新任务。
            if (lastSent != null) scope.launch {
                runCatching { authorized { token ->
                    client.delete("$base/live/sessions/$session") {
                        bearerAuth(token); header("X-EveryTalk-Device", deviceId)
                        parameter("runId", publisher.messageUuid)
                    }
                } }
            }
        }
    }

    /** gzip + base64：长回复的正文、思考和步骤每秒整份上报，压缩后流量约为原文 1/4。 */
    internal fun encode(snapshot: LiveSnapshot): String {
        val message = snapshot.message
        // 只去掉设备文件路径和可重建的 Markdown 缓存。变更卡片、工具详情、搜索来源和用量均是可见状态。
        val slim = message.copy(
            parts = emptyList(), attachments = emptyList(), imageUrls = null,
            contextCompressionState = null,
        )
        val raw = json.encodeToString(LiveSnapshot.serializer(), snapshot.copy(message = slim)).toByteArray()
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        return Base64.encodeToString(compressed, Base64.NO_WRAP)
    }

    internal fun decode(body: String): LiveSnapshot = GZIPInputStream(Base64.decode(body, Base64.NO_WRAP).inputStream()).use {
        json.decodeFromString(LiveSnapshot.serializer(), it.readBytes().decodeToString())
    }

    // ---------- 观看端：接收 ----------

    private var listener: Job? = null
    @Volatile private var foreground = false
    @Volatile private var backgroundSince = 0L
    private const val BACKGROUND_GRACE_MS = 5 * 60_000L

    /**
     * 前台、本机有任务在跑、或退到后台不满 5 分钟时保持长连接：切出去回个消息再回来不用重连，
     * 后台跑任务的设备也能立即收到远程停止。其余时间断开，避免后台常驻耗电。
     */
    private fun wanted() = foreground || publishers.isNotEmpty() ||
        System.currentTimeMillis() - backgroundSince < BACKGROUND_GRACE_MS

    fun setForeground(context: Context, foreground: Boolean) {
        app = context.applicationContext
        this.foreground = foreground
        if (!foreground) backgroundSince = System.currentTimeMillis()
        ensureListening()
    }

    @Synchronized
    private fun ensureListening() {
        if (wanted() && listener?.isActive != true) listener = scope.launch { listen() }
    }

    fun requestRemoteStop(sessionUuid: String) {
        scope.launch {
            runCatching { authorized { token -> client.post("$base/live/sessions/$sessionUuid/cancel") { bearerAuth(token) } } }
        }
    }

    private suspend fun listen() = coroutineScope {
        // 条件不再满足时断开正在读取的连接。
        val connection = launch { connectLoop() }
        while (connection.isActive) {
            delay(30_000)
            if (!wanted()) connection.cancel()
        }
        // 暂停订阅不代表任务结束；返回前台后继续展示最后快照，等待服务端对账。
    }

    private var remoteAccountId: String? = null

    private suspend fun connectLoop() {
        var backoff = 2_000L
        while (wanted()) {
            val accountId = (app?.let { AuthRuntime.get(it).auth.state.value } as? AuthState.SignedIn)?.user?.id
            if (remoteAccountId != accountId) {
                _remote.value = emptyMap()
                remoteAccountId = accountId
            }
            if (!allowed()) { _remote.value = emptyMap(); delay(30_000); continue }
            try {
                val token = AuthRuntime.get(requireNotNull(app)).account.accessTokenForOwnedServer(backoff > 2_000L) ?: error("缺少登录会话")
                client.prepareGet("$base/live/events") {
                    parameter("device", deviceId); bearerAuth(token)
                    timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 60_000 }
                }.execute { response ->
                    check(response.status.isSuccess()) { "实时通道 ${response.status.value}" }
                    backoff = 2_000L
                    // 断线期间可能错过 changed，重连成功先补拉一次。
                    SyncScheduler.request(requireNotNull(app))
                    val reader = BoundedSseLineReader(response.bodyAsChannel(), maxEventBytes = 4L shl 20, maxStreamBytes = Long.MAX_VALUE)
                    var event = ""
                    val data = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> { dispatch(event, data.toString()); event = ""; data.clear() }
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> data.append(line.substring(5).removePrefix(" "))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
            // 网络断开只触发重连，不能把运行状态和已收到的内容清空。
            delay(backoff)
        }
    }

    private fun dispatch(event: String, data: String) {
        runCatching {
            when (event) {
                "changed" -> SyncScheduler.request(requireNotNull(app))
                "state" -> {
                    val sessions = json.parseToJsonElement(data).jsonObject.getValue("sessions").jsonArray
                        .mapTo(hashSetOf()) { it.jsonPrimitive.content }
                    // 重连补发的快照先到；集合里缺少的旧运行已结束，不继续显示停止按钮。
                    _remote.update { current -> current.mapValues { (session, run) ->
                        if (session in sessions) run else run.copy(running = false)
                    } }
                }
                "cancel" -> forwardCancel(json.parseToJsonElement(data).jsonObject.getValue("session").jsonPrimitive.content)
                "live" -> {
                    val value = json.parseToJsonElement(data).jsonObject
                    val session = value.getValue("session").jsonPrimitive.content
                    val snapshot = decode(value.getValue("body").jsonPrimitive.content)
                    val runId = value["runId"]?.jsonPrimitive?.content ?: snapshot.messageUuid
                    _remote.update { it + (session to LiveRemoteRun(snapshot.messageUuid, snapshot.message, snapshot.running, runId)) }
                }
                "end" -> {
                    val value = json.parseToJsonElement(data).jsonObject
                    val session = value.getValue("session").jsonPrimitive.content
                    val ended = _remote.value[session] ?: return@runCatching
                    if (value["runId"]?.jsonPrimitive?.content?.let { it != ended.runId } == true) return@runCatching
                    val final = ended.copy(running = false)
                    _remote.update { current -> if (current[session] === ended) current + (session to final) else current }
                    // 完整归档可能仍在下载；不能按 60 秒定时器删掉唯一完整的正文、边界和变更卡片。
                    // 消息列表在正式历史追平后采用 Room，新运行和账号切换会替换这份快照。
                    SyncScheduler.request(requireNotNull(app))
                }
            }
        }
    }
}

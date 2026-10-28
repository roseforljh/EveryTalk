package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.auth.AuthRuntime
import com.android.everytalk.data.auth.AuthState
import com.android.everytalk.data.sync.syncServerConfigured
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/** 模型流响应的最小视图。直连和云端续接共用，解析器不需要知道数据从哪来。 */
internal class ModelStreamResponse(
    val status: HttpStatusCode,
    private val contentType: ContentType?,
    private val channel: ByteReadChannel,
) {
    fun bodyAsChannel(): ByteReadChannel = channel

    suspend fun readErrorTextAtMost(): String? = try {
        decodeResponseText(channel.readBytesAtMost(MAX_ERROR_RESPONSE_BYTES), contentType)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * 所有模型流请求的统一出口。
 * 已登录走后端脱钩任务：后端自己连模型，手机断网或熄屏后带字节位置续接；
 * 未登录、地址不支持或后端不可用时走原来的直连。
 */
internal suspend fun HttpClient.streamModel(
    url: String,
    build: HttpRequestBuilder.() -> Unit,
    block: suspend (ModelStreamResponse) -> Unit,
) {
    if (ModelStreamRelay.tryRelay(this, url, build, block)) return
    preparePost(url, build).execute { response ->
        block(ModelStreamResponse(response.status, runCatching { response.contentType() }.getOrNull(), response.bodyAsChannel()))
    }
}

internal object ModelStreamRelay {
    private const val TAG = "ModelStreamRelay"
    /** 低于后端 24 MiB 上限，留出信封字段余量。 */
    private const val MAX_RELAY_BODY_CHARS = 20 * 1024 * 1024
    /** 连续续接失败超过这个时长才放弃，覆盖熄屏后网络长时间挂起。 */
    private const val MAX_RECONNECT_MILLIS = 30 * 60_000L
    private const val STREAM_IDLE_MILLIS = 90_000L

    private data class Run(val id: String, val status: Int, val contentType: ContentType?)
    private class RunGoneException(message: String) : IOException(message)

    internal data class Envelope(val json: String)

    suspend fun tryRelay(
        client: HttpClient,
        url: String,
        build: HttpRequestBuilder.() -> Unit,
        block: suspend (ModelStreamResponse) -> Unit,
    ): Boolean {
        val base = BuildConfig.EVERYTALK_SYNC_BASE_URL.trim().trimEnd('/').takeIf(::syncServerConfigured) ?: return false
        val runtime = AuthRuntime.current() ?: return false
        if (runtime.auth.state.value !is AuthState.SignedIn || runtime.account.state.value.sessionExpired) return false
        val envelope = envelope(HttpRequestBuilder().also { it.url(url); build(it) }) ?: return false
        val run = create(client, runtime.account, base, envelope) ?: return false
        relay(client, runtime.account, base, run, block)
        return true
    }

    /**
     * 把直连请求原样折成后端信封。鉴权头只用来识别协议和取出 Key，后端会按协议重新生成。
     * 无法识别或不是标准 HTTPS 地址时返回 null，调用方继续直连。
     */
    internal fun envelope(request: HttpRequestBuilder): Envelope? {
        if (request.url.protocol != URLProtocol.HTTPS) return null
        val body = when (val raw = request.body) {
            is String -> raw
            is TextContent -> raw.text
            else -> return null
        }
        if (body.length > MAX_RELAY_BODY_CHARS || !body.trimStart().startsWith("{")) return null
        val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
        val anthropicKey = request.headers["x-api-key"]?.takeIf { it.isNotBlank() }
        val geminiKey = request.url.parameters["key"]?.takeIf { it.isNotBlank() }
        val (protocol, apiKey) = when {
            anthropicKey != null -> "anthropic" to anthropicKey
            geminiKey != null -> {
                request.url.parameters.remove("key")
                "gemini" to geminiKey
            }
            bearer != null -> "openai" to bearer
            else -> return null
        }
        val head = buildJsonObject {
            put("target", request.url.buildString())
            put("protocol", protocol)
            put("apiKey", apiKey)
            put("headers", buildJsonObject {
                request.headers.getAll(HttpHeaders.Accept)?.firstOrNull()?.let { put("accept", it) }
                request.headers["anthropic-beta"]?.let { put("anthropic-beta", it) }
            })
        }.toString()
        // 请求体已经是 JSON 字符串，直接拼接，避免把带图片的大请求再解析一遍。
        return Envelope(head.dropLast(1) + ",\"body\":" + body + "}")
    }

    /** 创建失败一律回退直连。ponytail: 创建成功但响应丢失时后端会多跑一次，等同用户重发。 */
    private suspend fun create(client: HttpClient, account: AccountManager, base: String, envelope: Envelope): Run? {
        for (attempt in 0..1) {
            try {
                val token = account.accessTokenForOwnedServer(forceRefresh = attempt > 0) ?: return null
                val response = client.preparePost("$base/v1/chat/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(envelope.json)
                    timeout { connectTimeoutMillis = 15_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
                }.execute { response -> response.status.value to response.bodyAsText() }
                if (response.first == 401 && attempt == 0) continue
                if (response.first != 200) {
                    Log.w(TAG, "relay unavailable status=${response.first}, falling back to direct")
                    return null
                }
                val json = Json.parseToJsonElement(response.second).jsonObject
                return Run(
                    id = json.getValue("id").jsonPrimitive.content,
                    status = json.getValue("status").jsonPrimitive.int,
                    contentType = runCatching { ContentType.parse(json.getValue("content_type").jsonPrimitive.content) }.getOrNull(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "relay create failed type=${e.javaClass.simpleName}, falling back to direct")
                return null
            }
        }
        return null
    }

    private suspend fun relay(
        client: HttpClient,
        account: AccountManager,
        base: String,
        run: Run,
        block: suspend (ModelStreamResponse) -> Unit,
    ) {
        val channel = ByteChannel(autoFlush = true)
        try {
            coroutineScope {
                val pump = launch {
                    try {
                        pumpRun(client, account, base, run, channel)
                        channel.flushAndClose()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 解析器读到这个异常时与直连断网相同，走现有的网络错误处理。
                        channel.cancel(e)
                    }
                }
                try {
                    block(ModelStreamResponse(HttpStatusCode.fromValue(run.status), run.contentType, channel))
                } finally {
                    pump.cancel()
                }
            }
        } catch (e: CancellationException) {
            // 用户停止或打断时通知后端结束模型请求，避免继续消耗额度。
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    runCatching {
                        val token = account.accessTokenForOwnedServer() ?: return@runCatching
                        client.delete("$base/v1/chat/runs/${run.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
                    }
                }
            }
            throw e
        }
    }

    private suspend fun pumpRun(client: HttpClient, account: AccountManager, base: String, run: Run, channel: ByteChannel) {
        var offset = 0L
        var failingSince = 0L
        var backoff = 1_000L
        var forceRefresh = false
        val buffer = ByteArray(8 * 1024)
        while (true) {
            try {
                val token = account.accessTokenForOwnedServer(forceRefresh) ?: throw RunGoneException("登录已失效，云端任务无法续接")
                forceRefresh = false
                val finished = client.prepareGet("$base/v1/chat/runs/${run.id}/stream") {
                    parameter("offset", offset)
                    header(HttpHeaders.Authorization, "Bearer $token")
                    // 熄屏后连接可能半开不报错，空闲超时后主动重连续接。
                    timeout { connectTimeoutMillis = 15_000; socketTimeoutMillis = STREAM_IDLE_MILLIS; requestTimeoutMillis = Long.MAX_VALUE }
                }.execute { response ->
                    when (response.status.value) {
                        200 -> {
                            val body = response.bodyAsChannel()
                            while (true) {
                                val count = body.readAvailable(buffer, 0, buffer.size)
                                if (count == -1) break
                                if (count > 0) {
                                    channel.writeFully(buffer, 0, count)
                                    offset += count
                                    failingSince = 0L
                                    backoff = 1_000L
                                }
                            }
                            true
                        }
                        401 -> { forceRefresh = true; false }
                        404, 410 -> throw RunGoneException("云端任务已失败或过期")
                        else -> false
                    }
                }
                if (finished) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunGoneException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "relay stream interrupted at offset=$offset type=${e.javaClass.simpleName}, reconnecting")
            }
            val now = System.currentTimeMillis()
            if (failingSince == 0L) failingSince = now
            if (now - failingSince > MAX_RECONNECT_MILLIS) throw IOException("云端任务长时间无法续接")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }
}

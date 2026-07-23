package com.android.everytalk.data.mcp.transport

import android.util.Log
import com.android.everytalk.data.network.MAX_ERROR_RESPONSE_BYTES
import com.android.everytalk.data.network.MAX_MCP_EVENT_BYTES
import com.android.everytalk.data.network.BoundedByteLineReader
import com.android.everytalk.data.network.ensureSseEventWithinLimit
import com.android.everytalk.data.network.readErrorTextAtMost
import com.android.everytalk.data.network.readTextAtMost
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.append
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.protocolWithAuthority
import io.ktor.utils.io.ByteReadChannel
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SseClientTransport"

/**
 * MCP 旧版 SSE 传输。
 */
class SseClientTransport(
    private val client: HttpClient,
    private val urlString: String,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractTransport() {

    private val initialized = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val endpoint = CompletableDeferred<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var streamChannel: ByteReadChannel? = null
    private var job: Job? = null

    override suspend fun start() {
        check(!closed.get()) { "SseClientTransport is closed!" }
        check(initialized.compareAndSet(false, true)) {
            "SSEClientTransport already started! If using Client class, note that connect() calls start() automatically."
        }

        job = scope.launch(CoroutineName("SseMcpClientTransport.connect#${hashCode()}")) {
            try {
                client.prepareGet(urlString) {
                    requestBuilder()
                    accept(ContentType.Text.EventStream)
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val body = response.readErrorTextAtMost() ?: "(no body)"
                        error("Error opening SSE endpoint (HTTP ${response.status}): $body")
                    }
                    val contentType = response.contentType()?.withoutParameters()
                    if (contentType != null && contentType != ContentType.Text.EventStream) {
                        val body = response.readTextAtMost(MAX_ERROR_RESPONSE_BYTES)
                        error("Unexpected SSE content type $contentType: $body")
                    }

                    val channel = response.bodyAsChannel()
                    streamChannel = channel
                    collectMessages(channel, response.call.request.url)
                }
            } catch (e: CancellationException) {
                if (!endpoint.isCompleted) endpoint.cancel(e)
                throw e
            } catch (e: Throwable) {
                if (!endpoint.isCompleted) endpoint.completeExceptionally(e)
                _onError(e)
            } finally {
                closeResources()
            }
        }

        try {
            endpoint.await()
        } catch (e: CancellationException) {
            closeResources()
            throw e
        } catch (e: Throwable) {
            closeResources()
            throw e
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        check(initialized.get()) { "SseClientTransport is not initialized!" }
        check(!closed.get() && job?.isActive == true) { "SseClientTransport is closed!" }
        check(endpoint.isCompleted) { "Not connected!" }

        try {
            client.preparePost(endpoint.getCompleted()) {
                requestBuilder()
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(McpJson.encodeToString(message))
            }.execute { response ->
                val bodyText = response.readTextAtMost(MAX_ERROR_RESPONSE_BYTES)
                if (!response.status.isSuccess()) {
                    error("Error POSTing to endpoint (HTTP ${response.status}): $bodyText")
                }
            }

            Log.d(TAG, "Client successfully sent message via SSE")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _onError(e)
            throw e
        }
    }

    override suspend fun close() {
        closeResources()
    }

    private suspend fun collectMessages(channel: ByteReadChannel, requestUrl: Url) {
        collectMcpSseEvents(channel) { event ->
            when (event.event) {
                "error" -> {
                    val details = event.data.orEmpty().take(1024)
                    val error = IllegalStateException("SSE error: $details")
                    throw error
                }

                "open" -> Unit
                "endpoint" -> handleEndpoint(event.data.orEmpty(), requestUrl)
                else -> event.data?.let { handleMessage(it) }
            }
        }
    }

    private fun handleEndpoint(eventData: String, requestUrl: Url) {
        try {
            val baseUrl = requestUrl.let { url ->
                val path = url.encodedPath
                when {
                    path.isEmpty() -> url.protocolWithAuthority
                    path.endsWith("/") -> url.protocolWithAuthority + path.removeSuffix("/")
                    else -> url.protocolWithAuthority + path.take(path.lastIndexOf("/"))
                }
            }
            val endpointUrl = if (eventData.startsWith("/")) {
                Url(requestUrl.protocolWithAuthority + eventData)
            } else {
                Url("$baseUrl/$eventData")
            }
            endpoint.complete(endpointUrl.toString())
            Log.d(TAG, "Client connected to SSE endpoint")
        } catch (e: Throwable) {
            endpoint.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun handleMessage(data: String) {
        try {
            _onMessage(McpJson.decodeFromString<JSONRPCMessage>(data))
        } catch (e: SerializationException) {
            _onError(e)
        }
    }

    private suspend fun closeResources() {
        if (!closed.compareAndSet(false, true)) return

        streamChannel?.cancel(null)
        streamChannel = null

        val currentJob = currentCoroutineContext()[Job]
        val runningJob = job
        job = null
        if (runningJob != null && runningJob !== currentJob) {
            runningJob.cancelAndJoin()
        }

        scope.cancel()
        endpoint.cancel()
        invokeOnCloseCallback()
    }
}

internal data class McpSseEvent(
    val id: String?,
    val event: String?,
    val data: String?,
)

internal class McpSseEventParser {
    private var id: String? = null
    private var event: String? = null
    private val data = StringBuilder()

    fun accept(line: String): McpSseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null

        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    fun finish(): McpSseEvent? = dispatch()

    private fun dispatch(): McpSseEvent? {
        if (id == null && event == null && data.isEmpty()) return null
        val result = McpSseEvent(id = id, event = event, data = data.takeIf { it.isNotEmpty() }?.toString())
        id = null
        event = null
        data.clear()
        return result
    }
}

internal suspend fun collectMcpSseEvents(
    source: ByteReadChannel,
    maxEventBytes: Long = MAX_MCP_EVENT_BYTES,
    onEvent: suspend (McpSseEvent) -> Unit,
) {
    require(maxEventBytes in 1..Int.MAX_VALUE.toLong()) { "MCP SSE 事件上限无效" }
    val lines = BoundedByteLineReader(source, maxEventBytes)
    val parser = McpSseEventParser()
    var eventStartBytes = 0L

    while (true) {
        val line = lines.readLine(eventStartBytes + maxEventBytes, maxEventBytes) ?: break
        ensureSseEventWithinLimit(eventStartBytes, lines.totalBytesRead, maxEventBytes)
        parser.accept(line)?.let { onEvent(it) }
        if (line.isEmpty()) eventStartBytes = lines.totalBytesRead
    }
    parser.finish()?.let { onEvent(it) }
}

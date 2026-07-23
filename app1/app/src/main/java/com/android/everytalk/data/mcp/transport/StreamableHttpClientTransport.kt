package com.android.everytalk.data.mcp.transport

import android.util.Log
import com.android.everytalk.data.network.MAX_ERROR_RESPONSE_BYTES
import com.android.everytalk.data.network.MAX_MCP_EVENT_BYTES
import com.android.everytalk.data.network.readErrorTextAtMost
import com.android.everytalk.data.network.readTextAtMost
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StreamableHttpTransport"

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"

class StreamableHttpError(val code: Int? = null, message: String? = null) :
    Exception("Streamable HTTP error: $message")

class StreamableHttpClientTransport(
    private val client: HttpClient,
    private val url: String,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractTransport() {
    var sessionId: String? = null
        private set
    var protocolVersion: String? = null

    private val initialized = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private var sseChannel: ByteReadChannel? = null
    private var sseJob: Job? = null

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    private var lastEventId: String? = null

    override suspend fun start() {
        check(!closed.get()) { "StreamableHttpClientTransport is closed!" }
        if (!initialized.compareAndSet(false, true)) {
            error("StreamableHttpClientTransport already started!")
        }
        Log.d(TAG, "Client transport starting...")
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        send(message, options?.resumptionToken, options?.onResumptionToken)
    }

    suspend fun send(
        message: JSONRPCMessage,
        resumptionToken: String?,
        onResumptionToken: ((String) -> Unit)? = null,
    ) {
        check(initialized.get() && !closed.get()) { "Transport is not started or already closed" }
        Log.d(TAG, "Client sending ${message::class.simpleName} via POST")

        resumptionToken?.let { token ->
            startSseSession(
                resumptionToken = token,
                onResumptionToken = onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null,
            )
            return
        }

        val jsonBody = McpJson.encodeToString(message)
        client.preparePost(url) {
            applyCommonHeaders(this)
            headers.append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
            requestBuilder()
        }.execute { response ->
            response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }

            if (response.status == HttpStatusCode.Accepted) {
                response.bodyAsChannel().cancel(null)
                if (message is JSONRPCNotification && message.method == "notifications/initialized") {
                    scope.launch {
                        try {
                            startSseSession(onResumptionToken = onResumptionToken)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to start SSE session, falling back to JSON-only mode", e)
                            _onError(e)
                        }
                    }
                }
                return@execute
            }

            if (!response.status.isSuccess()) {
                val error = StreamableHttpError(
                    response.status.value,
                    response.readErrorTextAtMost() ?: "(no body)",
                )
                _onError(error)
                throw error
            }

            when (response.contentType()?.withoutParameters()) {
                ContentType.Application.Json -> response.readTextAtMost(MAX_MCP_EVENT_BYTES)
                    .takeIf { it.isNotEmpty() }
                    ?.let { json ->
                        try {
                            _onMessage(McpJson.decodeFromString<JSONRPCMessage>(json))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            _onError(e)
                            throw e
                        }
                    }

                ContentType.Text.EventStream -> handleInlineSse(
                    response,
                    onResumptionToken = onResumptionToken,
                    replayMessageId = if (message is JSONRPCRequest) message.id else null,
                )

                else -> {
                    val body = response.readTextAtMost(MAX_ERROR_RESPONSE_BYTES)
                    if (response.contentType() == null && body.isBlank()) return@execute

                    val ct = response.contentType()?.toString() ?: "<none>"
                    val error = StreamableHttpError(-1, "Unexpected content type: $ct")
                    _onError(error)
                    throw error
                }
            }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.d(TAG, "Client transport closing.")

        try {
            terminateSession()
        } finally {
            sseChannel?.cancel(null)
            sseChannel = null
            val currentJob = currentCoroutineContext()[Job]
            val runningJob = sseJob
            sseJob = null
            if (runningJob != null && runningJob !== currentJob) {
                runningJob.cancelAndJoin()
            }
            scope.cancel()
            invokeOnCloseCallback()
        }
    }

    suspend fun terminateSession() {
        if (sessionId == null) return
        Log.d(TAG, "Terminating MCP session")
        client.prepareDelete(url) {
            applyCommonHeaders(this)
            requestBuilder()
        }.execute { response ->
            if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
                val details = response.readErrorTextAtMost() ?: response.status.description
                val error = StreamableHttpError(
                    response.status.value,
                    "Failed to terminate session: $details",
                )
                Log.e(TAG, "Failed to terminate session", error)
                _onError(error)
                throw error
            }

            response.bodyAsChannel().cancel(null)
        }

        sessionId = null
        lastEventId = null
        Log.d(TAG, "Session terminated successfully")
    }

    private suspend fun startSseSession(
        resumptionToken: String? = null,
        replayMessageId: RequestId? = null,
        onResumptionToken: ((String) -> Unit)? = null,
    ) {
        sseChannel?.cancel(null)
        sseChannel = null
        sseJob?.cancelAndJoin()

        Log.d(TAG, "Client attempting to start SSE session")
        val started = CompletableDeferred<Unit>()
        sseJob = scope.launch(CoroutineName("StreamableHttpTransport.collect#${hashCode()}")) {
            try {
                client.prepareGet(url) {
                    applyCommonHeaders(this)
                    accept(ContentType.Text.EventStream)
                    (resumptionToken ?: lastEventId)?.let { headers.append(MCP_RESUMPTION_TOKEN_HEADER, it) }
                    requestBuilder()
                }.execute { response ->
                    val responseContentType = response.contentType()?.withoutParameters()
                    if (response.status == HttpStatusCode.MethodNotAllowed) {
                        Log.i(TAG, "Server returned 405 for GET/SSE, stream disabled.")
                        started.complete(Unit)
                        return@execute
                    }
                    if (responseContentType == ContentType.Application.Json) {
                        Log.i(TAG, "Server returned application/json for GET/SSE, using JSON-only mode.")
                        started.complete(Unit)
                        return@execute
                    }
                    if (!response.status.isSuccess()) {
                        throw StreamableHttpError(
                            response.status.value,
                            response.readErrorTextAtMost() ?: "(no body)",
                        )
                    }
                    if (responseContentType != null && responseContentType != ContentType.Text.EventStream) {
                        throw StreamableHttpError(-1, "Unexpected SSE content type: $responseContentType")
                    }

                    val channel = response.bodyAsChannel()
                    sseChannel = channel
                    started.complete(Unit)
                    collectSse(channel, replayMessageId, onResumptionToken)
                }
            } catch (e: CancellationException) {
                if (!started.isCompleted) started.cancel(e)
                throw e
            } catch (e: Throwable) {
                if (!started.isCompleted) {
                    started.completeExceptionally(e)
                } else {
                    _onError(e)
                }
            } finally {
                sseChannel = null
            }
        }

        try {
            started.await()
            Log.d(TAG, "Client SSE session started successfully.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _onError(e)
            throw e
        }
    }

    private fun applyCommonHeaders(builder: HttpRequestBuilder) {
        builder.headers {
            sessionId?.let { append(MCP_SESSION_ID_HEADER, it) }
            protocolVersion?.let { append(MCP_PROTOCOL_VERSION_HEADER, it) }
        }
    }

    private suspend fun collectSse(
        channel: ByteReadChannel,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
    ) {
        collectMcpSseEvents(channel) { event ->
            event.id?.let {
                lastEventId = it
                onResumptionToken?.invoke(it)
            }
            Log.d(
                TAG,
                "Client received SSE event: event=${event.event}, dataChars=${event.data?.length ?: 0}, hasId=${event.id != null}",
            )
            when (event.event) {
                null, "message" -> event.data?.takeIf { it.isNotEmpty() }?.let { json ->
                    try {
                        val msg = McpJson.decodeFromString<JSONRPCMessage>(json)
                        if (replayMessageId != null && msg is JSONRPCResponse) {
                            _onMessage(msg.copy(id = replayMessageId))
                        } else {
                            _onMessage(msg)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        _onError(e)
                    }
                }

                "error" -> _onError(StreamableHttpError(null, event.data))
            }
        }
    }

    private suspend fun handleInlineSse(
        response: HttpResponse,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
    ) {
        Log.d(TAG, "Handling inline SSE from POST response")
        collectMcpSseEvents(response.bodyAsChannel()) { event ->
            event.id?.let {
                lastEventId = it
                onResumptionToken?.invoke(it)
            }
            val data = event.data?.takeIf { it.isNotBlank() } ?: return@collectMcpSseEvents
            if (event.event == null || event.event == "message") {
                try {
                    val msg = McpJson.decodeFromString<JSONRPCMessage>(data)
                    if (replayMessageId != null && msg is JSONRPCResponse) {
                        _onMessage(msg.copy(id = replayMessageId))
                    } else {
                        _onMessage(msg)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _onError(e)
                    throw e
                }
            }
        }
    }
}

package com.android.everytalk.data.mcp.transport

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

private const val TAG = "StreamableHttpTransport"

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"

class StreamableHttpError(val code: Int? = null, message: String? = null) :
    Exception("Streamable HTTP error: $message")

class StreamableHttpClientTransport(
    private val client: HttpClient,
    private val url: String,
    private val reconnectionTime: Duration? = null,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractTransport() {
    var sessionId: String? = null
        private set
    var protocolVersion: String? = null

    private val initialized = AtomicBoolean(false)

    private var sseSession: ClientSSESession? = null
    private var sseJob: Job? = null

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    private var lastEventId: String? = null

    override suspend fun start() {
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
        check(initialized.get()) { "Transport is not started" }
        Log.d(TAG, "Client sending message via POST to $url: ${McpJson.encodeToString(message)}")

        resumptionToken?.let { token ->
            startSseSession(
                resumptionToken = token,
                onResumptionToken = onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null,
            )
            return
        }

        val jsonBody = McpJson.encodeToString(message)
        val response = client.post(url) {
            applyCommonHeaders(this)
            headers.append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
            requestBuilder()
        }

        response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }

        if (response.status == HttpStatusCode.Accepted) {
            if (message is JSONRPCNotification && message.method == "notifications/initialized") {
                scope.launch {
                    try {
                        startSseSession(onResumptionToken = onResumptionToken)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to start SSE session, falling back to JSON-only mode", e)
                        _onError(e)
                    }
                }
            }
            return
        }

        if (!response.status.isSuccess()) {
            val error = StreamableHttpError(response.status.value, response.bodyAsText())
            _onError(error)
            throw error
        }

        when (response.contentType()?.withoutParameters()) {
            ContentType.Application.Json -> response.bodyAsText().takeIf { it.isNotEmpty() }?.let { json ->
                runCatching { McpJson.decodeFromString<JSONRPCMessage>(json) }
                    .onSuccess { _onMessage(it) }
                    .onFailure {
                        _onError(it)
                        throw it
                    }
            }

            ContentType.Text.EventStream -> handleInlineSse(
                response,
                onResumptionToken = onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null,
            )

            else -> {
                val body = response.bodyAsText()
                if (response.contentType() == null && body.isBlank()) return

                val ct = response.contentType()?.toString() ?: "<none>"
                val error = StreamableHttpError(-1, "Unexpected content type: $ct")
                _onError(error)
                throw error
            }
        }
    }

    override suspend fun close() {
        if (!initialized.get()) return
        Log.d(TAG, "Client transport closing.")

        try {
            terminateSession()

            sseSession?.cancel()
            sseJob?.cancelAndJoin()
            scope.cancel()
        } catch (_: Exception) {
        } finally {
            initialized.set(false)
            _onClose()
        }
    }

    suspend fun terminateSession() {
        if (sessionId == null) return
        Log.d(TAG, "Terminating session: $sessionId")
        val response = client.delete(url) {
            applyCommonHeaders(this)
            requestBuilder()
        }

        if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
            val error = StreamableHttpError(
                response.status.value,
                "Failed to terminate session: ${response.status.description}",
            )
            Log.e(TAG, "Failed to terminate session", error)
            _onError(error)
            throw error
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
        sseSession?.cancel()
        sseJob?.cancelAndJoin()

        Log.d(TAG, "Client attempting to start SSE session at url: $url")
        try {
            sseSession = client.sseSession(
                urlString = url,
                reconnectionTime = reconnectionTime,
            ) {
                method = HttpMethod.Get
                applyCommonHeaders(this)
                accept(ContentType.Application.Json)
                (resumptionToken ?: lastEventId)?.let { headers.append(MCP_RESUMPTION_TOKEN_HEADER, it) }
                requestBuilder()
            }
            Log.d(TAG, "Client SSE session started successfully.")
        } catch (e: SSEClientException) {
            val responseStatus = e.response?.status
            val responseContentType = e.response?.contentType()

            if (responseStatus == HttpStatusCode.MethodNotAllowed) {
                Log.i(TAG, "Server returned 405 for GET/SSE, stream disabled.")
                return
            }

            if (responseContentType?.match(ContentType.Application.Json) == true) {
                Log.i(TAG, "Server returned application/json for GET/SSE, using JSON-only mode.")
                return
            }

            _onError(e)
            throw e
        }

        sseJob = scope.launch(CoroutineName("StreamableHttpTransport.collect#${hashCode()}")) {
            sseSession?.let { collectSse(it, replayMessageId, onResumptionToken) }
        }
    }

    private fun applyCommonHeaders(builder: HttpRequestBuilder) {
        builder.headers {
            sessionId?.let { append(MCP_SESSION_ID_HEADER, it) }
            protocolVersion?.let { append(MCP_PROTOCOL_VERSION_HEADER, it) }
        }
    }

    private suspend fun collectSse(
        session: ClientSSESession,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
    ) {
        try {
            session.incoming.collect { event ->
                event.id?.let {
                    lastEventId = it
                    onResumptionToken?.invoke(it)
                }
                Log.d(TAG, "Client received SSE event: event=${event.event}, data=${event.data}, id=${event.id}")
                when (event.event) {
                    null, "message" ->
                        event.data?.takeIf { it.isNotEmpty() }?.let { json ->
                            runCatching { McpJson.decodeFromString<JSONRPCMessage>(json) }
                                .onSuccess { msg ->
                                    if (replayMessageId != null && msg is JSONRPCResponse) {
                                        _onMessage(msg.copy(id = replayMessageId))
                                    } else {
                                        _onMessage(msg)
                                    }
                                }
                                .onFailure(_onError)
                        }

                    "error" -> _onError(StreamableHttpError(null, event.data))
                }
            }
        } catch (_: CancellationException) {
        } catch (t: Throwable) {
            _onError(t)
        }
    }

    private suspend fun handleInlineSse(
        response: HttpResponse,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
    ) {
        Log.d(TAG, "Handling inline SSE from POST response")
        val channel = response.bodyAsChannel()

        val sb = StringBuilder()
        var id: String? = null
        var eventName: String? = null

        suspend fun dispatch(id: String?, eventName: String?, data: String) {
            id?.let {
                lastEventId = it
                onResumptionToken?.invoke(it)
            }
            if (data.isBlank()) {
                return
            }
            if (eventName == null || eventName == "message") {
                runCatching { McpJson.decodeFromString<JSONRPCMessage>(data) }
                    .onSuccess { msg ->
                        if (replayMessageId != null && msg is JSONRPCResponse) {
                            _onMessage(msg.copy(id = replayMessageId))
                        } else {
                            _onMessage(msg)
                        }
                    }
                    .onFailure {
                        _onError(it)
                        throw it
                    }
            }
        }

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.isEmpty()) {
                dispatch(id = id, eventName = eventName, data = sb.toString())
                id = null
                eventName = null
                sb.clear()
                continue
            }
            when {
                line.startsWith("id:") -> id = line.substringAfter("id:").trim()
                line.startsWith("event:") -> eventName = line.substringAfter("event:").trim()
                line.startsWith("data:") -> sb.append(line.substringAfter("data:").trim())
            }
        }
    }
}

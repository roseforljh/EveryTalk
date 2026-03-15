package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.parseQueryString
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class OpenClawGatewayClient(
    private val httpClient: HttpClient,
    private val json: Json = defaultJson
) : OpenClawChatTransport {

    override suspend fun streamChat(request: ChatRequest): Flow<AppStreamEvent> = channelFlow {
        val rawAddress = request.apiAddress?.trim().orEmpty()
        if (rawAddress.isBlank()) {
            send(AppStreamEvent.Error("OpenClaw Gateway address is empty."))
            send(AppStreamEvent.Finish("openclaw_invalid_config"))
            return@channelFlow
        }

        val uri = java.net.URI(rawAddress)
        val scheme = if (uri.scheme.equals("wss", ignoreCase = true)) URLProtocol.WSS else URLProtocol.WS
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val messageText = extractLatestUserText(request)
        val sessionKey = resolveSessionKey(request)
        val requestId = UUID.randomUUID().toString()
        val idempotencyKey = UUID.randomUUID().toString()

        OpenClawRuntimeState.update(sessionKey = sessionKey, runId = null)

        val session = httpClient.webSocketSession {
            url {
                protocol = scheme
                host = uri.host
                port = if (uri.port == -1) scheme.defaultPort else uri.port
                encodedPath = path
                uri.rawQuery?.let { parameters.appendAll(parseQueryString(it)) }
            }
            if (request.apiKey.isNotBlank()) {
                headers.append(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
            }
        }

        val connectPayload = OpenClawConnectRequest(
            id = UUID.randomUUID().toString(),
            params = OpenClawConnectParams(auth = OpenClawAuth(token = request.apiKey))
        )
        session.send(Frame.Text(json.encodeToString(connectPayload)))

        val sendPayload = buildChatSendRequest(
            request = request,
            messageText = messageText,
            requestId = requestId,
            sessionKey = sessionKey,
            idempotencyKey = idempotencyKey
        )
        session.send(Frame.Text(json.encodeToString(sendPayload)))

        val readerJob = launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            OpenClawEventMapper.mapChatEvent(frame.readText(), json)?.let { send(it) }
                        }
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                send(AppStreamEvent.Error(e.message ?: "OpenClaw Gateway stream failed."))
            } finally {
                send(AppStreamEvent.Finish("completed"))
                OpenClawRuntimeState.clear()
            }
        }

        readerJob.join()
    }

    companion object {
        fun resolveSessionKey(request: ChatRequest): String {
            val seed = request.conversationId?.takeIf { it.isNotBlank() }
                ?: request.deviceId?.takeIf { it.isNotBlank() }
                ?: "default"
            return buildSessionKey(seed)
        }

        fun buildSessionKey(conversationSeed: String): String {
            return "et:$conversationSeed"
        }

        fun buildChatSendRequest(
            request: ChatRequest,
            messageText: String,
            requestId: String,
            sessionKey: String,
            idempotencyKey: String
        ): OpenClawRpcRequest {
            return OpenClawRpcRequest(
                id = requestId,
                method = "chat.send",
                params = OpenClawChatSendParams(
                    sessionKey = sessionKey,
                    idempotencyKey = idempotencyKey,
                    text = messageText,
                    agentId = request.model.ifBlank { null }
                )
            )
        }

        fun extractLatestUserText(request: ChatRequest): String {
            return request.messages.lastOrNull()?.let { message ->
                when (message) {
                    is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> message.content
                    is com.android.everytalk.data.DataClass.PartsApiMessage -> message.parts.filterIsInstance<com.android.everytalk.data.DataClass.ApiContentPart.Text>()
                        .joinToString("\n") { it.text }
                }
            }.orEmpty()
        }

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }
}

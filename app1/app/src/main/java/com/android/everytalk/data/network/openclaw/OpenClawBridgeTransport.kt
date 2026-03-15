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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class OpenClawBridgeTransport(
    private val httpClient: HttpClient,
    private val json: Json = OpenClawGatewayClient.defaultJson
) : OpenClawChatTransport {

    override suspend fun streamChat(request: ChatRequest): Flow<AppStreamEvent> = channelFlow {
        val bridgeUrl = resolveBridgeUrl(request)
        if (bridgeUrl.isBlank()) {
            send(AppStreamEvent.Error("OpenClaw Bridge address is unreachable."))
            send(AppStreamEvent.Finish("openclaw_bridge_invalid_config"))
            return@channelFlow
        }

        val sessionKey = OpenClawGatewayClient.resolveSessionKey(request)
        val requestId = UUID.randomUUID().toString()
        val text = OpenClawGatewayClient.extractLatestUserText(request)
        val session = createSession(bridgeUrl = bridgeUrl, apiKey = request.apiKey)

        session.send(Frame.Text(json.encodeToString(buildConnectRequest(requestId = requestId, sessionKey = sessionKey))))
        session.send(Frame.Text(json.encodeToString(buildChatSendEnvelope(request = request, requestId = requestId, sessionKey = sessionKey, text = text))))

        val readerJob = launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> mapBridgeEvent(frame.readText())?.let { send(it) }
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                send(AppStreamEvent.Error(e.message ?: "OpenClaw Bridge stream failed."))
            } finally {
                send(AppStreamEvent.Finish("completed"))
            }
        }

        readerJob.join()
    }

    override suspend fun abortCurrentRun() {
        val context = OpenClawRuntimeState.current() ?: return
        OpenClawRuntimeState.markAbortRequested(context.sessionKey, context.runId)
    }

    internal fun buildConnectRequest(requestId: String, sessionKey: String): BridgeEnvelope {
        return BridgeEnvelope(
            type = "bridge.connect",
            requestId = requestId,
            sessionKey = sessionKey,
            payload = buildJsonObject {
                put("client", "EveryTalk")
                put("version", "1")
            }
        )
    }

    internal fun resolveBridgeUrl(request: ChatRequest): String {
        return request.openClawBridgeUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: request.apiAddress?.trim().orEmpty()
    }

    internal fun buildChatSendEnvelope(
        request: ChatRequest,
        requestId: String,
        sessionKey: String,
        text: String
    ): BridgeEnvelope {
        return BridgeEnvelope(
            type = "bridge.chat.send",
            requestId = requestId,
            sessionKey = sessionKey,
            payload = buildJsonObject {
                put("text", text)
                request.model.ifBlank { null }?.let { put("agentId", it) }
                request.conversationId?.takeIf { it.isNotBlank() }?.let { put("conversationId", it) }
            }
        )
    }

    internal fun buildAbortEnvelope(requestId: String, sessionKey: String, runId: String?): BridgeEnvelope {
        return BridgeEnvelope(
            type = "bridge.chat.abort",
            requestId = requestId,
            sessionKey = sessionKey,
            runId = runId,
            payload = buildJsonObject {
                runId?.let { put("runId", it) }
            }
        )
    }

    internal fun mapBridgeEvent(raw: String): AppStreamEvent? {
        val event = json.decodeFromString<BridgeEnvelope>(raw)
        if (event.sessionKey != null || event.runId != null) {
            OpenClawRuntimeState.update(event.sessionKey ?: OpenClawRuntimeState.current()?.sessionKey ?: "", event.runId)
        }
        val payload = event.payload as? JsonObject
        return when (event.type) {
            "bridge.chat.delta" -> AppStreamEvent.Content(payload?.get("text")?.jsonPrimitive?.content.orEmpty())
            "bridge.chat.done" -> AppStreamEvent.Finish("completed")
            "bridge.chat.error" -> AppStreamEvent.Error(payload?.get("message")?.jsonPrimitive?.content ?: "Bridge error")
            else -> null
        }
    }

    private suspend fun createSession(bridgeUrl: String, apiKey: String) = httpClient.webSocketSession {
        val uri = java.net.URI(bridgeUrl)
        url {
            protocol = if (uri.scheme.equals("wss", ignoreCase = true)) URLProtocol.WSS else URLProtocol.WS
            host = uri.host
            port = if (uri.port == -1) protocol.defaultPort else uri.port
            encodedPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            uri.rawQuery?.let { parameters.appendAll(parseQueryString(it)) }
        }
        if (apiKey.isNotBlank()) {
            headers.append(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }
}

@Serializable
internal data class BridgeEnvelope(
    val type: String,
    val requestId: String,
    val sessionKey: String? = null,
    val runId: String? = null,
    val payload: JsonElement? = null
)

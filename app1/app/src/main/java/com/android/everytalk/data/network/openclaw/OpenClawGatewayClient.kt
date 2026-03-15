package com.android.everytalk.data.network.openclaw

import android.content.Context
import android.os.Build
import android.util.Log
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.UUID

class OpenClawGatewayClient(
    private val httpClient: HttpClient,
    private val deviceIdentityManager: OpenClawDeviceIdentityManager,
    private val json: Json = defaultJson
) : OpenClawChatTransport {

    private enum class GatewayConnectionState {
        DISCONNECTED,
        SOCKET_CONNECTED,
        CHALLENGE_RECEIVED,
        CONNECT_SENT,
        CONNECTED
    }

    class PendingFrameQueue {
        private val frames = mutableListOf<String>()

        fun enqueue(frame: String) {
            frames += frame
        }

        fun drain(): List<String> {
            val snapshot = frames.toList()
            frames.clear()
            return snapshot
        }
    }

    data class DeviceIdentity(
        val deviceId: String,
        val publicKeyRaw: ByteArray,
        val privateKeyRaw: ByteArray
    ) {
        fun publicKeyBase64Url(): String = OpenClawDeviceAuthSigner.base64Url(publicKeyRaw)
    }

    private val pendingFrames = PendingFrameQueue()
    private var connectionState: GatewayConnectionState = GatewayConnectionState.DISCONNECTED

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

        logDebug("OpenClaw send", "sessionKey=$sessionKey requestId=$requestId messages.size=${request.messages.size} input=${messageText.take(80)}")
        request.messages.forEachIndexed { index, message ->
            val preview = when (message) {
                is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> message.content
                is com.android.everytalk.data.DataClass.PartsApiMessage -> message.parts
                    .filterIsInstance<com.android.everytalk.data.DataClass.ApiContentPart.Text>()
                    .joinToString(" ") { it.text }
            }.replace("\n", "\\n").take(80)
            logDebug("requestMessage[$index]", "role=${message.role} preview=$preview")
        }

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
        logDebug("WebSocket connected", "{\"gatewayUrl\":\"$rawAddress\",\"sessionKey\":\"$sessionKey\"}")

        connectionState = GatewayConnectionState.SOCKET_CONNECTED

        val identity = deviceIdentityManager.getOrCreate().toClientIdentity()

        enqueuePending(json.encodeToString(buildHistoryRequest(sessionKey = sessionKey)))

        val sendPayload = buildChatSendRequest(
            request = request,
            messageText = messageText,
            requestId = requestId,
            sessionKey = sessionKey,
            idempotencyKey = idempotencyKey
        )
        enqueuePending(json.encodeToString(sendPayload))

        ensureConnected(session = session, request = request, identity = identity)

        val readerJob = launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val raw = frame.readText()
                            logDebug("received frame", raw)
                            OpenClawEventMapper.mapChatEvent(raw, json)?.let { send(it) }
                        }
                        is Frame.Close -> {
                            logDebug("close reason", "closed")
                            break
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                logDebug("close reason / exception", e.message ?: "unknown")
                send(AppStreamEvent.Error(e.message ?: "OpenClaw Gateway stream failed."))
            } finally {
                send(AppStreamEvent.Finish("completed"))
                OpenClawRuntimeState.clear()
            }
        }

        readerJob.join()
    }

    companion object {
        private const val CLIENT_ID = "openclaw-android"
        private const val CLIENT_MODE = "ui"
        private const val ROLE = "operator"
        private const val PLATFORM = "android"
        private val OPERATOR_SCOPES = listOf("operator.read", "operator.write", "operator.admin")

        fun resolveSessionKey(request: ChatRequest): String {
            val remoteSeed = request.provider.takeIf {
                it.equals("OpenClaw Remote", ignoreCase = true)
            }?.let {
                request.apiAddress?.trim()?.let(::deriveRemoteSessionSeed)
            }
            val seed = request.openClawSessionId?.takeIf { it.isNotBlank() }
                ?: remoteSeed
                ?: request.conversationId?.takeIf { it.isNotBlank() }
                ?: request.deviceId?.takeIf { it.isNotBlank() }
                ?: "main"
            return buildSessionKey(seed)
        }

        fun buildSessionKey(conversationSeed: String): String {
            return conversationSeed.trim().takeIf { it.isNotBlank() }?.let {
                if (it == "main" || it.startsWith("et:")) it else "et:$it"
            } ?: "main"
        }

        fun deriveRemoteSessionSeed(apiAddress: String): String {
            return runCatching {
                val host = java.net.URI(apiAddress).host?.trim().orEmpty()
                if (host.isBlank()) "remote:main" else "remote:$host"
            }.getOrDefault("remote:main")
        }

        fun buildConnectRequest(
            request: ChatRequest,
            challengeNonce: String,
            identity: DeviceIdentity,
            clientVersion: String = "1.0",
            platform: String = PLATFORM,
            deviceFamily: String = ""
        ): OpenClawConnectRequest {
            val signedAt = System.currentTimeMillis()
            val scopes = OPERATOR_SCOPES
            val payload = OpenClawDeviceAuthSigner.buildDeviceAuthPayloadV3(
                deviceId = identity.deviceId,
                clientId = CLIENT_ID,
                clientMode = CLIENT_MODE,
                role = ROLE,
                scopes = scopes,
                signedAtMs = signedAt,
                token = request.apiKey,
                nonce = challengeNonce,
                platform = platform,
                deviceFamily = deviceFamily
            )
            val signature = OpenClawDeviceAuthSigner.signDevicePayload(
                privateKeyRaw = identity.privateKeyRaw,
                payload = payload
            )
            return OpenClawConnectRequest(
                id = UUID.randomUUID().toString(),
                params = OpenClawConnectParams(
                    client = OpenClawClientDescriptor(
                        id = CLIENT_ID,
                        version = clientVersion,
                        platform = platform,
                        mode = CLIENT_MODE
                    ),
                    role = ROLE,
                    scopes = scopes,
                    auth = OpenClawAuth(token = request.apiKey),
                    locale = Locale.getDefault().toLanguageTag().ifBlank { "en-US" },
                    userAgent = "EveryTalk-Android/1.0",
                    device = OpenClawDeviceDescriptor(
                        id = identity.deviceId,
                        publicKey = identity.publicKeyBase64Url(),
                        signature = signature,
                        signedAt = signedAt,
                        nonce = challengeNonce
                    )
                )
            )
        }

        fun buildChatSendRequest(
            request: ChatRequest,
            messageText: String,
            requestId: String,
            sessionKey: String,
            idempotencyKey: String
        ): OpenClawRpcRequest<OpenClawChatSendParams> {
            return OpenClawRpcRequest(
                id = requestId,
                method = "chat.send",
                params = OpenClawChatSendParams(
                    sessionKey = sessionKey,
                    idempotencyKey = idempotencyKey,
                    message = messageText,
                    deliver = false
                )
            )
        }

        fun buildHistoryRequest(sessionKey: String): OpenClawRpcRequest<OpenClawSessionParams> {
            return OpenClawRpcRequest(
                id = UUID.randomUUID().toString(),
                method = "chat.history",
                params = OpenClawSessionParams(sessionKey = sessionKey)
            )
        }

        fun buildAbortRequest(sessionKey: String, runId: String? = null): OpenClawRpcRequest<OpenClawAbortParams> {
            return OpenClawRpcRequest(
                id = UUID.randomUUID().toString(),
                method = "chat.abort",
                params = OpenClawAbortParams(
                    sessionKey = sessionKey,
                    runId = runId
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

        private fun defaultDeviceName(): String {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            return listOf(manufacturer, model)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .ifBlank { "EveryTalk Android" }
        }

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    private fun logDebug(stage: String, raw: String) {
        val truncated = if (raw.length > 2048) raw.take(2048) + "..." else raw
        runCatching {
            Log.d("OpenClawGateway", "$stage: $truncated")
        }
    }

    private suspend fun ensureConnected(
        session: io.ktor.websocket.WebSocketSession,
        request: ChatRequest,
        identity: DeviceIdentity
    ) {
        if (connectionState == GatewayConnectionState.CONNECTED) return

        val challenge = awaitConnectChallenge(session)
        connectionState = GatewayConnectionState.CHALLENGE_RECEIVED

        val connectPayload = buildConnectRequest(
            request = request,
            challengeNonce = challenge.nonce,
            identity = identity
        )
        val connectFrame = json.encodeToString(connectPayload)
        logDebug("sent frame", connectFrame)
        session.send(Frame.Text(connectFrame))
        connectionState = GatewayConnectionState.CONNECT_SENT

        awaitConnectHello(session)
        connectionState = GatewayConnectionState.CONNECTED
        sendPendingAfterHello(session)
    }

    private fun enqueuePending(frame: String) {
        pendingFrames.enqueue(frame)
    }

    private suspend fun sendPendingAfterHello(session: io.ktor.websocket.WebSocketSession) {
        pendingFrames.drain().forEach { frame ->
            logDebug("sent frame", frame)
            session.send(Frame.Text(frame))
        }
    }

    private suspend fun awaitConnectChallenge(session: io.ktor.websocket.WebSocketSession): OpenClawChallengeEvent {
        while (true) {
            when (val frame = session.incoming.receive()) {
                is Frame.Text -> {
                    val raw = frame.readText()
                    logDebug("received frame", raw)
                    parseChallenge(raw)?.let { return it }
                    val root = json.parseToJsonElement(raw).jsonObject
                    if (root["type"]?.jsonPrimitive?.content == "res") {
                        error("Expected connect.challenge before connect response: $raw")
                    }
                }
                is Frame.Close -> error("OpenClaw Gateway closed before connect.challenge")
                else -> Unit
            }
        }
    }

    private suspend fun awaitConnectHello(session: io.ktor.websocket.WebSocketSession) {
        while (true) {
            when (val frame = session.incoming.receive()) {
                is Frame.Text -> {
                    val raw = frame.readText()
                    logDebug("received frame", raw)
                    val root = json.parseToJsonElement(raw).jsonObject
                    if (root["type"]?.jsonPrimitive?.content == "res") {
                        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
                        if (!ok) {
                            error("Connect rejected: $raw")
                        }
                        return
                    }
                }
                is Frame.Close -> error("OpenClaw Gateway closed before connect hello")
                else -> Unit
            }
        }
    }

    private fun parseChallenge(raw: String): OpenClawChallengeEvent? {
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val event = root["event"]?.jsonPrimitive?.content
            if (root["type"]?.jsonPrimitive?.content == "event" && event == "connect.challenge") {
                val payload = root["payload"]?.jsonObject ?: return null
                OpenClawChallengeEvent(
                    nonce = payload["nonce"]?.jsonPrimitive?.content.orEmpty(),
                    ts = payload["ts"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else {
                null
            }
        }.getOrNull()
    }

    private fun OpenClawDeviceIdentity.toClientIdentity(): DeviceIdentity {
        return DeviceIdentity(
            deviceId = deviceId,
            publicKeyRaw = publicKeyRaw,
            privateKeyRaw = privateKeyRaw
        )
    }

    private data class OpenClawChallengeEvent(
        val nonce: String,
        val ts: Long
    )
}

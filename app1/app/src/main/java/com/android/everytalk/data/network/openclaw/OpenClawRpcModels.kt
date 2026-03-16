package com.android.everytalk.data.network.openclaw

import kotlinx.serialization.Serializable

@Serializable
data class OpenClawRpcRequest<T>(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: T
)

@Serializable
data class OpenClawRpcError(
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class OpenClawRpcResponse<T>(
    val type: String,
    val id: String,
    val ok: Boolean? = null,
    val payload: T? = null,
    val error: OpenClawRpcError? = null
)

@Serializable
data class OpenClawChatSendParams(
    val sessionKey: String,
    val idempotencyKey: String,
    val message: String,
    val deliver: Boolean = false
)

@Serializable
data class OpenClawSessionParams(
    val sessionKey: String
)

@Serializable
data class OpenClawSessionsPreviewParams(
    val keys: List<String>,
    val limit: Int = 12,
    val maxChars: Int = 240
)

@Serializable
data class OpenClawSessionPreviewContentItem(
    val role: String? = null,
    val text: String? = null
)

@Serializable
data class OpenClawSessionPreviewItem(
    val key: String? = null,
    val sessionKey: String? = null,
    val title: String? = null,
    val preview: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val reasoning: String? = null,
    val verbose: String? = null,
    val elevated: String? = null,
    val updatedAt: Long? = null,
    val items: List<OpenClawSessionPreviewContentItem> = emptyList()
)

@Serializable
data class OpenClawSessionsPreviewResponse(
    val ts: Long? = null,
    val previews: List<OpenClawSessionPreviewItem> = emptyList()
)

@Serializable
data class OpenClawModelsListParams(
    val provider: String? = null
)

@Serializable
data class OpenClawModelDescriptor(
    val provider: String? = null,
    val model: String? = null,
    val id: String? = null,
    val name: String? = null
)

@Serializable
data class OpenClawModelsProviderEntry(
    val provider: String? = null,
    val models: List<OpenClawModelDescriptor> = emptyList(),
    val items: List<OpenClawModelDescriptor> = emptyList()
)

@Serializable
data class OpenClawModelsListResponse(
    val providers: List<OpenClawModelsProviderEntry> = emptyList(),
    val models: List<OpenClawModelDescriptor> = emptyList(),
    val items: List<OpenClawModelDescriptor> = emptyList()
)

@Serializable
data class OpenClawRuntimeStatusPayload(
    val sessionKey: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val reasoning: String? = null,
    val verbose: String? = null,
    val elevated: String? = null
)

@Serializable
data class OpenClawAbortParams(
    val sessionKey: String,
    val runId: String? = null
)

@Serializable
data class OpenClawConnectRequest(
    val type: String = "req",
    val id: String,
    val method: String = "connect",
    val params: OpenClawConnectParams
)

@Serializable
data class OpenClawConnectParams(
    val minProtocol: Int = 3,
    val maxProtocol: Int = 3,
    val client: OpenClawClientDescriptor,
    val role: String,
    val scopes: List<String> = emptyList(),
    val caps: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val permissions: Map<String, Boolean> = emptyMap(),
    val auth: OpenClawAuth,
    val locale: String = "en-US",
    val userAgent: String = "EveryTalk-Android/1.0",
    val device: OpenClawDeviceDescriptor
)

@Serializable
data class OpenClawClientDescriptor(
    val id: String,
    val version: String,
    val platform: String,
    val mode: String
)

@Serializable
data class OpenClawDeviceDescriptor(
    val id: String,
    val publicKey: String,
    val signature: String,
    val signedAt: Long,
    val nonce: String
)

@Serializable
data class OpenClawAuth(
    val token: String
)

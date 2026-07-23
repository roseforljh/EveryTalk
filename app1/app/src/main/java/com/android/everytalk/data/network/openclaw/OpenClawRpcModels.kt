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

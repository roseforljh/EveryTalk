package com.android.everytalk.data.network.openclaw

import kotlinx.serialization.Serializable

@Serializable
data class OpenClawRpcRequest(
    val id: String,
    val method: String,
    val params: OpenClawChatSendParams
)

@Serializable
data class OpenClawChatSendParams(
    val sessionKey: String,
    val idempotencyKey: String,
    val text: String,
    val agentId: String? = null
)

@Serializable
data class OpenClawAbortRequest(
    val id: String,
    val method: String,
    val params: OpenClawAbortParams
)

@Serializable
data class OpenClawAbortParams(
    val sessionKey: String,
    val runId: String? = null
)

@Serializable
data class OpenClawConnectRequest(
    val id: String,
    val method: String = "connect",
    val params: OpenClawConnectParams
)

@Serializable
data class OpenClawConnectParams(
    val auth: OpenClawAuth
)

@Serializable
data class OpenClawAuth(
    val token: String
)

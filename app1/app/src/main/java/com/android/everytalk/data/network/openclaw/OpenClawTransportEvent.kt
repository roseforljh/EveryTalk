package com.android.everytalk.data.network.openclaw

import kotlinx.serialization.Serializable

@Serializable
data class OpenClawTransportEvent(
    val sessionKey: String? = null,
    val runId: String? = null,
    val requestId: String? = null,
    val type: String,
    val text: String? = null,
    val message: String? = null
)

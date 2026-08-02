package com.android.everytalk.data.network

import kotlinx.serialization.Serializable

@Serializable
data class WebFetchResult(
    val success: Boolean,
    val requestedUrl: String,
    val finalUrl: String? = null,
    val title: String? = null,
    val content: String? = null,
    val truncated: Boolean = false,
    val truncationReason: String? = null,
    val statusCode: Int? = null,
    val error: String? = null,
)

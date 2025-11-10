package com.android.everytalk.data.DataClass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("body")
    val body: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("prerelease")
    val prerelease: Boolean = false
)
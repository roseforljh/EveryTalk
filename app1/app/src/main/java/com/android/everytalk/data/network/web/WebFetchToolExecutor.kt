package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object WebFetchToolExecutor {
    private const val DEFAULT_MAX_CONTENT_CHARS = 12_000

    suspend fun execute(arguments: JsonObject): JsonObject {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val maxContentChars = arguments["max_chars"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it > 0 }
            ?: DEFAULT_MAX_CONTENT_CHARS

        val result = WebFetchService.fetch(
            url = url,
            maxContentChars = maxContentChars,
        )

        val images = if (result.success && !result.content.isNullOrBlank()) {
            WebFetchImageExtractor.extractAndDownloadImages(result.content)
        } else {
            emptyList()
        }

        return buildJsonObject {
            put("ok", JsonPrimitive(result.success))
            put("requestedUrl", JsonPrimitive(result.requestedUrl))
            result.finalUrl?.let { put("finalUrl", JsonPrimitive(it)) }
            result.title?.let { put("title", JsonPrimitive(it)) }
            result.content?.let { put("content", JsonPrimitive(it)) }
            put("truncated", JsonPrimitive(result.truncated))
            result.truncationReason?.let { put("truncationReason", JsonPrimitive(it)) }
            result.statusCode?.let { put("statusCode", JsonPrimitive(it)) }
            result.error?.let { put("error", JsonPrimitive(it)) }
            put("maxContentChars", JsonPrimitive(maxContentChars))
            if (images.isNotEmpty()) {
                put("_images", buildJsonArray {
                    images.forEach { img ->
                        add(buildJsonObject {
                            put("url", JsonPrimitive(img.url))
                            put("base64", JsonPrimitive(img.base64Data))
                            put("mimeType", JsonPrimitive(img.mimeType))
                        })
                    }
                })
            }
        }
    }
}

package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object WebFetchToolExecutor {
    private const val DEFAULT_MAX_CONTENT_CHARS = 12_000

    suspend fun execute(arguments: JsonObject): JsonObject {
        return execute(arguments, { url, maxChars -> WebFetchService.fetch(url, maxChars) }, WebFetchImages::fetch)
    }

    /** 在网页解析前分流图片，避免将 PNG 等二进制内容交给文本解析服务。 */
    internal suspend fun execute(
        arguments: JsonObject,
        fetchPage: suspend (String, Int) -> WebFetchResult,
        fetchImage: suspend (String) -> JsonObject,
    ): JsonObject {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (arguments["format"]?.jsonPrimitive?.contentOrNull == "image" || WebFetchImages.isImageUrl(url)) {
            return fetchImage(url)
        }
        val maxContentChars = arguments["max_chars"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it > 0 }
            ?: DEFAULT_MAX_CONTENT_CHARS

        val result = fetchPage(url, maxContentChars)

        val response = buildJsonObject {
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
        }
        return if (result.success) WebFetchImages.attach(response, result.imageUrls) else response
    }
}

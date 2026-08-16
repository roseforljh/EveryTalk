package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ChatRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*

private const val MAX_FILE_UPLOAD_RESPONSE_BYTES = 1L * 1024L * 1024L

internal data class OpenAiToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String
)

internal data class OpenAIParseResult(
    val hasToolCalls: Boolean,
    val fullText: String,
    val reasoningContent: String = "",
    val usage: TokenUsage? = null,
    val toolCalls: List<OpenAiToolCallInfo> = emptyList(),
)

internal fun mapToJsonElement(map: Map<String, Any>): JsonElement {
    return buildJsonObject {
        map.forEach { (key, value) ->
            put(key, anyToJsonElement(value))
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun anyToJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> mapToJsonElement(value as Map<String, Any>)
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}

internal suspend fun uploadFileToDashScope(
    client: HttpClient,
    apiKey: String,
    fileName: String,
    fileBytes: ByteArray
): String {
    // https://dashscope.aliyuncs.com/compatible-mode/v1/files
    return client.preparePost("https://dashscope.aliyuncs.com/compatible-mode/v1/files") {
        setBody(MultiPartFormDataContent(formData {
            append("file", fileBytes, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
                    "txt" -> "text/plain"
                    "pdf" -> "application/pdf"
                    "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> "application/octet-stream"
                }
                append(HttpHeaders.ContentType, mimeType)
            })
            append("purpose", "file-extract")
        }))
        header(HttpHeaders.Authorization, "Bearer $apiKey")
    }.execute { response ->
        if (!response.status.isSuccess()) {
            throw Exception("Upload failed: ${response.status}")
        }

        val json = Json.parseToJsonElement(
            response.readTextAtMost(MAX_FILE_UPLOAD_RESPONSE_BYTES)
        ).jsonObject
        json["id"]?.jsonPrimitive?.content ?: throw Exception("No file id in response")
    }
}

internal fun shouldFallbackToResponses(errorBody: String?): Boolean {
    if (errorBody.isNullOrBlank()) return false
    val lower = errorBody.lowercase()
    // HTML 页面（Cloudflare 拦截）或非 JSON 响应
    return lower.contains("<html") ||
        lower.contains("cloudflare") ||
        lower.contains("<!doctype") ||
        (lower.contains("403 forbidden") && !lower.startsWith("{"))
}

internal fun resolvedOpenAIApiAddress(request: ChatRequest): String =
    request.apiAddress?.trim()?.takeIf { it.isNotEmpty() }
        ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trim().takeIf { it.isNotEmpty() }
        ?: "https://api.openai.com"

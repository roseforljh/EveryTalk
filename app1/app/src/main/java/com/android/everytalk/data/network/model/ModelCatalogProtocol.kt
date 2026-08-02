package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.modelParameterProtocol
import io.ktor.http.URLBuilder
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal const val MAX_MODEL_CATALOG_PAGES = 20
internal const val MAX_MODEL_CATALOG_ENTRIES = 20_000

internal enum class ModelCatalogAuthMode {
    BEARER,
    ANTHROPIC,
    GOOGLE_API_KEY_HEADER,
}

internal data class ModelCatalogEndpoint(
    val protocol: ModelParameterProtocol,
    val normalizedBase: String,
    val listUrl: String,
    val authMode: ModelCatalogAuthMode,
)

internal data class ModelPageCursor(
    val parameter: String? = null,
    val value: String? = null,
    val directUrl: String? = null,
)

private val pagingJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun resolveModelCatalogEndpoint(
    apiUrl: String,
    channel: String?,
): ModelCatalogEndpoint {
    val normalizedBase = normalizeApiBase(apiUrl)
    val protocol = modelParameterProtocol(channel.orEmpty())
    val uri = runCatching { URI(normalizedBase) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    val scheme = uri?.scheme ?: "https"
    val googleOfficial = host == "generativelanguage.googleapis.com" ||
        (host.endsWith("googleapis.com") && host.contains("generativelanguage"))
    val listUrl = when {
        googleOfficial -> "$scheme://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
        protocol == ModelParameterProtocol.GEMINI -> resolveGeminiModelsUrl(normalizedBase)
        protocol == ModelParameterProtocol.ANTHROPIC -> URLBuilder(
            AnthropicDirectClient.resolveModelsUrl(normalizedBase)
        ).apply {
            parameters.append("limit", "1000")
        }.buildString()
        host.contains("open.bigmodel.cn") -> "$scheme://open.bigmodel.cn/api/paas/v4/models"
        else -> resolveOpenAIModelsUrl(normalizedBase)
    }
    val authMode = when {
        googleOfficial -> ModelCatalogAuthMode.GOOGLE_API_KEY_HEADER
        protocol == ModelParameterProtocol.ANTHROPIC -> ModelCatalogAuthMode.ANTHROPIC
        else -> ModelCatalogAuthMode.BEARER
    }
    return ModelCatalogEndpoint(protocol, normalizedBase, listUrl, authMode)
}

internal fun buildModelDetailUrl(endpoint: ModelCatalogEndpoint, modelId: String): String {
    val base = endpoint.listUrl.substringBefore('?').trimEnd('/')
    val normalizedModelId = modelId.removePrefix("models/").trim()
    val encoded = URLEncoder.encode(normalizedModelId, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    return "$base/$encoded"
}

internal fun applyModelPageCursor(currentUrl: String, cursor: ModelPageCursor): String? {
    cursor.directUrl?.let { nextUrl ->
        val current = runCatching { URI(currentUrl) }.getOrNull() ?: return null
        val next = runCatching { current.resolve(nextUrl) }.getOrNull() ?: return null
        val sameOrigin = current.scheme.equals(next.scheme, ignoreCase = true) &&
            current.host.equals(next.host, ignoreCase = true) &&
            effectivePort(current) == effectivePort(next)
        return next.toString().takeIf { sameOrigin }
    }
    val parameter = cursor.parameter?.takeIf(String::isNotBlank) ?: return null
    val value = cursor.value?.takeIf(String::isNotBlank) ?: return null
    return URLBuilder(currentUrl).apply {
        parameters.remove(parameter)
        parameters.append(parameter, value)
    }.buildString()
}

internal fun parseModelPageCursor(
    responseBody: String,
    protocol: ModelParameterProtocol,
): ModelPageCursor? {
    val root = runCatching { pagingJson.parseToJsonElement(responseBody) as? JsonObject }
        .getOrNull() ?: return null
    if (protocol == ModelParameterProtocol.GEMINI) {
        return root.string("nextPageToken")
            ?.takeIf(String::isNotBlank)
            ?.let { ModelPageCursor(parameter = "pageToken", value = it) }
    }

    val hasMore = root.boolean("has_more") ?: root.boolean("hasMore")
    val lastId = root.string("last_id") ?: root.string("lastId")
    if (hasMore == true && !lastId.isNullOrBlank()) {
        val parameter = if (protocol == ModelParameterProtocol.ANTHROPIC) "after_id" else "after"
        return ModelPageCursor(parameter = parameter, value = lastId)
    }

    val containers = listOfNotNull(
        root,
        root["pagination"] as? JsonObject,
        root["meta"] as? JsonObject,
    )
    containers.forEach { container ->
        val next = container["next"] ?: container["next_page"] ?: container["nextPage"]
        when (next) {
            is JsonPrimitive -> {
                val value = next.contentOrNull?.takeIf(String::isNotBlank)
                if (value != null) {
                    return if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith('/')) {
                        ModelPageCursor(directUrl = value)
                    } else {
                        ModelPageCursor(parameter = "page", value = value)
                    }
                }
            }
            is JsonObject -> {
                val direct = next.string("url") ?: next.string("href")
                if (!direct.isNullOrBlank()) return ModelPageCursor(directUrl = direct)
                val cursor = next.string("cursor") ?: next.string("token")
                if (!cursor.isNullOrBlank()) return ModelPageCursor(parameter = "cursor", value = cursor)
            }
            else -> Unit
        }
        val cursor = container.string("next_cursor") ?: container.string("nextCursor")
        if (!cursor.isNullOrBlank()) return ModelPageCursor(parameter = "cursor", value = cursor)
    }
    return null
}

private fun normalizeApiBase(apiUrl: String): String {
    val raw = apiUrl.trim().removeSuffix("#").trim().trimEnd('/')
    return when {
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        raw.isNotEmpty() -> "https://$raw"
        else -> raw
    }
}

private fun resolveGeminiModelsUrl(base: String): String {
    val trimmed = base.trimEnd('/')
    return when {
        trimmed.endsWith("/v1beta/models", ignoreCase = true) -> "$trimmed?pageSize=1000"
        trimmed.endsWith("/v1beta", ignoreCase = true) -> "$trimmed/models?pageSize=1000"
        trimmed.endsWith("/models", ignoreCase = true) -> "$trimmed?pageSize=1000"
        else -> "$trimmed/v1beta/models?pageSize=1000"
    }
}

private fun resolveOpenAIModelsUrl(base: String): String {
    val uri = runCatching { URI(base) }.getOrNull() ?: return "$base/v1/models"
    var path = uri.path.orEmpty().trimEnd('/')
    val messageSuffixes = listOf(
        "/v1/chat/completions" to "/v1",
        "/chat/completions" to "",
        "/v1/completions" to "/v1",
        "/completions" to "",
        "/v1/responses" to "/v1",
        "/responses" to "",
    )
    messageSuffixes.firstOrNull { (suffix) -> path.endsWith(suffix, ignoreCase = true) }
        ?.let { (suffix, replacement) -> path = path.dropLast(suffix.length) + replacement }
    path = when {
        path.endsWith("/models", ignoreCase = true) -> path
        path.endsWith("/v1", ignoreCase = true) -> "$path/models"
        path.contains("/v1/", ignoreCase = true) -> "$path/models"
        else -> "$path/v1/models"
    }
    return URI(uri.scheme, uri.userInfo, uri.host, uri.port, path, null, null).toString()
}

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

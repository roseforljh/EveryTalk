package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 统一稳定 tools 结构，并为官方 OpenAI 请求生成缓存族标识。 */
internal object PromptCachePolicy {

    internal enum class ToolProfile {
        NONE,
        CURRENT_TIME,
        WEB_SEARCH,
        WEB_FETCH,
        MCP,
        CUSTOM,
        MIXED,
    }

    private const val CACHE_KEY_HASH_CHARS = 40
    private var lastLoggedToolProfile: ToolProfile? = null

    fun normalizeTools(tools: List<Map<String, Any>>?): List<Map<String, Any>>? {
        return tools.orEmpty()
            .map(::normalizeMap)
            .sortedWith(compareBy<Map<String, Any>>({ toolSortName(it) }, { canonicalJson(it) }))
            .ifEmpty { null }
    }

    fun toolSchemaHash(tools: List<Map<String, Any>>?): String =
        sha256(canonicalJson(normalizeTools(tools).orEmpty()))

    fun systemFingerprint(messages: List<AbstractApiMessage>): String =
        sha256(
            messages.firstOrNull { it.role.equals("system", ignoreCase = true) }
                ?.let(::extractText)
                .orEmpty(),
        ).take(CACHE_KEY_HASH_CHARS)

    fun toolProfile(tools: List<Map<String, Any>>?): ToolProfile {
        val names = normalizeTools(tools).orEmpty().mapNotNull(::extractToolName).toSet()
        if (names.isEmpty()) return ToolProfile.NONE
        val profiles = buildSet {
            if ("get_current_datetime" in names) add(ToolProfile.CURRENT_TIME)
            if ("web_search" in names || names.any { it.contains("search") }) add(ToolProfile.WEB_SEARCH)
            if ("webfetch" in names || names.any { it.contains("fetch") || it.contains("crawl") }) add(ToolProfile.WEB_FETCH)
            if (names.any { it.contains("mcp") || it.contains("query-docs") || it.contains("internal-service") }) add(ToolProfile.MCP)
            if (names.any { it !in setOf("get_current_datetime", "web_search", "webfetch") &&
                    !it.contains("search") && !it.contains("fetch") && !it.contains("crawl") &&
                    !it.contains("mcp") && !it.contains("query-docs") && !it.contains("internal-service")
            }) add(ToolProfile.CUSTOM)
        }
        return if (profiles.size == 1) profiles.single() else ToolProfile.MIXED
    }

    fun logToolProfile(tools: List<Map<String, Any>>?) {
        val normalized = normalizeTools(tools)
        val profile = toolProfile(normalized)
        val changed = synchronized(this) {
            if (lastLoggedToolProfile == profile) {
                false
            } else {
                lastLoggedToolProfile = profile
                true
            }
        }
        if (changed) {
            runCatching {
                Log.d(
                    "PromptCachePolicy",
                    "工具 profile 切换为 $profile，schema=${toolSchemaHash(normalized).take(16)}",
                )
            }
        }
    }

    fun buildOpenAICacheKey(
        apiAddress: String?,
        model: String,
        messages: List<AbstractApiMessage>,
        tools: List<Map<String, Any>>?,
    ): String? {
        if (!isOfficialOpenAIEndpoint(apiAddress) || !supportsPromptCaching(model)) return null
        val systemText = messages
            .firstOrNull { it.role.equals("system", ignoreCase = true) }
            ?.let(::extractText)
            .orEmpty()
        val material = buildString {
            append("protocol=").append(SystemPromptInjector.PROTOCOL_VERSION)
            append("\ncapabilities=").append(SystemPromptInjector.CAPABILITY_PROTOCOL_VERSION)
            append("\nmodel=").append(model.trim().lowercase())
            append("\nsystem=").append(sha256(systemText))
            append("\nprofile=").append(toolProfile(tools).name)
            append("\ntools=").append(toolSchemaHash(tools))
        }
        return "et-cap-v${SystemPromptInjector.CAPABILITY_PROTOCOL_VERSION}-${sha256(material).take(CACHE_KEY_HASH_CHARS)}"
    }

    fun isOfficialOpenAIEndpoint(apiAddress: String?): Boolean {
        val normalized = apiAddress?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val host = runCatching { URI(normalized).host }.getOrNull()
        return host.equals("api.openai.com", ignoreCase = true)
    }

    fun supportsPromptCaching(model: String): Boolean {
        val normalized = model.trim().lowercase()
        return normalized.startsWith("gpt-4o") ||
            normalized.startsWith("gpt-4.1") ||
            normalized.startsWith("gpt-4.5") ||
            Regex("^gpt-(?:[5-9]|[1-9]\\d)(?:[.-]|$)").containsMatchIn(normalized) ||
            normalized.matches(Regex("^o[134](?:-|$).*"))
    }

    private fun extractText(message: AbstractApiMessage): String = when (message) {
        is SimpleTextApiMessage -> message.content
        is PartsApiMessage -> message.parts
            .filterIsInstance<ApiContentPart.Text>()
            .joinToString("\n") { it.text }
    }

    private fun normalizeMap(map: Map<*, *>): Map<String, Any> = buildMap {
        map.entries
            .mapNotNull { entry -> (entry.key as? String)?.let { it to entry.value } }
            .sortedBy { it.first }
            .forEach { (key, value) -> put(key, normalizeValue(value)) }
    }

    private fun normalizeValue(value: Any?): Any = when (value) {
        null -> JsonNull
        JsonNull -> JsonNull
        is JsonObject -> normalizeMap(value)
        is JsonArray -> value.map(::normalizeValue)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull!!
            value.longOrNull != null -> value.longOrNull!!
            value.doubleOrNull != null -> value.doubleOrNull!!
            else -> value.content
        }
        is JsonElement -> value
        is Map<*, *> -> normalizeMap(value)
        is List<*> -> value.map(::normalizeValue)
        is Array<*> -> value.map(::normalizeValue)
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    private fun toolSortName(tool: Map<String, Any>): String {
        val function = tool["function"] as? Map<*, *>
        return ((function?.get("name") ?: tool["name"] ?: tool.keys.firstOrNull()) as? String)
            ?.lowercase()
            .orEmpty()
    }

    private fun extractToolName(tool: Map<String, Any>): String? {
        val function = tool["function"] as? Map<*, *>
        return ((function?.get("name") ?: tool["name"] ?: tool.keys.firstOrNull()) as? String)
            ?.lowercase()
    }

    private fun canonicalJson(value: Any?): String = toJsonElement(value).toString()

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> when (value) {
            is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to toJsonElement(it.value) })
            is JsonArray -> JsonArray(value.map(::toJsonElement))
            else -> value
        }
        is Map<*, *> -> JsonObject(
            value.entries
                .mapNotNull { entry -> (entry.key as? String)?.let { it to toJsonElement(entry.value) } }
                .sortedBy { it.first }
                .associate { it },
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        is Array<*> -> JsonArray(value.map(::toJsonElement))
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

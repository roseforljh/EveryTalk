package com.android.everytalk.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 合同 0.2.0 的配置字段扫描；只处理配置 value，不扫描消息正文中的 tokenUsage 等合法统计字段。 */
internal object SyncContractSanitizer {
    private val forbiddenNameParts = listOf(
        "key", "token", "secret", "password", "authorization", "cookie", "headers", "privatekey",
    )
    private val secretValues = listOf(
        Regex("(?i)\\bsk-[A-Za-z0-9][A-Za-z0-9_-]{7,}\\b"),
        Regex("(?i)\\bBearer[ \\t]+[A-Za-z0-9._~+/=-]{8,}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
        Regex("-----BEGIN [A-Z0-9][A-Z0-9 ]+-----"),
        Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@"),
    )

    fun validate(value: JsonElement) {
        walk(value, "")
    }

    fun isForbiddenFieldName(name: String): Boolean {
        val normalized = name.filter(Char::isLetterOrDigit).lowercase()
        return forbiddenNameParts.any(normalized::contains)
    }

    fun isValidEnvelope(value: JsonElement): Boolean {
        if (value !is JsonObject || value.keys != setOf("\$enc", "alg", "nonce", "ct", "keyVersion")) return false
        return runCatching {
            val envelope = syncJson.decodeFromJsonElement(SyncEncryptedEnvelope.serializer(), value)
            envelope.marker == 1 && envelope.alg == "AES-256-GCM" && envelope.keyVersion > 0 &&
                value.toString().toByteArray(Charsets.UTF_8).size <= 8192 &&
                SyncCrypto.decodeCanonicalBase64(envelope.nonce).size == 12 &&
                SyncCrypto.decodeCanonicalBase64(envelope.ct).size >= 16
        }.getOrDefault(false)
    }

    private fun walk(value: JsonElement, path: String) {
        when (value) {
            is JsonObject -> {
                if (value.containsKey("\$enc")) require(isValidEnvelope(value)) { "INVALID_ENVELOPE" }
                else value.forEach { (name, child) ->
                    // 合同允许凭据字段名，但整个值必须是严格的密文信封。
                    require(!isForbiddenFieldName(name) || isValidEnvelope(child)) { "FORBIDDEN_FIELD_NAME" }
                    walk(child, "$path/${escape(name)}")
                }
            }
            is JsonArray -> value.forEachIndexed { index, child -> walk(child, "$path/$index") }
            is JsonPrimitive -> value.contentOrNull?.let { text ->
                require(secretValues.none { it.containsMatchIn(text) }) { "CONTAINS_SECRET" }
            }
        }
    }

    private fun escape(value: String): String = value.replace("~", "~0").replace("/", "~1")

}

/** 合同允许的传输字段名，避免 maxTokens/maxContextTokens 被名称扫描误判。 */
internal object SyncWireFieldNames {
    const val OUTPUT_LIMIT = "outputLimit"
    const val CONTEXT_LIMIT = "contextLimit"

    fun fromLocal(name: String): String = when (name) {
        "maxTokens" -> OUTPUT_LIMIT
        "maxContextTokens" -> CONTEXT_LIMIT
        else -> name
    }

    fun toLocal(name: String): String = when (name) {
        OUTPUT_LIMIT -> "maxTokens"
        CONTEXT_LIMIT -> "maxContextTokens"
        else -> name
    }
}

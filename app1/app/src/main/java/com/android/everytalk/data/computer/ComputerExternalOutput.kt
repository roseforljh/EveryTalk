package com.android.everytalk.data.computer

import kotlinx.serialization.json.*

/**
 * 工具内容进入模型前的公共边界。先遮蔽凭据再裁剪，避免先截断破坏凭据格式。
 * 这里只处理不可信正文；执行 ID、授权代次和干预类型必须由调用方在本地生成。
 */
internal object ComputerExternalOutput {
    data class Text(val text: String, val truncated: Boolean)
    data class Value(val value: JsonElement, val truncated: Boolean)

    private const val secretName = "(?:authorization|proxy-authorization|set-cookie|cookie|[a-z0-9_]*token|[a-z0-9_]*secret|password|passwd|api[_ -]?key|private[_ -]?key)"
    private val sensitiveName = Regex("(?i)(token|secret|password|passwd|authorization|cookie|private[_ -]?key|api[_ -]?key)")
    private val privateKey = Regex("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-\\r\\n]*PRIVATE KEY-----|$)")
    private val header = Regex("(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]*")
    private val assignment = Regex("(?i)(?<![a-z0-9_])([\"']?$secretName[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}\\]\\\"']+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")

    fun isSensitiveName(name: String): Boolean = sensitiveName.containsMatchIn(name)

    fun text(value: String, maxChars: Int, maxLines: Int = Int.MAX_VALUE, maxLineChars: Int = Int.MAX_VALUE): Text {
        require(maxChars > 0 && maxLines > 0 && maxLineChars > 0)
        val redacted = value.replace(privateKey, "[REDACTED_KEY]")
            .replace(header) { it.groupValues[1] + "[REDACTED]" }
            .replace(bearer, "Bearer [REDACTED]")
            .replace(assignment) { it.groupValues[1] + "\"[REDACTED]\"" }
        var truncated = false
        val output = StringBuilder()
        for ((index, line) in redacted.lineSequence().withIndex()) {
            if (index >= maxLines) { truncated = true; break }
            val part = (if (index > 0) "\n" else "") + line.take(maxLineChars)
            if (line.length > maxLineChars) truncated = true
            val remaining = maxChars - output.length
            output.append(part.take(remaining))
            if (part.length > remaining) { truncated = true; break }
        }
        return Text(output.toString(), truncated)
    }

    /** 按字段遮蔽数据库/JSON 的 Secret，并限制深度、节点、单值和序列化后总大小。 */
    fun json(value: JsonElement, maxChars: Int = 32_000): Value {
        var truncated = false
        var nodes = 0
        fun visit(item: JsonElement, depth: Int): JsonElement {
            if (++nodes > 2_000 || depth > 16) { truncated = true; return JsonPrimitive("[TRUNCATED]") }
            return when (item) {
                is JsonObject -> {
                    if (item.size > 50) truncated = true
                    JsonObject(item.entries.take(50).associate { (key, child) ->
                        key to if (isSensitiveName(key)) JsonPrimitive("[REDACTED]") else visit(child, depth + 1)
                    })
                }
                is JsonArray -> {
                    if (item.size > 100) truncated = true
                    JsonArray(item.take(100).map { visit(it, depth + 1) })
                }
                is JsonPrimitive -> if (item.isString) {
                    val safe = text(item.content, 4_000)
                    truncated = truncated || safe.truncated
                    JsonPrimitive(safe.text)
                } else item
            }
        }
        val sanitized = visit(value, 0)
        val encoded = sanitized.toString()
        return if (encoded.length <= maxChars) Value(sanitized, truncated)
        else Value(JsonPrimitive(encoded.take(maxChars / 2)), true)
    }

    /** 外部 API 的 ok/error/intervention 字段只留在 data 内，不能成为 App 执行协议。 */
    fun apiResult(value: JsonObject): JsonObject {
        val safe = json(value)
        return buildJsonObject {
            put("ok", true)
            put("data", safe.value)
            put("truncated", safe.truncated)
            put("untrusted_external_data", true)
        }
    }
}

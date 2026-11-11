package com.android.everytalk.data.agent

import com.android.everytalk.data.network.estimateToolLoopJsonTokens
import kotlinx.serialization.json.*

/** 读取已归档工具结果，不具备手机文件系统浏览能力。offset/limit 按 UTF-16 字符计数。 */
internal object AgentToolResultReadTool {
    const val NAME = "tool_result_read"
    val definition: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to NAME,
            "description" to "Read a saved full tool result in the current run without executing the original tool again. Use next_offset for the next page. Offsets count UTF-16 code units.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tool_call_id" to mapOf("type" to "string"),
                    "offset" to mapOf("type" to "integer", "minimum" to 0),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 16000),
                ),
                "required" to listOf("tool_call_id"),
                "additionalProperties" to false,
            ),
        ),
    )

    fun withTool(tools: List<Map<String, Any>>?): List<Map<String, Any>>? =
        tools?.let { if (it.isEmpty() || it.any { tool -> McpToolDiscovery.name(tool) == NAME }) it else it + definition }

    /** 先对最终 JSON 页计费再推进游标，防止公共截断后二次读取跳过内容。 */
    fun page(text: String, arguments: JsonObject, maxTokens: Long): JsonObject {
        require(arguments.keys.all { it in setOf("tool_call_id", "offset", "limit") }) { "不支持的读取参数" }
        val offset = arguments["offset"]?.jsonPrimitive?.intOrNull ?: if ("offset" in arguments) error("offset 无效") else 0
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: if ("limit" in arguments) error("limit 无效") else 4000
        require(offset in 0..text.length && limit in 1..16000) { "读取范围无效" }
        require(offset == 0 || offset == text.length || !text[offset].isLowSurrogate() || !text[offset - 1].isHighSurrogate()) { "offset 不能拆开 Unicode 字符" }
        fun value(end: Int) = buildJsonObject {
            put("text", text.substring(offset, end)); put("next_offset", end); put("eof", end == text.length)
        }
        var end = minOf(text.length, offset + limit)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        while (estimateToolLoopJsonTokens(value(end)) > maxTokens && end > offset) {
            end = offset + (end - offset) / 2
            if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        require(end > offset || offset == text.length) { "本页预算过小，请增加 limit 或减少同轮调用数" }
        return value(end)
    }
}

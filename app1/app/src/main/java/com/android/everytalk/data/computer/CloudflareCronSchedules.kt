package com.android.everytalk.data.computer

import kotlinx.serialization.json.*

/**
 * Cron 的内部协议只保留表达式。更新时间等 API 字段不进入审批内容。
 * 这里限制体积、控制字符和五段结构；Cloudflare 扩展语法由服务端最终校验。
 */
internal object CloudflareCronSchedules {
    fun validate(values: List<String>): List<String> {
        require(values.size <= 64) { "Cron 配置数量超过 App 限制" }
        val normalized = values.map { value ->
            require(value.length in 1..256 && value.all { it in ' '..'~' }) { "Cron 表达式无效" }
            val fields = value.trim().split(Regex(" +"))
            require(fields.size == 5) { "Cron 表达式必须包含五段" }
            fields.joinToString(" ")
        }
        require(normalized.distinct().size == normalized.size) { "Cron 表达式重复" }
        return normalized.sorted()
    }

    fun fromArguments(arguments: JsonObject): List<String> {
        require(arguments.keys == setOf("worker_name", "schedules")) { "Cron 参数无效" }
        val array = arguments["schedules"] as? JsonArray ?: errorArgument()
        return validate(array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: errorArgument() })
    }

    fun fromResponse(response: JsonObject): List<String> {
        try {
            val schedules = (response["result"] as? JsonObject)?.get("schedules") as? JsonArray ?: errorArgument()
            return validate(schedules.map {
                ((it as? JsonObject)?.get("cron") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: errorArgument()
            })
        } catch (_: IllegalArgumentException) {
            throw CloudflareApiException("RESPONSE_INVALID", "Cloudflare Cron 响应格式无效")
        }
    }

    fun encode(values: List<String>): JsonArray = JsonArray(validate(values).map { cron ->
        buildJsonObject { put("cron", cron) }
    })

    private fun errorArgument(): Nothing = throw IllegalArgumentException("Cron 配置格式无效")
}

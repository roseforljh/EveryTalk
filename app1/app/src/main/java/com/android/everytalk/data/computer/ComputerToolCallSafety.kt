package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 统一判断 UNKNOWN 工具是否需要用户决定。
 * 执行器和跨进程恢复必须共用同一规则，避免同一条命令在前台被当成只读，重启后又弹成写操作。
 */
internal object ComputerToolCallSafety {
    fun isReadOnly(toolName: String, arguments: JsonObject): Boolean = when (toolName) {
        ComputerToolNames.READ_FILE, ComputerToolNames.DOWNLOAD,
        ComputerToolNames.WORKER_LIST, ComputerToolNames.WORKER_READ,
        "computer_worker_status", "computer_worker_logs", ComputerToolNames.WORKER_HEALTH,
        "computer_kv_list_keys", "computer_kv_get",
        ComputerToolNames.D1_LIST, ComputerToolNames.D1_SCHEMA,
        ComputerToolNames.KV_LIST_NAMESPACES, ComputerToolNames.R2_LIST_BUCKETS,
        ComputerToolNames.R2_LIST_OBJECTS, ComputerToolNames.R2_GET_METADATA,
        ComputerToolNames.DO_LIST, ComputerToolNames.QUEUES_LIST,
        ComputerToolNames.DO_OBJECTS_LIST, ComputerToolNames.QUEUES_GET,
        ComputerToolNames.QUEUES_METRICS, ComputerToolNames.QUEUES_PEEK,
        ComputerToolNames.CRON_LIST -> true
        ComputerToolNames.D1_QUERY -> isReadOnlySql(arguments.stringValue("sql"))
        ComputerToolNames.EXEC -> isReadOnlyExec(arguments)
        else -> false
    }

    fun requiresUnknownApproval(
        toolName: String,
        arguments: JsonObject,
        permissionMode: ComputerPermissionMode,
    ): Boolean {
        if (isReadOnly(toolName, arguments)) return false
        return when (permissionMode) {
            ComputerPermissionMode.FULL -> false
            ComputerPermissionMode.SMART -> arguments.booleanValue("ask_user_approval") ?: true
            ComputerPermissionMode.MANUAL -> true
        }
    }

    private fun isReadOnlyExec(arguments: JsonObject): Boolean {
        if (arguments.stringValue("target")?.lowercase() !in setOf(null, "host", "container")) return false
        if (arguments["env"] is JsonObject && (arguments["env"] as JsonObject).isNotEmpty()) return false
        if (!arguments.stringValue("stdin").isNullOrEmpty()) return false
        if (arguments.booleanValue("background") == true || arguments.booleanValue("as_root") == true) return false
        val secretNames = arguments["secret_names"] as? JsonArray
        if (secretNames?.isNotEmpty() == true) return false
        val command = arguments.stringValue("command") ?: return false
        val cwd = arguments.stringValue("cwd") ?: "~"
        return !ComputerHostCommandPolicy.assess(
            ComputerExecRequest(command = command, cwd = cwd, target = ComputerExecTarget.HOST),
        ).requiresConfirmation
    }

    /** D1 查询的读写边界由应用判断，避免模型通过参数绕过确认。 */
    private fun isReadOnlySql(sql: String?): Boolean {
        val withoutComments = sql
            ?.replace(Regex("--[^\\r\\n]*"), " ")
            ?.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            ?: return false
        val normalized = withoutComments.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized.isBlank() || normalized.length > 64 * 1024) return false
        // 只接受一个查询语句；尾部一个分号允许，分号后的第二条语句一律视为写入风险。
        val statement = normalized.removeSuffix(";").trim()
        if (';' in statement) return false
        if (!Regex("^(select|with|pragma|explain)(\\s|$)").containsMatchIn(statement)) return false
        return listOf("insert", "update", "delete", "replace", "drop", "alter", "create", "attach", "detach", "vacuum")
            .none { Regex("\\b$it\\b").containsMatchIn(statement) }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.booleanValue(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}

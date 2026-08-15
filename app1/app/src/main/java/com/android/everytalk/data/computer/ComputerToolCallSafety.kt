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
        ComputerToolNames.READ_FILE, ComputerToolNames.DOWNLOAD -> true
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

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.booleanValue(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}

package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 将可信请求快照展开为独立上下文，保留当前 Run 和只对当前 ToolCall 有效的审批标记。 */
fun ComputerRequestContext.targetContexts(): List<ComputerRequestContext> =
    if (selectedTargets.isEmpty()) listOf(this) else selectedTargets.map { target ->
        copy(
            computerId = target.computerId,
            workspaceId = target.workspaceId,
            permissionMode = target.permissionMode,
            cloudflareBinding = target.cloudflareBinding,
            selectedTargets = emptyList(),
        )
    }

/**
 * 模型只提供目标 ID，Workspace 和权限只能取自发送时冻结的本地快照。
 * 多选时禁止省略或猜测目标；单机旧请求兼容原来的无参数调用。
 */
fun resolveComputerToolContext(
    toolName: String,
    arguments: JsonObject,
    context: ComputerRequestContext?,
): ComputerRequestContext? {
    if (context == null || toolName !in ComputerToolNames.allProviders) return context
    val requested = arguments["computer_id"]
    require(requested == null || requested is JsonPrimitive && requested.isString) { "computer_id 必须是服务器 ID 字符串" }
    val id = (requested as? JsonPrimitive)?.content
    val targets = context.targetContexts()
    require(id != null || targets.size == 1) { "已选择多台服务器，请明确指定 computer_id" }
    val target = targets.singleOrNull { id == null || it.computerId == id }
        ?: throw IllegalArgumentException("目标服务器不在本次请求已选择的范围内")
    context.selectedTargets.firstOrNull { it.computerId == target.computerId }?.let { binding ->
        require(if (toolName in ComputerToolNames.cloudflare) binding.provider == ComputerProvider.CLOUDFLARE
            else binding.provider == ComputerProvider.SSH) { "目标服务器不支持此类工具" }
    }
    return target
}

/** 为每类工具只公开能够执行它的目标，不把 SSH 指令误路由到 Cloudflare。 */
fun multiComputerToolDefinitions(
    context: ComputerRequestContext,
    workerWriteEnabled: Boolean = true,
    resourceToolsEnabled: Boolean = true,
): List<Map<String, Any>> {
    val targets = context.selectedTargets
    require(targets.isNotEmpty()) { "多服务器工具需要冻结的目标快照" }
    return targets.groupBy { it.provider }.flatMap { (provider, available) ->
        // 只要同类目标存在智能审批，就公开该参数；执行期仍逐台读取最新权限。
        val mode = if (available.any { it.permissionMode == ComputerPermissionMode.SMART }) {
            ComputerPermissionMode.SMART
        } else ComputerPermissionMode.MANUAL
        val definitions = if (provider == ComputerProvider.CLOUDFLARE) {
            ComputerToolCatalog.cloudflareDefinitions(workerWriteEnabled, resourceToolsEnabled, mode)
        } else ComputerToolCatalog.definitions(mode)
        definitions.map { definition ->
            val function = definition.getValue("function") as Map<*, *>
            val parameters = function["parameters"] as Map<*, *>
            val properties = parameters["properties"] as? Map<*, *> ?: emptyMap<String, Any>()
            val required = parameters["required"] as? List<*> ?: emptyList<String>()
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to requireNotNull(function["name"]),
                    "description" to requireNotNull(function["description"]),
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to (properties + mapOf("computer_id" to mapOf(
                            "type" to "string",
                            "description" to "本次操作的服务器 ID；必须根据用户目标明确选择，不得广播到所有机器。",
                            "enum" to available.map { it.computerId },
                        ))),
                        "required" to (required + "computer_id"),
                        "additionalProperties" to false,
                    ),
                ),
            )
        }
    }
}

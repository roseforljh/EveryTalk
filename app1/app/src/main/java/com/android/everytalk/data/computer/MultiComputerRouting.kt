package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 首次发送与后续轮次共用多机说明。先列全所有目标的环境，再追加一个状态块，
 * 避免刷新策略在第一台的状态标签处截断，丢失第二台的名称、权限和工具说明。
 * 输入只含冻结目标的非敏感引用；SSH 凭据继续由应用本地使用。
 */
internal fun buildMultiComputerSessionPrompt(
    targets: List<Pair<ComputerTargetBinding, String>>,
): String = buildString {
    appendLine("当前会话已选择下列多台服务器，可在同一任务中分别控制，无需切换会话。每次服务器工具调用必须指定 computer_id。")
    appendLine("连接和认证由应用使用已保存的配置完成，不要因为同时操作多台服务器而索要密码或私钥。实际可执行范围以各目标的权限和工具结果为准。")
    appendLine("根据用户目标自主选择服务器和步骤；独立的只读检查可同轮调用，互相依赖的步骤应等待前一步结果。最终用自然语言说明各台服务器的结果。")
    if (targets.count { it.first.provider == ComputerProvider.SSH } > 1) {
        appendLine("在已选 SSH 服务器间传文件：先对源服务器调用 download，取得成功结果中的 attachment_id；再对目标服务器调用 upload，使用该 attachment_id。A 到 B、B 到 A 都可如此完成，不需要两台服务器能直接互连。目录可先用 exec 打包，传输后在目标解包。路径以各自文件工具目录为准，必要时先用 exec 准备文件；不要把文件正文或凭据塞进聊天。")
    }
    targets.forEach { (target, prompt) ->
        appendLine("服务器 ID=${target.computerId}，名称=${JsonPrimitive(target.displayName)}：")
        appendLine(prompt.substringBefore("<computer-session-state>").trim())
    }
    val states = targets.filter { "<computer-session-state>" in it.second }
    if (states.isNotEmpty()) {
        appendLine("<computer-session-state>")
        states.forEach { (target, prompt) ->
            appendLine("服务器 ID=${target.computerId}，名称=${JsonPrimitive(target.displayName)}：")
            appendLine(prompt.substringAfter("<computer-session-state>").substringBefore("</computer-session-state>").trim())
        }
        appendLine("</computer-session-state>")
    }
}

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
        val mode = when {
            available.any { it.permissionMode == ComputerPermissionMode.SMART } -> ComputerPermissionMode.SMART
            available.all { it.permissionMode == ComputerPermissionMode.FULL } -> ComputerPermissionMode.FULL
            else -> ComputerPermissionMode.MANUAL
        }
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
                            "description" to ("本次操作的服务器 ID；根据用户目标选择。可用目标：" +
                                available.joinToString { "${it.computerId}=${JsonPrimitive(it.displayName)}" }),
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

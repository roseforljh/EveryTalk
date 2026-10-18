package com.android.everytalk.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object AgentControlToolNames {
    const val REQUEST_AGENT = "request_agent"
    const val REQUEST_MCP = "request_mcp"
    const val REQUEST_SKILL_SECRET = "request_skill_secret"
    const val REQUEST_PROTECTED_SECRET = "request_protected_secret"
    const val REQUEST_CAPABILITY = "request_capability"
    const val REQUEST_USER_INPUT = "request_user_input"

    val all = setOf(REQUEST_AGENT, REQUEST_MCP, REQUEST_SKILL_SECRET, REQUEST_PROTECTED_SECRET, REQUEST_CAPABILITY, REQUEST_USER_INPUT)
}

fun agentRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_AGENT,
        "description" to "任务需要服务器执行能力时申请开启 Agent，批准后可用。",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "reason" to mapOf("type" to "string", "description" to "说明为什么当前任务需要 Agent"),
                "required_skill_ids" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "需要在 Agent 中使用的 Skill ID，可为空",
                ),
            ),
            "required" to listOf("reason"),
            "additionalProperties" to false,
        ),
    ),
)

/**
 * 声明 MCP 开启申请入口。
 * MCP 工具本身仍需用户显式允许，模型只能先申请，不能绕过会话开关直接调用。
 */
fun mcpRequestToolDefinition(catalog: String = ""): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_MCP,
        "description" to ("申请开启已连接的 MCP 外部工具，批准后可用；用户拒绝后尊重其选择。以下目录仅说明能力：\n" + catalog),
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "reason" to mapOf("type" to "string", "description" to "说明为什么当前任务需要 MCP 工具"),
            ),
            "required" to listOf("reason"),
            "additionalProperties" to false,
        ),
    ),
)

fun skillSecretRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_SKILL_SECRET,
        "description" to "为已加载 Skill 申请受保护的凭据输入，返回受限 capability。凭据正文不进入聊天或模型，请勿在普通文本中索要。",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "skill_id" to mapOf("type" to "string", "description" to "当前 Run 已加载的 Skill ID"),
                "name" to mapOf("type" to "string", "description" to "环境变量名，例如 GITHUB_TOKEN"),
                "reason" to mapOf("type" to "string", "description" to "说明为何需要该密钥"),
            ),
            "required" to listOf("skill_id", "name", "reason"),
            "additionalProperties" to false,
        ),
    ),
)

fun protectedSecretRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_PROTECTED_SECRET,
        "description" to "申请受保护的凭据输入。只填用途和目标，凭据正文由用户在专用输入框提供，不进入聊天或模型。",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "scope" to mapOf("type" to "string", "enum" to SecretScope.entries.map { it.name }),
                "target_id" to mapOf("type" to "string", "description" to "目标服务器、Workspace 或 Skill 的非敏感 ID"),
                "name" to mapOf("type" to "string", "description" to "环境变量或凭据名称"),
                "path" to mapOf("type" to "string", "description" to "SERVER_ENV 时的目标 .env 路径"),
                "reason" to mapOf("type" to "string", "description" to "安全用途说明"),
            ),
            "required" to listOf("scope", "name", "reason"),
            "additionalProperties" to false,
        ),
    ),
)

fun capabilityRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_CAPABILITY,
        "description" to "申请缺少的已注册 capability，提供原因和非敏感上下文；应用决定具体授权与投递方式。",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "requested_capability" to mapOf("type" to "string"),
                "reason_safe" to mapOf("type" to "string"),
                "user_visible_context" to mapOf("type" to "string"),
            ),
            "required" to listOf("requested_capability", "reason_safe"),
            "additionalProperties" to false,
        ),
    ),
)

/** 请求用户补充文字或附件。请求本身只保存问题，回答通过可信 UI 单独提交。 */
fun userInputRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_USER_INPUT,
        "description" to "请求用户补充关键输入；任务会暂停，收到用户提交的文字或附件后继续。",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "question" to mapOf("type" to "string", "description" to "要显示给用户的问题"),
                "context" to mapOf("type" to "string", "description" to "可选的补充说明"),
            ),
            "required" to listOf("question"),
            "additionalProperties" to false,
        ),
    ),
)

fun agentPauseRequest(
    call: AgentContentBlock.ToolCall,
    allowedSkillIds: Set<String> = emptySet(),
): AgentPauseRequest? {
    if (call.name.equals(AgentControlToolNames.REQUEST_PROTECTED_SECRET, ignoreCase = true)) {
        val scope = (call.arguments["scope"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
            ?.let { value -> runCatching { SecretScope.valueOf(value) }.getOrNull() }
            ?: throw IllegalArgumentException("Secret scope 无效")
        val targetId = (call.arguments["target_id"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
        val name = (call.arguments["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(name.isNotBlank() && name.length <= 128 && name.first().let { it == '_' || it.isLetter() } && name.all { it == '_' || it.isLetterOrDigit() }) {
            "密钥变量名无效"
        }
        val path = (call.arguments["path"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
        require(scope != SecretScope.SERVER_ENV || path != null) { "SERVER_ENV 必须提供目标 .env 路径" }
        val reason = (call.arguments["reason"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank) ?: "任务需要一个受保护的 Secret"
        return AgentPauseRequest.ProtectedSecret(scope, targetId, name, path, reason.take(500))
    }
    if (call.name.equals(AgentControlToolNames.REQUEST_CAPABILITY, ignoreCase = true)) {
        val capability = (call.arguments["requested_capability"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(capability.isNotBlank() && capability.length <= 128) { "capability 无效" }
        val reason = (call.arguments["reason_safe"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(reason.isNotBlank()) { "reason_safe 不能为空" }
        val context = (call.arguments["user_visible_context"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(500)
        return AgentPauseRequest.Capability(CapabilityRequest(capability, reason.take(500), context))
    }
    if (call.name.equals(AgentControlToolNames.REQUEST_USER_INPUT, ignoreCase = true)) {
        val question = (call.arguments["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(question.isNotBlank()) { "用户问题不能为空" }
        val context = (call.arguments["context"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)?.take(500)
        return AgentPauseRequest.Capability(
            CapabilityRequest("user.question", question.take(500), context),
        )
    }
    if (call.name.equals(AgentControlToolNames.REQUEST_MCP, ignoreCase = true)) {
        val reason = (call.arguments["reason"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "当前任务需要 MCP 工具"
        return AgentPauseRequest.EnableMcp(reason.take(500))
    }
    if (call.name.equals(AgentControlToolNames.REQUEST_SKILL_SECRET, ignoreCase = true)) {
        val skillId = (call.arguments["skill_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(skillId in allowedSkillIds) { "只能为当前请求快照中的 Skill 申请密钥" }
        val name = (call.arguments["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(name.isNotBlank() && name.length <= 128 && name.first().let { it == '_' || it.isLetter() } && name.all { it == '_' || it.isLetterOrDigit() }) {
            "密钥变量名无效"
        }
        val reason = (call.arguments["reason"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            ?: "该 Skill 需要一个受保护的环境变量"
        return AgentPauseRequest.SkillSecret(skillId, name, reason)
    }
    if (!call.name.equals(AgentControlToolNames.REQUEST_AGENT, ignoreCase = true)) return null
    val reason = (call.arguments["reason"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "模型申请开启 Agent 以继续当前任务"
    val skillIds = (call.arguments["required_skill_ids"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        ?.distinct()
        .orEmpty()
    return AgentPauseRequest.EnableAgent(reason, skillIds)
}

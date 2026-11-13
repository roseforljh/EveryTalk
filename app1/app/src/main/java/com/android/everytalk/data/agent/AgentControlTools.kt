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
        "description" to "Request Agent access when the task needs server execution; usable after the user approves.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "reason" to mapOf("type" to "string", "description" to "Why the current task needs the Agent"),
                "required_skill_ids" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Skill IDs to use in the Agent; may be empty",
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
        "description" to ("Request access to the connected MCP tools; usable after the user approves. Respect a refusal. The catalog below only describes capabilities:\n" + catalog),
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "reason" to mapOf("type" to "string", "description" to "Why the current task needs MCP tools"),
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
        "description" to "Request protected credential input for a loaded Skill; returns a limited capability. The credential never enters the chat or the model, so never ask for it in plain text.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "skill_id" to mapOf("type" to "string", "description" to "Skill ID loaded in the current run"),
                "name" to mapOf("type" to "string", "description" to "Environment variable name, e.g. GITHUB_TOKEN"),
                "reason" to mapOf("type" to "string", "description" to "Why this secret is needed"),
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
        "description" to "Request protected credential input. Give only the purpose and target; the user enters the credential in a dedicated field and it never enters the chat or the model.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "scope" to mapOf("type" to "string", "enum" to SecretScope.entries.map { it.name }),
                "target_id" to mapOf("type" to "string", "description" to "Non-sensitive ID of the target server, Workspace or Skill"),
                "name" to mapOf("type" to "string", "description" to "Environment variable or credential name"),
                "path" to mapOf("type" to "string", "description" to "Target .env path for SERVER_ENV"),
                "reason" to mapOf("type" to "string", "description" to "Non-sensitive purpose"),
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
        "description" to "Request a missing registered capability with a reason and non-sensitive context; the app decides how to grant and deliver it.",
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
        "description" to "Ask the user for missing critical input; the task pauses and continues after the user submits text or attachments.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "question" to mapOf("type" to "string", "description" to "Question shown to the user"),
                "context" to mapOf("type" to "string", "description" to "Optional extra context"),
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
        // 模型输入错误必须回到工具结果，不能直到 Broker 挂起时才终止整轮任务。
        val policy = AgentInterventionPolicyRegistry().resolve(capability)
        require(policy != null) {
            if (capability == AgentControlToolNames.REQUEST_AGENT) "request_agent 是工具名，请直接调用 request_agent 申请服务器，不要通过 request_capability。"
            else "未注册的 capability：$capability，请使用已注册能力或对应的专用工具。"
        }
        require(policy.minimumSource == InterventionRequestSource.MODEL_HINT) { "此能力必须由执行器确认，不能由模型直接申请。" }
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

package com.android.everytalk.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object AgentControlToolNames {
    const val REQUEST_AGENT = "request_agent"
    const val REQUEST_SKILL_SECRET = "request_skill_secret"
    const val REQUEST_CAPABILITY = "request_capability"

    val all = setOf(REQUEST_AGENT, REQUEST_SKILL_SECRET, REQUEST_CAPABILITY)
}

fun agentRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_AGENT,
        "description" to "申请开启当前会话的 Agent 服务器能力。只有确实需要执行脚本、命令或服务器文件操作时调用，调用后必须等待用户确认。",
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

fun skillSecretRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_SKILL_SECRET,
        "description" to "旧 Skill 授权兼容入口。凭据正文不会返回模型；应用会映射为受限 capability，不向任意命令注入 Secret。",
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

fun capabilityRequestToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to AgentControlToolNames.REQUEST_CAPABILITY,
        "description" to "执行中缺少外部能力时申请用户接力。只能填写已注册 capability、原因和安全上下文，字段、目标、Adapter 与投递方式由本地策略决定。",
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

fun agentPauseRequest(
    call: AgentContentBlock.ToolCall,
    allowedSkillIds: Set<String> = emptySet(),
): AgentPauseRequest? {
    if (call.name.equals(AgentControlToolNames.REQUEST_CAPABILITY, ignoreCase = true)) {
        val capability = (call.arguments["requested_capability"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(capability.isNotBlank() && capability.length <= 128) { "capability 无效" }
        val reason = (call.arguments["reason_safe"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(reason.isNotBlank()) { "reason_safe 不能为空" }
        val context = (call.arguments["user_visible_context"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(500)
        return AgentPauseRequest.Capability(CapabilityRequest(capability, reason.take(500), context))
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

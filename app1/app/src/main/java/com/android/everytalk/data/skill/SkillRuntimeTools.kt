package com.android.everytalk.data.skill

import com.android.everytalk.data.agent.AgentContentBlock
import com.android.everytalk.data.agent.AgentRunStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun skillToolDefinitions(): List<Map<String, Any>> = listOf(
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to SkillToolNames.LOAD_SKILL,
            "description" to "读取 Skill 的完整 SKILL.md 和附带文件目录。决定使用任何 Skill 后必须先调用。",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "skill_id" to mapOf("type" to "string"),
                    "content_hash" to mapOf("type" to "string"),
                ),
                "required" to listOf("skill_id", "content_hash"),
                "additionalProperties" to false,
            ),
        ),
    ),
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to SkillToolNames.READ_SKILL_FILE,
            "description" to "按需读取已加载 Skill 的一个文本附带文件。",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "skill_id" to mapOf("type" to "string"),
                    "content_hash" to mapOf("type" to "string"),
                    "path" to mapOf("type" to "string"),
                ),
                "required" to listOf("skill_id", "content_hash", "path"),
                "additionalProperties" to false,
            ),
        ),
    ),
)

/** Skill 工具独立于 MCP 执行器，确保只能读取当前 Run 冻结的版本。 */
class SkillRuntimeTools(
    private val repository: SkillRepository,
    private val runStore: AgentRunStore,
) {
    fun handles(name: String): Boolean = SkillToolNames.all.any { it.equals(name, ignoreCase = true) }

    suspend fun displayName(call: AgentContentBlock.ToolCall, runId: String): String? {
        val skillId = call.arguments["skill_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val hash = call.arguments["content_hash"]?.jsonPrimitive?.contentOrNull ?: return null
        val run = runStore.getRun(runId) ?: return null
        return runStore.decodeRequestSnapshot(run)
            ?.skillSnapshot
            ?.allowedVersion(skillId, hash)
            ?.name
    }

    suspend fun execute(call: AgentContentBlock.ToolCall, runId: String): JsonElement {
        val skillId = call.arguments["skill_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val hash = call.arguments["content_hash"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(skillId.isNotBlank() && hash.isNotBlank()) { "skill_id 和 content_hash 不能为空" }
        val run = runStore.getRun(runId) ?: error("当前 Run 不存在")
        val snapshot = runStore.decodeRequestSnapshot(run)?.skillSnapshot
            ?: error("当前 Run 没有 Skill 快照")
        val allowed = snapshot.allowedVersion(skillId, hash) ?: error("该 Skill 版本不在当前请求快照中")
        return when {
            call.name.equals(SkillToolNames.LOAD_SKILL, ignoreCase = true) -> {
                val files = repository.manifest(skillId, hash)
                val markdown = repository.readSkillMarkdown(skillId, hash)
                repository.recordUse(skillId)
                buildJsonObject {
                    put("skill_id", skillId)
                    put("name", allowed.name)
                    put("content_hash", hash)
                    put("skill_markdown", markdown)
                    put("files", buildJsonArray {
                        files.forEach { file ->
                            add(buildJsonObject {
                                put("path", file.path)
                                put("size", file.size)
                                put("type", if (file.text) "text" else "binary")
                                put("sha256", file.sha256)
                            })
                        }
                    })
                }
            }
            call.name.equals(SkillToolNames.READ_SKILL_FILE, ignoreCase = true) -> {
                val path = call.arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(path.isNotBlank()) { "path 不能为空" }
                buildJsonObject {
                    put("skill_id", skillId)
                    put("content_hash", hash)
                    put("path", path)
                    put("content", repository.readTextFile(skillId, hash, path))
                }
            }
            else -> error("未知 Skill 工具：${call.name}")
        }
    }
}

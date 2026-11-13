package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.LocalWorkspaceFileBridge
import com.android.everytalk.data.network.AttachmentToolExecutor
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillToolNames
import com.android.everytalk.models.SelectedMediaItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 将已授权工具转换为后端执行绑定。连接信息只进入加密任务输入，绝不混入模型工具描述。
 * Skill/附件/沙箱全部读取本轮快照；只有手机写入与人工授权进入设备等待，未知工具不能冒充已迁移。
 */
internal suspend fun cloudAgentTools(
    context: Context,
    request: ChatRequest,
    attachments: List<SelectedMediaItem.GenericFile>,
    skills: SkillRepository,
): JsonArray = withContext(Dispatchers.IO) {
    val all = AgentToolResultReadTool.withTool(request.tools).orEmpty()
    val direct = McpToolDiscovery.directTools(all).mapTo(hashSetOf(), McpToolDiscovery::name)
    val projected = McpToolDiscovery.project(all, emptyList()).orEmpty()
    val discovery = projected.firstOrNull { McpToolDiscovery.name(it) == McpToolDiscovery.NAME }
    val definitions = all + listOfNotNull(discovery?.takeUnless { tool -> all.any { McpToolDiscovery.name(it) == McpToolDiscovery.name(tool) } })
    val names = definitions.map(McpToolDiscovery::name).toSet()
    val versions = if (names.any { it in SkillToolNames.all }) {
        val snapshot = checkNotNull(request.localSkillSnapshot) { "当前请求没有 Skill 快照" }
        val allowed = (snapshot.automaticCatalog.map { it.skillId to it.contentHash } +
            snapshot.manualReferences.map { it.skillId to it.contentHash }).distinct()
        buildJsonArray {
            for ((id, hash) in allowed) {
                val version = checkNotNull(snapshot.allowedVersion(id, hash))
                val manifest = skills.manifest(id, hash)
                add(buildJsonObject {
                    put("skillId", id); put("contentHash", hash); put("name", version.name)
                    put("files", buildJsonObject {
                        for (file in manifest.filter { it.text }) put(file.path, skills.readTextFile(id, hash, file.path))
                    })
                    put("manifest", buildJsonArray {
                        manifest.forEach { file -> add(buildJsonObject {
                            put("path", file.path); put("size", file.size); put("sha256", file.sha256)
                            put("type", if (file.text) "text" else "binary")
                        }) }
                    })
                })
            }
        }
    } else JsonArray(emptyList())
    val attachmentFiles = if ("read_attachment" in names) AttachmentToolExecutor.cloudSnapshot(context, attachments) else JsonObject(emptyMap())
    val bashFiles = if ("local_bash" in names) {
        val workspace = checkNotNull(request.localComputerRequestContext).workspaceId
        require(workspace.isNotBlank() && workspace.none { it == '/' || it == '\\' || it == ':' } && workspace !in setOf(".", "..")) { "Workspace 身份无效" }
        val root = File(context.filesDir, "agent-workspaces/$workspace")
        if (root.isDirectory) LocalWorkspaceFileBridge().load(root) else emptyMap()
    } else emptyMap()
    buildJsonArray {
        for (definition in definitions) {
            val function = definition["function"] as? Map<*, *> ?: definition
            val name = McpToolDiscovery.name(definition)
            // 原生供应商工具（Gemini 搜索、代码执行等）后端还没有转换，不能悄悄丢掉。
            require(name.isNotEmpty()) { "原生供应商工具尚未支持云端执行" }
            val binding = when (name) {
                "get_current_datetime" -> buildJsonObject { put("kind", "time") }
                "webfetch" -> buildJsonObject { put("kind", "webfetch") }
                "web_search" -> buildJsonObject {
                    val search = checkNotNull(request.localExternalWebSearchConfig) { "当前请求没有搜索授权" }
                    put("kind", "search"); put("provider", search.providerId); put("apiKey", search.apiKey)
                }
                SkillToolNames.LOAD_SKILL, SkillToolNames.READ_SKILL_FILE -> buildJsonObject {
                    put("kind", "skills"); put("operation", name); put("versions", versions)
                }
                "read_attachment" -> buildJsonObject { put("kind", "attachments"); put("files", attachmentFiles) }
                "local_bash" -> buildJsonObject { put("kind", "bash"); put("files", JsonObject(bashFiles.mapValues { JsonPrimitive(it.value) })) }
                McpToolDiscovery.NAME -> buildJsonObject { put("kind", "discovery") }
                // 向用户提问：云端停在等待回答，任一登录设备在提问卡里回答后从断点继续。
                AgentControlToolNames.REQUEST_USER_INPUT -> buildJsonObject { put("kind", "question") }
                // 保存到手机：先等用户确认，再由持有该 Workspace 的手机执行写入并回传结果。
                ComputerToolNames.LOCAL_FILE_SAVE -> buildJsonObject { put("kind", "device") }
                // 云端任务的工具集在提交时冻结，运行中开启的新能力这一轮用不上，如实返回给模型。
                in AgentControlToolNames.all -> buildJsonObject {
                    put("kind", "unsupported")
                    put("message", "云端执行中无法开启新的能力或补充密钥。请告诉用户：在手机上开启所需能力后重新发送消息，下一轮即可使用。")
                }
                AgentToolResultReadTool.NAME -> buildJsonObject { put("kind", "tool_result") }
                else -> request.localCloudMcpBindings[name]
                    ?: error("工具 $name 尚未准备云端执行绑定")
            }
            add(buildJsonObject {
                put("name", name); put("description", function["description"]?.toString().orEmpty())
                put("parameters", function["parameters"]?.let(::anyToJsonElement) ?: buildJsonObject { put("type", "object") })
                put("binding", binding)
                put("approval", if (name == ComputerToolNames.LOCAL_FILE_SAVE) "always" else "never")
                put("replay", if (binding["kind"]?.jsonPrimitive?.content in setOf("mcp", "device")) "never" else "safe")
                if (name.startsWith("mcp_") && name !in direct) put("deferred", true)
            })
        }
    }
}

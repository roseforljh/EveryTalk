package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.LocalWorkspaceFileBridge
import com.android.everytalk.data.computer.targetContexts
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
    val sshNative = setOf(ComputerToolNames.EXEC, ComputerToolNames.READ_FILE, ComputerToolNames.WRITE_FILE, ComputerToolNames.EDIT)
    // 服务器在云端可直连（公网 IPv4、已记录主机密钥）时，四个基础工具由后端直接执行；否则和其他手机能力一样交给发起设备。
    val sshTargets = if (names.any { it in sshNative }) cloudSshTargets(context, request.localComputerRequestContext) else null
    buildJsonArray {
        for (definition in definitions) {
            val function = definition["function"] as? Map<*, *> ?: definition
            val name = McpToolDiscovery.name(definition)
            // 原生供应商工具（Gemini 搜索、代码执行）不是函数工具：由 cloudAgentInput 按本机同一判定放进 nativeTools。
            if (name.isEmpty()) continue
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
                in sshNative -> sshTargets?.let { targets -> buildJsonObject {
                    put("kind", "ssh"); put("operation", name); put("targets", targets.first)
                } } ?: buildJsonObject { put("kind", "device") }
                // 开启 MCP/Agent、补充密钥、申请能力：发起设备弹出与本机相同的卡片，
                // 用户在手机上完成后（密钥只留在手机）把与本机相同的工具结果交回云端。
                in AgentControlToolNames.all -> buildJsonObject { put("kind", "device") }
                AgentToolResultReadTool.NAME -> buildJsonObject { put("kind", "tool_result") }
                // 其余能力（记忆、Cloudflare、终端/上传/下载/开端口、自定义工具）依赖手机上的数据、令牌或文件：
                // 任务仍在云端，跑到这一步由发起设备用本机执行器执行这一个工具，结果交回云端。
                else -> request.localCloudMcpBindings[name] ?: buildJsonObject { put("kind", "device") }
            }
            val approval = when {
                name == ComputerToolNames.LOCAL_FILE_SAVE -> "always"
                name == ComputerToolNames.EXEC && binding["kind"]?.jsonPrimitive?.content == "ssh" -> checkNotNull(sshTargets).second
                else -> "never"
            }
            add(buildJsonObject {
                put("name", name); put("description", function["description"]?.toString().orEmpty())
                put("parameters", function["parameters"]?.let(::anyToJsonElement) ?: buildJsonObject { put("type", "object") })
                put("binding", binding)
                put("approval", approval)
                put("replay", if (binding["kind"]?.jsonPrimitive?.content in setOf("mcp", "device") ||
                    name in setOf(ComputerToolNames.EXEC, ComputerToolNames.WRITE_FILE, ComputerToolNames.EDIT)) "never" else "safe")
                if (name.startsWith("mcp_") && name !in direct) put("deferred", true)
            })
        }
    }
}

/**
 * 本轮选中的 SSH 目标连接信息，只进入加密任务输入。exec 审批：全部完全信任则不问；
 * 智能审批由模型的 ask_user_approval 决定（与本机同一规则）；含手动审批的由发起设备按本机策略判断。
 * 任一目标云端连不到（内网、无主机密钥、Workspace 不明）返回 null，整组改由手机执行。
 */
private suspend fun cloudSshTargets(
    context: Context,
    computer: com.android.everytalk.data.computer.ComputerRequestContext?,
): Pair<JsonObject, String>? {
    computer ?: return null
    val repository = com.android.everytalk.data.computer.ComputerRepository(context)
    val credentials = com.android.everytalk.data.computer.ComputerCredentialStore(context)
    val targets = computer.targetContexts().filter { target ->
        computer.selectedTargets.firstOrNull { it.computerId == target.computerId }?.provider
            ?.let { it == com.android.everytalk.data.computer.ComputerProvider.SSH } ?: (target.cloudflareBinding == null)
    }
    if (targets.isEmpty()) return null
    val modes = mutableSetOf<com.android.everytalk.data.computer.ComputerPermissionMode>()
    val bindings = buildJsonObject {
        for (target in targets) {
            val server = repository.getComputer(target.computerId) ?: return null
            val workspace = repository.getWorkspace(target.workspaceId)?.hostPath?.takeIf { it.startsWith("/") } ?: return null
            val hostKey = server.hostKeyBlobBase64?.takeIf(String::isNotBlank) ?: return null
            val addresses = runCatching { java.net.InetAddress.getAllByName(server.host).toList() }.getOrNull() ?: return null
            if (addresses.none { it is java.net.Inet4Address } || addresses.any { it is java.net.Inet4Address && !it.isCloudReachable() }) return null
            modes += target.permissionMode
            val credential = runCatching { credentials.loadComputerCredential(server.id) }.getOrNull() ?: return null
            try {
                put(server.id, buildJsonObject {
                    put("host", server.host); put("port", server.port); put("username", server.username)
                    put("hostKey", hostKey); put("workspace", workspace)
                    when (credential) {
                        is com.android.everytalk.data.computer.ComputerCredential.Password -> put("password", String(credential.password))
                        is com.android.everytalk.data.computer.ComputerCredential.PrivateKey -> {
                            put("privateKey", String(credential.privateKey))
                            credential.passphrase?.let { put("passphrase", String(it)) }
                        }
                    }
                })
            } finally { credential.clear() }
        }
    }
    val approval = when {
        com.android.everytalk.data.computer.ComputerPermissionMode.MANUAL in modes -> "always"
        com.android.everytalk.data.computer.ComputerPermissionMode.SMART in modes -> "smart"
        else -> "never"
    }
    return bindings to approval
}

/** 与后端 publicIPv4 同一规则：内网、回环、链路本地、CGNAT、文档与测试网段、组播都连不到。 */
private fun java.net.Inet4Address.isCloudReachable(): Boolean {
    val (a, b, c) = address.map { it.toInt() and 0xff }
    return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && b in 64..127 || a == 169 && b == 254 ||
        a == 172 && b in 16..31 || a == 192 && (b == 0 || b == 2 || b == 168) ||
        a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
}

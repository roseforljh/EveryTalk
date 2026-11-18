package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.LocalWorkspaceFileBridge
import com.android.everytalk.data.computer.targetContexts
import com.android.everytalk.data.computer.SSH_HOME_PATH
import com.android.everytalk.data.network.AttachmentToolExecutor
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillToolNames
import com.android.everytalk.models.SelectedMediaItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.android.everytalk.data.database.entities.toModel

/**
 * 服务器声明支持的能力全集；与后端 /v1/agent/capabilities 保持一致。
 * 旧后端没有这个入口时集合为空，提交时退回旧版设备执行，不会因为能力不认识而报错。
 */
internal val ALL_FEATURES = setOf("submission", "secret", "control", "memory", "cloudflare", "ssh_ops", "artifacts", "mcp_enable", "workspace")

/**
 * 将已授权工具转换为后端执行绑定。连接信息只进入加密任务输入，绝不混入模型工具描述。
 * Skill/附件/沙箱全部读取本轮快照；只有手机写入与人工授权进入设备等待，未知工具不能冒充已迁移。
 */
internal suspend fun cloudAgentTools(
    context: Context,
    request: ChatRequest,
    attachments: List<SelectedMediaItem.GenericFile>,
    skills: SkillRepository,
    /** 申请开启 MCP 时预冻结的工具来源：本会话尚未启用、但设置里已启用的 MCP 服务。 */
    mcpEnableRequest: ChatRequest? = null,
    /** 服务器声明的能力：缺哪项就退回旧版设备执行，旧后端也能照常工作。 */
    serverFeatures: Set<String> = ALL_FEATURES,
): JsonArray = withContext(Dispatchers.IO) {
    fun supports(feature: String) = feature in serverFeatures
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
    // 申请开启 MCP 时，把设置里已启用的服务也在提交时一起冻结；批准后服务器直接声明，不再等手机准备。
    val mcpEnableTools = if (AgentControlToolNames.REQUEST_MCP in names && mcpEnableRequest != null) {
        JsonArray(cloudAgentTools(context, mcpEnableRequest, emptyList(), skills, null, serverFeatures).filter { item ->
            val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.isNotEmpty() && name !in AgentControlToolNames.all && name !in names
        })
    } else null
    val bashFiles = if ("local_bash" in names) {
        val workspace = checkNotNull(request.localComputerRequestContext).workspaceId
        require(workspace.isNotBlank() && workspace.none { it == '/' || it == '\\' || it == ':' } && workspace !in setOf(".", "..")) { "Workspace 身份无效" }
        val root = File(context.filesDir, "agent-workspaces/$workspace")
        if (root.isDirectory) LocalWorkspaceFileBridge().load(root) else emptyMap()
    } else emptyMap()
    // 8 个 SSH 工具全部可由后端直连执行：终端会话在服务器进程里，下载成为云端产物挂到回复，上传读取本任务附件或下载产物。
    val baseSsh = setOf(ComputerToolNames.EXEC, ComputerToolNames.READ_FILE, ComputerToolNames.WRITE_FILE, ComputerToolNames.EDIT)
    val sshNative = if (supports("ssh_ops")) baseSsh + setOf(ComputerToolNames.TERMINAL, ComputerToolNames.UPLOAD,
        ComputerToolNames.DOWNLOAD, ComputerToolNames.OPEN_PORT) else baseSsh
    // 服务器在云端可直连（公网 IPv4、已记录主机密钥）时，四个基础工具由后端直接执行；否则和其他手机能力一样交给发起设备。
    val sshTargets = if (names.any { it in sshNative || it in setOf(AgentControlToolNames.REQUEST_SKILL_SECRET,
        AgentControlToolNames.REQUEST_PROTECTED_SECRET) }) cloudSshTargets(context, request.localComputerRequestContext) else null
    // Cloudflare 工具由后端直接调 Cloudflare API：提交前在手机上续期一次 access token，冻结进加密输入；拿不到授权就如实说明。
    val cloudflare = if (names.any { it in ComputerToolNames.cloudflare }) runCatching { cloudflareTargets(context, request.localComputerRequestContext) }
        .getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error; null } else null
    // 补过的凭据只能经 exec 的 secret_names 按名字注入（服务器先让用户确认命令、输出里遮蔽值）；本轮能申请凭据时才给 exec 加这个参数。
    val secretExec = supports("secret_exec") && supports("secret") &&
        names.any { it == AgentControlToolNames.REQUEST_SKILL_SECRET || it == AgentControlToolNames.REQUEST_PROTECTED_SECRET }
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
                } } ?: buildJsonObject {
                    // 云端连不到（内网、未记录主机密钥、工作目录不明）如实告诉模型，不悄悄转回手机执行。
                    put("kind", "unsupported")
                    put("message", "云端任务无法直连这台服务器（内网地址、未记录主机密钥或工作目录不明），这一步没有执行；请告诉用户换用公网可直连的服务器")
                }
                // 凭据由服务器接收保存：Skill 变量进账号同步，服务器环境变量直接写入目标服务器 .env。
                AgentControlToolNames.REQUEST_SKILL_SECRET -> if (!supports("secret")) buildJsonObject { put("kind", "device") } else buildJsonObject {
                    put("kind", "secret"); put("skills", JsonArray(skillIdsOf(request)))
                    put("targets", sshTargets?.first ?: JsonObject(emptyMap()))
                }
                AgentControlToolNames.REQUEST_PROTECTED_SECRET -> if (!supports("secret")) buildJsonObject { put("kind", "device") } else buildJsonObject {
                    put("kind", "secret"); put("skills", JsonArray(emptyList()))
                    put("targets", sshTargets?.first ?: JsonObject(emptyMap()))
                }
                // 开启 MCP：批准后服务器直接把预冻结的工具并入后续轮次，不需要手机准备执行环境。
                AgentControlToolNames.REQUEST_MCP -> if (!supports("mcp_enable")) buildJsonObject { put("kind", "device") } else buildJsonObject {
                    put("kind", "mcp_request"); put("tools", mcpEnableTools ?: JsonArray(emptyList()))
                }
                // 申请 Agent：所选服务器的工具已在提交时冻结；申请能力：服务器如实说明，不虚构授权。
                AgentControlToolNames.REQUEST_AGENT -> if (!supports("control")) buildJsonObject { put("kind", "device") } else buildJsonObject { put("kind", "agent_request") }
                AgentControlToolNames.REQUEST_CAPABILITY -> if (!supports("control")) buildJsonObject { put("kind", "device") } else buildJsonObject { put("kind", "capability") }
                in AgentControlToolNames.all -> buildJsonObject { put("kind", "device") }
                AgentToolResultReadTool.NAME -> buildJsonObject { put("kind", "tool_result") }
                // 账号记忆由服务器读写并同步下发；写入依据只能来自本轮问题原文，手机离线不影响。
                com.android.everytalk.data.memory.MemoryTool.NAME -> if (!supports("memory")) buildJsonObject { put("kind", "device") } else buildJsonObject {
                    put("kind", "memory"); put("userText", checkNotNull(request.localMemorySnapshot) { "本次请求没有记忆授权" }.userText)
                }
                in ComputerToolNames.cloudflare -> if (!supports("cloudflare")) buildJsonObject { put("kind", "device") } else cloudflare?.takeIf { it.isNotEmpty() }?.let { targets -> buildJsonObject {
                    put("kind", "cloudflare"); put("targets", targets)
                } } ?: buildJsonObject { put("kind", "unsupported"); put("message", "Cloudflare 授权不可用或已失效，请在设置里重新授权 Cloudflare 后重发") }
                // 其余能力（终端/上传/下载/开端口、自定义工具）依赖手机上的数据、令牌或文件：
                // 任务仍在云端，跑到这一步由发起设备用本机执行器执行这一个工具，结果交回云端。
                else -> request.localCloudMcpBindings[name] ?: mcpEnableRequest?.localCloudMcpBindings?.get(name)
                    ?: buildJsonObject { put("kind", "device") }
            }
            val approval = when {
                name == ComputerToolNames.LOCAL_FILE_SAVE -> "always"
                name == ComputerToolNames.DOWNLOAD -> "never"
                name != ComputerToolNames.READ_FILE && name in sshNative && binding["kind"]?.jsonPrimitive?.content == "ssh" -> checkNotNull(sshTargets).second
                else -> "never"
            }
            add(buildJsonObject {
                put("name", name); put("description", function["description"]?.toString().orEmpty())
                val parameters = function["parameters"]?.let(::anyToJsonElement) ?: buildJsonObject { put("type", "object") }
                put("parameters", if (name == ComputerToolNames.EXEC && secretExec && binding["kind"]?.jsonPrimitive?.content == "ssh") withSecretNames(parameters) else parameters)
                put("binding", binding)
                put("approval", approval)
                put("replay", if (binding["kind"]?.jsonPrimitive?.content in setOf("mcp", "device") ||
                    binding["kind"]?.jsonPrimitive?.content == "cloudflare" &&
                        !com.android.everytalk.data.computer.ComputerToolCallSafety.isReadOnly(name, JsonObject(emptyMap())) ||
                    name in setOf(ComputerToolNames.EXEC, ComputerToolNames.WRITE_FILE, ComputerToolNames.EDIT, ComputerToolNames.TERMINAL,
                        ComputerToolNames.UPLOAD, ComputerToolNames.OPEN_PORT)) "never" else "safe")
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
            // SSH Workspace 默认就是 "~"（登录用户 home），后端按 home 解析；只认绝对路径会让 SSH 工具全部退回手机执行。
            val workspace = repository.getWorkspace(target.workspaceId)?.hostPath
                ?.takeIf { it == SSH_HOME_PATH || it.startsWith("/") } ?: return null
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

/** 本轮冻结的 Skill 版本 ID，凭据申请只能指向它们，不能借机为任意技能取密钥。 */
private fun skillIdsOf(request: ChatRequest): List<kotlinx.serialization.json.JsonPrimitive> =
    (request.localSkillSnapshot?.let { snapshot ->
        (snapshot.automaticCatalog.map { it.skillId } + snapshot.manualReferences.map { it.skillId })
    }.orEmpty()).distinct().map(::JsonPrimitive)

/** 在 exec 参数里加 secret_names：只写变量名，值由服务器注入。 */
internal fun withSecretNames(parameters: JsonElement): JsonElement {
    val schema = parameters as? JsonObject ?: return parameters
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(schema + ("properties" to JsonObject(properties + ("secret_names" to buildJsonObject {
        put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("maxItems", 32)
        put("description", "Names of credentials the user already provided via request_protected_secret / request_skill_secret. " +
            "The server exports them as environment variables for this command only, asks the user to confirm first, and masks their values in the output. " +
            "Reference them as \$NAME; never print or send them elsewhere.")
    }))))
}

/**
 * 本轮选中的 Cloudflare 目标：账号、能力、scope、权限模式和当前 access token，只进入加密任务输入。
 * 取 Token 走与本机执行相同的 CloudflareRequestCredentials（逐项核对绑定）；续期交给服务器，凭据本身随账号同步。
 */
private suspend fun cloudflareTargets(
    context: Context,
    computer: com.android.everytalk.data.computer.ComputerRequestContext?,
): JsonObject {
    computer ?: return JsonObject(emptyMap())
    return com.android.everytalk.data.computer.ComputerRepository(context).use { repository -> cloudflareTargets(repository, computer, context) }
}

private suspend fun cloudflareTargets(
    repository: com.android.everytalk.data.computer.ComputerRepository,
    computer: com.android.everytalk.data.computer.ComputerRequestContext,
    context: Context,
): JsonObject {
    val json = Json { ignoreUnknownKeys = true }
    val dao = repository.dao()
    // 只读取当前令牌、不在手机续期：快到期或被拒时由服务器用账号同步里的 refresh token 续期并写回，两边不会互相作废。
    val credentials = com.android.everytalk.data.computer.CloudflareRequestCredentials(dao,
        com.android.everytalk.data.computer.ComputerCredentialStore(context), json, null, null)
    return buildJsonObject {
        for (target in computer.targetContexts().filter { it.cloudflareBinding != null }) {
            val config = dao.getCloudflareConfig(target.computerId)?.toModel(json) ?: continue
            val token = credentials.token(target)
            val authorization = dao.getCloudflareAuthorization(config.authorizationId)?.toModel(json) ?: continue
            put(target.computerId, buildJsonObject {
                put("accountId", config.accountId); put("token", token)
                authorization.expiresAt?.let { put("expiresAt", it) }
                put("clientId", com.android.everytalk.BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID)
                put("scopes", JsonArray(authorization.grantedScopes.sorted().map(::JsonPrimitive)))
                put("capabilities", JsonArray(config.capabilities.map { JsonPrimitive(it.name) }))
                put("workspaceId", target.workspaceId); put("permissionMode", target.permissionMode.name)
            })
        }
    }
}

/**
 * Worker 部署和 R2 上传读取的手机 Workspace 原字节（base64），发送时冻结一份交给云端；
 * 跳过依赖与构建缓存目录，敏感文件由后端按部署规则拒绝。超出体积直接失败，不能悄悄少传文件。
 */
internal suspend fun cloudAgentWorkspaces(context: Context, request: ChatRequest): JsonObject? = withContext(Dispatchers.IO) {
    val names = request.tools.orEmpty().map(McpToolDiscovery::name).toSet()
    if (ComputerToolNames.WORKER_DEPLOY !in names && ComputerToolNames.R2_UPLOAD !in names) return@withContext null
    val computer = request.localComputerRequestContext ?: return@withContext null
    var total = 0L
    com.android.everytalk.data.computer.ComputerRepository(context).use { repository -> buildJsonObject {
        for (target in computer.targetContexts().filter { it.cloudflareBinding != null }) {
            val root = repository.getWorkspace(target.workspaceId)?.hostPath?.let(::File)?.canonicalFile?.takeIf { it.isDirectory } ?: continue
            put(target.workspaceId, buildJsonObject {
                root.walkTopDown().onEnter { it.name !in setOf(".git", "node_modules", "build", ".cache", ".gradle") }
                    .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { file ->
                        val relative = file.canonicalFile.relativeTo(root).invariantSeparatorsPath
                        require(!relative.startsWith("..")) { "Workspace 文件路径越界" }
                        total += file.length()
                        require(total <= 32L * 1024 * 1024) { "Workspace 超过云端任务 32 MB 上限，请精简后重发" }
                        put(relative, java.util.Base64.getEncoder().encodeToString(file.readBytes()))
                    }
            })
        }
    } }
}

/** 与后端 publicIPv4 同一规则：内网、回环、链路本地、CGNAT、文档与测试网段、组播都连不到。 */
private fun java.net.Inet4Address.isCloudReachable(): Boolean {
    val (a, b, c) = address.map { it.toInt() and 0xff }
    return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && b in 64..127 || a == 169 && b == 254 ||
        a == 172 && b in 16..31 || a == 192 && (b == 0 || b == 2 || b == 168) ||
        a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
}

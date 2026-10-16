package com.android.everytalk.data.sync

import androidx.room.withTransaction
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ApiConfigEntity
import com.android.everytalk.data.database.entities.ComputerEntity
import com.android.everytalk.data.database.entities.ComputerWorkspaceEntity
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.database.entities.UserSettingEntity
import com.android.everytalk.data.database.entities.localSyncUuid
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 把本地配置投影为合同中的逐条 user_settings；机密字段只有解锁后才进入 payload。 */
internal class SyncSettingsProjector(
    private val database: AppDatabase,
    private val secrets: SyncSecretManager? = null,
    private val userId: String? = null,
    private val secretsEpoch: Long = 0L,
) {
    private val settings = database.settingsDao()
    private val sessions = database.chatDao()
    private var currentKeyVersion: Long? = null
    private var existingValues: Map<Pair<String, String>, JsonObject> = emptyMap()

    /** 在用户删除时保留逐条墓碑；下次联网才发送，不依赖设备当时是否登录。 */
    suspend fun markDeleted(kind: String, key: String) {
        val old = settings.getUserSetting(kind, key) ?: return
        if (!old.deleted) settings.upsertUserSettings(listOf(old.copy(value = "{}", deleted = true,
            updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1), syncStatus = SyncStatus.PENDING)))
    }

    suspend fun refresh() = database.withTransaction {
        migrateLegacySettingKeys()
        existingValues = settings.getSyncableUserSettings().mapNotNull { row ->
            (runCatching { syncJson.parseToJsonElement(row.value) }.getOrNull() as? JsonObject)?.let { (row.kind to row.key) to it }
        }.toMap()
        currentKeyVersion = settings.getUserSetting("crypto_keys", "default")?.let {
            runCatching { syncJson.decodeFromString<SyncCryptoKeysValue>(it.value).version }.getOrNull()
        } ?: userId?.let { secrets?.cachedKeyVersion(it, secretsEpoch) }
        database.apiConfigDao().getConfigs(false).forEach { upsert("api_configs", it.id, configValue(it)) }
        database.apiConfigDao().getConfigs(true).forEach { upsert("api_configs", it.id, configValue(it, image = true)) }
        selectedModels(); webSearchConfigs(); memorySwitches(); conversationSettings(); groupMembers(); pinnedItems()
        database.voiceConfigDao().getAll().forEach { voice ->
            upsert("voice_configs", voice.id, buildJsonObject {
                put("name", voice.name); put("provider", voice.provider)
                put("sttPlatform", voice.sttPlatform); syncEndpoint("sttEndpoint", voice.sttApiUrl); put("sttModel", voice.sttModel)
                put("chatPlatform", voice.chatPlatform); syncEndpoint("chatEndpoint", voice.chatApiUrl); put("chatModel", voice.chatModel)
                put("ttsPlatform", voice.ttsPlatform); syncEndpoint("ttsEndpoint", voice.ttsApiUrl); put("ttsModel", voice.ttsModel)
                put("voiceName", voice.voiceName); put("realtimeStreaming", voice.useRealtimeStreaming); put("createdAt", voice.createdAt)
                secret("sttApiKey", voice.sttApiKey, "voice_configs", voice.id, "/sttApiKey")
                secret("chatApiKey", voice.chatApiKey, "voice_configs", voice.id, "/chatApiKey")
                secret("ttsApiKey", voice.ttsApiKey, "voice_configs", voice.id, "/ttsApiKey")
            }, voice.updatedAt)
        }
        database.mcpConfigDao().getAllSnapshot().forEach { server ->
            val syncKey = syncStableSettingKey("mcp_servers", server.id)
            upsert("mcp_servers", syncKey, buildJsonObject {
                put("localId", server.id); put("name", server.name); syncEndpoint("endpoint", server.url)
                put("transport", server.transportType); put("enabled", server.enabled)
                parseHeaders(server.headers)?.let { secretJson("headers", it, "mcp_servers", syncKey, "/headers") }
            })
        }
        database.skillDao().getAll().forEach {
            val value = syncSkillValue(it).toMutableMap()
            val previous = existingValues["skills" to it.skillId]
            if (previous?.get("contentHash") == value["contentHash"]) {
                listOf("fileRefs", "fileManifest", "manifestRef").forEach { name -> previous?.get(name)?.let { field -> value[name] = field } }
            }
            upsert("skills", it.skillId, JsonObject(value), it.updatedAt)
        }
        customVoiceModels(); voicePlatforms()
        database.computerDao().getAllSnapshot().forEach { upsert("computers", it.id, computerValue(it), it.updatedAt) }
        database.computerDao().getAllWorkspacesSnapshot().forEach { workspace ->
            upsert("computer_workspaces", syncStableSettingKey("computer_workspaces", workspace.id), workspaceValue(workspace, sessions.getStoredSession(workspace.conversationId)?.uuid))
        }
    }

    private suspend fun selectedModels() {
        listOf("text" to "selected_api_config_id", "image" to "selected_image_gen_config_id", "voice" to "selected_voice_config_id", "web_search" to "selected_external_web_search_provider_id").forEach { (scope, localKey) ->
            val value = settings.getValue(localKey)
            if (value == null && settings.getUserSetting("selected_models", scope) == null) return@forEach
            upsert("selected_models", scope, buildJsonObject { put(if (scope == "web_search") "providerId" else "configId", value?.let(::JsonPrimitive) ?: JsonNull) })
        }
    }

    private suspend fun webSearchConfigs() {
        val raw = settings.getValue("external_web_search_configs") ?: return
        runCatching { syncJson.decodeFromString<List<ExternalWebSearchProviderConfig>>(raw) }.getOrNull().orEmpty().forEach { config ->
            upsert("web_search_configs", config.providerId, buildJsonObject {
                put("providerId", config.providerId); secret("apiKey", config.apiKey, "web_search_configs", config.providerId, "/apiKey")
            })
        }
    }

    private suspend fun memorySwitches() {
        val enabledRaw = settings.getValue("memory_enabled")
        val autoCaptureRaw = settings.getValue("memory_auto_capture_enabled")
        if (enabledRaw == null && autoCaptureRaw == null) return
        val enabled = enabledRaw?.toBooleanStrictOrNull() ?: false
        val autoCapture = autoCaptureRaw?.toBooleanStrictOrNull() ?: false
        upsert("memory_switches", "default", buildJsonObject { put("memoryEnabled", enabled); put("autoCaptureEnabled", autoCapture) })
    }

    /** 每个文字会话一条记录；本地偏好中的设备状态字段不进入同步。 */
    private suspend fun conversationSettings() {
        suspend fun mapping(key: String): JsonObject = settings.getValue(key)?.let {
            runCatching { syncJson.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: JsonObject(emptyMap())
        val toggles = mapping("conversation_function_toggle_states")
        val models = mapping("conversation_api_config_ids")
        val computerSelections = database.computerDao().getAllSelectionsSnapshot().associate { it.conversationId to it.selectedComputerId }
        sessions.getAllSessions(false).filterNot { it.deleted || it.isImageGeneration }.forEach { session ->
            val toggle = toggles[session.id]?.jsonObject
            val old = settings.getUserSetting("conversation_settings", session.uuid)
            val oldValue = old?.value?.let { runCatching { syncJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            val computerId = computerSelections[session.id]
            val legacyPrompt = if (oldValue?.containsKey("systemPrompt") != true) sessions.getLegacySystemPrompt(session.id) else null
            if (toggle == null && models[session.id] == null && computerId == null && oldValue == null && legacyPrompt == null) return@forEach
            upsert("conversation_settings", session.uuid, buildJsonObject {
                oldValue?.forEach { (name, element) -> put(name, element) }
                if ("modelId" in oldValue.orEmpty() || models[session.id] != null) put("modelId", models[session.id] ?: JsonNull)
                if ("computerId" in oldValue.orEmpty() || computerId != null) put("computerId", computerId?.let(::JsonPrimitive) ?: JsonNull)
                oldValue?.get("systemPrompt")?.let { element -> put("systemPrompt", element) }
                oldValue?.get("promptEnabled")?.let { element -> put("promptEnabled", element) }
                legacyPrompt?.let { put("systemPrompt", it); put("promptEnabled", false) }
                // 旧版本序列化会省略默认 false；本地已有该会话设置时，缺字段表示关闭，不能沿用云端旧 true。
                if (toggle != null) listOf("webSearchEnabled", "codeExecutionEnabled", "mcpEnabled", "agentEnabled").forEach { name ->
                    put(name, toggle[name] ?: JsonPrimitive(false))
                }
            })
        }
    }

    private suspend fun groupMembers() {
        val sessionsById = sessions.getAllSessions(false).filterNot { it.deleted }.associateBy { it.id }
        val legacySettings = settings.getAllUserSettings().filter { it.kind == "conversation_groups" && !it.deleted }
        val groups = buildList<Triple<String, List<String>, UserSettingEntity?>> {
            settings.getConversationGroups().forEach { add(Triple(it.groupName, it.conversationIds, null)) }
            legacySettings.filterNot { legacy -> any { it.first == legacy.key } }.forEach { legacy ->
                val ids = runCatching { SyncPayloadMapper.conversationIds(buildJsonObject {
                    put("value", SyncPayloadMapper.settingValue(legacy.value, "conversation_groups"))
                }) }.getOrDefault(emptyList())
                add(Triple(legacy.key, ids, legacy))
            }
        }
        groups.forEach { (groupName, localIds, legacy) -> localIds.forEach { rawId ->
            val session = sessionsById[rawId] ?: sessions.getSessionByUuid(rawId) ?: return@forEach
            if (session.isImageGeneration) return@forEach
            val key = Base64.getUrlEncoder().withoutPadding().encodeToString(groupName.toByteArray()) + "." + session.uuid
            val legacySetting = legacy ?: settings.getUserSetting("conversation_groups", groupName)
            upsert(
                "group_members", key,
                buildJsonObject { put("groupName", groupName); put("conversationId", session.uuid) },
                modifiedAt = legacySetting?.updatedAt ?: System.currentTimeMillis(),
                syncStatus = legacySetting?.syncStatus ?: SyncStatus.PENDING,
            )
        } }
        // 旧整表行只能用于一次转换，不能借用成员的 canonical_id，否则唯一索引会吞掉成员行。
        legacySettings.forEach { legacy ->
            settings.deleteUserSetting(legacy.kind, legacy.key)
            settings.deleteSetting("sync_rejected:user_settings:${legacy.uuid}")
        }
    }

    private suspend fun pinnedItems() {
        val sessionsById = sessions.getAllSessions(false).filterNot { it.deleted }.associateBy { it.id }
        settings.getPinnedIds(false).forEach { localId -> sessionsById[localId]?.takeUnless { it.isImageGeneration }?.let {
            upsert("pinned_items", it.uuid, buildJsonObject { })
        } }
    }

    private suspend fun customVoiceModels() {
        settings.getByPrefix("custom_models_").forEach { item ->
            val parts = item.key.removePrefix("custom_models_").split('_', limit = 2)
            if (parts.size != 2 || parts[0] !in setOf("stt", "chat", "tts")) return@forEach
            val models = item.value.split(',').map(String::trim).filter(String::isNotBlank).distinct()
            upsert("voice_custom_models", "${parts[0]}:${parts[1]}", buildJsonObject {
                put("platform", parts[1]); put("models", JsonArray(models.map(::JsonPrimitive)))
            })
        }
    }

    private suspend fun voicePlatforms() {
        (database.voiceConfigDao().getAll().flatMap { listOf(it.sttPlatform, it.chatPlatform, it.ttsPlatform) } +
            settings.getByPrefix("voice_llm_platform:").map { it.key.removePrefix("voice_llm_platform:") })
            .filter(String::isNotBlank).distinct().forEach { platform ->
                upsert("voice_llm_platforms", platform, buildJsonObject { put("platform", platform) })
            }
    }

    private fun configValue(config: ApiConfigEntity, image: Boolean = false): JsonObject = buildJsonObject {
        put("name", config.name); syncEndpoint("address", config.address); put("model", config.model); put("provider", config.provider)
        put("channel", config.channel); put("imageConfig", image); put("modality", config.modalityType.name); put("temperature", config.temperature)
        // null 表示用户已清空可选参数；缺字段只用于兼容旧客户端，不能把清空误当成“沿用旧值”。
        put("topP", config.topP?.let(::JsonPrimitive) ?: JsonNull)
        put("outputLimit", config.maxTokens?.let(::JsonPrimitive) ?: JsonNull)
        put("defaultUseWebSearch", config.defaultUseWebSearch?.let(::JsonPrimitive) ?: JsonNull)
        put("imageSize", config.imageSize?.let(::JsonPrimitive) ?: JsonNull)
        put("inferenceSteps", config.numInferenceSteps?.let(::JsonPrimitive) ?: JsonNull)
        put("guidanceScale", config.guidanceScale?.let(::JsonPrimitive) ?: JsonNull)
        put("codeExecutionEnabled", config.enableCodeExecution?.let(::JsonPrimitive) ?: JsonNull)
        // 自定义工具定义同样属于模型配置；可疑内容只能进密文，不能绕过配置扫描。
        if (config.toolsJson == null) put("toolDefinitions", JsonNull)
        else runCatching { syncJson.parseToJsonElement(config.toolsJson) }.getOrNull()?.let { definitions ->
            if (runCatching { SyncContractSanitizer.validate(definitions) }.isSuccess) put("toolDefinitions", definitions)
            else secretJson("toolDefinitions", definitions, "api_configs", config.id, "/toolDefinitions")
        }
        put("modelParameters", buildJsonObject {
            put("protocolOverride", config.modelParameters.apiProtocolOverride?.name ?: ""); put("reasoningMode", config.modelParameters.reasoningMode.name)
            put("reasoningEffort", config.modelParameters.reasoningEffort); put("thoughtBudget", config.modelParameters.thinkingBudget)
            put("contextLimit", config.modelParameters.maxContextTokens); put("autoCompressionEnabled", config.modelParameters.autoContextCompressionEnabled)
            put("autoCompressionThreshold", config.modelParameters.autoContextCompressionThresholdPercent)
            put("customReasoningEfforts", JsonArray(config.modelParameters.customReasoningEfforts.map(::JsonPrimitive)))
            // 参数名作为列表项的值保存，不当作 JSON 属性名；敏感参数的值仍必须加密。
            val parameters = config.modelParameters.customParameters
            if (parameters != null) {
                put("customParameters", JsonArray(parameters.mapIndexed { index, parameter ->
                    buildJsonObject {
                        put("name", parameter.name); put("type", parameter.type.name); put("enabled", parameter.enabled)
                        val raw = JsonPrimitive(parameter.value)
                        val nested = if (parameter.type == com.android.everytalk.data.DataClass.CustomParameterType.JSON)
                            runCatching { syncJson.parseToJsonElement(parameter.value) }.getOrNull() else raw
                        if (SyncContractSanitizer.isForbiddenFieldName(parameter.name) ||
                            runCatching { SyncContractSanitizer.validate(nested ?: raw) }.isFailure) {
                            secretJson("value", raw, "api_configs", config.id, "/modelParameters/customParameters/$index/value")
                        } else put("value", raw)
                    }
                }))
            } else put("customParameters", JsonNull)
            val previous = (existingValues["api_configs" to config.id]?.get("modelParameters") as? JsonObject)?.get("customParameters")
            fun containsEnvelope(element: JsonElement): Boolean = when (element) {
                is JsonObject -> SyncContractSanitizer.isValidEnvelope(element) || element.values.any(::containsEnvelope)
                is JsonArray -> element.any(::containsEnvelope)
                else -> false
            }
            if (previous != null && containsEnvelope(previous) &&
                (userId == null || currentKeyVersion == null || secrets?.hasCachedDek(userId, currentKeyVersion!!, secretsEpoch) != true)) {
                // 未解锁时不重排带密文的参数数组，防止 AAD 下标改变或把云端凭据投影成空列表。
                put("customParameters", previous)
            }
        })
        secret("apiKey", config.key, "api_configs", config.id, "/apiKey")
    }

    private fun computerValue(computer: ComputerEntity): JsonObject = buildJsonObject {
        put("displayName", computer.displayName); put("provider", computer.provider); put("host", computer.host); put("port", computer.port)
        put("username", computer.username); put("authKind", computer.authKind); put("runMode", computer.runMode); put("sandboxImage", computer.sandboxImage ?: "")
        put("allowPrivateNetwork", computer.allowPrivateNetwork); put("permissionMode", computer.permissionMode); put("createdAt", computer.createdAt)
    }

    private fun workspaceValue(workspace: ComputerWorkspaceEntity, conversationUuid: String?): JsonObject = buildJsonObject {
        put("localId", workspace.id); put("computerId", workspace.computerId); conversationUuid?.let { put("sessionId", it) }
        put("runMode", workspace.runMode); workspace.containerImage?.let { put("containerImage", it) }; put("createdAt", workspace.createdAt)
    }

    private suspend fun upsert(
        kind: String,
        key: String,
        value: JsonObject,
        modifiedAt: Long = System.currentTimeMillis(),
        syncStatus: String = SyncStatus.PENDING,
    ) {
        val old = settings.getUserSetting(kind, key)
        val merged = preserveEncryptedLeaves(old?.value, value)
        val raw = merged.toString()
        fun withToggleDefaults(element: JsonElement): JsonElement =
            if (kind == "conversation_settings" && element is JsonObject) JsonObject(
                mapOf("webSearchEnabled" to JsonPrimitive(false), "codeExecutionEnabled" to JsonPrimitive(false),
                    "mcpEnabled" to JsonPrimitive(false), "agentEnabled" to JsonPrimitive(false)) + element
            ) else element
        // 服务端会排序 JSON 属性，并可能把 0.0 写成 0；这些变化不代表用户修改了配置。
        // 同样，旧云端缺省的 false 和显式 false 等价，不能在拉取后制造“本地修改”。
        if (old != null && !old.deleted && syncSettingValuesEqual(withToggleDefaults(syncJson.parseToJsonElement(old.value)), withToggleDefaults(merged))) return
        settings.upsertUserSettings(listOf(UserSettingEntity(kind, key, raw, old?.uuid ?: localSyncUuid("user_settings", "$kind:$key"), maxOf(modifiedAt, (old?.updatedAt ?: 0L) + 1), false, syncStatus)))
    }

    /** 未解锁时保留已有密文，防止后台投影把已同步凭据清空。 */
    private fun preserveEncryptedLeaves(oldRaw: String?, current: JsonObject): JsonObject {
        val old = oldRaw?.let { runCatching { syncJson.parseToJsonElement(it) }.getOrNull() as? JsonObject } ?: return current
        fun preserve(previous: JsonElement?, next: JsonElement): JsonElement = when {
            previous is JsonObject && next is JsonObject -> buildJsonObject {
                next.forEach { (name, child) -> put(name, preserve(previous[name], child)) }
                previous.forEach { (name, child) ->
                    if (name !in next && SyncContractSanitizer.isValidEnvelope(child)) put(name, child)
                }
            }
            // AAD 包含数组下标；只有同位置同名参数才能沿用旧密文，不能挪动密文。
            previous is JsonArray && next is JsonArray -> JsonArray(next.mapIndexed { index, child ->
                val prior = previous.getOrNull(index)
                if (prior is JsonObject && child is JsonObject && prior["name"] == child["name"]) preserve(prior, child) else child
            })
            else -> next
        }
        return preserve(old, current) as JsonObject
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.secret(name: String, value: String, kind: String, key: String, pointer: String) {
        // 空值也要加密：用户清空 Key 是一次真实修改，不能沿用旧密文使其复活。
        secretJson(name, JsonPrimitive(value), kind, key, pointer)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.secretJson(name: String, value: JsonElement, kind: String, key: String, pointer: String) {
        val account = userId ?: return
        val version = currentKeyVersion ?: return
        val previous = pointer.removePrefix("/").split('/').fold(existingValues[kind to key] as JsonElement?) { parent, part ->
            when (parent) {
                is JsonObject -> parent[part.replace("~1", "/").replace("~0", "~")]
                is JsonArray -> part.toIntOrNull()?.let(parent::getOrNull)
                else -> null
            }
        }
        // 随机 nonce 只用于真实改动；每次轮询重加密会让配置永远 PENDING。
        if (previous != null) {
            val decrypted = secrets?.decryptField(account, kind, key, pointer, secretsEpoch, previous)
            if (decrypted == null || (decrypted == value && SyncContractSanitizer.isValidEnvelope(previous))) {
                // 损坏密文不能被尚未恢复的空字段覆盖；上层显示错误，保留本机已有 Key。
                put(name, previous)
                return
            }
            // 旧版漏序列化 $enc/alg；能认证但形状不合法时重新封装，不能永久沿用旧错误信封。
        }
        val encrypted = secrets?.encryptField(account, kind, key, pointer, version, secretsEpoch, value) ?: return
        put(name, encrypted)
    }

    private fun parseHeaders(raw: String): JsonElement? = runCatching { syncJson.parseToJsonElement(raw) }.getOrNull()

    /**
     * 地址一旦带用户密码或疑似密钥查询参数，整条地址都不进入同步。
     * 这样不会把凭据藏在 URL 的 query、userinfo 或未登记参数里。
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.syncEndpoint(name: String, raw: String) {
        syncSafeEndpoint(raw)?.let { put(name, it) }
    }

    /**
     * 旧 MCP/工作区的 key 不是 UUID，服务器从未接纳过这些逻辑记录。
     * 在同一事务内替换同步投影并重新入队；业务表 ID、连接和本机凭据完全保留。
     * 不能只清失败提示，否则 markAllLocalDataPending 会再次上传旧的非法 key。
     */
    private suspend fun migrateLegacySettingKeys() = database.withTransaction {
        settings.getAllUserSettings().filter {
            it.kind in setOf("mcp_servers", "computer_workspaces") && !isCanonicalUuid(it.key)
        }.forEach { legacy ->
            val key = syncStableSettingKey(legacy.kind, legacy.key)
            val current = settings.getUserSetting(legacy.kind, key)
            val oldValue = runCatching { syncJson.parseToJsonElement(legacy.value).jsonObject }.getOrNull()
            val value = buildJsonObject {
                oldValue?.forEach { (name, child) ->
                    // 改 key 会改变加密 AAD，旧信封不可原样重放；凭据仍由业务表保留。
                    if (SyncContractSanitizer.isValidEnvelope(child)) return@forEach
                    if (legacy.kind == "computer_workspaces" && name in setOf("remotePath", "containerName")) return@forEach
                    if (name == "endpoint") {
                        (child as? JsonPrimitive)?.content?.let(::syncSafeEndpoint)?.let { put(name, it) }
                    } else put(name, child)
                }
                put("localId", legacy.key)
            }
            settings.deleteUserSetting(legacy.kind, legacy.key)
            if (current == null) settings.upsertUserSettings(listOf(legacy.copy(
                key = key, value = value.toString(), syncStatus = SyncStatus.PENDING,
            )))
            settings.deleteSetting("sync_rejected:user_settings:${legacy.uuid}")
        }
    }
}

/** 合同要求 UUID key；本地旧 ID 可能是 mcp-cloudflare、ws_xxx 等任意字符串。 */
internal fun syncStableSettingKey(kind: String, localId: String): String =
    if (isCanonicalUuid(localId)) localId.lowercase() else localSyncUuid(kind, localId)

/** 远端目录实际保存 owner/repo，兼容安全 URL；不能把本机路径或带凭据的地址当来源。 */
internal fun syncSafeSkillRepository(raw: String): String? =
    if (Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+").matches(raw) && raw.substringAfter('/') !in setOf(".", "..")) raw
    else syncSafeEndpoint(raw)

internal fun isCanonicalUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

/** 合同要求 URL 不带 userinfo，也不能带疑似密钥参数名。 */
internal fun syncSafeEndpoint(raw: String): String? {
    if (raw.isBlank()) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
    if (runCatching { SyncContractSanitizer.validate(JsonPrimitive(raw)) }.isFailure) return null
    // 少数 MCP 服务把凭据放在路径中；已知的密钥前缀同样留在本机。
    if (Regex("(?i)(?:^|/)(?:fc|sk|tvly)-[A-Za-z0-9_-]{16,}(?:/|$)").containsMatchIn(uri.path.orEmpty())) return null
    // fragment 从不发给目标 HTTP 服务，也不需要上传到账号。
    if (uri.rawFragment != null) return syncSafeEndpoint(raw.substringBefore('#'))
    val sensitiveParts = listOf("token", "key", "secret", "signature", "password", "authorization", "cookie", "headers", "privatekey")
    val query = uri.rawQuery ?: return raw
    val hasSensitiveName = query.split('&').any { pair ->
        val name = pair.substringBefore('=').let { encoded ->
            runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(encoded)
        }
        val normalized = name.filter(Char::isLetterOrDigit).lowercase()
        sensitiveParts.any(normalized::contains)
    }
    return raw.takeUnless { hasSensitiveName }
}

/** JSON 对象顺序不影响内容，数值的等价表示也不产生新修改；数组顺序和字符串仍严格比较。 */
internal fun syncSettingValuesEqual(left: JsonElement, right: JsonElement): Boolean = when {
    left is JsonObject && right is JsonObject -> left.keys == right.keys && left.all { (key, value) ->
        syncSettingValuesEqual(value, right.getValue(key))
    }
    left is JsonArray && right is JsonArray -> left.size == right.size && left.indices.all {
        syncSettingValuesEqual(left[it], right[it])
    }
    left is JsonPrimitive && right is JsonPrimitive && !left.isString && !right.isString -> {
        val a = left.content.toBigDecimalOrNull()
        val b = right.content.toBigDecimalOrNull()
        if (a != null && b != null) a.compareTo(b) == 0 else left == right
    }
    else -> left == right
}

/** 技能只同步安装和版本信息，路径、使用次数和文件内容留在本机。 */
internal fun syncSkillValue(skill: SkillInstallationEntity): JsonObject = buildJsonObject {
    put("packageId", skill.packageId); put("packageName", skill.packageName); put("name", skill.name); put("sourceType", skill.sourceType)
    put("contentHash", skill.currentHash); put("enabled", skill.enabled); put("invocationMode", skill.invocationMode); put("installedAt", skill.createdAt)
    put("description", skill.description)
    // 远端仓库中的相对路径属于来源信息；本机绝对路径和 Skill 文件内容不传。
    if (skill.sourceType == "REMOTE") {
        skill.sourceRepository?.let(::syncSafeSkillRepository)?.let { put("sourceRepository", it) }
        skill.sourcePath?.takeIf { !it.startsWith('/') && !it.contains(':') && !it.contains('\\') && it.split('/').none { part -> part == ".." } }
            ?.let { put("repositoryPath", it) }
    }
}

/** 本地保存复用已缓存账号密钥封存修改；无网络请求，游客状态也不启动后台任务。 */
internal suspend fun captureSyncSettings(context: android.content.Context, database: AppDatabase) {
    val user = database.settingsDao().getValue(SyncStateStore.KEY_ACCOUNT)
    val epoch = SyncStateStore(database).readSecretsEpoch().coerceAtLeast(0)
    SyncSettingsProjector(database, SyncSecretManager(context), user, epoch).refresh()
}

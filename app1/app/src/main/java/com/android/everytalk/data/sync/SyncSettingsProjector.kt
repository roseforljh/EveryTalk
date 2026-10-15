package com.android.everytalk.data.sync

import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ApiConfigEntity
import com.android.everytalk.data.database.entities.ComputerEntity
import com.android.everytalk.data.database.entities.ComputerWorkspaceEntity
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.database.entities.UserSettingEntity
import com.android.everytalk.data.database.entities.localSyncUuid
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    suspend fun refresh() {
        currentKeyVersion = settings.getUserSetting("crypto_keys", "default")?.let {
            runCatching { syncJson.decodeFromString<SyncCryptoKeysValue>(it.value).version }.getOrNull()
        }
        database.apiConfigDao().getConfigs(false).forEach { upsert("api_configs", it.id, configValue(it)) }
        database.apiConfigDao().getConfigs(true).forEach { upsert("api_configs", it.id, configValue(it, image = true)) }
        selectedModels(); webSearchConfigs(); memorySwitches(); conversationSettings(); groupMembers(); pinnedItems()
        database.voiceConfigDao().getAll().forEach { voice ->
            upsert("voice_configs", voice.id, buildJsonObject {
                put("name", voice.name); put("provider", voice.provider)
                put("sttPlatform", voice.sttPlatform); put("sttEndpoint", voice.sttApiUrl); put("sttModel", voice.sttModel)
                put("chatPlatform", voice.chatPlatform); put("chatEndpoint", voice.chatApiUrl); put("chatModel", voice.chatModel)
                put("ttsPlatform", voice.ttsPlatform); put("ttsEndpoint", voice.ttsApiUrl); put("ttsModel", voice.ttsModel)
                put("voiceName", voice.voiceName); put("realtimeStreaming", voice.useRealtimeStreaming); put("createdAt", voice.createdAt)
                secret("sttApiKey", voice.sttApiKey, "voice_configs", voice.id, "/sttApiKey")
                secret("chatApiKey", voice.chatApiKey, "voice_configs", voice.id, "/chatApiKey")
                secret("ttsApiKey", voice.ttsApiKey, "voice_configs", voice.id, "/ttsApiKey")
            }, voice.updatedAt)
        }
        database.mcpConfigDao().getAllSnapshot().forEach { server ->
            upsert("mcp_servers", server.id, buildJsonObject {
                put("name", server.name); put("endpoint", server.url); put("transport", server.transportType); put("enabled", server.enabled)
                parseHeaders(server.headers)?.let { secretJson("headers", it, "mcp_servers", server.id, "/headers") }
            })
        }
        database.skillDao().getAll().forEach { upsert("skills", it.skillId, skillValue(it)) }
        customVoiceModels(); voicePlatforms()
        database.computerDao().getAllSnapshot().forEach { upsert("computers", it.id, computerValue(it)) }
        database.computerDao().getAllWorkspacesSnapshot().forEach { workspace ->
            upsert("computer_workspaces", workspace.id, workspaceValue(workspace, sessions.getStoredSession(workspace.conversationId)?.uuid))
        }
    }

    private suspend fun selectedModels() {
        listOf("text" to "selected_api_config_id", "image" to "selected_image_gen_config_id", "voice" to "selected_voice_config_id", "web_search" to "selected_external_web_search_provider_id").forEach { (scope, localKey) ->
            val value = settings.getValue(localKey) ?: return@forEach
            upsert("selected_models", scope, buildJsonObject { if (scope == "web_search") put("providerId", value) else put("configId", value) })
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
        val raw = settings.getValue("conversation_function_toggle_states") ?: return
        val toggles = runCatching { syncJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        sessions.getAllSessions(false).filterNot { it.deleted || it.isImageGeneration }.forEach { session ->
            val toggle = toggles[session.id]?.jsonObject
            val old = settings.getUserSetting("conversation_settings", session.uuid)
            val oldValue = old?.value?.let { runCatching { syncJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            upsert("conversation_settings", session.uuid, buildJsonObject {
                oldValue?.get("systemPrompt")?.let { element -> put("systemPrompt", element) }
                oldValue?.get("promptEnabled")?.let { element -> put("promptEnabled", element) }
                toggle?.get("webSearchEnabled")?.let { element -> put("webSearchEnabled", element) }
                toggle?.get("codeExecutionEnabled")?.let { element -> put("codeExecutionEnabled", element) }
                toggle?.get("mcpEnabled")?.let { element -> put("mcpEnabled", element) }
                toggle?.get("agentEnabled")?.let { element -> put("agentEnabled", element) }
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
        database.voiceConfigDao().getAll().flatMap { listOf(it.sttPlatform, it.chatPlatform, it.ttsPlatform) }
            .filter(String::isNotBlank).distinct().forEach { platform ->
                upsert("voice_llm_platforms", platform, buildJsonObject { put("platform", platform) })
            }
    }

    private fun configValue(config: ApiConfigEntity, image: Boolean = false): JsonObject = buildJsonObject {
        put("name", config.name); put("address", config.address); put("model", config.model); put("provider", config.provider)
        put("channel", config.channel); put("imageConfig", image); put("modality", config.modalityType.name); put("temperature", config.temperature)
        config.topP?.let { put("topP", it) }; config.maxTokens?.let { put("outputLimit", it) }; config.defaultUseWebSearch?.let { put("defaultUseWebSearch", it) }
        config.imageSize?.let { put("imageSize", it) }; config.numInferenceSteps?.let { put("inferenceSteps", it) }; config.guidanceScale?.let { put("guidanceScale", it) }
        config.enableCodeExecution?.let { put("codeExecutionEnabled", it) }
        put("modelParameters", buildJsonObject {
            put("protocolOverride", config.modelParameters.apiProtocolOverride?.name ?: ""); put("reasoningMode", config.modelParameters.reasoningMode.name)
            put("reasoningEffort", config.modelParameters.reasoningEffort); put("thoughtBudget", config.modelParameters.thinkingBudget)
            put("contextLimit", config.modelParameters.maxContextTokens); put("autoCompressionEnabled", config.modelParameters.autoContextCompressionEnabled)
            put("autoCompressionThreshold", config.modelParameters.autoContextCompressionThresholdPercent)
        })
        secret("apiKey", config.key, "api_configs", config.id, "/apiKey")
    }

    /** 技能只同步安装和版本信息，路径、使用次数和文件内容留在本机。 */
    private fun skillValue(skill: SkillInstallationEntity): JsonObject = buildJsonObject {
        put("packageId", skill.packageId); put("packageName", skill.packageName); put("name", skill.name); put("sourceType", skill.sourceType)
        put("contentHash", skill.currentHash); put("enabled", skill.enabled); put("invocationMode", skill.invocationMode); put("installedAt", skill.createdAt)
    }

    private fun computerValue(computer: ComputerEntity): JsonObject = buildJsonObject {
        put("displayName", computer.displayName); put("provider", computer.provider); put("host", computer.host); put("port", computer.port)
        put("username", computer.username); put("authKind", computer.authKind); put("runMode", computer.runMode); put("sandboxImage", computer.sandboxImage ?: "")
        put("allowPrivateNetwork", computer.allowPrivateNetwork); put("permissionMode", computer.permissionMode); put("createdAt", computer.createdAt)
    }

    private fun workspaceValue(workspace: ComputerWorkspaceEntity, conversationUuid: String?): JsonObject = buildJsonObject {
        put("computerId", workspace.computerId); conversationUuid?.let { put("sessionId", it) }; put("runMode", workspace.runMode); put("remotePath", workspace.hostPath)
        workspace.containerName?.let { put("containerName", it) }; workspace.containerImage?.let { put("containerImage", it) }; put("createdAt", workspace.createdAt)
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
        if (old?.value == raw && !old.deleted) return
        settings.upsertUserSettings(listOf(UserSettingEntity(kind, key, raw, old?.uuid ?: localSyncUuid("user_settings", "$kind:$key"), maxOf(modifiedAt, (old?.updatedAt ?: 0L) + 1), false, syncStatus)))
    }

    /** 未解锁时保留已有密文，防止后台投影把已同步凭据清空。 */
    private fun preserveEncryptedLeaves(oldRaw: String?, current: JsonObject): JsonObject {
        val old = oldRaw?.let { runCatching { syncJson.parseToJsonElement(it) }.getOrNull() as? JsonObject } ?: return current
        return buildJsonObject {
            current.forEach { (name, child) -> put(name, child) }
            old.forEach { (name, child) -> if (!current.containsKey(name) && SyncContractSanitizer.isForbiddenFieldName(name) && SyncContractSanitizer.isValidEnvelope(child)) put(name, child) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.secret(name: String, value: String, kind: String, key: String, pointer: String) {
        if (value.isNotBlank()) secretJson(name, JsonPrimitive(value), kind, key, pointer)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.secretJson(name: String, value: JsonElement, kind: String, key: String, pointer: String) {
        val account = userId ?: return
        val version = currentKeyVersion ?: return
        val encrypted = secrets?.encryptField(account, kind, key, pointer, version, secretsEpoch, value) ?: return
        put(name, encrypted)
    }

    private fun parseHeaders(raw: String): JsonElement? = runCatching { syncJson.parseToJsonElement(raw) }.getOrNull()
}

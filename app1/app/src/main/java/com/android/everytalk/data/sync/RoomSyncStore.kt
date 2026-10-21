package com.android.everytalk.data.sync

import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.DataClass.CustomModelParameter
import com.android.everytalk.data.DataClass.CustomParameterType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig

internal interface SyncStore {
    suspend fun prepareForSync() = Unit
    suspend fun pendingBatch(maxItems: Int = 500): List<PendingChangeSnapshot>
    suspend fun reservePush(snapshots: List<PendingChangeSnapshot>)
    suspend fun applyPush(response: PushResponse, snapshots: List<PendingChangeSnapshot>)
    suspend fun applyPullPage(response: PullResponse)
}

/**
 * 同步在独立事务中应用远端记录，不调用本地 save/upsert 边界，防止拉取制造 PENDING。
 * 页面旧 ID 与远端 UUID 分开；消息提前到达时创建 SYNCED 的占位会话。
 */
internal class RoomSyncStore(
    private val database: AppDatabase,
    private val secrets: SyncSecretManager? = null,
    private val userId: String? = null,
) : SyncStore {
    /** 与服务端 messages.payload 的 64 KiB 默认上限一致，不另加客户端正文限制。 */
    private val maxMessagePayloadBytes = 64 * 1024
    private val optionalMessageFieldsForSize = listOf(
        "displaySegments",
        "bodySegments",
        "reasoning",
        "contextUsageSnapshot",
        "tokenUsage",
        "enabledToolIds",
        "executionFinishedAt",
    )
    private val messageSupplementFields = optionalMessageFieldsForSize.toSet()
    private val chat = database.chatDao()
    private val memory = database.memoryDao()
    private val settings = database.settingsDao()
    private val attachments = database.syncAttachmentDao()
    private val state = SyncStateStore(database)

    /** 先封存本机设置变更再拉取，防止首个远端分页重建列表时抹掉游客的分组和置顶。 */
    override suspend fun prepareForSync() = database.withTransaction {
        SyncSettingsProjector(database, secrets, userId, state.readSecretsEpoch().coerceAtLeast(0L)).refresh()
        if (settings.getValue("sync_projection_recovery_version") != "5") {
            // 仅重放远端快照，修复旧版漏应用的行。不能在没有服务器对账证据时
            // 把 LOCAL_ONLY 或所有配置强制改成待上传，否则会突破用户的数据保留决定。
            settings.deleteSetting(SyncStateStore.KEY_CURSOR)
            settings.insertSetting(SystemSettingEntity("sync_projection_recovery_version", "5"))
            // 旧版本把图片历史标成 LOCAL_ONLY；升级后只需一次性重新排队，
            // 让已有图片会话和附件进入新的正式同步合同。
            chat.markImageSessionsPending()
            chat.markImageMessagesPending()
        }
    }

    private suspend fun preparePullPage() {
        // 不能只在请求开始时投影一次：用户可能在网络请求期间修改服务器、密钥或会话设置。
        // 这里先把最新业务表快照转成 PENDING，再进入应用远端页的 Room 事务。
        SyncSettingsProjector(
            database = database,
            secrets = secrets,
            userId = userId,
            secretsEpoch = state.readSecretsEpoch().coerceAtLeast(0L),
        ).refresh()
    }

    /**
     * 同步密码输入成功后重新应用已经拉取的配置。
     * 这里只写入实际配置表，不修改 user_settings 的同步状态，因此不会产生回传。
     */
    suspend fun restoreUnlockedSettings() = database.withTransaction {
        settings.getAllUserSettings().filter { !it.deleted && it.syncStatus == SyncStatus.SYNCED }.forEach { restoreSetting(it) }
        projectSettings()
        SyncLocalChanges.notifyChanged()
    }

    private suspend fun restoreSetting(setting: UserSettingEntity) {
        val value = runCatching { syncJson.parseToJsonElement(setting.value).jsonObject }.getOrNull() ?: return
        when (setting.kind) {
            "selected_models" -> {
                val localKey = when (setting.key) {
                    "text" -> "selected_api_config_id"
                    "image" -> "selected_image_gen_config_id"
                    "voice" -> "selected_voice_config_id"
                    "web_search" -> "selected_external_web_search_provider_id"
                    else -> return
                }
                val field = if (setting.key == "web_search") "providerId" else "configId"
                if (field !in value) return
                val selected = value[field]?.jsonPrimitive?.contentOrNull
                if (selected == null) settings.deleteSetting(localKey)
                else settings.insertSetting(SystemSettingEntity(localKey, selected))
            }
            "memory_switches" -> {
                value["memoryEnabled"]?.jsonPrimitive?.contentOrNull?.let { settings.insertSetting(SystemSettingEntity("memory_enabled", it)) }
                value["autoCaptureEnabled"]?.jsonPrimitive?.contentOrNull?.let { settings.insertSetting(SystemSettingEntity("memory_auto_capture_enabled", it)) }
            }
            "web_search_configs" -> restoreWebSearch(setting, value)
            "api_configs" -> restoreApiConfig(setting, value)
            "voice_configs" -> restoreVoiceConfig(setting, value)
            "mcp_servers" -> restoreMcp(setting, value)
            "skills" -> restoreSkill(setting, value)
            "conversation_settings" -> restoreConversationSettings(setting, value)
            "voice_custom_models" -> {
                val parts = setting.key.split(':', limit = 2)
                if (parts.size == 2 && parts[0] in setOf("stt", "chat", "tts")) {
                    val models = value["models"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    settings.insertSetting(SystemSettingEntity("custom_models_${parts[0]}_${parts[1]}", models.joinToString(",")))
                }
            }
            "voice_llm_platforms" -> {
                val platform = value["platform"]?.jsonPrimitive?.contentOrNull ?: setting.key
                if (platform.isNotBlank()) settings.insertSetting(SystemSettingEntity("voice_llm_platform:$platform", "true"))
            }
            "computers" -> restoreComputer(setting, value)
            "computer_workspaces" -> restoreWorkspace(setting, value)
            "skill_variables" -> Unit
        }
    }

    /** 引用尚未拉到时保留同步记录，后续分页结束再应用；不写本地变更入口。 */
    private suspend fun restoreConversationSettings(setting: UserSettingEntity, value: JsonObject) {
        val session = chat.getSessionByUuid(setting.key)?.takeUnless { it.deleted } ?: return
        suspend fun merge(localKey: String, element: JsonElement) {
            val old = settings.getValue(localKey)?.let { runCatching { syncJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            val merged = if (element == JsonNull) old.orEmpty() - session.id
                else old.orEmpty() + (session.id to if (element is JsonObject) JsonObject((old?.get(session.id) as? JsonObject).orEmpty() + element) else element)
            settings.insertSetting(SystemSettingEntity(localKey, JsonObject(merged).toString()))
        }
        value["modelId"]?.let { merge("conversation_api_config_ids", it) }
        val switches = value.filterKeys { it in setOf("webSearchEnabled", "codeExecutionEnabled", "mcpEnabled", "agentEnabled") }
        if (switches.isNotEmpty()) merge("conversation_function_toggle_states", JsonObject(switches))
        value["computerId"]?.jsonPrimitive?.contentOrNull?.let { computerId ->
            if (database.computerDao().getComputer(computerId) != null) {
                database.computerDao().upsertSelection(
                    com.android.everytalk.data.database.entities.ConversationComputerSelectionEntity(
                        conversationId = session.id,
                        selectedComputerId = computerId,
                        updatedAt = setting.updatedAt,
                    ),
                )
            }
        }
        if (value["computerId"] == JsonNull) database.computerDao().clearSelection(session.id)
    }

    private suspend fun restoreComputer(setting: UserSettingEntity, value: JsonObject) {
        val old = database.computerDao().getAllSnapshot().firstOrNull { it.id == setting.key }
        fun text(name: String, fallback: String = "") = value[name]?.jsonPrimitive?.contentOrNull ?: fallback
        val base = old ?: com.android.everytalk.data.computer.Computer(
            id = setting.key, displayName = text("displayName"), host = text("host"), port = 22,
            username = text("username"), authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson)
        database.computerDao().upsertComputer(base.copy(
            displayName = text("displayName", base.displayName), provider = text("provider", base.provider),
            host = text("host", base.host), port = value["port"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 } ?: base.port,
            username = text("username", base.username), authKind = text("authKind", base.authKind),
            runMode = text("runMode", base.runMode), sandboxImage = text("sandboxImage", base.sandboxImage.orEmpty()).ifEmpty { null },
            allowPrivateNetwork = value["allowPrivateNetwork"]?.jsonPrimitive?.booleanOrNull ?: base.allowPrivateNetwork,
            permissionMode = text("permissionMode", base.permissionMode), createdAt = value["createdAt"]?.jsonPrimitive?.longOrNull ?: base.createdAt,
            updatedAt = setting.updatedAt,
        ))
    }

    private suspend fun restoreWorkspace(setting: UserSettingEntity, value: JsonObject) {
        val computerId = value["computerId"]?.jsonPrimitive?.contentOrNull ?: return
        if (database.computerDao().getAllSnapshot().none { it.id == computerId }) return
        val sessionId = value["sessionId"]?.jsonPrimitive?.contentOrNull?.let { chat.getSessionByUuid(it)?.id } ?: return
        val localId = value["localId"]?.jsonPrimitive?.contentOrNull ?: setting.key
        val old = database.computerDao().getWorkspace(computerId, sessionId)
        val next = (old ?: ComputerWorkspaceEntity(localId, computerId, sessionId, "DIRECT", "", null, null, "STOPPED", setting.updatedAt, 0)).copy(
            runMode = value["runMode"]?.jsonPrimitive?.contentOrNull ?: old?.runMode ?: "DIRECT",
            containerImage = value["containerImage"]?.jsonPrimitive?.contentOrNull ?: old?.containerImage,
            createdAt = value["createdAt"]?.jsonPrimitive?.longOrNull ?: old?.createdAt ?: setting.updatedAt,
        )
        // 本机工作目录、容器名称和凭据不来自云端；已有设备的这些字段保持原值。
        database.computerDao().upsertWorkspace(next)
    }

    private suspend fun restoreApiConfig(setting: UserSettingEntity, value: JsonObject) {
        val old = database.apiConfigDao().getConfigs(value["imageConfig"]?.jsonPrimitive?.booleanOrNull == true).firstOrNull { it.id == setting.key }
        val parameters = value["modelParameters"] as? JsonObject
        val customParameters = when (val entries = parameters?.get("customParameters")) {
            null -> old?.modelParameters?.customParameters
            JsonNull -> null
            else -> (entries as? JsonArray)?.mapIndexedNotNull { index, item ->
                val parameter = item as? JsonObject ?: return@mapIndexedNotNull null
                val name = parameter["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                val raw = parameter["value"]?.let {
                    decryptedJson(setting, it, "/modelParameters/customParameters/$index/value")?.jsonPrimitive?.contentOrNull
                }
                // 无法解密时只保留本机同名参数，不能把密文或空值当作凭据写进请求。
                if (raw == null) old?.modelParameters?.customParameters?.find { it.name == name }
                else CustomModelParameter(name, raw,
                    parameter["type"]?.jsonPrimitive?.contentOrNull?.let { runCatching { CustomParameterType.valueOf(it) }.getOrNull() } ?: CustomParameterType.STRING,
                    parameter["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)
            }
        }
        val modelParameters = value["modelParameters"]?.jsonObject?.let { params ->
            (old?.modelParameters ?: ModelParameters()).copy(
                apiProtocolOverride = params["protocolOverride"]?.jsonPrimitive?.contentOrNull?.let { name ->
                    ModelParameterProtocol.entries.firstOrNull { it.name == name }
                },
                reasoningMode = params["reasoningMode"]?.jsonPrimitive?.contentOrNull?.let { name ->
                    ReasoningMode.entries.firstOrNull { it.name == name }
                } ?: old?.modelParameters?.reasoningMode ?: ReasoningMode.EFFORT,
                reasoningEffort = params["reasoningEffort"]?.jsonPrimitive?.contentOrNull ?: (old?.modelParameters ?: ModelParameters()).reasoningEffort,
                thinkingBudget = params["thoughtBudget"]?.jsonPrimitive?.intOrNull ?: (old?.modelParameters ?: ModelParameters()).thinkingBudget,
                maxContextTokens = params["contextLimit"]?.jsonPrimitive?.intOrNull ?: (old?.modelParameters ?: ModelParameters()).maxContextTokens,
                autoContextCompressionEnabled = params["autoCompressionEnabled"]?.jsonPrimitive?.booleanOrNull ?: (old?.modelParameters ?: ModelParameters()).autoContextCompressionEnabled,
                autoContextCompressionThresholdPercent = params["autoCompressionThreshold"]?.jsonPrimitive?.intOrNull ?: (old?.modelParameters ?: ModelParameters()).autoContextCompressionThresholdPercent,
                customParameters = customParameters,
                customReasoningEfforts = (params["customReasoningEfforts"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: old?.modelParameters?.customReasoningEfforts.orEmpty(),
            )
        } ?: old?.modelParameters ?: ModelParameters()
        val key = decryptedString(setting, value, "apiKey", "/apiKey") ?: old?.key ?: ""
        val toolDefinitions = if (value["toolDefinitions"] == JsonNull) null
            else value["toolDefinitions"]?.let { decryptedJson(setting, it, "/toolDefinitions")?.toString() } ?: old?.toolsJson
        database.apiConfigDao().insertConfigs(listOf(ApiConfigEntity(
            id = setting.key, address = value["address"]?.jsonPrimitive?.contentOrNull ?: old?.address.orEmpty(), key = key,
            model = value["model"]?.jsonPrimitive?.contentOrNull ?: old?.model.orEmpty(), provider = value["provider"]?.jsonPrimitive?.contentOrNull ?: old?.provider.orEmpty(),
            name = value["name"]?.jsonPrimitive?.contentOrNull ?: old?.name.orEmpty(), channel = value["channel"]?.jsonPrimitive?.contentOrNull ?: old?.channel.orEmpty(),
            isValid = old?.isValid ?: false, modalityType = value["modality"]?.jsonPrimitive?.contentOrNull?.let { runCatching { ModalityType.valueOf(it) }.getOrNull() } ?: old?.modalityType ?: ModalityType.TEXT,
            temperature = value["temperature"]?.jsonPrimitive?.floatOrNull ?: old?.temperature ?: 0.7f,
            topP = if ("topP" in value) value["topP"]?.jsonPrimitive?.floatOrNull else old?.topP,
            maxTokens = if ("outputLimit" in value) value["outputLimit"]?.jsonPrimitive?.intOrNull else old?.maxTokens,
            defaultUseWebSearch = if ("defaultUseWebSearch" in value) value["defaultUseWebSearch"]?.jsonPrimitive?.booleanOrNull else old?.defaultUseWebSearch,
            imageSize = if ("imageSize" in value) value["imageSize"]?.jsonPrimitive?.contentOrNull else old?.imageSize,
            numInferenceSteps = if ("inferenceSteps" in value) value["inferenceSteps"]?.jsonPrimitive?.intOrNull else old?.numInferenceSteps,
            guidanceScale = if ("guidanceScale" in value) value["guidanceScale"]?.jsonPrimitive?.floatOrNull else old?.guidanceScale, modelParameters = modelParameters,
            toolsJson = toolDefinitions,
            enableCodeExecution = if ("codeExecutionEnabled" in value) value["codeExecutionEnabled"]?.jsonPrimitive?.booleanOrNull else old?.enableCodeExecution,
            isImageGenConfig = value["imageConfig"]?.jsonPrimitive?.booleanOrNull ?: old?.isImageGenConfig ?: false,
        )))
    }

    private suspend fun restoreWebSearch(setting: UserSettingEntity, value: JsonObject) {
        val rows = settings.getValue("external_web_search_configs")?.let { runCatching { syncJson.decodeFromString<List<ExternalWebSearchProviderConfig>>(it) }.getOrNull() }.orEmpty().toMutableList()
        val old = rows.firstOrNull { it.providerId == setting.key }
        val apiKey = decryptedString(setting, value, "apiKey", "/apiKey") ?: old?.apiKey.orEmpty()
        val next = ExternalWebSearchProviderConfig(setting.key, apiKey)
        rows.removeAll { it.providerId == setting.key }; rows += next
        settings.insertSetting(SystemSettingEntity("external_web_search_configs", syncJson.encodeToString(rows)))
    }

    private suspend fun restoreVoiceConfig(setting: UserSettingEntity, value: JsonObject) {
        val old = database.voiceConfigDao().getAll().firstOrNull { it.id == setting.key }
        fun text(name: String, fallback: String = "") = value[name]?.jsonPrimitive?.contentOrNull ?: fallback
        database.voiceConfigDao().insertAll(listOf(VoiceBackendConfigEntity(
            id = setting.key, name = text("name", old?.name.orEmpty()), provider = text("provider", old?.provider.orEmpty()),
            sttPlatform = text("sttPlatform", old?.sttPlatform.orEmpty()), sttApiKey = decryptedString(setting, value, "sttApiKey", "/sttApiKey") ?: old?.sttApiKey.orEmpty(), sttApiUrl = text("sttEndpoint", old?.sttApiUrl.orEmpty()), sttModel = text("sttModel", old?.sttModel.orEmpty()),
            chatPlatform = text("chatPlatform", old?.chatPlatform.orEmpty()), chatApiKey = decryptedString(setting, value, "chatApiKey", "/chatApiKey") ?: old?.chatApiKey.orEmpty(), chatApiUrl = text("chatEndpoint", old?.chatApiUrl.orEmpty()), chatModel = text("chatModel", old?.chatModel.orEmpty()),
            ttsPlatform = text("ttsPlatform", old?.ttsPlatform.orEmpty()), ttsApiKey = decryptedString(setting, value, "ttsApiKey", "/ttsApiKey") ?: old?.ttsApiKey.orEmpty(), ttsApiUrl = text("ttsEndpoint", old?.ttsApiUrl.orEmpty()), ttsModel = text("ttsModel", old?.ttsModel.orEmpty()),
            voiceName = text("voiceName", old?.voiceName.orEmpty()), useRealtimeStreaming = value["realtimeStreaming"]?.jsonPrimitive?.booleanOrNull ?: old?.useRealtimeStreaming ?: false,
            isValid = old?.isValid ?: false, createdAt = value["createdAt"]?.jsonPrimitive?.longOrNull ?: old?.createdAt ?: setting.updatedAt, updatedAt = setting.updatedAt,
        )))
    }

    private suspend fun restoreMcp(setting: UserSettingEntity, value: JsonObject) {
        // 合同要求 mcp_servers 的 key 是 UUID；localId 只用于把配置恢复到本机原有记录。
        val localId = value["localId"]?.jsonPrimitive?.contentOrNull ?: setting.key
        val old = database.mcpConfigDao().getConfigById(localId)
        val headers = value["headers"]?.let { decryptedJson(setting, it, "/headers")?.toString() } ?: old?.headers ?: "[]"
        // 未知传输类型不能写入后让 Room 观察者在 valueOf 处永久退出。
        val transport = value["transport"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("SSE", "HTTP") }
            ?: old?.transportType ?: "SSE"
        val endpoint = value["endpoint"]?.jsonPrimitive?.contentOrNull?.let(::syncSafeEndpoint) ?: old?.url.orEmpty()
        database.mcpConfigDao().insertConfig(McpServerConfigEntity(localId, value["name"]?.jsonPrimitive?.contentOrNull ?: old?.name.orEmpty(), endpoint, transport, value["enabled"]?.jsonPrimitive?.booleanOrNull ?: old?.enabled ?: true, headers))
    }

    private suspend fun restoreSkill(setting: UserSettingEntity, value: JsonObject) {
        val old = database.skillDao().getInstallation(setting.key)
        database.skillDao().upsertInstallation(SkillInstallationEntity(
            skillId = setting.key, name = value["name"]?.jsonPrimitive?.contentOrNull ?: old?.name.orEmpty(),
            description = value["description"]?.jsonPrimitive?.contentOrNull ?: old?.description.orEmpty(),
            sourceType = value["sourceType"]?.jsonPrimitive?.contentOrNull ?: old?.sourceType.orEmpty(),
            sourceRepository = value["sourceRepository"]?.jsonPrimitive?.contentOrNull?.let(::syncSafeSkillRepository) ?: old?.sourceRepository,
            sourcePath = value["repositoryPath"]?.jsonPrimitive?.contentOrNull?.takeIf {
                !it.startsWith('/') && !it.contains(':') && !it.contains('\\') && it.split('/').none { part -> part == ".." }
            } ?: old?.sourcePath,
            currentHash = value["contentHash"]?.jsonPrimitive?.contentOrNull ?: old?.currentHash.orEmpty(),
            enabled = value["enabled"]?.jsonPrimitive?.booleanOrNull ?: old?.enabled ?: true,
            invocationMode = value["invocationMode"]?.jsonPrimitive?.contentOrNull ?: old?.invocationMode.orEmpty(),
            updateHash = old?.updateHash, createdAt = value["installedAt"]?.jsonPrimitive?.longOrNull ?: old?.createdAt ?: setting.updatedAt,
            updatedAt = setting.updatedAt, lastUsedAt = old?.lastUsedAt, useCount = old?.useCount ?: 0L,
            packageId = value["packageId"]?.jsonPrimitive?.contentOrNull ?: old?.packageId.orEmpty(),
            packageName = value["packageName"]?.jsonPrimitive?.contentOrNull ?: old?.packageName.orEmpty(),
        ))
    }

    private suspend fun decryptedString(setting: UserSettingEntity, value: JsonObject, name: String, pointer: String): String? =
        value[name]?.let { element -> (if (element is JsonPrimitive) element else decryptedJson(setting, element, pointer)) as? JsonPrimitive }?.contentOrNull

    private suspend fun decryptedJson(setting: UserSettingEntity, value: JsonElement, pointer: String): JsonElement? =
        if (value is JsonObject && value.containsKey("\$enc")) {
            // 未解锁仅跳过机密字段，不能把信封当明文解析并阻塞普通数据的拉取。
            if (secrets != null && userId != null) secrets.decryptField(userId, setting.kind, setting.key, pointer, state.readSecretsEpoch().coerceAtLeast(0L), value) else null
        } else value

    override suspend fun pendingBatch(maxItems: Int): List<PendingChangeSnapshot> {
        require(maxItems in 1..500)
        SyncSettingsProjector(
            database = database,
            secrets = secrets,
            userId = userId,
            secretsEpoch = state.readSecretsEpoch().coerceAtLeast(0L),
        ).refresh()
        // 若整批都是超限记录，继续取后面的合法条目，不能让坏数据饿死后续历史。
        while (true) {
        val snapshots = ArrayList<PendingChangeSnapshot>(maxItems)
        var advancedPendingQueue = false
        chat.getPendingSyncSessions(maxItems).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.session(it), SyncEntity.SESSIONS, it.id, it.updatedAt)
        }
        if (snapshots.size < maxItems) {
            val pendingMessages = chat.getPendingSyncMessageRows(maxItems - snapshots.size)
            pendingMessages.forEach {
            val message = it.toSyncEntity()
            val session = chat.getStoredSession(message.sessionId) ?: return@forEach
            val change = messageChangeForPush(message, session.uuid)
            if (change == null) {
                // 远端已经拥有这条消息的全部可同步补充字段，本地的 PENDING 只是旧版本遗留状态。
                    advancedPendingQueue = chat.markMessageSyncedIfVersion(message.uuid, message.updatedAt) > 0 || advancedPendingQueue
            } else {
                snapshots += PendingChangeSnapshot(change, SyncEntity.MESSAGES, message.id, message.updatedAt)
            }
            }
        }
        if (snapshots.size < maxItems) memory.getPendingSyncMemories(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.memory(it,
                sourceSessionUuid(it.sourceConversationId), sourceMessageUuid(it.sourceMessageId)),
                SyncEntity.MEMORIES, it.id, it.clientModifiedAt)
        }
        if (snapshots.size < maxItems) settings.getPendingUserSettings(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.userSetting(it), SyncEntity.USER_SETTINGS, it.uuid, it.updatedAt)
        }
        if (snapshots.size < maxItems) attachments.getPending(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(attachmentChange(it), SyncEntity.ATTACHMENTS, it.id, it.updatedAt)
        }
        // 用序列化后的 UTF-8 字节计数，同时限制总请求和单条消息，不能仅按条数估算。
        val batch = ArrayList<PendingChangeSnapshot>()
        var bytes = 14 // {"changes":[]} 的固定开销加余量。
        for (snapshot in snapshots) {
            val size = syncJson.encodeToString(PushChange.serializer(), snapshot.change).toByteArray().size
            val payloadBytes = snapshot.change.payload.toString().toByteArray().size
            if (size > 1024 * 1024 - 14 || (snapshot.entity == SyncEntity.MESSAGES && payloadBytes > maxMessagePayloadBytes)) {
                reject(snapshot, "MESSAGE_PAYLOAD_TOO_LARGE")
                advancedPendingQueue = true
                continue
            }
            if (bytes + size + 1 > 1024 * 1024) break
            batch += snapshot
            bytes += size + 1
        }
        if (batch.isNotEmpty() || !advancedPendingQueue) return batch
        }
    }

    /**
     * 思考过程和统计字段属于可选补充字段。它们加起来超过服务器上限时，
     * 按顺序从网络 payload 移除，保留本地完整数据和消息正文；后续仍可补传未发送的字段。
     * 若核心正文本身就超限，继续走明确的 MESSAGE_PAYLOAD_TOO_LARGE 拒绝路径。
     */
    private fun fitMessageSnapshot(snapshot: PendingChangeSnapshot): PendingChangeSnapshot {
        if (snapshot.entity != SyncEntity.MESSAGES || snapshot.change.deleted) return snapshot
        var payload = snapshot.change.payload
        if (payload.toString().toByteArray().size <= maxMessagePayloadBytes) return snapshot
        for (field in optionalMessageFieldsForSize) {
            if (!payload.containsKey(field)) continue
            payload = JsonObject(payload.filterKeys { it != field })
            if (payload.toString().toByteArray().size <= maxMessagePayloadBytes) break
        }
        return snapshot.copy(change = snapshot.change.copy(payload = payload))
    }

    private fun fitMessageChange(change: PushChange): PushChange =
        fitMessageSnapshot(PendingChangeSnapshot(change, SyncEntity.MESSAGES, change.id, 0L)).change

    /**
     * 构造当前消息真正要发送的变更。
     * 新消息先发送完整核心字段；服务器已有该 UUID 时，只发送远端尚未拥有的补充字段，
     * 这样 reasoning/tokenUsage 等大字段可以单独走 additive merge，不会再次修改正文。
     */
    private suspend fun messageChangeForPush(message: MessageEntity, sessionUuid: String): PushChange? {
        val full = SyncPayloadMapper.message(message, sessionUuid)
        val archiveReady = settings.getValue("sync_resource_source:${message.uuid}") != null
        if (archiveReady && full.payload.toString().toByteArray().size > maxMessagePayloadBytes) {
            // 完整内容已通过加密文件持久化。普通消息仅保存稳定索引，绝不截断正文或制造新 UUID。
            return full.copy(payload = JsonObject(full.payload.filterKeys { it in setOf("sessionId", "sender", "timestamp", "name", "outputType", "isError", "modelName", "providerName") }))
        }
        if (message.deleted || !message.everSynced) return fitMessageChange(full)
        val remoteFields = settings.getValue(remoteMessageFieldsKey(message.uuid))
            ?.split(',')?.filter(String::isNotBlank)?.toSet() ?: return fitMessageChange(full)
        val missing = full.payload.keys.intersect(messageSupplementFields).filterNot(remoteFields::contains).toSet()
        if (missing.isEmpty()) return null
        // 补充字段仍是一条 messages 变更；服务端每次都校验 sessionId，不能省略。
        return full.copy(payload = JsonObject(full.payload.filterKeys { it in missing || it == "sessionId" }))
    }

    private fun remoteMessageFieldsKey(uuid: String): String = "sync_message_fields:$uuid"

    private suspend fun attachmentChange(value: SyncAttachmentEntity): PushChange {
        val change = SyncPayloadMapper.attachment(value)
        val metadata = settings.getValue("sync_resource_meta:${value.id}")?.let { runCatching { syncJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        val extra = metadata?.filterKeys { it in setOf("role", "resourceDigest", "resourceVersion", "chunkIndex", "chunkCount", "keyVersion", "mediaId", "originalSize", "originalDigest", "resultPath") }.orEmpty()
        return change.copy(payload = JsonObject(change.payload + extra))
    }

    /** 来源记录允许先后乱序到达：cloud: 前缀携带 UUID，不能因为尚未拉到正文丢掉来源。 */
    private suspend fun sourceSessionUuid(id: String?): String? = id?.let {
            chat.getStoredSession(it)?.uuid
            ?: it.takeIf { value -> value.startsWith("cloud:") }?.removePrefix("cloud:")
    }
    private suspend fun sourceMessageUuid(id: String?): String? = id?.let {
        chat.getStoredMessage(it, "")?.uuid ?: it.takeIf { value -> value.startsWith("cloud:") }?.removePrefix("cloud:")
    }

    override suspend fun reservePush(snapshots: List<PendingChangeSnapshot>) {
        chat.reserveUploadingMessages(snapshots.filter { it.entity == SyncEntity.MESSAGES }.map { it.change.id })
    }

    /** 以时间版本和实际白名单内容双重比对；同毫秒写入的新修改也不会被旧响应确认。 */
    private suspend fun stillCurrent(s: PendingChangeSnapshot): Boolean = when (s.entity) {
        SyncEntity.SESSIONS -> chat.getSessionByUuid(s.change.id)?.let {
            it.updatedAt == s.version && SyncPayloadMapper.session(it) == s.change
        } ?: false
        SyncEntity.MESSAGES -> chat.getSyncMessageByUuid(s.change.id)?.let {
            val message = it.toSyncEntity()
            val session = chat.getStoredSession(message.sessionId)
            message.updatedAt == s.version && session != null && messageChangeForPush(message, session.uuid) == s.change
        } ?: false
        SyncEntity.MEMORIES -> memory.getByUuid(s.change.id)?.let {
            it.clientModifiedAt == s.version && SyncPayloadMapper.memory(it,
                sourceSessionUuid(it.sourceConversationId), sourceMessageUuid(it.sourceMessageId)) == s.change
        } ?: false
        SyncEntity.USER_SETTINGS -> settings.getUserSettingByUuid(s.change.id)?.let {
            it.updatedAt == s.version && SyncPayloadMapper.userSetting(it) == s.change
        } ?: false
        SyncEntity.ATTACHMENTS -> attachments.get(s.change.id)?.let {
            it.updatedAt == s.version && attachmentChange(it) == s.change
        } ?: false
    }

    override suspend fun applyPush(response: PushResponse, snapshots: List<PendingChangeSnapshot>) = database.withTransaction {
        // 缺结果、重复 index 或陌生 status 属于合同错误：保留待同步，不误标成功。
        require(response.results.size == snapshots.size && response.results.map { it.index }.toSet() == snapshots.indices.toSet())
        response.results.forEach { result ->
            val s = snapshots[result.index]
            require(result.status in setOf("accepted", "ignored", "unchanged", "rejected"))
            if (result.status == "rejected") {
                val code = requireNotNull(result.error).code
                if (code == "session_deleted" && s.entity == SyncEntity.MESSAGES) {
                    chat.discardMessageByUuid(s.change.id)
                    settings.deleteSetting(rejectionKey(s))
                } else if (code == "SECRETS_EPOCH_MISMATCH") {
                    // 保留 PENDING，下一轮先 pull 最新密钥代次或重新计算可选字段后再试。
                    // 这类错误不能进入永久失败列表，也不能让有效变更被整批阻塞。
                    settings.deleteSetting(rejectionKey(s))
                } else reject(s, code)
                return@forEach
            }
            require(result.id == s.change.id && result.entity == s.change.entity && result.serverSeq != null)
            val unchanged = stillCurrent(s)
            if (s.entity == SyncEntity.USER_SETTINGS && result.canonicalId != null) {
                validateUuid(result.canonicalId)
                settings.getUserSettingByUuid(s.change.id)?.let {
                    // canonical_id 必须采用，即使内容已经有新修改；新内容保持 PENDING。
                    settings.upsertUserSettings(listOf(it.copy(uuid = result.canonicalId)))
                }
            }
            if (unchanged) when (s.entity) {
                SyncEntity.SESSIONS -> chat.markSessionSyncedIfVersion(s.change.id, s.version)
                SyncEntity.MESSAGES -> chat.markMessageSyncedIfVersion(s.change.id, s.version)
                SyncEntity.MEMORIES -> memory.markSyncedIfVersion(s.change.id, s.version)
                SyncEntity.USER_SETTINGS -> settings.markUserSettingSyncedIfVersion(result.canonicalId ?: s.change.id, s.version)
                SyncEntity.ATTACHMENTS -> attachments.markSyncedIfVersion(s.change.id, s.version)
            }
            if (unchanged && result.status == "ignored" && s.entity != SyncEntity.MESSAGES) {
                // 首次 pull 可能因本地 PENDING 而暂存了更旧的远端版本；服务器判旧写入
                // ignored 后不会分配新序号，必须重放权威结果，否则两台会永远各显示各的。
                settings.deleteSetting(SyncStateStore.KEY_CURSOR)
            }
            settings.deleteSetting(rejectionKey(s))
        }
    }

    private suspend fun reject(snapshot: PendingChangeSnapshot, code: String) = database.withTransaction {
        if (!stillCurrent(snapshot)) return@withTransaction
        when (snapshot.entity) {
            SyncEntity.SESSIONS -> chat.markSessionRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.MESSAGES -> chat.markMessageRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.MEMORIES -> memory.markRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.USER_SETTINGS -> settings.markUserSettingRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.ATTACHMENTS -> attachments.markRejectedIfVersion(snapshot.change.id, snapshot.version)
        }
        settings.insertSetting(SystemSettingEntity(rejectionKey(snapshot), code))
    }

    /** 一页记录和 next_cursor 原子提交；任何写入失败都不推进游标，不静默丢弃数据。 */
    override suspend fun applyPullPage(response: PullResponse) {
      // 让直接调用该入口的同步测试和恢复路径也具备同样的版本保护。
      preparePullPage()
      database.withTransaction {
        response.changes.sortedBy { it.serverSeq }.forEach { record ->
            validateUuid(record.id)
            require(record.serverSeq > 0)
            when (record.entity) {
                "sessions" -> applySession(record)
                "messages" -> applyMessage(record)
                "memories" -> applyMemory(record)
                "user_settings" -> applyUserSetting(record)
                "attachments" -> applyAttachment(record)
                else -> error("未知同步实体")
            }
        }
        projectSettings()
        settings.getSyncableUserSettings().filter { it.kind in setOf("conversation_settings", "computer_workspaces") }
            .forEach { restoreSetting(it) }
        state.saveCursor(response.nextCursor, response.epoch)
      }
      if (response.changes.isNotEmpty()) SyncLocalChanges.notifyChanged()
    }

    /** 服务端裁剪未来时间；本地比较也用服务端 updated_at 作为上限，不能用设备当前时间。 */
    private fun modified(record: SyncRecord): Long {
        val value = SyncPayloadMapper.long(record.payload, "clientModifiedAt")
        require(value in 0..9007199254740991L)
        return record.updatedAt?.let { minOf(value, Instant.parse(it).toEpochMilli()) } ?: value
    }

    private suspend fun applySession(record: SyncRecord) {
        val old = chat.getSessionByUuid(record.id)
        val version = modified(record)
        if (old?.deleted == true && !record.deleted) return
        // 删除会话是不可逆事实，无论本地改标题的时间都必须清理消息。
        if (!record.deleted && old?.syncStatus == SyncStatus.PENDING && old.updatedAt > version) return
        val candidate = ChatSessionEntity(
            id = old?.id ?: "cloud:${record.id}",
            creationTimestamp = SyncPayloadMapper.long(record.payload, "creationTimestamp", old?.creationTimestamp ?: 0),
            lastModifiedTimestamp = SyncPayloadMapper.long(record.payload, "lastModifiedTimestamp", old?.lastModifiedTimestamp ?: version),
            isImageGeneration = SyncPayloadMapper.boolean(record.payload, "isImageGeneration"),
            title = if (record.payload.containsKey("title")) SyncPayloadMapper.string(record.payload, "title") else old?.title,
            uuid = record.id, updatedAt = version, deleted = record.deleted, syncStatus = SyncStatus.SYNCED,
        )
        if (old == null) {
            chat.insertRemoteSession(candidate)
            // IGNORE 不代表成功；旧库主键冲突时必须回滚整页，不能带着缺行推进游标。
            check(chat.getSessionByUuid(record.id) != null) { "SYNC_SESSION_IDENTITY_CONFLICT" }
        } else chat.updateRemoteSession(candidate)
        if (record.deleted) {
            chat.deletePendingMessagesForConversation(candidate.id)
            chat.deleteDrafts(candidate.id, chat.getDrafts(candidate.id).map { it.message.id })
            chat.deleteMessagesForRemoteDeletedSession(candidate.id)
        }
    }

    private suspend fun applyMessage(record: SyncRecord) {
        if (record.deleted) {
            chat.markRemoteMessageDeleted(record.id)
            // 没有内容的墓碑也必须保留，避免乱序重放把消息复活。
            settings.insertSetting(SystemSettingEntity("sync_message_deleted:${record.id}", "true"))
            return
        }
        if (settings.getValue("sync_message_deleted:${record.id}") != null) return
        val sessionUuid = requireNotNull(SyncPayloadMapper.string(record.payload, "sessionId"))
        validateUuid(sessionUuid)
        val timestamp = SyncPayloadMapper.long(record.payload, "timestamp")
        val session = chat.getSessionByUuid(sessionUuid) ?: ChatSessionEntity(
            id = "cloud:$sessionUuid", creationTimestamp = timestamp, lastModifiedTimestamp = timestamp,
            isImageGeneration = false, uuid = sessionUuid, updatedAt = 0, syncStatus = SyncStatus.SYNCED,
        ).also {
            chat.insertRemoteSession(it)
            check(chat.getSessionByUuid(sessionUuid) != null) { "SYNC_SESSION_IDENTITY_CONFLICT" }
        }
        if (session.deleted) return
        val sender = Sender.valueOf(requireNotNull(SyncPayloadMapper.string(record.payload, "sender")))
        require(sender in setOf(Sender.User, Sender.AI, Sender.System, Sender.Tool))
        fun <T> optional(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
            record.payload[name]?.let { runCatching { syncJson.decodeFromJsonElement(serializer, it) }.getOrNull() }
        val message = Message(
            id = "cloud:${record.id}", text = SyncPayloadMapper.string(record.payload, "text").orEmpty(), sender = sender,
            reasoning = SyncPayloadMapper.string(record.payload, "reasoning"), contentStarted = true,
            isError = SyncPayloadMapper.boolean(record.payload, "isError"), name = SyncPayloadMapper.string(record.payload, "name"),
            timestamp = timestamp, outputType = SyncPayloadMapper.string(record.payload, "outputType") ?: "general",
            modelName = SyncPayloadMapper.string(record.payload, "modelName"), providerName = SyncPayloadMapper.string(record.payload, "providerName"),
            tokenUsage = optional("tokenUsage", TokenUsage.serializer()),
            contextUsageSnapshot = optional("contextUsageSnapshot", ContextUsageSnapshot.serializer())?.copy(messageId = "cloud:${record.id}"),
            executionFinishedAt = record.payload["executionFinishedAt"]?.jsonPrimitive?.longOrNull,
            enabledToolIds = record.payload["enabledToolIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            contentParts = (record.payload["bodySegments"] as? JsonArray)?.mapNotNull { part ->
                val item = part as? JsonObject ?: return@mapNotNull null
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> MessageContentPart.Text(item["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "skill" -> {
                        val skillId = item["skillId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        MessageContentPart.SkillReference(MessageSkillReference(skillId,
                            item["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            item["sourceType"]?.jsonPrimitive?.contentOrNull?.let { runCatching { SkillSourceType.valueOf(it) }.getOrNull() } ?: SkillSourceType.LOCAL_IMPORT,
                            contentHash = item["contentHash"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    }
                    else -> null
                }
            }.orEmpty(),
            executionTrace = record.payload["displaySegments"]?.jsonArray?.mapNotNull { segment ->
                val part = segment.jsonObject
                val type = part["type"]?.jsonPrimitive?.contentOrNull
                val source = SyncPayloadMapper.string(record.payload, if (type == "reasoning") "reasoning" else "text").orEmpty()
                val start = part["startUtf16"]?.jsonPrimitive?.intOrNull
                val length = part["lengthUtf16"]?.jsonPrimitive?.intOrNull
                // 兼容旧版完整 text；引用按 Android 字符串的 UTF-16 下标校验，先减后比避免溢出。
                val text = part["text"]?.jsonPrimitive?.contentOrNull ?: if (start != null && length != null &&
                    start in 0..source.length && length >= 0 && length <= source.length - start) source.substring(start, start + length)
                    else return@mapNotNull null
                when (type) {
                    "content" -> ExecutionTraceEvent.Content(text)
                    "reasoning" -> ExecutionTraceEvent.Reasoning(text)
                    else -> null
                }
            }.orEmpty(),
            conversationId = session.id,
        ).toEntity(session.id).copy(uuid = record.id, updatedAt = timestamp, syncStatus = SyncStatus.SYNCED, everSynced = true)
        val remoteFieldsBefore = settings.getValue(remoteMessageFieldsKey(record.id))
            ?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()
        val old = chat.getMessageByUuid(record.id)
        if (old == null) {
            chat.insertRemoteMessage(message)
            check(chat.getSyncMessageByUuid(record.id) != null) { "SYNC_MESSAGE_IDENTITY_CONFLICT" }
        } else {
            // 补充合并只填缺失字段。旧版留下的云端空白占位允许用服务器正文补齐，
            // 但不能覆盖本机原件、待上传编辑或已恢复的完整归档。
            val supplemented = old.copy(
                text = if (old.id.startsWith("cloud:") && old.syncStatus == SyncStatus.SYNCED && old.text.isEmpty() &&
                    settings.getValue("sync_resource_restored:${old.uuid}") == null) message.text else old.text,
                reasoning = old.reasoning ?: message.reasoning,
                tokenUsage = old.tokenUsage ?: message.tokenUsage,
                contextUsageSnapshot = old.contextUsageSnapshot ?: message.contextUsageSnapshot?.copy(messageId = old.id),
                executionFinishedAt = old.executionFinishedAt ?: message.executionFinishedAt,
                enabledToolIds = old.enabledToolIds.ifEmpty { message.enabledToolIds },
                executionTrace = old.executionTrace.ifEmpty { message.executionTrace },
                contentParts = if (old.contentParts.any { it is MessageContentPart.SkillReference }) old.contentParts
                    else message.contentParts.ifEmpty { old.contentParts },
            )
            if (supplemented != old) chat.writeMessages(listOf(supplemented.toMessage().toEntity(old.sessionId).copy(
                uuid = old.uuid, updatedAt = old.updatedAt, deleted = old.deleted,
                syncStatus = old.syncStatus, everSynced = old.everSynced,
            )))
            // 旧版已标记同步、实际却没有上传的文字/统计，在重放时按补充合并重新入队。
            // 仅补服务器没有的字段；已有字段不一致时不自动重传，避免触发不可变冲突。
            val outgoing = SyncPayloadMapper.message(supplemented, sessionUuid).payload
            val supplementalFields = messageSupplementFields
            if (!old.deleted && old.syncStatus == SyncStatus.SYNCED &&
                supplementalFields.any { outgoing.containsKey(it) && it !in remoteFieldsBefore && it !in record.payload } &&
                // 已有补充字段不会再发送；旧版完整片段与新版位置引用的表示差异不能阻塞其他缺失字段。
                outgoing.filterKeys { it !in supplementalFields }.all { (name, value) -> !record.payload.containsKey(name) || syncSettingValuesEqual(value, record.payload.getValue(name)) }) {
                chat.markMessagePendingByUuid(record.id)
            }
        }
        val remoteFields = (remoteFieldsBefore + record.payload.keys).toList().sorted()
        settings.insertSetting(SystemSettingEntity(remoteMessageFieldsKey(record.id), remoteFields.joinToString(",")))
    }

    private suspend fun applyMemory(record: SyncRecord) {
        val old = memory.getByUuid(record.id)
        val version = modified(record)
        if (old?.deleted == true && !record.deleted) return
        if (!record.deleted && old?.syncStatus == SyncStatus.PENDING && old.clientModifiedAt > version) return
        val candidate = MemoryEntity(
            id = old?.id ?: "cloud:${record.id}",
            category = SyncPayloadMapper.string(record.payload, "category") ?: old?.category.orEmpty(),
            content = SyncPayloadMapper.string(record.payload, "content") ?: old?.content.orEmpty(),
            origin = SyncPayloadMapper.string(record.payload, "origin") ?: old?.origin.orEmpty(),
            sourceConversationId = SyncPayloadMapper.string(record.payload, "sourceConversationId")?.let { validateUuid(it); chat.getSessionByUuid(it)?.id ?: "cloud:$it" },
            sourceMessageId = SyncPayloadMapper.string(record.payload, "sourceMessageId")?.let { validateUuid(it); chat.getMessageByUuid(it)?.id ?: "cloud:$it" },
            createdAt = SyncPayloadMapper.long(record.payload, "createdAt", old?.createdAt ?: version),
            updatedAt = version, enabled = SyncPayloadMapper.boolean(record.payload, "enabled", old?.enabled ?: true),
            uuid = record.id, syncUpdatedAt = version, deleted = record.deleted, syncStatus = SyncStatus.SYNCED,
        )
        if (old == null) memory.insertRemote(candidate) else memory.updateRemote(candidate)
    }

    private suspend fun applyUserSetting(record: SyncRecord) {
        val kind = requireNotNull(SyncPayloadMapper.string(record.payload, "kind"))
        val key = requireNotNull(SyncPayloadMapper.string(record.payload, "key"))
        require(kind in REGISTERED_SETTING_KINDS && key.toByteArray().size in 1..1024)
        val old = settings.getUserSetting(kind, key)
        val version = modified(record)
        // 已同步的本地删除也是事实。旧的非删除快照不能把它复活；只有更晚的
        // 客户端修改版本才允许重新创建同一个配置。
        if (old?.deleted == true && !record.deleted && old.updatedAt >= version) {
            if (kind == "computers") {
                // 兼容墓碑写入后进程在删除业务行前被杀的窗口；旧云端快照到达时
                // 也必须把残留业务行清掉，不能只保护 user_settings 墓碑。
                database.computerDao().deleteComputer(
                    old.value.let { runCatching { syncJson.parseToJsonElement(it).jsonObject["localId"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                        ?: key,
                )
            }
            settings.upsertUserSettings(listOf(old.copy(uuid = record.id)))
            return
        }
        if (old?.syncStatus == SyncStatus.PENDING && old.updatedAt > version) {
            // 内容保留本地较新版本，但始终采用服务器逻辑键的 canonical id。
            settings.upsertUserSettings(listOf(old.copy(uuid = record.id)))
            return
        }
        val value = if (record.deleted) "{}" else requireNotNull(record.payload["value"]).toString()
        val setting = UserSettingEntity(
            kind, key, value, record.id, version, record.deleted, SyncStatus.SYNCED,
        )
        settings.upsertUserSettings(listOf(setting))
        // 普通配置必须在拉取时生效，不能等待用户进入已取消的同步密码流程。
        if (!record.deleted) restoreSetting(setting) else {
            when (kind) {
                "api_configs" -> database.apiConfigDao().deleteConfigById(key)
                "voice_configs" -> database.voiceConfigDao().deleteById(key)
                "mcp_servers" -> database.mcpConfigDao().deleteConfigById(
                    old?.value?.let { runCatching { syncJson.parseToJsonElement(it).jsonObject["localId"]?.jsonPrimitive?.contentOrNull }.getOrNull() } ?: key)
                "skills" -> database.skillDao().delete(key)
                // computers 是业务表，不是只读的设置投影。云端墓碑到达后必须删除
                // 本地实体；其外键会继续清理工作区、会话绑定和连接相关索引。
                "computers" -> database.computerDao().deleteComputer(
                    old?.value?.let { runCatching { syncJson.parseToJsonElement(it).jsonObject["localId"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                        ?: key,
                )
            }
        }
        if (record.deleted && kind == UserSettingKind.CONVERSATION_GROUPS) settings.deleteConversationGroup(key)
    }

    /** 每页完成后重建展示投影，解决设置先于会话到达；不调用本地保存逻辑。 */
    private suspend fun projectSettings() {
        val synced = settings.getSyncableUserSettings()
        val textPinned = synced.filter { it.kind == "pinned_items" }.mapNotNull {
            chat.getSessionByUuid(it.key)?.takeIf { session -> !session.deleted }?.id
        }
        // 墓碑同样是权威记录，不能把 cloud: 开头的旧置顶重新加回来。
        val allSettings = settings.getAllUserSettings()
        val pinKeys = allSettings.filter { it.kind == "pinned_items" }.mapTo(hashSetOf()) { it.key }
        val localPins = settings.getPinnedIds(false).filter { id ->
            chat.getStoredSession(id)?.let { !it.deleted && it.uuid !in pinKeys } == true
        }
        settings.replacePinnedItems(false, (textPinned + localPins).distinct().map { PinnedItemEntity(it, false) })
        val memberKeys = allSettings.filter { it.kind == "group_members" }.mapTo(hashSetOf()) { it.key }
        val localGroups = settings.getConversationGroups().map { group ->
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(group.groupName.toByteArray(Charsets.UTF_8))
            // 分组内新建的会话在首条消息前只有临时编号、尚未落库；不能当作失效成员删掉，
            // 否则首次保存迁移编号时找不到归属，会话会掉到普通列表。
            group.copy(conversationIds = group.conversationIds.filter { id ->
                chat.getStoredSession(id)?.let { !it.deleted && (it.isImageGeneration || "$encoded.${it.uuid}" !in memberKeys) } ?: true
            })
        }
        val legacyGroups = synced.filter { it.kind == "conversation_groups" }.mapNotNull { setting ->
            val rawIds = runCatching { syncJson.parseToJsonElement(setting.value) }.getOrNull()
            val uuids = when (rawIds) {
                is JsonArray -> rawIds.mapNotNull { it.jsonPrimitive.contentOrNull }
                is JsonObject -> rawIds["conversationIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                else -> emptyList()
            }
            val ids = uuids.mapNotNull { uuid ->
                chat.getSessionByUuid(uuid)?.takeIf { !it.deleted }?.id
            }
            if (ids.isEmpty() && uuids.isNotEmpty()) null
            else ConversationGroupEntity(setting.key, ids)
        }
        val grouped = synced.filter { it.kind == "group_members" }.mapNotNull { setting ->
            val value = runCatching { syncJson.parseToJsonElement(setting.value).jsonObject }.getOrNull() ?: return@mapNotNull null
            val conversationUuid = value["conversationId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val localId = chat.getSessionByUuid(conversationUuid)?.takeIf { !it.deleted }?.id ?: return@mapNotNull null
            setting.key.substringBeforeLast('.').let { encoded ->
                runCatching { String(java.util.Base64.getUrlDecoder().decode(encoded)) to localId }.getOrNull()
            }
        }.groupBy({ it.first }, { it.second })
        settings.replaceConversationGroups((localGroups + legacyGroups + grouped.map { (name, ids) ->
            ConversationGroupEntity(name, ids.distinct())
        }).groupBy { it.groupName }.map { (name, groups) ->
            ConversationGroupEntity(name, groups.flatMap { it.conversationIds }.distinct())
        })
    }

    private fun rejectionKey(snapshot: PendingChangeSnapshot) = "sync_rejected:${snapshot.change.entity}:${snapshot.change.id}"
    private fun validateUuid(id: String) { require(UUID.fromString(id).toString() == id.lowercase()) }

    private suspend fun applyAttachment(record: SyncRecord) {
        val old = attachments.get(record.id)
        if (record.deleted) {
            settings.deleteSetting("sync_resource_meta:${record.id}")
            attachments.upsert(
                (old ?: SyncAttachmentEntity(record.id, "", "", "", "application/octet-stream", 1, "0".repeat(64), kind = "file"))
                    .copy(deleted = true, syncStatus = SyncStatus.SYNCED),
            )
            return
        }
        val payload = record.payload
        // 与游标同事务保留扩展字段，网络传输在事务外执行；杀进程后仍可继续下载。
        settings.insertSetting(SystemSettingEntity("sync_resource_meta:${record.id}", payload.toString()))
        val sessionId = requireNotNull(SyncPayloadMapper.string(payload, "sessionId"))
        val messageId = requireNotNull(SyncPayloadMapper.string(payload, "messageId"))
        validateUuid(sessionId); validateUuid(messageId)
        val size = SyncPayloadMapper.long(payload, "size")
        val sha256 = requireNotNull(SyncPayloadMapper.string(payload, "sha256"))
        require(size >= 1 && sha256.matches(Regex("[0-9a-f]{64}")))
        attachments.upsert(SyncAttachmentEntity(
            id = record.id,
            sessionId = sessionId,
            messageId = messageId,
            name = SyncPayloadMapper.string(payload, "name").orEmpty(),
            mime = SyncPayloadMapper.string(payload, "mime").orEmpty(),
            size = size,
            sha256 = sha256,
            fileId = payload["fileId"]?.jsonPrimitive?.contentOrNull,
            kind = SyncPayloadMapper.string(payload, "kind") ?: "file",
            position = SyncPayloadMapper.long(payload, "position").toInt().coerceAtLeast(0),
            updatedAt = modified(record),
            syncStatus = SyncStatus.SYNCED,
        ))
    }

    private companion object {
        val REGISTERED_SETTING_KINDS = setOf(
            "api_configs", "selected_models", "web_search_configs", "voice_configs",
            "voice_llm_platforms", "voice_custom_models", "mcp_servers", "skills",
            "skill_variables", "computers", "computer_workspaces", "conversation_settings",
            "memory_switches", "group_members", "pinned_items", "crypto_keys",
            "conversation_groups", // 仅兼容未发布的旧本地数据，新的记录使用 group_members。
        )
    }
}


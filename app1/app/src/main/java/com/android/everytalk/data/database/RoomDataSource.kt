package com.android.everytalk.data.database

import android.content.Context
import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.isBuiltinModel
import com.android.everytalk.data.DataClass.withBuiltinImageModels
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.SyncSessionReference
import com.android.everytalk.data.database.entities.syncUserSettingSnapshots
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.UserSettingEntity
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toMessage
import com.android.everytalk.data.database.entities.localSyncUuid
import com.android.everytalk.data.database.entities.toPreviewMessage
import com.android.everytalk.data.database.entities.HISTORY_PREVIEW_OUTPUT_TYPE
import com.android.everytalk.data.database.entities.toVoiceBackendConfig
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import com.android.everytalk.data.agent.AgentToolResultStore
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.CancellationException
import java.util.UUID
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class LoadedHistorySession(
    val sessionId: String,
    val messages: List<Message>,
)

data class SessionHistoryLoadResult(
    val sessions: List<LoadedHistorySession>,
    val failedSessionIds: Set<String>,
)

private val LAST_OPEN_SESSION_IDS = setOf(
    "last_open_chat_v1",
    "last_open_image_generation_v1",
)

class RoomDataSource(context: Context, private val database: AppDatabase = AppDatabase.getDatabase(context)) {
    private val applicationContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val converters = Converters()
    private val apiConfigDao = database.apiConfigDao()
    private val voiceConfigDao = database.voiceConfigDao()
    private val chatDao = database.chatDao()
    private val settingsDao = database.settingsDao()

    /** 仅由启动加载流程调用，不能在普通打开历史时把仍在运行的草稿提前定稿。 */
    suspend fun recoverInterruptedDrafts() = database.withTransaction {
        val runStore = com.android.everytalk.data.agent.AgentRunStore(database.agentDao())
        chatDao.getAllDrafts().forEach { draft ->
            // 草稿将封存为未完成消息；同一事务内终止旧续写/审批，不能稍后再改写其正式正文。
            runStore.cancelActiveRunByVisibleMessage(draft.message.id, "DRAFT_RECOVERED_AS_INCOMPLETE")
        }
        chatDao.recoverInterruptedDrafts()
    }

    // Helper to run suspend functions in blocking mode for compatibility if needed,
    // though DataPersistenceManager calls this from IO context via suspend functions usually?
    // DataPersistenceManager calls roomDataSource methods as 'suspend' calls inside 'withContext(Dispatchers.IO)'.
    // So RoomDataSource should expose 'suspend' functions.
    
    // --- Api Configs --- 
    suspend fun loadApiConfigs(): List<ApiConfig> {
        return apiConfigDao.getConfigs(isImageGen = false).map { it.toApiConfig() }
    }

    suspend fun saveApiConfigs(configs: List<ApiConfig>) = database.withTransaction {
        val removed = apiConfigDao.getConfigs(false).map { it.id }.toSet() - configs.map { it.id }.toSet()
        apiConfigDao.replaceConfigs(
            isImageGen = false,
            configs = configs.map { it.toEntity(isImageGenConfig = false) },
        )
        recordRemovedSettings("api_configs", removed)
        captureSyncSettings()
    }

    suspend fun loadImageGenApiConfigs(): List<ApiConfig> {
        return withBuiltinImageModels(apiConfigDao.getConfigs(isImageGen = true).map { it.toApiConfig() })
    }

    suspend fun saveImageGenApiConfigs(configs: List<ApiConfig>) = database.withTransaction {
        val stored = apiConfigDao.getConfigs(true).map { it.toApiConfig() }
        // 清空和导入同样保留已有内置 ID；缺失的模型再用稳定 ID 补齐。
        val protected = stored.filter { it.isBuiltinModel() && configs.none { candidate -> candidate.id == it.id } }
        val complete = withBuiltinImageModels(configs + protected)
        val removed = stored.map { it.id }.toSet() - complete.map { it.id }.toSet()
        apiConfigDao.replaceConfigs(
            isImageGen = true,
            configs = complete.map { it.toEntity(isImageGenConfig = true) },
        )
        recordRemovedSettings("api_configs", removed)
        captureSyncSettings()
    }
    
    suspend fun clearApiConfigs() {
        saveApiConfigs(emptyList())
    }

    suspend fun clearImageGenApiConfigs() {
        saveImageGenApiConfigs(emptyList())
    }

    // --- Voice Backend Configs ---
    suspend fun loadVoiceBackendConfigs(): List<VoiceBackendConfig> {
        return voiceConfigDao.getAll().map { it.toVoiceBackendConfig() }
    }

    suspend fun saveVoiceBackendConfigs(configs: List<VoiceBackendConfig>) = database.withTransaction {
        val removed = voiceConfigDao.getAll().map { it.id }.toSet() - configs.map { it.id }.toSet()
        voiceConfigDao.replaceAll(configs.map { it.toEntity() })
        recordRemovedSettings("voice_configs", removed)
        captureSyncSettings()
    }

    suspend fun clearVoiceBackendConfigs() {
        saveVoiceBackendConfigs(emptyList())
    }

    // --- Settings / Key-Value ---
    suspend fun loadSelectedConfigId(): String? = settingsDao.getValue("selected_api_config_id")
    suspend fun saveSelectedConfigId(id: String?) = saveSetting("selected_api_config_id", id)

    suspend fun loadSelectedImageGenConfigId(): String? = settingsDao.getValue("selected_image_gen_config_id")
    suspend fun saveSelectedImageGenConfigId(id: String?) = saveSetting("selected_image_gen_config_id", id)

    suspend fun loadSelectedVoiceConfigId(): String? = settingsDao.getValue("selected_voice_config_id")
    suspend fun saveSelectedVoiceConfigId(id: String?) = saveSetting("selected_voice_config_id", id)

    // --- Custom Providers ---
    suspend fun loadCustomProviders(): Set<String> {
        val jsonString = settingsDao.getValue("custom_providers")
        return if (jsonString != null) {
            try {
                json.decodeFromString(SetSerializer(String.serializer()), jsonString)
            } catch (e: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }
    }

    suspend fun saveCustomProviders(providers: Set<String>) {
        val jsonString = json.encodeToString(SetSerializer(String.serializer()), providers)
        saveSetting("custom_providers", jsonString)
    }

    suspend fun loadExternalWebSearchConfigs(): List<ExternalWebSearchProviderConfig> {
        val jsonString = settingsDao.getValue("external_web_search_configs")
        return if (jsonString != null) {
            try {
                json.decodeFromString<List<ExternalWebSearchProviderConfig>>(jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    suspend fun saveExternalWebSearchConfigs(configs: List<ExternalWebSearchProviderConfig>) {
        val jsonString = json.encodeToString(configs)
        saveSetting("external_web_search_configs", jsonString)
    }

    suspend fun loadSelectedExternalWebSearchProviderId(): String? =
        settingsDao.getValue("selected_external_web_search_provider_id")

    suspend fun saveSelectedExternalWebSearchProviderId(providerId: String?) {
        saveSetting("selected_external_web_search_provider_id", providerId)
    }

    private suspend fun saveSetting(key: String, value: String?) = database.withTransaction {
        if (value == null) {
            settingsDao.deleteSetting(key)
        } else {
            settingsDao.insertSetting(SystemSettingEntity(key, value))
        }
        if (key in setOf("selected_api_config_id", "selected_image_gen_config_id", "selected_voice_config_id",
                "selected_external_web_search_provider_id", "external_web_search_configs", "memory_enabled",
                "memory_auto_capture_enabled", "conversation_api_config_ids", "conversation_function_toggle_states") ||
            key.startsWith("custom_models_") || key.startsWith("voice_llm_platform:")) captureSyncSettings()
    }

    /** 在用户实际保存时封存逐条内容和修改时间，离线期间也记录；这里不发网络请求。 */
    private suspend fun captureSyncSettings() = com.android.everytalk.data.sync.captureSyncSettings(applicationContext, database)

    /** 删除只写对应逻辑记录的墓碑，不能把整张设置表当作本次保存的范围。 */
    private suspend fun recordRemovedSettings(kind: String, keys: Set<String>) {
        keys.forEach { key ->
            com.android.everytalk.data.sync.SyncSettingsProjector(database).markDeleted(kind, key)
        }
    }

    // --- Generic Settings Access (for flags like default_configs_initialized) ---
    suspend fun getSetting(key: String, defaultValue: String? = null): String? {
        return settingsDao.getValue(key) ?: defaultValue
    }

    suspend fun setSetting(key: String, value: String?) {
        saveSetting(key, value)
    }

    /** 将用户编辑的系统提示词保存为每会话一条同步设置，避免依赖历史 System 消息落盘。 */
    suspend fun saveConversationSettings(conversationId: String, systemPrompt: String, promptEnabled: Boolean) = database.withTransaction {
        val session = chatDao.getSession(conversationId) ?: return@withTransaction
        val kind = "conversation_settings"
        val key = session.uuid
        val old = settingsDao.getUserSetting(kind, key)
        val value = buildJsonObject {
            old?.value?.let { json.parseToJsonElement(it).jsonObject }?.forEach { (name, element) -> put(name, element) }
            put("systemPrompt", systemPrompt)
            put("promptEnabled", promptEnabled)
        }.toString()
        if (old?.value == value && !old.deleted) return@withTransaction
        settingsDao.upsertUserSettings(listOf(UserSettingEntity(
            kind = kind,
            key = key,
            value = value,
            uuid = old?.uuid ?: com.android.everytalk.data.database.entities.localSyncUuid("user_settings", "$kind:$key"),
            updatedAt = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1),
            syncStatus = com.android.everytalk.data.database.entities.SyncStatus.PENDING,
        )))
    }

    suspend fun loadConversationSettings(): Map<String, Pair<String, Boolean>> =
        settingsDao.getSyncableUserSettings().filter { it.kind == "conversation_settings" }.mapNotNull { setting ->
            runCatching {
                val value = json.parseToJsonElement(setting.value).jsonObject
                setting.key to (value["systemPrompt"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                    (value["promptEnabled"]?.jsonPrimitive?.boolean ?: false))
            }.getOrNull()
        }.toMap()

    suspend fun conversationIdForUuid(uuid: String): String? = chatDao.getSessionByUuid(uuid)?.id

    /** 语音自定义模型使用同步白名单中的逐平台记录，不再只依赖设备本地 SharedPreferences。 */
    suspend fun loadVoiceCustomModels(kind: String, platform: String): List<String> =
        settingsDao.getValue("custom_models_${kind}_${platform}")
            ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.distinct().orEmpty()

    suspend fun saveVoiceCustomModels(kind: String, platform: String, models: List<String>) {
        saveSetting("custom_models_${kind}_${platform}", models.distinct().joinToString(","))
    }

    suspend fun loadVoicePlatforms(): List<String> =
        settingsDao.getByPrefix("voice_llm_platform:")
            .map { it.key.removePrefix("voice_llm_platform:") }
            .filter(String::isNotBlank).distinct()

    // --- Chat History ---
    suspend fun loadChatHistory(): List<List<Message>> {
        return loadChatHistoryResult().sessions.map(LoadedHistorySession::messages)
    }

    /** null 读取所有旧会话，保持原会话 ID 和图片附件；无需批量迁移或重写正文。 */
    suspend fun loadChatHistoryResult(): SessionHistoryLoadResult = loadSessionsResult(isImageGeneration = null)

    suspend fun loadChatHistoryPreviewResult(): SessionHistoryLoadResult = loadSessionPreviewsResult(isImageGeneration = null)

    suspend fun saveChatHistory(
        history: List<List<Message>>,
        protectedSessionIds: Set<String> = emptySet(),
    ) {
        saveSessions(
            history = history,
            isImageGeneration = false,
            protectedSessionIds = protectedSessionIds,
        )
    }

    suspend fun loadImageGenerationHistory(): List<List<Message>> {
        return loadImageGenerationHistoryResult().sessions.map(LoadedHistorySession::messages)
    }

    suspend fun loadImageGenerationHistoryResult(): SessionHistoryLoadResult {
        return loadSessionsResult(isImageGeneration = true)
    }

    suspend fun loadImageGenerationHistoryPreviewResult(): SessionHistoryLoadResult {
        return loadSessionPreviewsResult(isImageGeneration = true)
    }

    suspend fun saveImageGenerationHistory(
        history: List<List<Message>>,
        protectedSessionIds: Set<String> = emptySet(),
    ) {
        saveSessions(
            history = history,
            isImageGeneration = true,
            protectedSessionIds = protectedSessionIds,
        )
    }

    suspend fun clearChatHistory() {
        database.withTransaction {
            chatDao.clearAllSessions(isImageGen = false)
            chatDao.clearAllSessions(isImageGen = true)
        }
    }

    suspend fun clearImageGenerationHistory() {
        chatDao.clearAllSessions(isImageGen = true)
    }

    private suspend fun loadSessionsResult(isImageGeneration: Boolean?): SessionHistoryLoadResult {
        val loadedSessions = mutableListOf<LoadedHistorySession>()
        val failedSessionIds = linkedSetOf<String>()
        val sessions = chatDao.getAllSessions(isImageGeneration)
            .filterNot { it.id in LAST_OPEN_SESSION_IDS }
        val rawMessagesBySession = chatDao.getRawMessagesForMode(isImageGeneration)
            .groupBy { it.sessionId }
        sessions.forEach { session ->
            try {
                loadedSessions += LoadedHistorySession(
                    sessionId = session.id,
                    messages = applySessionTitle(
                        session,
                        rawMessagesBySession[session.id].orEmpty().map { it.toMessage(converters) },
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failedSessionIds += session.id
            }
        }
        return SessionHistoryLoadResult(loadedSessions, failedSessionIds)
    }

    /** 启动只加载标量字段，禁止触发消息 JSON 的批量反序列化。 */
    private suspend fun loadSessionPreviewsResult(isImageGeneration: Boolean?): SessionHistoryLoadResult {
        val sessions = chatDao.getAllSessions(isImageGeneration)
            .filterNot { it.id in LAST_OPEN_SESSION_IDS }
        val messagesBySession = chatDao.getLightweightMessagesForMode(isImageGeneration)
            .groupBy { it.sessionId }
        return SessionHistoryLoadResult(
            sessions = sessions.map { session ->
                LoadedHistorySession(
                    sessionId = session.id,
                    messages = applySessionTitle(
                        session,
                        messagesBySession[session.id].orEmpty().map { it.toPreviewMessage(converters) },
                    ),
                )
            },
            failedSessionIds = emptySet(),
        )
    }

    /** 用户打开、分享或导出时才加载单个会话的完整消息。 */
    suspend fun loadHistorySession(sessionId: String): List<Message>? {
        val session = chatDao.getSession(sessionId) ?: return null
        return applySessionTitle(session, chatDao.getMessagesForSession(sessionId).map { it.toMessage() })
    }

    suspend fun renameHistorySession(sessionId: String, title: String) {
        chatDao.updateSessionTitle(sessionId, title)
    }

    suspend fun isImageGenerationSession(sessionId: String): Boolean {
        return chatDao.getSession(sessionId)?.isImageGeneration ?: false
    }

    private fun applySessionTitle(session: ChatSessionEntity, messages: List<Message>): List<Message> {
        val title = session.title?.trim()?.takeIf(String::isNotEmpty) ?: return messages
        val withoutLegacyTitle = ConversationNameHelper.withoutStoredConversationTitle(messages)
        return listOf(
            Message(
                id = "title_${session.id}",
                text = title,
                sender = com.android.everytalk.data.DataClass.Sender.System,
                timestamp = session.lastModifiedTimestamp,
                contentStarted = true,
                isPlaceholderName = true,
                outputType = HISTORY_PREVIEW_OUTPUT_TYPE,
                conversationId = session.id,
            ),
        ) + withoutLegacyTitle
    }

    suspend fun saveLoadedHistorySession(
        sessionId: String,
        messages: List<Message>,
        isImageGeneration: Boolean,
    ) {
        val existingSession = chatDao.getSession(sessionId)
        // 编辑首条消息会回退到空对话。保留会话身份和资源，只清空已有消息。
        if (messages.isEmpty()) {
            if (existingSession != null) chatDao.saveSessionWithMessages(existingSession, emptyList(), sessionChanged = false)
            return
        }
        val persistedMessages = ConversationNameHelper.withoutStoredConversationTitle(messages)
        val session = ChatSessionEntity(
            id = sessionId,
            creationTimestamp = existingSession?.creationTimestamp
                ?: persistedMessages.firstOrNull()?.timestamp
                ?: messages.first().timestamp,
            lastModifiedTimestamp = persistedMessages.lastOrNull()?.timestamp
                ?: existingSession?.lastModifiedTimestamp
                ?: messages.last().timestamp,
            isImageGeneration = isImageGeneration,
            title = existingSession?.title
                ?: ConversationNameHelper.getStoredConversationTitle(messages),
        )
        chatDao.saveSessionWithMessages(
            session = session,
            messages = persistedMessages.map { it.toEntity(sessionId, converters) },
            sessionChanged = session != existingSession,
            draftIds = persistedMessages.filter { it.isStreamingDraft }.mapTo(hashSetOf()) { it.id },
        )
    }

    suspend fun deleteHistorySession(sessionId: String) {
        if (sessionId !in LAST_OPEN_SESSION_IDS) {
            val runIds = database.agentDao().getRunsForSession(sessionId).map { it.id }
            chatDao.deleteSession(sessionId)
            val toolResultStore = AgentToolResultStore(applicationContext)
            runIds.forEach { runId -> toolResultStore.deleteRun(runId) }
        }
    }

    /** 历史、最近打开记录和旧 Provider 会话状态一起提交，失败时整体回滚。 */
    suspend fun rewindHistorySession(sessionId: String, messages: List<Message>, isImageGeneration: Boolean, updateLastOpen: Boolean) {
        database.withTransaction {
            saveLoadedHistorySession(sessionId, messages, isImageGeneration)
            if (!isImageGeneration) database.agentDao().deleteContinuationStates(sessionId)
            if (updateLastOpen) {
                saveLastOpenSession(
                    if (isImageGeneration) "last_open_image_generation_v1" else "last_open_chat_v1",
                    ConversationNameHelper.withoutStoredConversationTitle(messages),
                    isImageGeneration,
                )
            }
        }
    }

    private suspend fun saveSessions(
        history: List<List<Message>>,
        isImageGeneration: Boolean,
        protectedSessionIds: Set<String>,
    ) {
        val effectiveProtectedSessionIds = protectedSessionIds
        val existingSessions = chatDao.getAllSessions(if (isImageGeneration) true else null)
        val existingSessionsById = existingSessions.associateBy(ChatSessionEntity::id)

        history.forEach { messages ->
            if (messages.isNotEmpty()) {
                val stableId = ConversationNameHelper.resolveStableId(messages) ?: UUID.randomUUID().toString()
                if (stableId in effectiveProtectedSessionIds) return@forEach
                // 启动预览只含轻量字段，任何批量持久化都必须跳过，防止覆盖完整消息。
                if (messages.all { it.outputType == HISTORY_PREVIEW_OUTPUT_TYPE }) {
                    return@forEach
                }

                val existingSession = existingSessionsById[stableId]
                val persistedMessages = ConversationNameHelper.withoutStoredConversationTitle(messages)
                val session = ChatSessionEntity(
                    id = stableId,
                    creationTimestamp = existingSession?.creationTimestamp
                        ?: persistedMessages.firstOrNull()?.timestamp
                        ?: System.currentTimeMillis(),
                    lastModifiedTimestamp = persistedMessages.lastOrNull()?.timestamp
                        ?: existingSession?.lastModifiedTimestamp
                        ?: System.currentTimeMillis(),
                    isImageGeneration = isImageGeneration,
                    title = ConversationNameHelper.getStoredConversationTitle(messages) ?: existingSession?.title,
                )

                chatDao.saveSessionWithMessages(
                    session = session,
                    messages = persistedMessages.map { it.toEntity(stableId, converters) },
                    sessionChanged = session != existingSession,
                    draftIds = persistedMessages.filter { it.isStreamingDraft }.mapTo(hashSetOf()) { it.id },
                    removeMissingMessages = false,
                )
            }
        }
        // 历史列表是 UI 快照，可能还没看到刚从另一台设备拉下来的会话。
        // 缺席不代表用户删除；删除必须走 deleteHistorySession/clearChatHistory 的明确入口。
    }

    // --- Last Open Chat ---
    // Stored as a special session with a reserved ID or just generic key-value?
    // SP stored it as a JSON string in a specific key.
    // In Room, we can store it as a regular session with a specific ID like "last_open_text" / "last_open_image"
    // BUT, the message IDs must be preserved. If we change session ID, message IDs are fine.
    
    suspend fun loadLastOpenChat(): List<Message> {
        return loadLastOpenSession("last_open_chat_v1")
    }

    suspend fun saveLastOpenChat(messages: List<Message>) {
        saveLastOpenSession("last_open_chat_v1", messages, isImageGen = false)
    }

    suspend fun loadLastOpenImageGenerationChat(): List<Message> {
        return loadLastOpenSession("last_open_image_generation_v1")
    }

    suspend fun saveLastOpenImageGenerationChat(messages: List<Message>) {
        saveLastOpenSession("last_open_image_generation_v1", messages, isImageGen = true)
    }

    /**
     * 快照的外键指向缓存会话，不能用作真实会话 ID。新快照单独保存本机身份；
     * 旧快照从仍在历史库中的原消息找回身份，避免把云端首条消息当成另一条会话。
     * 只恢复身份而不替换正文，保留尚未写入历史的本机草稿。
     */
    private suspend fun loadLastOpenSession(sessionId: String): List<Message> = database.withTransaction {
        val messages = chatDao.getMessagesForSession(sessionId).map {
            it.toMessage().copy(id = it.id.removePrefix("$sessionId:"), conversationId = null)
        }
        if (messages.isEmpty()) return@withTransaction emptyList()
        var origin = settingsDao.getValue("${sessionId}_origin")
        if (origin == null) {
            for (message in messages) {
                val original = chatDao.getStoredMessage(message.id, localSyncUuid("messages", message.id))
                if (original != null && original.sessionId != sessionId) {
                    origin = original.sessionId
                    break
                }
            }
        }
        // 已删除会话不能通过首页缓存复活；未归档的新聊天则继续保留。
        if (origin != null && chatDao.getStoredSession(origin)?.deleted == true) return@withTransaction emptyList()
        val stableId = origin ?: ConversationNameHelper.resolveStableId(messages)
        messages.map { it.copy(conversationId = stableId) }
    }

    private suspend fun saveLastOpenSession(sessionId: String, messages: List<Message>, isImageGen: Boolean) = database.withTransaction {
        if (messages.isEmpty()) {
            chatDao.clearLastOpenSnapshot(sessionId)
            settingsDao.deleteSetting("${sessionId}_origin")
        } else {
            val session = ChatSessionEntity(
                id = sessionId,
                creationTimestamp = System.currentTimeMillis(),
                lastModifiedTimestamp = System.currentTimeMillis(),
                isImageGeneration = isImageGen,
                title = "Last Open"
            )
            // messages.id 是全表主键。快照使用独立存储 ID，避免 Upsert 把历史消息移入快照会话。
            // 对外读取时去掉该前缀，保持气泡、附件和 Agent 引用的真实消息 ID 不变。
            chatDao.saveSessionWithMessages(
                session, messages.map { it.copy(id = "$sessionId:${it.id}").toEntity(sessionId, converters) },
                draftIds = messages.filter { it.isStreamingDraft }.mapTo(hashSetOf()) { "$sessionId:${it.id}" },
            )
            ConversationNameHelper.resolveStableId(messages)?.let { origin ->
                settingsDao.insertSetting(SystemSettingEntity("${sessionId}_origin", origin))
            }
        }
    }

    // --- Pinned Items ---
    suspend fun loadPinnedTextIds(): Set<String> {
        return (settingsDao.getPinnedIds(isImageGen = false) + settingsDao.getPinnedIds(isImageGen = true)).toSet()
    }

    suspend fun savePinnedTextIds(ids: Set<String>) {
        database.withTransaction {
            savePinnedIds(emptySet(), isImageGen = true)
            savePinnedIds(ids, isImageGen = false)
        }
    }

    suspend fun loadPinnedImageIds(): Set<String> {
        return settingsDao.getPinnedIds(isImageGen = true).toSet()
    }

    suspend fun savePinnedImageIds(ids: Set<String>) {
        savePinnedIds(ids, isImageGen = true)
    }

    private suspend fun savePinnedIds(ids: Set<String>, isImageGen: Boolean) = database.withTransaction {
        val entities = ids.map { PinnedItemEntity(it, isImageGen) }
        settingsDao.replacePinnedItems(isImageGen, entities)
        if (!isImageGen) refreshSyncableUserSettings("pinned_items")
    }

    // --- Groups ---
    suspend fun loadConversationGroups(): Map<String, List<String>> {
        val groups = settingsDao.getConversationGroups()
        return groups.associate { it.groupName to it.conversationIds }
    }

    suspend fun saveConversationGroups(groups: Map<String, List<String>>) = database.withTransaction {
        val entities = groups.map { ConversationGroupEntity(it.key, it.value) }
        settingsDao.replaceConversationGroups(entities)
        refreshSyncableUserSettings("group_members")
    }

    /** 将现有本地设置投影为可同步记录；生图会话通过会话白名单自然排除。 */
    private suspend fun refreshSyncableUserSettings(kind: String) {
        val sessions = chatDao.getAllSessions(null).filterNot { it.id in LAST_OPEN_SESSION_IDS }.associateBy { it.id }
        val knownUuids = sessions.values.mapTo(hashSetOf()) { it.uuid }
        val snapshots = buildList {
            if (kind == "group_members") settingsDao.getConversationGroups().forEach { group ->
                group.conversationIds.forEach { id -> sessions[id]?.let { session ->
                    val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(group.groupName.toByteArray(Charsets.UTF_8))
                    add(UserSettingEntity(kind, "$encoded.${session.uuid}", buildJsonObject {
                        put("groupName", group.groupName); put("conversationId", session.uuid)
                    }.toString()))
                } }
            }
            if (kind == "pinned_items") settingsDao.getPinnedIds(false).forEach { id -> sessions[id]?.let {
                add(UserSettingEntity(kind, it.uuid, "{}"))
            } }
        }
        // 不改变内容的保存不能制造同步回环；同时保留服务器已分配的 canonical UUID。
        // 只能比较这次修改的类别；此前把模型、MCP 等所有配置误标为删除，导致换机丢设置。
        val previous = settingsDao.getAllUserSettings().filter { it.kind == kind }
        val keys = snapshots.map { it.kind to it.key }.toSet()
        val changes = snapshots.mapNotNull { incoming ->
            val old = previous.find { it.kind == incoming.kind && it.key == incoming.key }
            if (old != null && !old.deleted && old.value == incoming.value) null else
                incoming.copy(uuid = old?.uuid ?: incoming.uuid,
                    updatedAt = maxOf(incoming.updatedAt, (old?.updatedAt ?: 0) + 1))
        } + previous.filter {
            !it.deleted && (it.kind to it.key) !in keys &&
                (if (kind == "group_members") it.key.substringAfterLast('.') else it.key) in knownUuids
        }.map {
            it.copy(deleted = true, updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt + 1),
                syncStatus = com.android.everytalk.data.database.entities.SyncStatus.PENDING)
        }
        if (changes.isNotEmpty()) settingsDao.upsertUserSettings(changes)
    }

    suspend fun loadExpandedGroupKeys(): Set<String> {
        return settingsDao.getExpandedGroupKeys().toSet()
    }

    suspend fun saveExpandedGroupKeys(keys: Set<String>) {
        val entities = keys.map { ExpandedGroupEntity(it) }
        settingsDao.replaceExpandedGroups(entities)
    }

    suspend fun loadIsGroupSectionExpanded(): Boolean {
        return settingsDao.getValue("is_group_section_expanded")?.toBooleanStrictOrNull() ?: false
    }

    suspend fun saveIsGroupSectionExpanded(expanded: Boolean) {
        settingsDao.insertSetting(SystemSettingEntity("is_group_section_expanded", expanded.toString()))
    }

    // --- Conversation Api Config Mapping ---
    // 持久化会话ID到配置ID的映射，用于恢复会话时的模型选择
    
    suspend fun loadConversationApiConfigIds(): Map<String, String> {
        val jsonString = settingsDao.getValue("conversation_api_config_ids")
        return if (jsonString != null) {
            try {
                json.decodeFromString(
                    kotlinx.serialization.builtins.MapSerializer(
                        String.serializer(),
                        String.serializer()
                    ),
                    jsonString
                )
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    suspend fun saveConversationApiConfigIds(mapping: Map<String, String>) {
        val jsonString = json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                String.serializer(),
                String.serializer()
            ),
            mapping
        )
        saveSetting("conversation_api_config_ids", jsonString)
    }

    suspend fun vacuumDatabase() {
        database.openHelper.writableDatabase.execSQL("VACUUM")
    }
}

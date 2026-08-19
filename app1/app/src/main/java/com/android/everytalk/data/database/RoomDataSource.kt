package com.android.everytalk.data.database

import android.content.Context
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toMessage
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

class RoomDataSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val converters = Converters()
    private val apiConfigDao = database.apiConfigDao()
    private val voiceConfigDao = database.voiceConfigDao()
    private val chatDao = database.chatDao()
    private val settingsDao = database.settingsDao()

    // Helper to run suspend functions in blocking mode for compatibility if needed,
    // though DataPersistenceManager calls this from IO context via suspend functions usually?
    // DataPersistenceManager calls roomDataSource methods as 'suspend' calls inside 'withContext(Dispatchers.IO)'.
    // So RoomDataSource should expose 'suspend' functions.
    
    // --- Api Configs --- 
    suspend fun loadApiConfigs(): List<ApiConfig> {
        return apiConfigDao.getConfigs(isImageGen = false).map { it.toApiConfig() }
    }

    suspend fun saveApiConfigs(configs: List<ApiConfig>) {
        apiConfigDao.replaceConfigs(
            isImageGen = false,
            configs = configs.map { it.toEntity(isImageGenConfig = false) },
        )
    }

    suspend fun loadImageGenApiConfigs(): List<ApiConfig> {
        return apiConfigDao.getConfigs(isImageGen = true).map { it.toApiConfig() }
    }

    suspend fun saveImageGenApiConfigs(configs: List<ApiConfig>) {
        apiConfigDao.replaceConfigs(
            isImageGen = true,
            configs = configs.map { it.toEntity(isImageGenConfig = true) },
        )
    }
    
    suspend fun clearApiConfigs() {
        apiConfigDao.clearConfigs(isImageGen = false)
    }

    suspend fun clearImageGenApiConfigs() {
        apiConfigDao.clearConfigs(isImageGen = true)
    }

    // --- Voice Backend Configs ---
    suspend fun loadVoiceBackendConfigs(): List<VoiceBackendConfig> {
        return voiceConfigDao.getAll().map { it.toVoiceBackendConfig() }
    }

    suspend fun saveVoiceBackendConfigs(configs: List<VoiceBackendConfig>) {
        voiceConfigDao.replaceAll(configs.map { it.toEntity() })
    }

    suspend fun clearVoiceBackendConfigs() {
        voiceConfigDao.clearAll()
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

    private suspend fun saveSetting(key: String, value: String?) {
        if (value == null) {
            settingsDao.deleteSetting(key)
        } else {
            settingsDao.insertSetting(SystemSettingEntity(key, value))
        }
    }

    // --- Generic Settings Access (for flags like default_configs_initialized) ---
    suspend fun getSetting(key: String, defaultValue: String? = null): String? {
        return settingsDao.getValue(key) ?: defaultValue
    }

    suspend fun setSetting(key: String, value: String?) {
        saveSetting(key, value)
    }

    // --- Chat History ---
    suspend fun loadChatHistory(): List<List<Message>> {
        return loadChatHistoryResult().sessions.map(LoadedHistorySession::messages)
    }

    suspend fun loadChatHistoryResult(): SessionHistoryLoadResult {
        return loadSessionsResult(isImageGeneration = false)
    }

    suspend fun loadChatHistoryPreviewResult(): SessionHistoryLoadResult {
        return loadSessionPreviewsResult(isImageGeneration = false)
    }

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
        chatDao.clearAllSessions(isImageGen = false)
    }

    suspend fun clearImageGenerationHistory() {
        chatDao.clearAllSessions(isImageGen = true)
    }

    private suspend fun loadSessionsResult(isImageGeneration: Boolean): SessionHistoryLoadResult {
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
    private suspend fun loadSessionPreviewsResult(isImageGeneration: Boolean): SessionHistoryLoadResult {
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
            ),
        ) + withoutLegacyTitle
    }

    suspend fun saveLoadedHistorySession(
        sessionId: String,
        messages: List<Message>,
        isImageGeneration: Boolean,
    ) {
        if (messages.isEmpty()) return
        val existingSession = chatDao.getSession(sessionId)
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

    private suspend fun saveSessions(
        history: List<List<Message>>,
        isImageGeneration: Boolean,
        protectedSessionIds: Set<String>,
    ) {
        val newSessionIds = mutableSetOf<String>()
        val effectiveProtectedSessionIds = protectedSessionIds
        val existingSessions = chatDao.getAllSessions(isImageGeneration)
        val existingSessionsById = existingSessions.associateBy(ChatSessionEntity::id)

        history.forEach { messages ->
            if (messages.isNotEmpty()) {
                val stableId = ConversationNameHelper.resolveStableId(messages) ?: UUID.randomUUID().toString()
                newSessionIds.add(stableId)
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
                )
            }
        }
        existingSessions.forEach { existing ->
            if (existing.id !in newSessionIds &&
                existing.id !in effectiveProtectedSessionIds &&
                existing.id !in LAST_OPEN_SESSION_IDS
            ) {
                chatDao.deleteSession(existing.id)
            }
        }
    }

    // --- Last Open Chat ---
    // Stored as a special session with a reserved ID or just generic key-value?
    // SP stored it as a JSON string in a specific key.
    // In Room, we can store it as a regular session with a specific ID like "last_open_text" / "last_open_image"
    // BUT, the message IDs must be preserved. If we change session ID, message IDs are fine.
    
    suspend fun loadLastOpenChat(): List<Message> {
        return chatDao.getMessagesForSession("last_open_chat_v1").map { it.toMessage() }
    }

    suspend fun saveLastOpenChat(messages: List<Message>) {
        saveLastOpenSession("last_open_chat_v1", messages, isImageGen = false)
    }

    suspend fun loadLastOpenImageGenerationChat(): List<Message> {
        return chatDao.getMessagesForSession("last_open_image_generation_v1").map { it.toMessage() }
    }

    suspend fun saveLastOpenImageGenerationChat(messages: List<Message>) {
        saveLastOpenSession("last_open_image_generation_v1", messages, isImageGen = true)
    }

    private suspend fun saveLastOpenSession(sessionId: String, messages: List<Message>, isImageGen: Boolean) {
        if (messages.isEmpty()) {
            chatDao.deleteSession(sessionId)
        } else {
            val session = ChatSessionEntity(
                id = sessionId,
                creationTimestamp = System.currentTimeMillis(),
                lastModifiedTimestamp = System.currentTimeMillis(),
                isImageGeneration = isImageGen,
                title = "Last Open"
            )
            chatDao.saveSessionWithMessages(session, messages.map { it.toEntity(sessionId, converters) })
        }
    }

    // --- Pinned Items ---
    suspend fun loadPinnedTextIds(): Set<String> {
        return settingsDao.getPinnedIds(isImageGen = false).toSet()
    }

    suspend fun savePinnedTextIds(ids: Set<String>) {
        savePinnedIds(ids, isImageGen = false)
    }

    suspend fun loadPinnedImageIds(): Set<String> {
        return settingsDao.getPinnedIds(isImageGen = true).toSet()
    }

    suspend fun savePinnedImageIds(ids: Set<String>) {
        savePinnedIds(ids, isImageGen = true)
    }

    private suspend fun savePinnedIds(ids: Set<String>, isImageGen: Boolean) {
        val entities = ids.map { PinnedItemEntity(it, isImageGen) }
        settingsDao.replacePinnedItems(isImageGen, entities)
    }

    // --- Groups ---
    suspend fun loadConversationGroups(): Map<String, List<String>> {
        val groups = settingsDao.getConversationGroups()
        return groups.associate { it.groupName to it.conversationIds }
    }

    suspend fun saveConversationGroups(groups: Map<String, List<String>>) {
        val entities = groups.map { ConversationGroupEntity(it.key, it.value) }
        settingsDao.replaceConversationGroups(entities)
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

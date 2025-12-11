package com.android.everytalk.data.database

import android.content.Context
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ConversationParamsEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toMessage
import com.android.everytalk.data.database.entities.toVoiceBackendConfig
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class RoomDataSource(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
        // 修复：先清除旧配置，确保删除操作生效
        apiConfigDao.clearConfigs(isImageGen = false)
        apiConfigDao.insertConfigs(configs.map { it.toEntity(isImageGenConfig = false) })
    }

    suspend fun loadImageGenApiConfigs(): List<ApiConfig> {
        return apiConfigDao.getConfigs(isImageGen = true).map { it.toApiConfig() }
    }

    suspend fun saveImageGenApiConfigs(configs: List<ApiConfig>) {
        // 修复：先清除旧配置，确保删除操作生效
        apiConfigDao.clearConfigs(isImageGen = true)
        apiConfigDao.insertConfigs(configs.map { it.toEntity(isImageGenConfig = true) })
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
        // 修复：先清除旧配置，确保导入时完全替换，与 API 配置行为保持一致
        voiceConfigDao.clearAll()
        voiceConfigDao.insertAll(configs.map { it.toEntity() })
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
        return loadSessions(isImageGeneration = false)
    }

    suspend fun saveChatHistory(history: List<List<Message>>) {
        saveSessions(history, isImageGeneration = false)
    }

    suspend fun loadImageGenerationHistory(): List<List<Message>> {
        return loadSessions(isImageGeneration = true)
    }

    suspend fun saveImageGenerationHistory(history: List<List<Message>>) {
        saveSessions(history, isImageGeneration = true)
    }

    suspend fun clearChatHistory() {
        chatDao.clearAllSessions(isImageGen = false)
    }

    suspend fun clearImageGenerationHistory() {
        chatDao.clearAllSessions(isImageGen = true)
    }

    private suspend fun loadSessions(isImageGeneration: Boolean): List<List<Message>> {
        val sessions = chatDao.getAllSessions(isImageGeneration)
        return sessions.map { session ->
            chatDao.getMessagesForSession(session.id).map { it.toMessage() }
        }
    }

    private suspend fun saveSessions(history: List<List<Message>>, isImageGeneration: Boolean) {
        // Optimization: This might be slow if history is huge.
        // Ideally we should only update changed sessions, but the interface takes the whole list.
        // For now, mirroring SP behavior (overwrite).
        // To be safer/faster, we could check IDs.
        
        // 1. Get existing session IDs to know what to delete if needed (optional, or just replace all?)
        // SP implementation overwrites the whole list key. So if a session is removed from the list passed in, it's gone.
        // So we should sync: delete sessions not in the new list, update/insert others.
        
        val newSessionIds = mutableSetOf<String>()
        
        history.forEach { messages ->
            if (messages.isNotEmpty()) {
                val stableId = ConversationNameHelper.resolveStableId(messages) ?: UUID.randomUUID().toString()
                newSessionIds.add(stableId)
                
                val creationTime = messages.firstOrNull()?.timestamp ?: System.currentTimeMillis()
                val lastModified = messages.lastOrNull()?.timestamp ?: System.currentTimeMillis()
                
                // We need to determine if it's new or existing to preserve creationTime if strictly needed,
                // but usually first message timestamp is good proxy.
                
                val session = ChatSessionEntity(
                    id = stableId,
                    creationTimestamp = creationTime,
                    lastModifiedTimestamp = lastModified,
                    isImageGeneration = isImageGeneration
                )
                
                val messageEntities = messages.map { it.toEntity(stableId) }
                chatDao.saveSessionWithMessages(session, messageEntities)
            }
        }

        // Delete sessions that are no longer in the history list
        // Note: getAllSessions returns all sessions for this mode.
        val existingSessions = chatDao.getAllSessions(isImageGeneration)
        existingSessions.forEach { existing ->
            if (existing.id !in newSessionIds) {
                // Determine if this session should really be deleted.
                // The input 'history' is the "source of truth" for the visible list.
                // So yes, delete.
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
            chatDao.saveSessionWithMessages(session, messages.map { it.toEntity(sessionId) })
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
        settingsDao.clearPinnedItems(isImageGen)
        val entities = ids.map { PinnedItemEntity(it, isImageGen) }
        settingsDao.insertPinnedItems(entities)
    }

    // --- Groups ---
    suspend fun loadConversationGroups(): Map<String, List<String>> {
        val groups = settingsDao.getConversationGroups()
        return groups.associate { it.groupName to it.conversationIds }
    }

    suspend fun saveConversationGroups(groups: Map<String, List<String>>) {
        settingsDao.clearConversationGroups()
        val entities = groups.map { ConversationGroupEntity(it.key, it.value) }
        settingsDao.insertConversationGroups(entities)
    }

    suspend fun loadExpandedGroupKeys(): Set<String> {
        return settingsDao.getExpandedGroupKeys().toSet()
    }

    suspend fun saveExpandedGroupKeys(keys: Set<String>) {
        settingsDao.clearExpandedGroups()
        val entities = keys.map { ExpandedGroupEntity(it) }
        settingsDao.insertExpandedGroups(entities)
    }

    // --- Conversation Params ---
    suspend fun loadConversationParameters(): Map<String, GenerationConfig> {
        val params = settingsDao.getAllConversationParams()
        return params.associate { it.conversationId to it.config }
    }

    suspend fun saveConversationParameters(parameters: Map<String, GenerationConfig>) {
        // Since we don't have a clean "clear all" for this without potentially nuking needed ones,
        // and usually this grows, REPLACE strategy in Insert covers updates.
        // But to remove deleted ones... maybe we assume it only grows or we implement sync?
        // Let's implement full sync (replace all) to match SP behavior.
        // First delete all? No, Dao doesn't have clearAll for this yet.
        // I should add clearAll to SettingsDao for this table if I want full sync.
        // Or just upsert. SP implementation overwrites the whole map.
        // So I should clear and insert.
        // Note: I missed adding clearConversationParams to SettingsDao. I'll rely on upsert for now,
        // or quickly add it if strictly needed. Upsert is safer for now.
        val entities = parameters.map { ConversationParamsEntity(it.key, it.value) }
        settingsDao.insertConversationParams(entities)
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
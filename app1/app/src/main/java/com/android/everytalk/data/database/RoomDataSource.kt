package com.android.everytalk.data.database

import android.content.Context
import android.util.Log
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.database.converter.MessageConverter
import com.android.everytalk.data.database.entity.ConversationEntity
import com.android.everytalk.data.database.entity.ConversationGroupEntity
import com.android.everytalk.data.database.entity.ConversationType
import com.android.everytalk.data.database.entity.UserPreferenceEntity
import com.android.everytalk.data.database.entity.UserPreferenceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/**
 * Room 数据源
 *
 * 封装所有数据库操作，提供与 SharedPreferencesDataSource 相似的接口
 */
class RoomDataSource(context: Context) {
    
    private val TAG = "RoomDataSource"
    private val database = AppDatabase.getInstance(context)
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()
    private val conversationGroupDao = database.conversationGroupDao()
    private val userPreferenceDao = database.userPreferenceDao()
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }
    
    // ==================== 会话操作 ====================
    
    /**
     * 保存聊天历史（文本模式）
     */
    suspend fun saveChatHistory(history: List<List<Message>>) {
        saveConversationHistory(history, ConversationType.TEXT)
    }
    
    /**
     * 加载聊天历史（文本模式）
     */
    suspend fun loadChatHistory(): List<List<Message>> {
        return loadConversationHistory(ConversationType.TEXT)
    }
    
    /**
     * 保存图像生成历史
     */
    suspend fun saveImageGenerationHistory(history: List<List<Message>>) {
        saveConversationHistory(history, ConversationType.IMAGE)
    }
    
    /**
     * 加载图像生成历史
     */
    suspend fun loadImageGenerationHistory(): List<List<Message>> {
        return loadConversationHistory(ConversationType.IMAGE)
    }
    
    /**
     * 清除聊天历史
     */
    suspend fun clearChatHistory() {
        withContext(Dispatchers.IO) {
            conversationDao.deleteAllConversationsByType(ConversationType.TEXT)
            Log.d(TAG, "Cleared all text chat history")
        }
    }
    
    /**
     * 清除图像生成历史
     */
    suspend fun clearImageGenerationHistory() {
        withContext(Dispatchers.IO) {
            conversationDao.deleteAllConversationsByType(ConversationType.IMAGE)
            Log.d(TAG, "Cleared all image generation history")
        }
    }
    
    // ==================== 内部实现 ====================
    
    private suspend fun saveConversationHistory(history: List<List<Message>>, type: String) {
        withContext(Dispatchers.IO) {
            try {
                // 获取现有会话
                val existingConversations = conversationDao.getAllConversationsByTypeOnce(type)
                val existingIds = existingConversations.map { it.id }.toSet()
                
                // 处理每个会话
                val newConversationIds = mutableSetOf<String>()
                
                history.forEachIndexed { index, messages ->
                    if (messages.isEmpty()) return@forEachIndexed
                    
                    // 使用第一条消息的ID作为会话ID，或生成新ID
                    val conversationId = messages.firstOrNull()?.id ?: UUID.randomUUID().toString()
                    newConversationIds.add(conversationId)
                    
                    // 提取系统提示词
                    val systemPrompt = messages.firstOrNull { 
                        it.sender == com.android.everytalk.data.DataClass.Sender.System 
                    }?.text
                    
                    // 提取标题（第一条用户消息的前30个字符）
                    val title = messages.firstOrNull { 
                        it.sender == com.android.everytalk.data.DataClass.Sender.User 
                    }?.text?.take(30)
                    
                    // 创建或更新会话
                    val conversation = ConversationEntity(
                        id = conversationId,
                        type = type,
                        title = title,
                        systemPrompt = systemPrompt,
                        createdAt = messages.minOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                        updatedAt = messages.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                        isPinned = existingConversations.find { it.id == conversationId }?.isPinned ?: false,
                        pinnedOrder = existingConversations.find { it.id == conversationId }?.pinnedOrder ?: 0
                    )
                    
                    conversationDao.upsertConversation(conversation)
                    
                    // 保存消息
                    val messageEntities = MessageConverter.toEntityList(messages, conversationId)
                    messageDao.deleteMessagesByConversation(conversationId) // 先删除旧消息
                    messageDao.upsertMessages(messageEntities)
                }
                
                // 删除不再存在的会话
                existingIds.subtract(newConversationIds).forEach { idToDelete ->
                    conversationDao.deleteConversationById(idToDelete)
                }
                
                Log.d(TAG, "Saved ${history.size} conversations of type $type")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving conversation history", e)
                throw e
            }
        }
    }
    
    private suspend fun loadConversationHistory(type: String): List<List<Message>> {
        return withContext(Dispatchers.IO) {
            try {
                val conversations = conversationDao.getAllConversationsByTypeOnce(type)
                
                val result = conversations.mapNotNull { conversation ->
                    val messageEntities = messageDao.getMessagesByConversationOnce(conversation.id)
                    if (messageEntities.isNotEmpty()) {
                        MessageConverter.fromEntityList(messageEntities)
                    } else {
                        null
                    }
                }
                
                Log.d(TAG, "Loaded ${result.size} conversations of type $type")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error loading conversation history", e)
                emptyList()
            }
        }
    }
    
    // ==================== 单个会话操作 ====================
    
    /**
     * 保存单个会话
     */
    suspend fun saveConversation(messages: List<Message>, type: String, conversationId: String? = null) {
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) return@withContext
            
            val id = conversationId ?: messages.firstOrNull()?.id ?: UUID.randomUUID().toString()
            
            val systemPrompt = messages.firstOrNull { 
                it.sender == com.android.everytalk.data.DataClass.Sender.System 
            }?.text
            
            val title = messages.firstOrNull { 
                it.sender == com.android.everytalk.data.DataClass.Sender.User 
            }?.text?.take(30)
            
            val conversation = ConversationEntity(
                id = id,
                type = type,
                title = title,
                systemPrompt = systemPrompt,
                createdAt = messages.minOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                updatedAt = messages.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            )
            
            conversationDao.upsertConversation(conversation)
            
            val messageEntities = MessageConverter.toEntityList(messages, id)
            messageDao.deleteMessagesByConversation(id)
            messageDao.upsertMessages(messageEntities)
            
            Log.d(TAG, "Saved conversation $id with ${messages.size} messages")
        }
    }
    
    /**
     * 加载单个会话
     */
    suspend fun loadConversation(conversationId: String): List<Message>? {
        return withContext(Dispatchers.IO) {
            val messageEntities = messageDao.getMessagesByConversationOnce(conversationId)
            if (messageEntities.isNotEmpty()) {
                MessageConverter.fromEntityList(messageEntities)
            } else {
                null
            }
        }
    }
    
    /**
     * 删除单个会话
     */
    suspend fun deleteConversation(conversationId: String) {
        withContext(Dispatchers.IO) {
            conversationDao.deleteConversationById(conversationId)
            Log.d(TAG, "Deleted conversation $conversationId")
        }
    }
    
    // ==================== 最后打开的会话 ====================
    
    // 使用特殊的会话ID来存储"最后打开的会话"
    private val LAST_OPEN_TEXT_CHAT_ID = "__last_open_text_chat__"
    private val LAST_OPEN_IMAGE_CHAT_ID = "__last_open_image_chat__"
    
    /**
     * 保存最后打开的聊天
     */
    suspend fun saveLastOpenChat(messages: List<Message>) {
        if (messages.isEmpty()) {
            deleteConversation(LAST_OPEN_TEXT_CHAT_ID)
        } else {
            saveConversation(messages, ConversationType.TEXT, LAST_OPEN_TEXT_CHAT_ID)
        }
    }
    
    /**
     * 加载最后打开的聊天
     */
    suspend fun loadLastOpenChat(): List<Message> {
        return loadConversation(LAST_OPEN_TEXT_CHAT_ID) ?: emptyList()
    }
    
    /**
     * 保存最后打开的图像生成聊天
     */
    suspend fun saveLastOpenImageGenerationChat(messages: List<Message>) {
        if (messages.isEmpty()) {
            deleteConversation(LAST_OPEN_IMAGE_CHAT_ID)
        } else {
            saveConversation(messages, ConversationType.IMAGE, LAST_OPEN_IMAGE_CHAT_ID)
        }
    }
    
    /**
     * 加载最后打开的图像生成聊天
     */
    suspend fun loadLastOpenImageGenerationChat(): List<Message> {
        return loadConversation(LAST_OPEN_IMAGE_CHAT_ID) ?: emptyList()
    }
    
    // ==================== 置顶操作 ====================
    
    /**
     * 保存置顶的会话ID（文本模式）
     */
    suspend fun savePinnedTextIds(ids: Set<String>) {
        withContext(Dispatchers.IO) {
            conversationDao.unpinAllConversations(ConversationType.TEXT)
            if (ids.isNotEmpty()) {
                conversationDao.pinConversations(ids.toList())
            }
        }
    }
    
    /**
     * 加载置顶的会话ID（文本模式）
     */
    suspend fun loadPinnedTextIds(): Set<String> {
        return withContext(Dispatchers.IO) {
            conversationDao.getPinnedConversationIds(ConversationType.TEXT).toSet()
        }
    }
    
    /**
     * 保存置顶的会话ID（图像模式）
     */
    suspend fun savePinnedImageIds(ids: Set<String>) {
        withContext(Dispatchers.IO) {
            conversationDao.unpinAllConversations(ConversationType.IMAGE)
            if (ids.isNotEmpty()) {
                conversationDao.pinConversations(ids.toList())
            }
        }
    }
    
    /**
     * 加载置顶的会话ID（图像模式）
     */
    suspend fun loadPinnedImageIds(): Set<String> {
        return withContext(Dispatchers.IO) {
            conversationDao.getPinnedConversationIds(ConversationType.IMAGE).toSet()
        }
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取所有包含图片的消息（用于图片文件引用检查）
     */
    suspend fun getAllMessagesWithImages(): List<Message> {
        return withContext(Dispatchers.IO) {
            val entities = messageDao.getAllMessagesWithImages()
            MessageConverter.fromEntityList(entities)
        }
    }
    
    /**
     * 获取数据库统计信息
     */
    suspend fun getStats(): DatabaseStats {
        return withContext(Dispatchers.IO) {
            val textCount = conversationDao.getConversationCount(ConversationType.TEXT)
            val imageCount = conversationDao.getConversationCount(ConversationType.IMAGE)
            DatabaseStats(
                textConversationCount = textCount,
                imageConversationCount = imageCount
            )
        }
    }
    
    data class DatabaseStats(
        val textConversationCount: Int,
        val imageConversationCount: Int
    )
    
    // ==================== 分组操作 ====================
    
    /**
     * 保存会话分组
     */
    suspend fun saveConversationGroups(groups: Map<String, List<String>>) {
        withContext(Dispatchers.IO) {
            try {
                // 获取现有分组
                val existingGroups = conversationGroupDao.getAllGroupsOnce()
                val existingNames = existingGroups.map { it.groupName }.toSet()
                
                // 处理每个分组
                val newGroupNames = mutableSetOf<String>()
                
                groups.forEach { (groupName, conversationIds) ->
                    newGroupNames.add(groupName)
                    
                    val conversationIdsJson = json.encodeToString(
                        ListSerializer(String.serializer()),
                        conversationIds
                    )
                    
                    val group = ConversationGroupEntity(
                        groupName = groupName,
                        conversationIdsJson = conversationIdsJson,
                        createdAt = existingGroups.find { it.groupName == groupName }?.createdAt
                            ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    
                    conversationGroupDao.upsertGroup(group)
                }
                
                // 删除不再存在的分组
                existingNames.subtract(newGroupNames).forEach { nameToDelete ->
                    conversationGroupDao.deleteGroupByName(nameToDelete)
                }
                
                Log.d(TAG, "Saved ${groups.size} conversation groups")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving conversation groups", e)
                throw e
            }
        }
    }
    
    /**
     * 加载会话分组
     */
    suspend fun loadConversationGroups(): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val groups = conversationGroupDao.getAllGroupsOnce()
                
                val result = groups.associate { group ->
                    val conversationIds = try {
                        json.decodeFromString(
                            ListSerializer(String.serializer()),
                            group.conversationIdsJson
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse conversation IDs for group ${group.groupName}", e)
                        emptyList()
                    }
                    group.groupName to conversationIds
                }
                
                Log.d(TAG, "Loaded ${result.size} conversation groups")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error loading conversation groups", e)
                emptyMap()
            }
        }
    }
    
    /**
     * 清除所有分组
     */
    suspend fun clearConversationGroups() {
        withContext(Dispatchers.IO) {
            conversationGroupDao.deleteAllGroups()
            Log.d(TAG, "Cleared all conversation groups")
        }
    }
    
    // ==================== 用户偏好操作 ====================
    
    /**
     * 保存展开的分组键
     */
    suspend fun saveExpandedGroupKeys(keys: Set<String>) {
        withContext(Dispatchers.IO) {
            try {
                val keysJson = json.encodeToString(
                    SetSerializer(String.serializer()),
                    keys
                )
                
                val preference = UserPreferenceEntity(
                    key = UserPreferenceKeys.EXPANDED_GROUP_KEYS,
                    value = keysJson,
                    updatedAt = System.currentTimeMillis()
                )
                
                userPreferenceDao.upsertPreference(preference)
                Log.d(TAG, "Saved ${keys.size} expanded group keys")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving expanded group keys", e)
                throw e
            }
        }
    }
    
    /**
     * 加载展开的分组键
     */
    suspend fun loadExpandedGroupKeys(): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                val keysJson = userPreferenceDao.getPreferenceValue(UserPreferenceKeys.EXPANDED_GROUP_KEYS)
                
                if (keysJson.isNullOrEmpty()) {
                    emptySet()
                } else {
                    json.decodeFromString(
                        SetSerializer(String.serializer()),
                        keysJson
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading expanded group keys", e)
                emptySet()
            }
        }
    }
    
    /**
     * 保存用户偏好
     */
    suspend fun saveUserPreference(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val preference = UserPreferenceEntity(
                key = key,
                value = value,
                updatedAt = System.currentTimeMillis()
            )
            userPreferenceDao.upsertPreference(preference)
        }
    }
    
    /**
     * 加载用户偏好
     */
    suspend fun loadUserPreference(key: String): String? {
        return withContext(Dispatchers.IO) {
            userPreferenceDao.getPreferenceValue(key)
        }
    }
    
    /**
     * 清除所有用户偏好
     */
    suspend fun clearUserPreferences() {
        withContext(Dispatchers.IO) {
            userPreferenceDao.deleteAllPreferences()
            Log.d(TAG, "Cleared all user preferences")
        }
    }
    
    // ==================== 清理操作 ====================
    
    /**
     * 清除所有数据（用于完全重置）
     */
    suspend fun clearAllData() {
        withContext(Dispatchers.IO) {
            conversationDao.deleteAllConversationsByType(ConversationType.TEXT)
            conversationDao.deleteAllConversationsByType(ConversationType.IMAGE)
            conversationGroupDao.deleteAllGroups()
            userPreferenceDao.deleteAllPreferences()
            Log.d(TAG, "Cleared all database data")
        }
    }
}
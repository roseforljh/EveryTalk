package com.android.everytalk.util.storage

import android.util.Log
import com.android.everytalk.data.DataClass.Message
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 增量备份管理器
 * 跟踪已修改的会话，只保存发生变化的数据，避免全量保存
 */
class IncrementalBackupManager {
    private val TAG = "IncrementalBackup"
    
    // 脏标记集合：记录需要保存的会话ID
    private val dirtyTextConversations = ConcurrentHashMap.newKeySet<String>()
    private val dirtyImageConversations = ConcurrentHashMap.newKeySet<String>()
    
    // 会话内容哈希缓存：用于检测内容是否真正发生变化
    private val textConversationHashes = ConcurrentHashMap<String, Int>()
    private val imageConversationHashes = ConcurrentHashMap<String, Int>()
    
    // 同步锁
    private val syncMutex = Mutex()
    
    /**
     * 标记文本会话为脏（需要保存）
     */
    fun markTextConversationDirty(conversationId: String) {
        dirtyTextConversations.add(conversationId)
        Log.d(TAG, "Marked text conversation dirty: $conversationId")
    }
    
    /**
     * 标记图像会话为脏（需要保存）
     */
    fun markImageConversationDirty(conversationId: String) {
        dirtyImageConversations.add(conversationId)
        Log.d(TAG, "Marked image conversation dirty: $conversationId")
    }
    
    /**
     * 检查并标记会话是否发生变化
     * @return true 如果内容确实发生了变化
     */
    fun checkAndMarkIfChanged(
        conversationId: String,
        messages: List<Message>,
        isImageGeneration: Boolean
    ): Boolean {
        val newHash = computeConversationHash(messages)
        val hashMap = if (isImageGeneration) imageConversationHashes else textConversationHashes
        val dirtySet = if (isImageGeneration) dirtyImageConversations else dirtyTextConversations
        
        val oldHash = hashMap[conversationId]
        return if (oldHash == null || oldHash != newHash) {
            hashMap[conversationId] = newHash
            dirtySet.add(conversationId)
            Log.d(TAG, "Conversation changed: $conversationId (old=$oldHash, new=$newHash)")
            true
        } else {
            Log.d(TAG, "Conversation unchanged: $conversationId")
            false
        }
    }
    
    /**
     * 获取需要保存的文本会话ID列表
     */
    fun getDirtyTextConversationIds(): Set<String> {
        return dirtyTextConversations.toSet()
    }
    
    /**
     * 获取需要保存的图像会话ID列表
     */
    fun getDirtyImageConversationIds(): Set<String> {
        return dirtyImageConversations.toSet()
    }
    
    /**
     * 清除文本会话的脏标记（保存完成后调用）
     */
    fun clearTextDirtyFlag(conversationId: String) {
        dirtyTextConversations.remove(conversationId)
        Log.d(TAG, "Cleared dirty flag for text conversation: $conversationId")
    }
    
    /**
     * 清除图像会话的脏标记（保存完成后调用）
     */
    fun clearImageDirtyFlag(conversationId: String) {
        dirtyImageConversations.remove(conversationId)
        Log.d(TAG, "Cleared dirty flag for image conversation: $conversationId")
    }
    
    /**
     * 清除所有脏标记（批量保存完成后调用）
     */
    fun clearAllDirtyFlags() {
        dirtyTextConversations.clear()
        dirtyImageConversations.clear()
        Log.d(TAG, "Cleared all dirty flags")
    }
    
    /**
     * 过滤出需要保存的会话
     */
    suspend fun filterDirtyConversations(
        conversations: List<List<Message>>,
        isImageGeneration: Boolean
    ): List<Pair<Int, List<Message>>> = syncMutex.withLock {
        val dirtySet = if (isImageGeneration) dirtyImageConversations else dirtyTextConversations
        
        if (dirtySet.isEmpty()) {
            Log.d(TAG, "No dirty conversations to save")
            return@withLock emptyList()
        }
        
        val result = conversations.mapIndexedNotNull { index, messages ->
            val conversationId = messages.firstOrNull()?.id
            if (conversationId != null && dirtySet.contains(conversationId)) {
                index to messages
            } else {
                null
            }
        }
        
        Log.d(TAG, "Filtered ${result.size} dirty conversations out of ${conversations.size}")
        result
    }
    
    /**
     * 检查是否有待保存的变更
     */
    fun hasPendingChanges(): Boolean {
        return dirtyTextConversations.isNotEmpty() || dirtyImageConversations.isNotEmpty()
    }
    
    /**
     * 获取待保存变更数量
     */
    fun getPendingChangesCount(): Int {
        return dirtyTextConversations.size + dirtyImageConversations.size
    }
    
    /**
     * 计算会话内容哈希值
     */
    private fun computeConversationHash(messages: List<Message>): Int {
        if (messages.isEmpty()) return 0
        
        var hash = 17
        messages.forEach { message ->
            hash = 31 * hash + message.id.hashCode()
            hash = 31 * hash + message.text.hashCode()
            hash = 31 * hash + (message.reasoning?.hashCode() ?: 0)
            hash = 31 * hash + (message.imageUrls?.hashCode() ?: 0)
            hash = 31 * hash + message.attachments.size
        }
        return hash
    }
    
    /**
     * 更新会话哈希缓存（用于初始化时加载历史数据）
     */
    fun updateConversationHash(
        conversationId: String,
        messages: List<Message>,
        isImageGeneration: Boolean
    ) {
        val hash = computeConversationHash(messages)
        val hashMap = if (isImageGeneration) imageConversationHashes else textConversationHashes
        hashMap[conversationId] = hash
    }
    
    /**
     * 批量更新会话哈希缓存
     */
    fun batchUpdateHashes(
        conversations: List<List<Message>>,
        isImageGeneration: Boolean
    ) {
        conversations.forEach { messages ->
            val conversationId = messages.firstOrNull()?.id
            if (conversationId != null) {
                updateConversationHash(conversationId, messages, isImageGeneration)
            }
        }
        Log.d(TAG, "Batch updated ${conversations.size} conversation hashes")
    }
    
    /**
     * 清理已删除会话的哈希缓存
     */
    fun removeConversationHash(conversationId: String, isImageGeneration: Boolean) {
        val hashMap = if (isImageGeneration) imageConversationHashes else textConversationHashes
        hashMap.remove(conversationId)
        val dirtySet = if (isImageGeneration) dirtyImageConversations else dirtyTextConversations
        dirtySet.remove(conversationId)
        Log.d(TAG, "Removed hash for conversation: $conversationId")
    }
}

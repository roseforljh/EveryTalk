package com.android.everytalk.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 图像历史会话管理器
 * 在客户端本地存储图像生成历史，支持连续对话和参考图
 */
object ImageHistoryManager {
    private const val TAG = "ImageHistoryManager"
    private const val PREFS_NAME = "image_history_prefs"
    private const val KEY_PREFIX_HISTORY = "history_"
    private const val KEY_PREFIX_LAST_IMAGE = "last_image_"
    private const val MAX_HISTORY_PER_CONVERSATION = 50
    private const val MAX_TOTAL_CONVERSATIONS = 100
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * 图像记录数据类
     */
    @Serializable
    data class ImageRecord(
        val timestamp: Long,
        val images: List<String>,  // Data URI 或 URL 列表
        val prompt: String? = null,
        val seed: Int? = null,
        val model: String? = null,
        val aspectRatio: String? = null
    )
    
    /**
     * 会话历史数据类
     */
    @Serializable
    data class ConversationHistory(
        val conversationId: String,
        val records: MutableList<ImageRecord> = mutableListOf(),
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 保存图像到历史记录
     * 
     * @param context 应用上下文
     * @param conversationId 会话ID
     * @param images 图像列表（Data URI 或 URL）
     * @param prompt 生成提示词
     * @param seed 种子值
     * @param model 使用的模型
     * @param aspectRatio 宽高比
     */
    suspend fun saveImages(
        context: Context,
        conversationId: String,
        images: List<String>,
        prompt: String? = null,
        seed: Int? = null,
        model: String? = null,
        aspectRatio: String? = null
    ) = withContext(Dispatchers.IO) {
        if (conversationId.isBlank() || images.isEmpty()) {
            Log.w(TAG, "saveImages: 无效的参数 (conversationId=$conversationId, images.size=${images.size})")
            return@withContext
        }
        
        try {
            val prefs = getPrefs(context)
            val historyKey = KEY_PREFIX_HISTORY + conversationId
            
            // 读取现有历史
            val history = loadHistory(context, conversationId) ?: ConversationHistory(conversationId)
            
            // 添加新记录
            val record = ImageRecord(
                timestamp = System.currentTimeMillis(),
                images = images,
                prompt = prompt,
                seed = seed,
                model = model,
                aspectRatio = aspectRatio
            )
            history.records.add(record)
            
            // 限制记录数量
            while (history.records.size > MAX_HISTORY_PER_CONVERSATION) {
                history.records.removeAt(0)
            }
            
            // 保存历史
            val historyJson = json.encodeToString(history)
            prefs.edit().putString(historyKey, historyJson).apply()
            
            // 更新最后一张图片缓存
            if (images.isNotEmpty()) {
                val lastImageKey = KEY_PREFIX_LAST_IMAGE + conversationId
                prefs.edit().putString(lastImageKey, images.first()).apply()
            }
            
            Log.i(TAG, "保存 ${images.size} 张图片到会话 $conversationId，当前共 ${history.records.size} 条记录")
            
            // 清理过多的会话
            cleanupOldConversations(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "保存图像历史失败", e)
        }
    }
    
    /**
     * 加载会话历史
     */
    suspend fun loadHistory(
        context: Context,
        conversationId: String
    ): ConversationHistory? = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext null
        
        try {
            val prefs = getPrefs(context)
            val historyKey = KEY_PREFIX_HISTORY + conversationId
            val historyJson = prefs.getString(historyKey, null) ?: return@withContext null
            
            json.decodeFromString<ConversationHistory>(historyJson)
        } catch (e: Exception) {
            Log.e(TAG, "加载图像历史失败", e)
            null
        }
    }
    
    /**
     * 列出会话的所有图像记录
     */
    suspend fun listImages(
        context: Context,
        conversationId: String
    ): List<ImageRecord> = withContext(Dispatchers.IO) {
        loadHistory(context, conversationId)?.records ?: emptyList()
    }
    
    /**
     * 获取会话最后生成的图片
     * 用于连续对话时的参考图
     */
    suspend fun getLastImage(
        context: Context,
        conversationId: String
    ): String? = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext null
        
        try {
            val prefs = getPrefs(context)
            val lastImageKey = KEY_PREFIX_LAST_IMAGE + conversationId
            prefs.getString(lastImageKey, null)
        } catch (e: Exception) {
            Log.e(TAG, "获取最后图片失败", e)
            null
        }
    }
    
    /**
     * 设置会话最后生成的图片
     */
    suspend fun setLastImage(
        context: Context,
        conversationId: String,
        imageUrl: String
    ) = withContext(Dispatchers.IO) {
        if (conversationId.isBlank() || imageUrl.isBlank()) return@withContext
        
        try {
            val prefs = getPrefs(context)
            val lastImageKey = KEY_PREFIX_LAST_IMAGE + conversationId
            prefs.edit().putString(lastImageKey, imageUrl).apply()
            Log.d(TAG, "更新会话 $conversationId 的最后图片")
        } catch (e: Exception) {
            Log.e(TAG, "设置最后图片失败", e)
        }
    }
    
    /**
     * 删除会话历史
     */
    suspend fun deleteConversation(
        context: Context,
        conversationId: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext false
        
        try {
            val prefs = getPrefs(context)
            val historyKey = KEY_PREFIX_HISTORY + conversationId
            val lastImageKey = KEY_PREFIX_LAST_IMAGE + conversationId
            
            prefs.edit()
                .remove(historyKey)
                .remove(lastImageKey)
                .apply()
            
            Log.i(TAG, "删除会话 $conversationId 的历史记录")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除会话历史失败", e)
            false
        }
    }
    
    /**
     * 清理过多的会话（保留最近的 MAX_TOTAL_CONVERSATIONS 个）
     */
    private suspend fun cleanupOldConversations(context: Context) = withContext(Dispatchers.IO) {
        try {
            val prefs = getPrefs(context)
            val allKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX_HISTORY) }
            
            if (allKeys.size <= MAX_TOTAL_CONVERSATIONS) return@withContext
            
            // 获取所有会话并按最后更新时间排序
            val conversations = allKeys.mapNotNull { key ->
                try {
                    val historyJson = prefs.getString(key, null) ?: return@mapNotNull null
                    val history = json.decodeFromString<ConversationHistory>(historyJson)
                    Pair(key, history.lastUpdated)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.second }
            
            // 删除最旧的会话
            val toDelete = conversations.take(conversations.size - MAX_TOTAL_CONVERSATIONS)
            val editor = prefs.edit()
            
            toDelete.forEach { (key, _) ->
                editor.remove(key)
                val conversationId = key.removePrefix(KEY_PREFIX_HISTORY)
                editor.remove(KEY_PREFIX_LAST_IMAGE + conversationId)
            }
            
            editor.apply()
            Log.i(TAG, "清理了 ${toDelete.size} 个旧会话")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理旧会话失败", e)
        }
    }
    
    /**
     * 清空所有历史
     */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        try {
            val prefs = getPrefs(context)
            prefs.edit().clear().apply()
            Log.i(TAG, "已清空所有图像历史")
        } catch (e: Exception) {
            Log.e(TAG, "清空历史失败", e)
        }
    }
    
    /**
     * 获取存储统计信息
     */
    suspend fun getStorageStats(context: Context): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val prefs = getPrefs(context)
            val historyKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX_HISTORY) }
            
            var totalRecords = 0
            var totalImages = 0
            
            historyKeys.forEach { key ->
                try {
                    val historyJson = prefs.getString(key, null) ?: return@forEach
                    val history = json.decodeFromString<ConversationHistory>(historyJson)
                    totalRecords += history.records.size
                    history.records.forEach { record ->
                        totalImages += record.images.size
                    }
                } catch (e: Exception) {
                    // 忽略损坏的记录
                }
            }
            
            mapOf(
                "conversationCount" to historyKeys.size,
                "totalRecords" to totalRecords,
                "totalImages" to totalImages
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取存储统计失败", e)
            emptyMap()
        }
    }
}
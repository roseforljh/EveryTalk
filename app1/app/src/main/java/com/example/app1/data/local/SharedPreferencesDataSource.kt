package com.example.app1.data.local

import android.content.Context
import androidx.core.content.edit
import com.example.app1.data.models.ApiConfig // 确认导入 ApiConfig
import com.example.app1.data.models.Message // 确认导入 Message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer // 用于 List<ApiConfig> 和 List<List<Message>>
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.io.println // 或者使用 Android Log

// --- 常量定义 ---
private const val PREFS_NAME = "app_settings" // SharedPreferences 文件名
private const val KEY_API_CONFIG_LIST = "api_config_list_v2" // API 配置列表 Key
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1" // **选中配置 ID Key (增加版本号)**
private const val KEY_CHAT_HISTORY = "chat_history_v1" // 聊天记录 Key
private const val KEY_SAVED_MODEL_NAMES_BY_PROVIDER = "saved_model_names_by_provider_v1" // 模型名称建议 Key

// --- Json 配置实例 ---
private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

// --- DataSource 类定义 ---
class SharedPreferencesDataSource(context: Context) {
    // 获取 SharedPreferences 实例
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- 序列化器定义 (提前定义更清晰) ---
    private val apiConfigListSerializer = ListSerializer(ApiConfig.serializer())
    private val chatHistorySerializer = ListSerializer(ListSerializer(Message.serializer()))
    private val modelNamesMapSerializer = MapSerializer(String.serializer(), SetSerializer(String.serializer()))


    // ================================
    // ===== API 配置相关方法 =========
    // ================================

    /**
     * 从 SharedPreferences 加载 API 配置列表。
     * @return 返回 ApiConfig 列表，如果出错或未找到则返回空列表。
     */
    fun loadApiConfigs(): List<ApiConfig> {
        val jsonString = sharedPrefs.getString(KEY_API_CONFIG_LIST, null)
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(apiConfigListSerializer, jsonString)
            } catch (e: Exception) { // 捕捉更广泛的异常
                println("SharedPreferences: Error loading/decoding API configs: ${e.message}")
                // clearApiConfigs() // 考虑错误时是否清除
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    /**
     * 将 API 配置列表保存到 SharedPreferences。
     * @param configs 要保存的 ApiConfig 列表。
     */
    fun saveApiConfigs(configs: List<ApiConfig>) {
        try {
            val jsonString = json.encodeToString(apiConfigListSerializer, configs)
            sharedPrefs.edit { putString(KEY_API_CONFIG_LIST, jsonString) }
            println("SharedPreferences: Saved ${configs.size} API configs.")
        } catch (e: Exception) {
            println("SharedPreferences: Error saving API configs: ${e.message}")
        }
    }

    /**
     * 清除存储的 API 配置列表。
     */
    fun clearApiConfigs() {
        sharedPrefs.edit { remove(KEY_API_CONFIG_LIST) }
        println("SharedPreferences: Cleared API configs.")
    }

    /**
     * 加载当前选中的 API 配置的 ID。
     * @return 返回选中的配置 ID 字符串，如果未设置则返回 null。
     */
    fun loadSelectedConfigId(): String? {
        val id = sharedPrefs.getString(KEY_SELECTED_API_CONFIG_ID, null)
        println("SharedPreferences: Loaded selected config ID: $id")
        return id
    }

    /**
     * 保存当前选中的 API 配置的 ID。
     * @param configId 要保存的配置 ID，可以为 null 来清除。
     */
    fun saveSelectedConfigId(configId: String?) {
        sharedPrefs.edit { putString(KEY_SELECTED_API_CONFIG_ID, configId) }
        println("SharedPreferences: Saved selected config ID: $configId")
    }

    // ================================
    // ===== 聊天记录相关方法 =========
    // ================================

    /**
     * 从 SharedPreferences 加载聊天记录。
     * @return 返回 List<List<Message>>，如果出错或未找到则返回空列表。
     */
    fun loadChatHistory(): List<List<Message>> {
        val jsonString = sharedPrefs.getString(KEY_CHAT_HISTORY, null)
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(chatHistorySerializer, jsonString).also {
                    println("SharedPreferences: Loaded ${it.size} chat conversations.")
                }
            } catch (e: Exception) {
                println("SharedPreferences: Error loading/decoding chat history: ${e.message}")
                // clearChatHistory() // 考虑错误时是否清除
                emptyList()
            }
        } else {
            println("SharedPreferences: No chat history found.")
            emptyList()
        }
    }

    /**
     * 将完整的聊天记录列表保存到 SharedPreferences。
     * @param history 要保存的 List<List<Message>>。
     */
    fun saveChatHistory(history: List<List<Message>>) {
        try {
            val jsonString = json.encodeToString(chatHistorySerializer, history)
            sharedPrefs.edit { putString(KEY_CHAT_HISTORY, jsonString) }
            println("SharedPreferences: Saved ${history.size} chat conversations.")
        } catch (e: Exception) {
            println("SharedPreferences: Error saving chat history: ${e.message}")
        }
    }

    /**
     * 清除存储的聊天记录。
     */
    fun clearChatHistory() {
        sharedPrefs.edit { remove(KEY_CHAT_HISTORY) }
        println("SharedPreferences: Cleared chat history.")
    }


    // ==============================================
    // ===== 按 Provider 保存的模型名称相关方法 =====
    // ==============================================

    /**
     * 加载按 Provider 分类的已保存模型名称。
     * @return 返回 Map<Provider名称, Set<模型名称>>，如果出错或未找到则返回空 Map。
     */
    fun loadSavedModelNamesByProvider(): Map<String, Set<String>> {
        val jsonString = sharedPrefs.getString(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, null)
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(modelNamesMapSerializer, jsonString).also {
                    println("SharedPreferences: Loaded saved model names for ${it.keys.size} providers.")
                }
            } catch (e: Exception) {
                println("SharedPreferences: Error loading/decoding saved model names: ${e.message}")
                emptyMap()
            }
        } else {
            println("SharedPreferences: No saved model names found.")
            emptyMap()
        }
    }

    /**
     * (私有) 保存按 Provider 分类的模型名称 Map。
     * @param modelNamesMap 要保存的 Map<String, Set<String>>。
     */
    private fun saveModelNamesMap(modelNamesMap: Map<String, Set<String>>) {
        try {
            val jsonString = json.encodeToString(modelNamesMapSerializer, modelNamesMap)
            sharedPrefs.edit { putString(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, jsonString) }
            println("SharedPreferences: Saved model names map.")
        } catch (e: Exception) {
            println("SharedPreferences: Error saving model names map: ${e.message}")
        }
    }

    /**
     * 添加一个模型名称到指定 Provider 的集合中。
     * @param provider Provider 名称。
     * @param modelName 要添加的模型名称。
     */
    fun addSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim(); val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) return
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap.getOrDefault(trimmedProvider, emptySet()).toMutableSet()
        if (currentSet.add(trimmedModelName)) {
            currentMap[trimmedProvider] = currentSet
            saveModelNamesMap(currentMap)
            println("SharedPreferences: Added model name '$trimmedModelName' for provider '$trimmedProvider'.")
        }
    }

    /**
     * 从指定 Provider 的集合中移除一个模型名称。
     * @param provider Provider 名称。
     * @param modelName 要移除的模型名称。
     */
    fun removeSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim(); val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) return
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap[trimmedProvider]?.toMutableSet()
        if (currentSet != null && currentSet.remove(trimmedModelName)) {
            if (currentSet.isEmpty()) { currentMap.remove(trimmedProvider) }
            else { currentMap[trimmedProvider] = currentSet }
            saveModelNamesMap(currentMap)
            println("SharedPreferences: Removed model name '$trimmedModelName' for provider '$trimmedProvider'.")
        }
    }

    /**
     * 清除所有与此应用相关的 SharedPreferences 数据。
     * **注意：** 这会清除所有 API 配置、选中的配置 ID、聊天记录和模型名称建议！
     */
    fun clearAllData() {
        sharedPrefs.edit { clear() } // clear() 会移除所有键值对
        println("SharedPreferences: Cleared all app settings data.")
    }
}
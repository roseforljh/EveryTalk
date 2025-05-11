package com.example.app1.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

// --- 常量定义 ---
private const val TAG = "SPDataSource"
private const val PREFS_NAME = "app_settings"
private const val KEY_API_CONFIG_LIST = "api_config_list_v2"
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1"
private const val KEY_CHAT_HISTORY = "chat_history_v1"
private const val KEY_SAVED_MODEL_NAMES_BY_PROVIDER = "saved_model_names_by_provider_v1"
// BEGIN: 新增用于 DataPersistenceManager 的通用键 (虽然现在没直接用到，但为了统一)
private const val KEY_LAST_OPEN_CHAT = "last_open_chat_v1" // 与 DataPersistenceManager 保持一致
private const val KEY_UI_THEME_COLOR = "ui_theme_color_v1" // 与 DataPersistenceManager 保持一致
// END: 新增

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

class SharedPreferencesDataSource(context: Context) {
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val apiConfigListSerializer = ListSerializer(ApiConfig.serializer())
    private val chatHistorySerializer = ListSerializer(ListSerializer(Message.serializer()))
    private val modelNamesMapSerializer =
        MapSerializer(String.serializer(), SetSerializer(String.serializer()))
    // BEGIN: 为 lastOpenChat 添加序列化器 (如果它与 chatHistory 中的单个聊天结构不同)
    // 如果 lastOpenChat 就是 List<Message>，则可以使用 ListSerializer(Message.serializer())
    private val singleChatSerializer = ListSerializer(Message.serializer())
    // END

    // =================================
    // ===== 通用 SharedPreferences 存取方法 =====
    // =================================
    fun saveString(key: String, value: String?) {
        sharedPrefs.edit { putString(key, value) }
        Log.d(TAG, "saveString: Key '$key' saved. Value starts with: ${value?.take(50)}")
    }

    fun getString(key: String, defaultValue: String?): String? {
        val value = sharedPrefs.getString(key, defaultValue)
        Log.d(TAG, "getString: Key '$key' loaded. Value starts with: ${value?.take(50)}")
        return value
    }

    fun saveInt(key: String, value: Int) {
        sharedPrefs.edit { putInt(key, value) }
        Log.d(TAG, "saveInt: Key '$key' saved with value: $value")
    }

    fun getInt(key: String, defaultValue: Int): Int {
        val value = sharedPrefs.getInt(key, defaultValue)
        Log.d(TAG, "getInt: Key '$key' loaded with value: $value")
        return value
    }

    fun remove(key: String) {
        sharedPrefs.edit { remove(key) }
        Log.d(TAG, "remove: Key '$key' removed.")
    }


    // ================================
    // ===== API 配置相关方法 =========
    // ================================
    fun loadApiConfigs(): List<ApiConfig> {
        val jsonString = getString(KEY_API_CONFIG_LIST, null) // 使用封装的方法
        Log.d(TAG, "loadApiConfigs: JSON from '$KEY_API_CONFIG_LIST': ${jsonString?.take(100)}...")
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(apiConfigListSerializer, jsonString).also {
                    Log.i(TAG, "loadApiConfigs: Decoded ${it.size} configs.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadApiConfigs: Error: ${e.message}", e)
                emptyList()
            }
        } else {
            Log.i(TAG, "loadApiConfigs: No data found.")
            emptyList()
        }
    }

    fun saveApiConfigs(configs: List<ApiConfig>) {
        try {
            val jsonString = json.encodeToString(apiConfigListSerializer, configs)
            saveString(KEY_API_CONFIG_LIST, jsonString) // 使用封装的方法
            Log.i(TAG, "saveApiConfigs: Saved ${configs.size} configs to '$KEY_API_CONFIG_LIST'.")
        } catch (e: Exception) {
            Log.e(TAG, "saveApiConfigs: Error: ${e.message}", e)
        }
    }

    fun clearApiConfigs() {
        remove(KEY_API_CONFIG_LIST) // 使用封装的方法
        Log.i(TAG, "clearApiConfigs: Cleared '$KEY_API_CONFIG_LIST'.")
    }

    fun loadSelectedConfigId(): String? {
        val id = getString(KEY_SELECTED_API_CONFIG_ID, null) // 使用封装的方法
        Log.d(TAG, "loadSelectedConfigId: ID from '$KEY_SELECTED_API_CONFIG_ID': $id")
        return id
    }

    fun saveSelectedConfigId(configId: String?) {
        saveString(KEY_SELECTED_API_CONFIG_ID, configId) // 使用封装的方法
        Log.i(TAG, "saveSelectedConfigId: Saved ID '$configId' to '$KEY_SELECTED_API_CONFIG_ID'.")
    }

    // ================================
    // ===== 聊天记录相关方法 =========
    // ================================
    fun loadChatHistory(): List<List<Message>> {
        Log.e(TAG, "loadChatHistory: ++++++++++++++ START ++++++++++++++")
        val jsonString = getString(KEY_CHAT_HISTORY, null) // 使用封装的方法
        Log.d(TAG, "loadChatHistory: JSON from '$KEY_CHAT_HISTORY' (partial): ${jsonString?.take(150)}...")

        if (!jsonString.isNullOrEmpty()) {
            try {
                val decodedList = json.decodeFromString(chatHistorySerializer, jsonString)
                Log.i(TAG, "loadChatHistory: Decoded ${decodedList.size} conversations.")
                decodedList.forEachIndexed { index, conversation ->
                    Log.d(TAG, "loadChatHistory: Conv $index: ${conversation.size} msgs. Preview: '${conversation.firstOrNull()?.text?.take(30)}'")
                }
                Log.e(TAG, "loadChatHistory: ++++++++++++++ SUCCESS - Size: ${decodedList.size} ++++++++++++++")
                return decodedList
            } catch (e: Exception) {
                Log.e(TAG, "loadChatHistory: Error decoding: ${e.message}", e)
                Log.e(TAG, "loadChatHistory: ++++++++++++++ ERROR RETURN EMPTY ++++++++++++++")
                return emptyList()
            }
        } else {
            Log.i(TAG, "loadChatHistory: No data (JSON null/empty).")
            Log.e(TAG, "loadChatHistory: ++++++++++++++ JSON EMPTY RETURN EMPTY ++++++++++++++")
            return emptyList()
        }
    }

    fun saveChatHistory(history: List<List<Message>>) {
        try {
            Log.i(TAG, "saveChatHistory: Serializing ${history.size} conversations.")
            history.forEachIndexed { index, conversation ->
                Log.d(TAG, "saveChatHistory: Serializing conv $index, ${conversation.size} msgs.")
            }
            val jsonString = json.encodeToString(chatHistorySerializer, history)
            saveString(KEY_CHAT_HISTORY, jsonString) // 使用封装的方法
            Log.i(TAG, "saveChatHistory: Saved ${history.size} conversations to '$KEY_CHAT_HISTORY'.")
        } catch (e: Exception) {
            Log.e(TAG, "saveChatHistory: Error: ${e.message}", e)
        }
    }

    fun clearChatHistory() {
        remove(KEY_CHAT_HISTORY) // 使用封装的方法
        Log.i(TAG, "clearChatHistory: Cleared '$KEY_CHAT_HISTORY'.")
    }

    // ==============================================
    // ===== 按 Provider 保存的模型名称相关方法 =====
    // ==============================================
    fun loadSavedModelNamesByProvider(): Map<String, Set<String>> {
        val jsonString = getString(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, null) // 使用封装的方法
        Log.d(TAG, "loadSavedModelNamesByProvider: JSON from '$KEY_SAVED_MODEL_NAMES_BY_PROVIDER': ${jsonString?.take(100)}...")
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(modelNamesMapSerializer, jsonString).also {
                    Log.i(TAG, "loadSavedModelNamesByProvider: Loaded names for ${it.keys.size} providers.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadSavedModelNamesByProvider: Error: ${e.message}", e)
                emptyMap()
            }
        } else {
            Log.i(TAG, "loadSavedModelNamesByProvider: No data found.")
            emptyMap()
        }
    }

    private fun saveModelNamesMap(modelNamesMap: Map<String, Set<String>>) {
        try {
            val jsonString = json.encodeToString(modelNamesMapSerializer, modelNamesMap)
            saveString(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, jsonString) // 使用封装的方法
            Log.i(TAG, "saveModelNamesMap: Saved map to '$KEY_SAVED_MODEL_NAMES_BY_PROVIDER'.")
        } catch (e: Exception) {
            Log.e(TAG, "saveModelNamesMap: Error: ${e.message}", e)
        }
    }

    fun addSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) {
            Log.w(TAG, "addSavedModelName: Blank provider or modelName. Provider: '$provider', ModelName: '$modelName'")
            return
        }
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap.getOrDefault(trimmedProvider, emptySet()).toMutableSet()
        if (currentSet.add(trimmedModelName)) {
            currentMap[trimmedProvider] = currentSet
            saveModelNamesMap(currentMap)
            Log.i(TAG, "addSavedModelName: Added '$trimmedModelName' for provider '$trimmedProvider'.")
        } else {
            Log.d(TAG, "addSavedModelName: '$trimmedModelName' already exists for '$trimmedProvider'.")
        }
    }

    fun removeSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) {
            Log.w(TAG, "removeSavedModelName: Blank provider or modelName. Provider: '$provider', ModelName: '$modelName'")
            return
        }
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap[trimmedProvider]?.toMutableSet()
        if (currentSet != null && currentSet.remove(trimmedModelName)) {
            if (currentSet.isEmpty()) {
                currentMap.remove(trimmedProvider)
                Log.d(TAG, "removeSavedModelName: Provider '$trimmedProvider' set empty, removed provider.")
            } else {
                currentMap[trimmedProvider] = currentSet
            }
            saveModelNamesMap(currentMap)
            Log.i(TAG, "removeSavedModelName: Removed '$trimmedModelName' from '$trimmedProvider'.")
        } else {
            Log.d(TAG, "removeSavedModelName: '$trimmedModelName' not found for '$trimmedProvider' or provider not found.")
        }
    }

    // ==============================================
    // ===== 特定用于 DataPersistenceManager 的方法（如果需要更细粒度的控制） =====
    // 这些键名应该与 DataPersistenceManager 中使用的键名一致
    // ==============================================

    fun saveLastOpenChatInternal(messages: List<Message>) {
        try {
            if (messages.isEmpty()) {
                remove(KEY_LAST_OPEN_CHAT)
                Log.d(TAG, "saveLastOpenChatInternal: Last open chat empty, removed key '$KEY_LAST_OPEN_CHAT'.")
            } else {
                val chatJson = json.encodeToString(singleChatSerializer, messages)
                saveString(KEY_LAST_OPEN_CHAT, chatJson)
                Log.d(TAG, "saveLastOpenChatInternal: Saved last open chat (${messages.size} msgs) to '$KEY_LAST_OPEN_CHAT'.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveLastOpenChatInternal: Error saving last open chat: ${e.message}", e)
        }
    }

    fun loadLastOpenChatInternal(): List<Message> {
        val chatJson = getString(KEY_LAST_OPEN_CHAT, null)
        return if (chatJson != null) {
            try {
                json.decodeFromString<List<Message>>(singleChatSerializer, chatJson) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "loadLastOpenChatInternal: Error decoding last open chat: ${e.message}", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }


    fun clearAllData() {
        sharedPrefs.edit { clear() }
        Log.w(TAG, "clearAllData: Cleared all data from '$PREFS_NAME'!")
    }
}
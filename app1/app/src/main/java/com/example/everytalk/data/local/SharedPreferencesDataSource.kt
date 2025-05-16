package com.example.everytalk.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.everytalk.data.DataClass.ApiConfig // 确保导入你修改后的 ApiConfig
import com.example.everytalk.data.DataClass.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

// --- 常量定义 ---
private const val TAG = "SPDataSource"
private const val PREFS_NAME = "app_settings"
private const val KEY_API_CONFIG_LIST = "api_config_list_v2" // 如果 ApiConfig 结构变化大，可以考虑更新版本号 e.g., v3
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1"
private const val KEY_CHAT_HISTORY = "chat_history_v1"
private const val KEY_SAVED_MODEL_NAMES_BY_PROVIDER = "saved_model_names_by_provider_v1"
private const val KEY_LAST_OPEN_CHAT = "last_open_chat_v1"
private const val KEY_UI_THEME_COLOR = "ui_theme_color_v1" // 与 DataPersistenceManager 保持一致

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
    isLenient = true
}

class SharedPreferencesDataSource(context: Context) {
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val apiConfigListSerializer: KSerializer<List<ApiConfig>> =
        ListSerializer(ApiConfig.serializer()) // 使用你更新后的 ApiConfig.serializer()
    private val chatHistorySerializer: KSerializer<List<List<Message>>> =
        ListSerializer(ListSerializer(Message.serializer()))
    private val modelNamesMapSerializer: KSerializer<Map<String, Set<String>>> =
        MapSerializer(String.serializer(), SetSerializer(String.serializer()))
    private val singleChatSerializer: KSerializer<List<Message>> =
        ListSerializer(Message.serializer())


    // =================================
    // ===== 通用 SharedPreferences 存取方法 =====
    // =================================
    private fun <T> saveData(key: String, value: T, serializer: KSerializer<T>) {
        try {
            val jsonString = json.encodeToString(serializer, value)
            sharedPrefs.edit { putString(key, jsonString) }
            Log.d(TAG, "saveData: Key '$key' saved. Data preview: ${jsonString.take(100)}...")
        } catch (e: SerializationException) {
            Log.e(TAG, "saveData: Serialization error for key '$key'. ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "saveData: Unexpected error saving data for key '$key'. ${e.message}", e)
        }
    }

    private fun <T> loadData(key: String, serializer: KSerializer<T>, defaultValue: T): T {
        val jsonString = sharedPrefs.getString(key, null)
        Log.d(
            TAG,
            "loadData: Attempting to load key '$key'. JSON preview: ${jsonString?.take(100)}..."
        )
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(serializer, jsonString).also {
                    Log.d(TAG, "loadData: Successfully decoded data for key '$key'.")
                }
            } catch (e: SerializationException) {
                Log.e(
                    TAG,
                    "loadData: Serialization error decoding key '$key'. Returning default. ${e.message}",
                    e
                )
                defaultValue
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "loadData: Unexpected error decoding key '$key'. Returning default. ${e.message}",
                    e
                )
                defaultValue
            }
        } else {
            Log.d(TAG, "loadData: No data found for key '$key'. Returning default.")
            defaultValue
        }
    }

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
        Log.d(TAG, "loadApiConfigs: Attempting to load API configs.")
        val jsonString = sharedPrefs.getString(KEY_API_CONFIG_LIST, null)
        Log.d(TAG, "loadApiConfigs: JSON from '$KEY_API_CONFIG_LIST': ${jsonString?.take(200)}...") // 增加预览长度

        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(apiConfigListSerializer, jsonString).also { configs ->
                    Log.i(TAG, "loadApiConfigs: Successfully decoded ${configs.size} API configs.")
                    // --- BEGIN: 添加详细打印每个 ApiConfig 的日志 ---
                    configs.forEachIndexed { index, config ->
                        Log.d(TAG, "Loaded ApiConfig[$index]: " +
                                "ID='${config.id}', " +
                                "Name='${config.name}', " +
                                "Address='${config.address}', " + // <<<< 重点关注这个 address
                                "Key (last 4)='...${config.key.takeLast(4)}', " +
                                "Model='${config.model}', " +
                                "Provider='${config.provider}', " +
                                "IsValid='${config.isValid}'")
                    }
                    // --- END: 添加详细打印每个 ApiConfig 的日志 ---
                }
            } catch (e: SerializationException) {
                Log.e(TAG, "loadApiConfigs: Serialization error decoding API configs. ${e.message}", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "loadApiConfigs: Unexpected error decoding API configs. ${e.message}", e)
                emptyList()
            }
        } else {
            Log.i(TAG, "loadApiConfigs: No API config data found for key '$KEY_API_CONFIG_LIST'. Returning empty list.")
            emptyList()
        }
    }

    fun saveApiConfigs(configs: List<ApiConfig>) {
        Log.i(TAG, "saveApiConfigs: Attempting to save ${configs.size} API configs.")
        // --- BEGIN: 添加保存前打印每个 ApiConfig 的日志 (可选，但有助于调试保存的数据) ---
        configs.forEachIndexed { index, config ->
            Log.d(TAG, "Saving ApiConfig[$index]: " +
                    "ID='${config.id}', " +
                    "Name='${config.name}', " +
                    "Address='${config.address}', " +
                    "Key (last 4)='...${config.key.takeLast(4)}', " +
                    "Model='${config.model}', " +
                    "Provider='${config.provider}', " +
                    "IsValid='${config.isValid}'")
        }
        // --- END: 添加保存前打印每个 ApiConfig 的日志 ---
        saveData(KEY_API_CONFIG_LIST, configs, apiConfigListSerializer)
    }

    fun clearApiConfigs() {
        remove(KEY_API_CONFIG_LIST)
        Log.i(TAG, "clearApiConfigs: Cleared '$KEY_API_CONFIG_LIST'.")
    }

    fun loadSelectedConfigId(): String? {
        val id = getString(KEY_SELECTED_API_CONFIG_ID, null)
        Log.d(TAG, "loadSelectedConfigId: ID from '$KEY_SELECTED_API_CONFIG_ID': $id")
        return id
    }

    fun saveSelectedConfigId(configId: String?) {
        saveString(KEY_SELECTED_API_CONFIG_ID, configId)
        Log.i(TAG, "saveSelectedConfigId: Saved ID '$configId' to '$KEY_SELECTED_API_CONFIG_ID'.")
    }

    // ================================
    // ===== 聊天记录相关方法 =========
    // ================================
    fun loadChatHistory(): List<List<Message>> {
        Log.d(TAG, "loadChatHistory: Attempting to load chat history.")
        return loadData(KEY_CHAT_HISTORY, chatHistorySerializer, emptyList()).also { history ->
            Log.i(TAG, "loadChatHistory: Loaded ${history.size} conversations.")
            if (history.isNotEmpty()) {
                history.take(3).forEachIndexed { index, conversation ->
                    Log.d(
                        TAG,
                        "loadChatHistory: Preview Conv $index: ${conversation.size} msgs. First msg: '${
                            conversation.firstOrNull()?.text?.take(30)
                        }'"
                    )
                }
            }
        }
    }

    fun saveChatHistory(history: List<List<Message>>) {
        Log.i(TAG, "saveChatHistory: Attempting to save ${history.size} conversations.")
        saveData(KEY_CHAT_HISTORY, history, chatHistorySerializer)
    }

    fun clearChatHistory() {
        remove(KEY_CHAT_HISTORY)
        Log.i(TAG, "clearChatHistory: Cleared '$KEY_CHAT_HISTORY'.")
    }

    // ==============================================
    // ===== 按 Provider 保存的模型名称相关方法 =====
    // ==============================================
    fun loadSavedModelNamesByProvider(): Map<String, Set<String>> {
        Log.d(TAG, "loadSavedModelNamesByProvider: Attempting to load model names.")
        return loadData(
            KEY_SAVED_MODEL_NAMES_BY_PROVIDER,
            modelNamesMapSerializer,
            emptyMap()
        ).also {
            Log.i(
                TAG,
                "loadSavedModelNamesByProvider: Loaded model names for ${it.keys.size} providers."
            )
        }
    }

    private fun saveModelNamesMap(modelNamesMap: Map<String, Set<String>>) {
        Log.d(TAG, "saveModelNamesMap: Attempting to save model names map.")
        saveData(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, modelNamesMap, modelNamesMapSerializer)
    }

    fun addSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) {
            Log.w(
                TAG,
                "addSavedModelName: Blank provider or modelName. Provider: '$provider', ModelName: '$modelName'"
            )
            return
        }
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap.getOrDefault(trimmedProvider, emptySet()).toMutableSet()

        if (currentSet.add(trimmedModelName)) {
            currentMap[trimmedProvider] = currentSet
            saveModelNamesMap(currentMap)
            Log.i(
                TAG,
                "addSavedModelName: Added '$trimmedModelName' for provider '$trimmedProvider'."
            )
        } else {
            Log.d(
                TAG,
                "addSavedModelName: '$trimmedModelName' already exists for '$trimmedProvider'. No change."
            )
        }
    }

    fun removeSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) {
            Log.w(
                TAG,
                "removeSavedModelName: Blank provider or modelName. Provider: '$provider', ModelName: '$modelName'"
            )
            return
        }
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap[trimmedProvider]?.toMutableSet()

        if (currentSet != null && currentSet.remove(trimmedModelName)) {
            if (currentSet.isEmpty()) {
                currentMap.remove(trimmedProvider)
                Log.d(
                    TAG,
                    "removeSavedModelName: Provider '$trimmedProvider' set became empty, removed provider entry."
                )
            } else {
                currentMap[trimmedProvider] = currentSet
            }
            saveModelNamesMap(currentMap)
            Log.i(TAG, "removeSavedModelName: Removed '$trimmedModelName' from '$trimmedProvider'.")
        } else {
            Log.d(
                TAG,
                "removeSavedModelName: '$trimmedModelName' not found for '$trimmedProvider' or provider not found. No change."
            )
        }
    }

    // ==============================================
    // ===== 特定用于 DataPersistenceManager 的方法 =====
    // ==============================================
    fun saveLastOpenChatInternal(messages: List<Message>) {
        Log.d(
            TAG,
            "saveLastOpenChatInternal: Attempting to save last open chat with ${messages.size} messages."
        )
        if (messages.isEmpty()) {
            remove(KEY_LAST_OPEN_CHAT)
            Log.d(
                TAG,
                "saveLastOpenChatInternal: Last open chat was empty, removed key '$KEY_LAST_OPEN_CHAT'."
            )
        } else {
            saveData(KEY_LAST_OPEN_CHAT, messages, singleChatSerializer)
        }
    }

    fun loadLastOpenChatInternal(): List<Message> {
        Log.d(TAG, "loadLastOpenChatInternal: Attempting to load last open chat.")
        return loadData(KEY_LAST_OPEN_CHAT, singleChatSerializer, emptyList()).also {
            Log.d(TAG, "loadLastOpenChatInternal: Loaded ${it.size} messages for last open chat.")
        }
    }

}
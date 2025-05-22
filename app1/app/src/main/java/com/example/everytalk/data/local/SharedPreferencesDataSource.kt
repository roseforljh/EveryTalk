package com.example.everytalk.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.everytalk.data.DataClass.ApiConfig
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
private const val KEY_API_CONFIG_LIST = "api_config_list_v2"
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1"
private const val KEY_CHAT_HISTORY = "chat_history_v1"
private const val KEY_SAVED_MODEL_NAMES_BY_API_ADDRESS =
    "saved_model_names_by_api_address_v2"
private const val KEY_LAST_OPEN_CHAT = "last_open_chat_v1"
private const val KEY_CUSTOM_PROVIDERS = "custom_providers_v1"


private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
    isLenient = true
}

class SharedPreferencesDataSource(context: Context) {
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Serializers
    private val apiConfigListSerializer: KSerializer<List<ApiConfig>> =
        ListSerializer(ApiConfig.serializer())
    private val chatHistorySerializer: KSerializer<List<List<Message>>> =
        ListSerializer(ListSerializer(Message.serializer()))
    private val modelNamesMapByAddressSerializer: KSerializer<Map<String, Set<String>>> =
        MapSerializer(String.serializer(), SetSerializer(String.serializer()))
    private val singleChatSerializer: KSerializer<List<Message>> =
        ListSerializer(Message.serializer())
    private val customProvidersSerializer: KSerializer<Set<String>> =
        SetSerializer(String.serializer())

    // =================================
    // ===== 通用 SharedPreferences 存取方法 =====
    // =================================
    private fun <T> saveData(key: String, value: T, serializer: KSerializer<T>) {
        try {
            val jsonString = json.encodeToString(serializer, value)
            sharedPrefs.edit { putString(key, jsonString) }
            Log.d(
                TAG,
                "saveData: Key '$key' saved. Data preview: ${jsonString.take(150)}..."
            ) // 增加预览长度
        } catch (e: SerializationException) {
            Log.e(
                TAG,
                "saveData: Serialization error for key '$key'. Value: $value. Error: ${e.message}",
                e
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "saveData: Unexpected error saving data for key '$key'. Value: $value. Error: ${e.message}",
                e
            )
        }
    }

    private fun <T> loadData(key: String, serializer: KSerializer<T>, defaultValue: T): T {
        val jsonString = sharedPrefs.getString(key, null)
        // ★ 增强日志：打印尝试加载的Key和获取到的原始JSON字符串（如果存在）★
        Log.d(
            TAG,
            "loadData: Attempting to load key '$key'. Raw JSON string: ${jsonString?.take(200)}"
        )
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(serializer, jsonString).also {
                    Log.i(TAG, "loadData: Successfully decoded data for key '$key'.") // 改为 Info 级别
                }
            } catch (e: SerializationException) {
                // ★ 增强日志：打印更详细的序列化错误信息，包括原始JSON ★
                Log.e(
                    TAG,
                    "loadData: SERIALIZATION ERROR decoding key '$key'. JSON: '$jsonString'. Returning default. Error: ${e.message}",
                    e
                )
                defaultValue
            } catch (e: Exception) {
                // ★ 增强日志：打印更详细的未知错误信息 ★
                Log.e(
                    TAG,
                    "loadData: UNEXPECTED ERROR decoding key '$key'. JSON: '$jsonString'. Returning default. Error: ${e.message}",
                    e
                )
                defaultValue
            }
        } else {
            // ★ 增强日志：明确数据未找到或为空 ★
            Log.w(
                TAG,
                "loadData: No data found for key '$key' or string is empty. Returning default."
            ) // 改为 Warning 级别
            defaultValue
        }
    }

    fun saveString(key: String, value: String?) {
        sharedPrefs.edit { putString(key, value) }
        Log.d(TAG, "saveString: Key '$key' saved. Value: '$value'")
    }

    fun getString(key: String, defaultValue: String?): String? {
        val value = sharedPrefs.getString(key, defaultValue)
        // ★ 增强日志：区分找到值、使用默认值、无默认值返回null的情况 ★
        if (sharedPrefs.contains(key)) { // 先检查key是否存在
            Log.d(TAG, "getString: Key '$key' found. Value loaded: '$value'")
        } else {
            Log.w(TAG, "getString: Key '$key' not found. Returning default value: '$defaultValue'")
        }
        return value
    }

    fun remove(key: String) {
        sharedPrefs.edit { remove(key) }
        Log.i(TAG, "remove: Key '$key' removed from SharedPreferences.")
    }


    fun loadApiConfigs(): List<ApiConfig> {
        Log.d(TAG, "loadApiConfigs: Loading API configs for key '$KEY_API_CONFIG_LIST'")
        return loadData(KEY_API_CONFIG_LIST, apiConfigListSerializer, emptyList())
    }

    fun saveApiConfigs(configs: List<ApiConfig>) {
        Log.d(
            TAG,
            "saveApiConfigs: Saving ${configs.size} API configs to key '$KEY_API_CONFIG_LIST'"
        )
        saveData(KEY_API_CONFIG_LIST, configs, apiConfigListSerializer)
    }

    fun loadSelectedConfigId(): String? {
        Log.d(
            TAG,
            "loadSelectedConfigId: Loading selected config ID for key '$KEY_SELECTED_API_CONFIG_ID'"
        )
        return getString(KEY_SELECTED_API_CONFIG_ID, null)
    }

    fun saveSelectedConfigId(configId: String?) {
        Log.d(
            TAG,
            "saveSelectedConfigId: Saving selected config ID '$configId' to key '$KEY_SELECTED_API_CONFIG_ID'"
        )
        saveString(KEY_SELECTED_API_CONFIG_ID, configId)
    }


    fun loadChatHistory(): List<List<Message>> {
        Log.d(TAG, "loadChatHistory: Loading chat history for key '$KEY_CHAT_HISTORY'")
        return loadData(KEY_CHAT_HISTORY, chatHistorySerializer, emptyList())
    }

    fun saveChatHistory(history: List<List<Message>>) {
        Log.d(
            TAG,
            "saveChatHistory: Saving ${history.size} conversations to key '$KEY_CHAT_HISTORY'"
        )
        saveData(KEY_CHAT_HISTORY, history, chatHistorySerializer)
    }

    fun saveCustomProviders(providers: Set<String>) {
        val trimmedProviders = providers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        Log.d(
            TAG,
            "saveCustomProviders: Saving ${trimmedProviders.size} custom providers to key '$KEY_CUSTOM_PROVIDERS'"
        )
        saveData(KEY_CUSTOM_PROVIDERS, trimmedProviders, customProvidersSerializer)
    }

    fun loadCustomProviders(): Set<String> {
        Log.d(TAG, "loadCustomProviders: Loading custom providers for key '$KEY_CUSTOM_PROVIDERS'")
        return loadData(KEY_CUSTOM_PROVIDERS, customProvidersSerializer, emptySet())
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun clearApiConfigs() {
        Log.i(TAG, "clearApiConfigs: Clearing API configs for key '$KEY_API_CONFIG_LIST'.")
        remove(KEY_API_CONFIG_LIST)
    }

    fun clearChatHistory() {
        Log.i(TAG, "clearChatHistory: Clearing chat history for key '$KEY_CHAT_HISTORY'.")
        remove(KEY_CHAT_HISTORY)
    }

    fun loadSavedModelNamesByApiAddress(): Map<String, Set<String>> {
        Log.d(
            TAG,
            "loadSavedModelNamesByApiAddress: Loading model names for key '$KEY_SAVED_MODEL_NAMES_BY_API_ADDRESS'."
        )
        return loadData(
            KEY_SAVED_MODEL_NAMES_BY_API_ADDRESS,
            modelNamesMapByAddressSerializer,
            emptyMap()
        ).also {
            Log.i(
                TAG,
                "loadSavedModelNamesByApiAddress: Loaded model names for ${it.keys.size} API addresses."
            )
        }
    }

    private fun saveModelNamesMapByApiAddress(modelNamesMap: Map<String, Set<String>>) {
        Log.d(
            TAG,
            "saveModelNamesMapByApiAddress: Saving model names map (${modelNamesMap.size} addresses) to key '$KEY_SAVED_MODEL_NAMES_BY_API_ADDRESS'."
        )
        saveData(
            KEY_SAVED_MODEL_NAMES_BY_API_ADDRESS,
            modelNamesMap,
            modelNamesMapByAddressSerializer
        )
    }

    fun addSavedModelNameForApiAddress(apiAddress: String, modelName: String) {
        val trimmedAddress = apiAddress.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedAddress.isBlank() || trimmedModelName.isBlank()) {
            Log.w(
                TAG,
                "addSavedModelNameForApiAddress: Blank address or modelName. Address: '$apiAddress', ModelName: '$modelName'"
            )
            return
        }
        val currentMap = loadSavedModelNamesByApiAddress().toMutableMap() // This will log loading
        val currentSet = currentMap.getOrDefault(trimmedAddress, emptySet()).toMutableSet()

        if (currentSet.add(trimmedModelName)) {
            currentMap[trimmedAddress] = currentSet
            saveModelNamesMapByApiAddress(currentMap) // This will log saving
            Log.i(
                TAG,
                "addSavedModelNameForApiAddress: Added '$trimmedModelName' for API address '$trimmedAddress'."
            )
        }
    }

    fun removeSavedModelNameForApiAddress(apiAddress: String, modelName: String) {
        val trimmedAddress = apiAddress.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedAddress.isBlank() || trimmedModelName.isBlank()) {
            Log.w(
                TAG,
                "removeSavedModelNameForApiAddress: Blank address or modelName. Address: '$apiAddress', ModelName: '$modelName'"
            )
            return
        }
        val currentMap = loadSavedModelNamesByApiAddress().toMutableMap() // This will log loading
        val currentSet = currentMap[trimmedAddress]?.toMutableSet()

        if (currentSet != null && currentSet.remove(trimmedModelName)) {
            if (currentSet.isEmpty()) {
                currentMap.remove(trimmedAddress)
            } else {
                currentMap[trimmedAddress] = currentSet
            }
            saveModelNamesMapByApiAddress(currentMap) // This will log saving
            Log.i(
                TAG,
                "removeSavedModelNameForApiAddress: Removed '$trimmedModelName' from '$trimmedAddress'."
            )
        }
    }

    fun saveLastOpenChatInternal(messages: List<Message>) {
        Log.d(
            TAG,
            "saveLastOpenChatInternal: Saving ${messages.size} messages to key '$KEY_LAST_OPEN_CHAT'."
        )
        if (messages.isEmpty()) {
            Log.d(
                TAG,
                "saveLastOpenChatInternal: Message list is empty, removing key '$KEY_LAST_OPEN_CHAT'."
            )
            remove(KEY_LAST_OPEN_CHAT)
        } else {
            saveData(KEY_LAST_OPEN_CHAT, messages, singleChatSerializer)
        }
    }

    fun loadLastOpenChatInternal(): List<Message> {
        Log.d(
            TAG,
            "loadLastOpenChatInternal: Loading last open chat for key '$KEY_LAST_OPEN_CHAT'."
        )
        return loadData(KEY_LAST_OPEN_CHAT, singleChatSerializer, emptyList())
    }
}
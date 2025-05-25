package com.example.everytalk.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

// MapSerializer 可能不再需要，如果其他地方也没用的话可以移除
// import kotlinx.serialization.builtins.MapSerializer


// --- 常量定义 ---
private const val TAG = "SPDataSource"
private const val PREFS_NAME = "app_settings"
private const val KEY_API_CONFIG_LIST = "api_config_list_v2"
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1"
private const val KEY_CHAT_HISTORY = "chat_history_v1"
private const val KEY_LAST_OPEN_CHAT = "last_open_chat_v1"
private const val KEY_CUSTOM_PROVIDERS = "custom_providers_v1"

// 所有与 KEY_SAVED_MODEL_NAMES_... 相关的常量已移除

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

    // stringToStringSetMapSerializer 已移除，因为它仅用于模型名称历史建议
    private val singleChatSerializer: KSerializer<List<Message>> =
        ListSerializer(Message.serializer())
    private val customProvidersSerializer: KSerializer<Set<String>> =
        SetSerializer(String.serializer())

    private fun <T> saveData(key: String, value: T, serializer: KSerializer<T>) {
        try {
            val jsonString = json.encodeToString(serializer, value)
            sharedPrefs.edit { putString(key, jsonString) }
            Log.d(TAG, "saveData: Key '$key' saved. Data preview: ${jsonString.take(150)}...")
        } catch (e: SerializationException) {
            Log.e(
                TAG,
                "saveData: Serialization error for key '$key'. Value (type: ${value?.let { it::class.simpleName } ?: "null"}). Error: ${e.message}",
                e)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "saveData: Unexpected error saving data for key '$key'. Value (type: ${value?.let { it::class.simpleName } ?: "null"}). Error: ${e.message}",
                e)
        }
    }

    private fun <T> loadData(key: String, serializer: KSerializer<T>, defaultValue: T): T {
        val jsonString = sharedPrefs.getString(key, null)
        Log.d(
            TAG,
            "loadData: Attempting to load key '$key'. Raw JSON string: ${jsonString?.take(200)}"
        )
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(serializer, jsonString).also {
                    Log.i(TAG, "loadData: Successfully decoded data for key '$key'.")
                }
            } catch (e: SerializationException) {
                Log.e(
                    TAG,
                    "loadData: SERIALIZATION ERROR decoding key '$key'. JSON: '$jsonString'. Returning default. Error: ${e.message}",
                    e
                )
                defaultValue
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "loadData: UNEXPECTED ERROR decoding key '$key'. JSON: '$jsonString'. Returning default. Error: ${e.message}",
                    e
                )
                defaultValue
            }
        } else {
            Log.w(
                TAG,
                "loadData: No data found for key '$key' or string is empty. Returning default."
            )
            defaultValue
        }
    }

    fun saveString(key: String, value: String?) {
        sharedPrefs.edit { putString(key, value) }
        Log.d(TAG, "saveString: Key '$key' saved. Value: '$value'")
    }

    fun getString(key: String, defaultValue: String?): String? {
        val value = sharedPrefs.getString(key, defaultValue)
        if (sharedPrefs.contains(key)) {
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

    fun loadApiConfigs(): List<ApiConfig> =
        loadData(KEY_API_CONFIG_LIST, apiConfigListSerializer, emptyList())

    fun saveApiConfigs(configs: List<ApiConfig>) =
        saveData(KEY_API_CONFIG_LIST, configs, apiConfigListSerializer)

    fun loadSelectedConfigId(): String? = getString(KEY_SELECTED_API_CONFIG_ID, null)
    fun saveSelectedConfigId(configId: String?) = saveString(KEY_SELECTED_API_CONFIG_ID, configId)
    fun loadChatHistory(): List<List<Message>> =
        loadData(KEY_CHAT_HISTORY, chatHistorySerializer, emptyList())

    fun saveChatHistory(history: List<List<Message>>) =
        saveData(KEY_CHAT_HISTORY, history, chatHistorySerializer)

    fun saveCustomProviders(providers: Set<String>) {
        val trimmedProviders = providers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        saveData(KEY_CUSTOM_PROVIDERS, trimmedProviders, customProvidersSerializer)
    }

    fun loadCustomProviders(): Set<String> =
        loadData(KEY_CUSTOM_PROVIDERS, customProvidersSerializer, emptySet())
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun clearApiConfigs() = remove(KEY_API_CONFIG_LIST)
    fun clearChatHistory() = remove(KEY_CHAT_HISTORY)

    // --- 所有与模型名称历史建议相关的方法均已移除 ---

    fun saveLastOpenChatInternal(messages: List<Message>) {
        if (messages.isEmpty()) remove(KEY_LAST_OPEN_CHAT)
        else saveData(KEY_LAST_OPEN_CHAT, messages, singleChatSerializer)
    }

    fun loadLastOpenChatInternal(): List<Message> =
        loadData(KEY_LAST_OPEN_CHAT, singleChatSerializer, emptyList())
}
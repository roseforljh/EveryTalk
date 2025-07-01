package com.example.everytalk.data.local

import android.content.Context
import androidx.core.content.edit
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

private const val PREFS_NAME = "app_settings"
private const val KEY_API_CONFIG_LIST = "api_config_list_v2"
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1"
private const val KEY_CHAT_HISTORY = "chat_history_v1"
private const val KEY_LAST_OPEN_CHAT = "last_open_chat_v1"
private const val KEY_CUSTOM_PROVIDERS = "custom_providers_v1"


private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = false
    isLenient = true
}

class SharedPreferencesDataSource(context: Context) {
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val apiConfigListSerializer: KSerializer<List<ApiConfig>> =
        ListSerializer(ApiConfig.serializer())
    private val chatHistorySerializer: KSerializer<List<List<Message>>> =
        ListSerializer(ListSerializer(Message.serializer()))

    private val singleChatSerializer: KSerializer<List<Message>> =
        ListSerializer(Message.serializer())
    private val customProvidersSerializer: KSerializer<Set<String>> =
        SetSerializer(String.serializer())

    private fun <T> saveData(key: String, value: T, serializer: KSerializer<T>) {
        try {
            val jsonString = json.encodeToString(serializer, value)
            sharedPrefs.edit { putString(key, jsonString) }
        } catch (e: SerializationException) {
            android.util.Log.e("DataSource", "Failed to serialize data for key: $key", e)
        } catch (e: Exception) {
            android.util.Log.e("DataSource", "An unexpected error occurred while saving data for key: $key", e)
        }
    }

    private fun <T> loadData(key: String, serializer: KSerializer<T>, defaultValue: T): T {
        val jsonString = sharedPrefs.getString(key, null)
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(serializer, jsonString)
            } catch (e: SerializationException) {
                android.util.Log.e("DataSource", "Failed to deserialize data for key: $key", e)
                defaultValue
            } catch (e: Exception) {
                android.util.Log.e("DataSource", "An unexpected error occurred while loading data for key: $key", e)
                defaultValue
            }
        } else {
            defaultValue
        }
    }

    fun saveString(key: String, value: String?) {
        sharedPrefs.edit { putString(key, value) }
    }

    fun getString(key: String, defaultValue: String?): String? {
        return sharedPrefs.getString(key, defaultValue)
    }

    fun remove(key: String) {
        sharedPrefs.edit { remove(key) }
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


    fun saveLastOpenChatInternal(messages: List<Message>) {
        if (messages.isEmpty()) remove(KEY_LAST_OPEN_CHAT)
        else saveData(KEY_LAST_OPEN_CHAT, messages, singleChatSerializer)
    }

    fun loadLastOpenChatInternal(): List<Message> =
        loadData(KEY_LAST_OPEN_CHAT, singleChatSerializer, emptyList())
}
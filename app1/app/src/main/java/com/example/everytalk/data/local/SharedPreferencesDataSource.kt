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
private const val KEY_IMAGE_GEN_API_CONFIG_LIST = "image_gen_api_config_list_v1"
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1"
private const val KEY_SELECTED_IMAGE_GEN_API_CONFIG_ID = "selected_image_gen_api_config_id_v1"
private const val KEY_CHAT_HISTORY = "chat_history_v1"
private const val KEY_IMAGE_GENERATION_HISTORY = "image_generation_history_v1"
private const val KEY_LAST_OPEN_CHAT = "last_open_chat_v1"
private const val KEY_CUSTOM_PROVIDERS = "custom_providers_v1"
private const val KEY_SYSTEM_PROMPT = "system_prompt_v1"
private const val KEY_LAST_OPEN_IMAGE_GENERATION = "last_open_image_generation_v1"


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

    fun loadImageGenApiConfigs(): List<ApiConfig> =
        loadData(KEY_IMAGE_GEN_API_CONFIG_LIST, apiConfigListSerializer, emptyList())

    fun saveImageGenApiConfigs(configs: List<ApiConfig>) =
        saveData(KEY_IMAGE_GEN_API_CONFIG_LIST, configs, apiConfigListSerializer)

    fun loadSelectedImageGenConfigId(): String? = getString(KEY_SELECTED_IMAGE_GEN_API_CONFIG_ID, null)
    fun saveSelectedImageGenConfigId(configId: String?) = saveString(KEY_SELECTED_IMAGE_GEN_API_CONFIG_ID, configId)

    fun loadChatHistory(): List<List<Message>> =
        loadData(KEY_CHAT_HISTORY, chatHistorySerializer, emptyList())

    fun saveChatHistory(history: List<List<Message>>) =
        saveData(KEY_CHAT_HISTORY, history, chatHistorySerializer)

    fun loadImageGenerationHistory(): List<List<Message>> =
        loadData(KEY_IMAGE_GENERATION_HISTORY, chatHistorySerializer, emptyList())

    fun saveImageGenerationHistory(history: List<List<Message>>) =
        saveData(KEY_IMAGE_GENERATION_HISTORY, history, chatHistorySerializer)

    fun loadCustomProviders(): Set<String> =
        loadData(KEY_CUSTOM_PROVIDERS, customProvidersSerializer, emptySet())

    fun saveCustomProviders(providers: Set<String>) =
        saveData(KEY_CUSTOM_PROVIDERS, providers, customProvidersSerializer)
fun clearApiConfigs() = remove(KEY_API_CONFIG_LIST)
fun clearImageGenApiConfigs() = remove(KEY_IMAGE_GEN_API_CONFIG_LIST)
fun clearChatHistory() = remove(KEY_CHAT_HISTORY)
fun clearImageGenerationHistory() = remove(KEY_IMAGE_GENERATION_HISTORY)



    fun saveLastOpenChat(messages: List<Message>) {
        if (messages.isEmpty()) remove(KEY_LAST_OPEN_CHAT)
        else saveData(KEY_LAST_OPEN_CHAT, messages, singleChatSerializer)
    }

    fun saveLastOpenImageGenerationChat(messages: List<Message>) {
        if (messages.isEmpty()) remove(KEY_LAST_OPEN_IMAGE_GENERATION)
        else saveData(KEY_LAST_OPEN_IMAGE_GENERATION, messages, singleChatSerializer)
    }

    fun loadLastOpenChat(): List<Message> =
        loadData(KEY_LAST_OPEN_CHAT, singleChatSerializer, emptyList())

    fun loadLastOpenImageGenerationChat(): List<Message> =
        loadData(KEY_LAST_OPEN_IMAGE_GENERATION, singleChatSerializer, emptyList())
   fun loadSystemPrompt(): String = getString(KEY_SYSTEM_PROMPT, "") ?: ""
   fun saveSystemPrompt(prompt: String) = saveString(KEY_SYSTEM_PROMPT, prompt)
}
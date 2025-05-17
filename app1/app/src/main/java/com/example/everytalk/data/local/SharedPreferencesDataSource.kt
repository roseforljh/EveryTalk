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
    "saved_model_names_by_api_address_v2" // 更新版本号以避免与旧数据冲突
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
            Log.d(TAG, "saveData: Key '$key' saved. Data preview: ${jsonString.take(100)}...")
        } catch (e: SerializationException) {
            Log.e(TAG, "saveData: Serialization error for key '$key'. ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "saveData: Unexpected error saving data for key '$key'. ${e.message}", e)
        }
    }

    private fun <T> loadData(key: String, serializer: KSerializer<T>, defaultValue: T): T {
        val jsonString = sharedPrefs.getString(key, null)
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

    fun saveString(key: String, value: String?) = sharedPrefs.edit { putString(key, value) }
    fun getString(key: String, defaultValue: String?): String? =
        sharedPrefs.getString(key, defaultValue)

    fun remove(key: String) = sharedPrefs.edit { remove(key) }

    // API 配置相关方法 (loadApiConfigs, saveApiConfigs, etc.) - 保持不变

    fun loadApiConfigs(): List<ApiConfig> {
        return loadData(KEY_API_CONFIG_LIST, apiConfigListSerializer, emptyList())
    }

    fun saveApiConfigs(configs: List<ApiConfig>) {
        saveData(KEY_API_CONFIG_LIST, configs, apiConfigListSerializer)
    }

    fun loadSelectedConfigId(): String? = getString(KEY_SELECTED_API_CONFIG_ID, null)
    fun saveSelectedConfigId(configId: String?) = saveString(KEY_SELECTED_API_CONFIG_ID, configId)


    // 聊天记录相关方法 (loadChatHistory, saveChatHistory, etc.) - 保持不变
    fun loadChatHistory(): List<List<Message>> {
        return loadData(KEY_CHAT_HISTORY, chatHistorySerializer, emptyList())
    }

    fun saveChatHistory(history: List<List<Message>>) {
        saveData(KEY_CHAT_HISTORY, history, chatHistorySerializer)
    }

    // 自定义平台 (Provider) 管理方法 - 保持不变
    fun saveCustomProviders(providers: Set<String>) {
        val trimmedProviders = providers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        saveData(KEY_CUSTOM_PROVIDERS, trimmedProviders, customProvidersSerializer)
    }

    fun loadCustomProviders(): Set<String> {
        return loadData(KEY_CUSTOM_PROVIDERS, customProvidersSerializer, emptySet())
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun clearApiConfigs() {
        remove(KEY_API_CONFIG_LIST)
        Log.i(TAG, "clearApiConfigs: Cleared '$KEY_API_CONFIG_LIST'.")
    }

    fun clearChatHistory() {
        remove(KEY_CHAT_HISTORY)
        Log.i(TAG, "clearChatHistory: Cleared '$KEY_CHAT_HISTORY'.")
    }

    // ==============================================
    // ===== 按 API 地址保存的模型名称相关方法 (已修改) =====
    // ==============================================
    fun loadSavedModelNamesByApiAddress(): Map<String, Set<String>> {
        Log.d(TAG, "loadSavedModelNamesByApiAddress: Attempting to load model names.")
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
            "saveModelNamesMapByApiAddress: Attempting to save model names map for ${modelNamesMap.size} addresses."
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
        val currentMap = loadSavedModelNamesByApiAddress().toMutableMap()
        val currentSet = currentMap.getOrDefault(trimmedAddress, emptySet()).toMutableSet()

        if (currentSet.add(trimmedModelName)) {
            currentMap[trimmedAddress] = currentSet
            saveModelNamesMapByApiAddress(currentMap)
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
        val currentMap = loadSavedModelNamesByApiAddress().toMutableMap()
        val currentSet = currentMap[trimmedAddress]?.toMutableSet()

        if (currentSet != null && currentSet.remove(trimmedModelName)) {
            if (currentSet.isEmpty()) {
                currentMap.remove(trimmedAddress)
            } else {
                currentMap[trimmedAddress] = currentSet
            }
            saveModelNamesMapByApiAddress(currentMap)
            Log.i(
                TAG,
                "removeSavedModelNameForApiAddress: Removed '$trimmedModelName' from '$trimmedAddress'."
            )
        }
    }

    // 特定用于 DataPersistenceManager 的方法 - 保持不变
    fun saveLastOpenChatInternal(messages: List<Message>) {
        if (messages.isEmpty()) remove(KEY_LAST_OPEN_CHAT)
        else saveData(KEY_LAST_OPEN_CHAT, messages, singleChatSerializer)
    }

    fun loadLastOpenChatInternal(): List<Message> {
        return loadData(KEY_LAST_OPEN_CHAT, singleChatSerializer, emptyList())
    }
}
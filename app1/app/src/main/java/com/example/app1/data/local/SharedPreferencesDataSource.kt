package com.example.app1.data.local

import android.content.Context
import android.util.Log // 使用 Android Log
import androidx.core.content.edit
import com.example.app1.data.models.ApiConfig // 确认导入 ApiConfig
import com.example.app1.data.models.Message // 确认导入 Message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException // 虽然未使用，但保留以备将来可能发生的特定序列化异常处理
import kotlinx.serialization.builtins.ListSerializer // 用于 List<ApiConfig> 和 List<List<Message>>
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

// --- 常量定义 ---
private const val TAG = "SPDataSource" // 日志标签
private const val PREFS_NAME = "app_settings" // SharedPreferences 文件名
private const val KEY_API_CONFIG_LIST = "api_config_list_v2" // API 配置列表 Key (v2)
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id_v1" // 选中配置 ID Key
private const val KEY_CHAT_HISTORY = "chat_history_v1" // 聊天记录 Key
private const val KEY_SAVED_MODEL_NAMES_BY_PROVIDER =
    "saved_model_names_by_provider_v1" // 模型名称建议 Key

// --- Json 配置实例 ---
// 配置 Kotlinx Serialization Json 实例
// ignoreUnknownKeys = true: 忽略JSON中存在但数据类中没有的字段，增强向前兼容性
// prettyPrint = false: 生成紧凑的JSON字符串，节省空间
// encodeDefaults = true: 序列化时包含具有默认值的属性
private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

// --- DataSource 类定义 ---
class SharedPreferencesDataSource(context: Context) {
    // 获取 SharedPreferences 实例
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- 序列化器定义 (提前定义更清晰，避免重复创建) ---
    // ApiConfig 列表的序列化器
    private val apiConfigListSerializer = ListSerializer(ApiConfig.serializer())

    // 聊天历史 (消息列表的列表) 的序列化器
    private val chatHistorySerializer = ListSerializer(ListSerializer(Message.serializer()))

    // 模型名称映射 (Provider名称 -> 模型名称集合) 的序列化器
    private val modelNamesMapSerializer =
        MapSerializer(String.serializer(), SetSerializer(String.serializer()))


    // ================================
    // ===== API 配置相关方法 =========
    // ================================

    /**
     * 从 SharedPreferences 加载 API 配置列表。
     * @return 返回 ApiConfig 列表，如果出错或未找到则返回空列表。
     */
    fun loadApiConfigs(): List<ApiConfig> {
        val jsonString = sharedPrefs.getString(KEY_API_CONFIG_LIST, null)
        Log.d(
            TAG,
            "loadApiConfigs: 从键 '$KEY_API_CONFIG_LIST' 加载的JSON字符串: ${jsonString?.take(100)}..."
        ) // 日志记录加载的JSON（部分）
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(apiConfigListSerializer, jsonString).also {
                    Log.i(TAG, "loadApiConfigs: 成功解码 ${it.size} 个 API 配置。")
                }
            } catch (e: Exception) { // 捕获更广泛的异常，如 JsonDecodingException, IllegalArgumentException 等
                Log.e(TAG, "loadApiConfigs: 加载/解码 API 配置时出错: ${e.message}", e)
                // clearApiConfigs() // 考虑错误时是否清除，当前不清除以避免数据丢失
                emptyList()
            }
        } else {
            Log.i(TAG, "loadApiConfigs: 未找到 API 配置数据。")
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
            Log.i(
                TAG,
                "saveApiConfigs: 已保存 ${configs.size} 个 API 配置到键 '$KEY_API_CONFIG_LIST'。"
            )
        } catch (e: Exception) {
            Log.e(TAG, "saveApiConfigs: 保存 API 配置时出错: ${e.message}", e)
        }
    }

    /**
     * 清除存储的 API 配置列表。
     */
    fun clearApiConfigs() {
        sharedPrefs.edit { remove(KEY_API_CONFIG_LIST) }
        Log.i(TAG, "clearApiConfigs: 已清除键 '$KEY_API_CONFIG_LIST' 的 API 配置。")
    }

    /**
     * 加载当前选中的 API 配置的 ID。
     * @return 返回选中的配置 ID 字符串，如果未设置则返回 null。
     */
    fun loadSelectedConfigId(): String? {
        val id = sharedPrefs.getString(KEY_SELECTED_API_CONFIG_ID, null)
        Log.d(
            TAG,
            "loadSelectedConfigId: 从键 '$KEY_SELECTED_API_CONFIG_ID' 加载的选中配置 ID: $id"
        )
        return id
    }

    /**
     * 保存当前选中的 API 配置的 ID。
     * @param configId 要保存的配置 ID，可以为 null 来清除。
     */
    fun saveSelectedConfigId(configId: String?) {
        sharedPrefs.edit { putString(KEY_SELECTED_API_CONFIG_ID, configId) }
        Log.i(
            TAG,
            "saveSelectedConfigId: 已将选中配置 ID '$configId' 保存到键 '$KEY_SELECTED_API_CONFIG_ID'。"
        )
    }

    // ================================
    // ===== 聊天记录相关方法 =========
    // ================================

    /**
     * 从 SharedPreferences 加载聊天记录。
     * @return 返回 List<List<Message>>，如果出错或未找到则返回空列表。
     */
    // 在 SharedPreferencesDataSource.kt
    fun loadChatHistory(): List<List<Message>> {
        Log.e(TAG, "loadChatHistory: ++++++++++++++ 方法开始执行 ++++++++++++++") // 使用Error级别使其在Logcat中更显眼
        val jsonString = sharedPrefs.getString(KEY_CHAT_HISTORY, null)
        Log.d(TAG, "loadChatHistory: 从键 '$KEY_CHAT_HISTORY' 加载的JSON字符串（部分）: ${jsonString?.take(150)}...")

        if (!jsonString.isNullOrEmpty()) {
            try {
                val decodedList = json.decodeFromString(chatHistorySerializer, jsonString)
                Log.i(TAG, "loadChatHistory: 成功解码 ${decodedList.size} 条聊天对话。")
                decodedList.forEachIndexed { index, conversation ->
                    Log.d(TAG, "loadChatHistory: 对话 $index 包含 ${conversation.size} 条消息。首条预览: '${conversation.firstOrNull()?.text?.take(30)}'")
                }
                Log.e(TAG, "loadChatHistory: ++++++++++++++ 方法正常返回 (解码成功) - 大小: ${decodedList.size} ++++++++++++++")
                return decodedList
            } catch (e: Exception) {
                Log.e(TAG, "loadChatHistory: 加载/解码聊天记录时出错: ${e.message}", e)
                Log.e(TAG, "loadChatHistory: ++++++++++++++ 方法因异常返回空列表 ++++++++++++++")
                return emptyList()
            }
        } else {
            Log.i(TAG, "loadChatHistory: 未找到聊天记录数据 (JSON字符串为空或null)。")
            Log.e(TAG, "loadChatHistory: ++++++++++++++ 方法因JSON为空返回空列表 ++++++++++++++")
            return emptyList()
        }
    }

    /**
     * 将完整的聊天记录列表保存到 SharedPreferences。
     * @param history 要保存的 List<List<Message>>。
     */
    fun saveChatHistory(history: List<List<Message>>) {
        try {
            Log.i(TAG, "saveChatHistory: 准备序列化 ${history.size} 条对话。")
            history.forEachIndexed { index, conversation ->
                Log.d(TAG, "saveChatHistory: 序列化对话 $index, 包含 ${conversation.size} 条消息。")
            }
            val jsonString = json.encodeToString(chatHistorySerializer, history)
            // Log.d(TAG, "saveChatHistory: Serialized JSON to save: ${jsonString.take(500)}") // 可选：打印部分JSON用于调试
            sharedPrefs.edit { putString(KEY_CHAT_HISTORY, jsonString) }
            Log.i(
                TAG,
                "saveChatHistory: 已将 ${history.size} 条聊天对话保存到键 '$KEY_CHAT_HISTORY'。"
            )
        } catch (e: Exception) {
            Log.e(TAG, "saveChatHistory: 保存聊天记录时出错: ${e.message}", e)
        }
    }

    /**
     * 清除存储的聊天记录。
     */
    fun clearChatHistory() {
        sharedPrefs.edit { remove(KEY_CHAT_HISTORY) }
        Log.i(TAG, "clearChatHistory: 已清除键 '$KEY_CHAT_HISTORY' 的聊天记录。")
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
        Log.d(
            TAG,
            "loadSavedModelNamesByProvider: 从键 '$KEY_SAVED_MODEL_NAMES_BY_PROVIDER' 加载的JSON: ${
                jsonString?.take(100)
            }..."
        )
        return if (!jsonString.isNullOrEmpty()) {
            try {
                json.decodeFromString(modelNamesMapSerializer, jsonString).also {
                    Log.i(
                        TAG,
                        "loadSavedModelNamesByProvider: 已为 ${it.keys.size} 个提供商加载已保存的模型名称。"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "loadSavedModelNamesByProvider: 加载/解码已保存的模型名称时出错: ${e.message}",
                    e
                )
                emptyMap()
            }
        } else {
            Log.i(TAG, "loadSavedModelNamesByProvider: 未找到已保存的模型名称。")
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
            Log.i(
                TAG,
                "saveModelNamesMap: 已保存模型名称映射到键 '$KEY_SAVED_MODEL_NAMES_BY_PROVIDER'。"
            )
        } catch (e: Exception) {
            Log.e(TAG, "saveModelNamesMap: 保存模型名称映射时出错: ${e.message}", e)
        }
    }

    /**
     * 添加一个模型名称到指定 Provider 的集合中。
     * @param provider Provider 名称。
     * @param modelName 要添加的模型名称。
     */
    fun addSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) {
            Log.w(
                TAG,
                "addSavedModelName: 提供商或模型名称为空，不添加。Provider: '$provider', ModelName: '$modelName'"
            )
            return
        }
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap.getOrDefault(trimmedProvider, emptySet()).toMutableSet()
        if (currentSet.add(trimmedModelName)) { // 如果成功添加（即之前不存在）
            currentMap[trimmedProvider] = currentSet
            saveModelNamesMap(currentMap)
            Log.i(
                TAG,
                "addSavedModelName: 已为提供商 '$trimmedProvider' 添加模型名称 '$trimmedModelName'。"
            )
        } else {
            Log.d(
                TAG,
                "addSavedModelName: 模型名称 '$trimmedModelName' 已存在于提供商 '$trimmedProvider'，未重复添加。"
            )
        }
    }

    /**
     * 从指定 Provider 的集合中移除一个模型名称。
     * @param provider Provider 名称。
     * @param modelName 要移除的模型名称。
     */
    fun removeSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) {
            Log.w(
                TAG,
                "removeSavedModelName: 提供商或模型名称为空，不移除。Provider: '$provider', ModelName: '$modelName'"
            )
            return
        }
        val currentMap = loadSavedModelNamesByProvider().toMutableMap()
        val currentSet = currentMap[trimmedProvider]?.toMutableSet() // 获取可变副本
        if (currentSet != null && currentSet.remove(trimmedModelName)) { // 如果集合存在且成功移除
            if (currentSet.isEmpty()) { // 如果移除后集合为空，则从Map中移除该Provider
                currentMap.remove(trimmedProvider)
                Log.d(
                    TAG,
                    "removeSavedModelName: 移除模型名称 '$trimmedModelName' 后，提供商 '$trimmedProvider' 的集合为空，已移除该提供商。"
                )
            } else {
                currentMap[trimmedProvider] = currentSet // 更新Map中的集合
            }
            saveModelNamesMap(currentMap) // 保存更新后的Map
            Log.i(
                TAG,
                "removeSavedModelName: 已从提供商 '$trimmedProvider' 移除模型名称 '$trimmedModelName'。"
            )
        } else {
            Log.d(
                TAG,
                "removeSavedModelName: 模型名称 '$trimmedModelName' 在提供商 '$trimmedProvider' 中未找到，或提供商不存在，未移除。"
            )
        }
    }

    /**
     * 清除所有与此应用相关的 SharedPreferences 数据。
     * **注意：** 这会清除所有 API 配置、选中的配置 ID、聊天记录和模型名称建议！
     */
    fun clearAllData() {
        sharedPrefs.edit { clear() } // clear() 会移除此 SharedPreferences 文件中的所有键值对
        Log.w(TAG, "clearAllData: 已清除文件 '$PREFS_NAME' 中的所有应用设置数据！")
    }
}
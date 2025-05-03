package com.example.app1.data.local

import android.content.Context
import androidx.core.content.edit
import com.example.app1.data.network.ApiConfig // 确认导入 ApiConfig
import com.example.app1.data.models.Message // 确认导入 Message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.io.println // 或者使用 Android Log

// --- 常量定义 ---
private const val PREFS_NAME = "app_settings" // SharedPreferences 文件名
private const val KEY_API_CONFIG_LIST = "api_config_list_v2" // API 配置列表 Key
private const val KEY_SELECTED_API_CONFIG_ID = "selected_api_config_id" // 选中配置 ID Key
private const val KEY_CHAT_HISTORY = "chat_history_v1" // 聊天记录 Key
private const val KEY_SAVED_MODEL_NAMES_BY_PROVIDER = "saved_model_names_by_provider_v1" // 模型名称建议 Key

// --- Json 配置实例 ---
// 配置 Json 实例，用于序列化和反序列化
// ignoreUnknownKeys = true: 增加兼容性，忽略 JSON 中存在但数据类中没有的字段
// prettyPrint = false: 存储时不需要格式化，节省空间
// encodeDefaults = true: 确保数据类中的默认值也被序列化存储
private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

// --- DataSource 类定义 ---
class SharedPreferencesDataSource(context: Context) {
    // 获取 SharedPreferences 实例
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ================================
    // ===== API 配置相关方法 =========
    // ================================

    /**
     * 从 SharedPreferences 加载 API 配置列表。
     * @return 返回 ApiConfig 列表，如果出错或未找到则返回空列表。
     */
    fun loadApiConfigs(): List<ApiConfig> {
        val jsonString = sharedPrefs.getString(KEY_API_CONFIG_LIST, null) // 读取 JSON 字符串
        return if (!jsonString.isNullOrEmpty()) { // 检查字符串是否有效
            try {
                // 尝试将 JSON 字符串反序列化为 List<ApiConfig>
                json.decodeFromString<List<ApiConfig>>(jsonString)
            } catch (e: SerializationException) {
                // 处理反序列化错误（通常是数据格式不匹配）
                println("SharedPreferences: 解码 API 配置时出错 (可能数据格式不匹配): ${e.message}")
                clearApiConfigs() // 清除无效数据
                emptyList() // 返回空列表
            } catch (e: Exception) {
                // 处理其他意外错误
                println("SharedPreferences: 加载 API 配置时发生意外错误: ${e.message}")
                emptyList() // 返回空列表
            }
        } else {
            // 如果 SharedPreferences 中没有找到对应的 Key 或值为空，则返回空列表
            emptyList()
        }
    }

    /**
     * 将 API 配置列表保存到 SharedPreferences。
     * @param configs 要保存的 ApiConfig 列表。
     */
    fun saveApiConfigs(configs: List<ApiConfig>) {
        try {
            // 将列表序列化为 JSON 字符串
            val jsonString = json.encodeToString(configs)
            // 使用 edit 块写入 SharedPreferences
            sharedPrefs.edit {
                putString(KEY_API_CONFIG_LIST, jsonString)
                // apply() 在 edit 块中是隐式调用的
            }
            println("SharedPreferences: 已保存 ${configs.size} 个 API 配置。")
        } catch (e: SerializationException) {
            // 处理序列化错误
            println("SharedPreferences: 编码 API 配置时出错: ${e.message}")
        } catch (e: Exception) {
            // 处理其他意外错误
            println("SharedPreferences: 保存 API 配置时发生意外错误: ${e.message}")
        }
    }

    /**
     * 清除存储的 API 配置列表。
     */
    fun clearApiConfigs() {
        sharedPrefs.edit {
            remove(KEY_API_CONFIG_LIST) // 移除对应的 Key
        }
        println("SharedPreferences: 已清除存储的 API 配置。")
    }

    /**
     * 加载当前选中的 API 配置的 ID。
     * @return 返回选中的配置 ID 字符串，如果未设置则返回 null。
     */
    fun loadSelectedConfigId(): String? = sharedPrefs.getString(KEY_SELECTED_API_CONFIG_ID, null)

    /**
     * 保存当前选中的 API 配置的 ID。
     * @param configId 要保存的配置 ID，可以为 null。
     */
    fun saveSelectedConfigId(configId: String?) {
        sharedPrefs.edit {
            putString(KEY_SELECTED_API_CONFIG_ID, configId)
        }
        println("SharedPreferences: 已保存选中的配置 ID: $configId")
    }

    // ================================
    // ===== 聊天记录相关方法 =========
    // ================================

    /**
     * 从 SharedPreferences 加载聊天记录。
     * @return 返回 List<List<Message>>，如果出错或未找到则返回空列表。
     */
    fun loadChatHistory(): List<List<Message>> {
        val jsonString = sharedPrefs.getString(KEY_CHAT_HISTORY, null) // 读取 JSON 字符串
        return if (!jsonString.isNullOrEmpty()) { // 检查字符串是否有效
            try {
                // 尝试将 JSON 字符串反序列化为 List<List<Message>>
                json.decodeFromString<List<List<Message>>>(jsonString).also {
                    println("SharedPreferences: 已加载 ${it.size} 条聊天对话。")
                }
            } catch (e: SerializationException) {
                // 处理反序列化错误
                println("SharedPreferences: 解码聊天记录时出错 (可能数据格式不匹配): ${e.message}")
                // clearChatHistory() // 可选：清除无效的聊天记录数据
                emptyList() // 返回空列表
            } catch (e: Exception) {
                // 处理其他意外错误
                println("SharedPreferences: 加载聊天记录时发生意外错误: ${e.message}")
                emptyList() // 返回空列表
            }
        } else {
            // 如果未找到或值为空，返回空列表
            println("SharedPreferences: 未找到聊天记录。")
            emptyList()
        }
    }

    /**
     * 将完整的聊天记录列表保存到 SharedPreferences。
     * @param history 要保存的 List<List<Message>>。
     */
    fun saveChatHistory(history: List<List<Message>>) {
        try {
            // 将列表序列化为 JSON 字符串
            val jsonString = json.encodeToString(history)
            // 写入 SharedPreferences
            sharedPrefs.edit {
                putString(KEY_CHAT_HISTORY, jsonString)
            }
            println("SharedPreferences: 已保存 ${history.size} 条聊天对话。")
        } catch (e: SerializationException) {
            // 处理序列化错误
            println("SharedPreferences: 编码聊天记录时出错: ${e.message}")
        } catch (e: Exception) {
            // 处理其他意外错误
            println("SharedPreferences: 保存聊天记录时发生意外错误: ${e.message}")
        }
    }

    /**
     * 清除存储的聊天记录。
     */
    fun clearChatHistory() {
        sharedPrefs.edit {
            remove(KEY_CHAT_HISTORY) // 移除对应的 Key
        }
        println("SharedPreferences: 已清除存储的聊天记录。")
    }


    // ==============================================
    // ===== 按 Provider 保存的模型名称相关方法 =====
    // ==============================================

    // 定义用于序列化 Map<String, Set<String>> 的类型
    private val modelNamesMapSerializer = MapSerializer(String.serializer(), SetSerializer(String.serializer()))

    /**
     * 加载按 Provider 分类的已保存模型名称。
     * @return 返回 Map<Provider名称, Set<模型名称>>，如果出错或未找到则返回空 Map。
     */
    fun loadSavedModelNamesByProvider(): Map<String, Set<String>> {
        val jsonString = sharedPrefs.getString(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, null) // 读取 JSON 字符串
        return if (!jsonString.isNullOrEmpty()) { // 检查字符串是否有效
            try {
                // 尝试反序列化为 Map<String, Set<String>>
                json.decodeFromString(modelNamesMapSerializer, jsonString).also {
                    println("SharedPreferences: 已加载 ${it.keys.size} 个 Provider 的模型名称建议。")
                }
            } catch (e: SerializationException) {
                // 处理反序列化错误
                println("SharedPreferences: 解码已存模型名称时出错: ${e.message}")
                emptyMap() // 返回空 Map
            } catch (e: Exception) {
                // 处理其他意外错误
                println("SharedPreferences: 加载已存模型名称时发生意外错误: ${e.message}")
                emptyMap() // 返回空 Map
            }
        } else {
            // 如果未找到或值为空，返回空 Map
            println("SharedPreferences: 未找到已存模型名称。")
            emptyMap()
        }
    }

    /**
     * (私有) 保存按 Provider 分类的模型名称 Map。
     * @param modelNamesMap 要保存的 Map<String, Set<String>>。
     */
    private fun saveModelNamesMap(modelNamesMap: Map<String, Set<String>>) {
        try {
            // 将 Map 序列化为 JSON 字符串
            val jsonString = json.encodeToString(modelNamesMapSerializer, modelNamesMap)
            // 写入 SharedPreferences
            sharedPrefs.edit {
                putString(KEY_SAVED_MODEL_NAMES_BY_PROVIDER, jsonString)
            }
            println("SharedPreferences: 已保存模型名称 Map。")
        } catch (e: SerializationException) {
            // 处理序列化错误
            println("SharedPreferences: 编码模型名称 Map 时出错: ${e.message}")
        } catch (e: Exception) {
            // 处理其他意外错误
            println("SharedPreferences: 保存模型名称 Map 时发生意外错误: ${e.message}")
        }
    }

    /**
     * 添加一个模型名称到指定 Provider 的集合中。
     * 会自动去重（因为使用的是 Set）。
     * @param provider Provider 名称。
     * @param modelName 要添加的模型名称。
     */
    fun addSavedModelName(provider: String, modelName: String) {
        val trimmedProvider = provider.trim()
        val trimmedModelName = modelName.trim()
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) return // 忽略空值

        val currentMap = loadSavedModelNamesByProvider().toMutableMap() // 获取当前 Map 的可变副本
        val currentSet = currentMap.getOrDefault(trimmedProvider, emptySet()).toMutableSet() // 获取对应 Set 的可变副本，如果不存在则创建新 Set

        val added = currentSet.add(trimmedModelName) // 尝试添加到 Set
        if (added) { // 只有当模型名称是新添加的时才保存
            currentMap[trimmedProvider] = currentSet // 更新 Map 中的 Set
            saveModelNamesMap(currentMap) // 保存整个更新后的 Map
            println("SharedPreferences: 已为 Provider '$trimmedProvider' 添加模型名称 '$trimmedModelName'。")
        } else {
            println("SharedPreferences: 模型名称 '$trimmedModelName' 已存在于 Provider '$trimmedProvider'，未重复添加。")
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
        if (trimmedProvider.isBlank() || trimmedModelName.isBlank()) return

        val currentMap = loadSavedModelNamesByProvider().toMutableMap() // 获取当前 Map 的可变副本
        val currentSet = currentMap[trimmedProvider]?.toMutableSet() // 获取对应 Set 的可变副本

        if (currentSet != null) { // 确保该 Provider 的 Set 存在
            val removed = currentSet.remove(trimmedModelName) // 尝试从 Set 中移除
            if (removed) { // 如果成功移除
                if (currentSet.isEmpty()) { // 如果移除后 Set 变为空
                    currentMap.remove(trimmedProvider) // 从 Map 中移除该 Provider 条目
                    println("SharedPreferences: 已移除 Provider '$trimmedProvider' 的最后一个模型名称，移除该 Provider 条目。")
                } else {
                    currentMap[trimmedProvider] = currentSet // 否则，更新 Map 中的 Set
                    println("SharedPreferences: 已从 Provider '$trimmedProvider' 移除模型名称 '$trimmedModelName'。")
                }
                saveModelNamesMap(currentMap) // 保存更新后的 Map
            } else {
                println("SharedPreferences: 未在 Provider '$trimmedProvider' 中找到要移除的模型名称 '$trimmedModelName'。")
            }
        } else {
            println("SharedPreferences: 未找到 Provider '$trimmedProvider' 对应的模型名称列表。")
        }
    }
}
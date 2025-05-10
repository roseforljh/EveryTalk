package com.example.app1.ui.screens.viewmodel.data

import android.util.Log
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update // 确保导入 update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataPersistenceManager(
    private val dataSource: SharedPreferencesDataSource, // 数据源 (SharedPreferencesDataSource)
    private val stateHolder: ViewModelStateHolder,     // ViewModel 状态持有者
    private val viewModelScope: CoroutineScope         // ViewModel 的协程作用域
) {
    private val TAG = "PersistenceManager" // 日志标签

    /**
     * 加载初始数据，包括 API 配置和聊天历史。
     * @param onLoadingComplete 加载完成后的回调，传递配置和历史是否存在。
     */
    fun loadInitialData(onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { // 在 IO 线程执行耗时操作
            Log.d(TAG, "loadInitialData: 开始加载初始数据...")

            // --- 加载 API 配置 ---
            Log.d(TAG, "loadInitialData: 调用 dataSource.loadApiConfigs()...")
            val loadedConfigs: List<ApiConfig> = dataSource.loadApiConfigs() // 从数据源加载
            stateHolder._apiConfigs.value = loadedConfigs // 更新 StateHolder
            val initialConfigPresent = loadedConfigs.isNotEmpty()
            Log.i(
                TAG,
                "loadInitialData: API 配置加载完成。数量: ${loadedConfigs.size}, initialConfigPresent: $initialConfigPresent"
            )

            // --- 加载并处理选中的 API 配置 ---
            Log.d(TAG, "loadInitialData: 调用 dataSource.loadSelectedConfigId()...")
            val selectedConfigId: String? = dataSource.loadSelectedConfigId()
            var selectedConfigFromDataSource: ApiConfig? = null
            if (selectedConfigId != null) {
                selectedConfigFromDataSource = loadedConfigs.find { it.id == selectedConfigId }
                if (selectedConfigFromDataSource == null) {
                    Log.w(
                        TAG,
                        "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。"
                    )
                    dataSource.saveSelectedConfigId(null) // 清除无效ID
                }
            }

            var finalSelectedConfig = selectedConfigFromDataSource
            var selectionActuallyChangedInThisLoad = false

            if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) { // 如果没有有效选中项，但有配置，则默认选第一个
                finalSelectedConfig = loadedConfigs.first()
                Log.i(
                    TAG,
                    "loadInitialData: 无有效选中配置，默认选择第一个: ID='${finalSelectedConfig.id}', 模型='${finalSelectedConfig.model}'。将保存此选择。"
                )
                dataSource.saveSelectedConfigId(finalSelectedConfig.id)
                selectionActuallyChangedInThisLoad = true
            } else if (finalSelectedConfig == null && selectedConfigId != null) { // 如果之前有ID但现在无效（例如列表为空）
                Log.w(
                    TAG,
                    "loadInitialData: 之前有选中ID '$selectedConfigId' 但当前配置列表为空或不匹配，清除持久化ID。"
                )
                dataSource.saveSelectedConfigId(null) // 清除无效ID
                selectionActuallyChangedInThisLoad = true // 状态改变了（从有ID到无ID）
            }

            // 更新 StateHolder 中的选中配置，仅当它与当前值不同时
            if (stateHolder._selectedApiConfig.value?.id != finalSelectedConfig?.id) {
                stateHolder._selectedApiConfig.value = finalSelectedConfig
                Log.i(
                    TAG,
                    "loadInitialData: StateHolder 中的选中配置已更新为: ID='${finalSelectedConfig?.id}', 模型='${finalSelectedConfig?.model}'"
                )
            } else if (selectionActuallyChangedInThisLoad) {
                // 即使最终 selectedConfig 与 stateHolder 中的相同（例如，都是null），但如果 dataSource 中的持久化 ID 被清除了，也算一种状态同步
                Log.d(
                    TAG,
                    "loadInitialData: StateHolder 中的选中配置与数据源一致，但数据源的持久化ID可能已更新/清除。"
                )
            }


            // --- 加载聊天历史记录 ---
            Log.d(TAG, "loadInitialData: 调用 dataSource.loadChatHistory()...")
            val loadedHistory: List<List<Message>> = dataSource.loadChatHistory() // 从数据源加载
            stateHolder._historicalConversations.update { oldHistory ->
                Log.i(
                    TAG,
                    "loadInitialData: 正在更新 _historicalConversations。旧大小: ${oldHistory.size}, 从dataSource新加载的大小: ${loadedHistory.size}"
                )
                loadedHistory // 直接使用从 dataSource 加载的数据
            }
            val initialHistoryPresent = loadedHistory.isNotEmpty()
            Log.i(
                TAG,
                "loadInitialData: 聊天历史加载完成。initialHistoryPresent: $initialHistoryPresent"
            )

            // 重置当前加载的历史索引
            stateHolder._loadedHistoryIndex.value = null
            Log.d(TAG, "loadInitialData: _loadedHistoryIndex 已重置为 null。")

            // --- 调用完成回调 ---
            withContext(Dispatchers.Main) { // 切换回主线程
                Log.d(
                    TAG,
                    "loadInitialData: 即将调用 onLoadingComplete(initialConfigPresent=$initialConfigPresent, initialHistoryPresent=$initialHistoryPresent) 回调。"
                )
                onLoadingComplete(initialConfigPresent, initialHistoryPresent)
            }
            Log.d(TAG, "loadInitialData: 初始数据加载的IO线程任务结束。")
        }
    }

    /** 清除所有聊天历史记录（仅持久化层）。 */
    fun clearAllChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求 dataSource 清除聊天历史...")
            dataSource.clearChatHistory()
            Log.i(TAG, "clearAllChatHistory: dataSource 已清除聊天历史。")
        }
    }

    /** 保存当前的 API 配置列表。 */
    fun saveApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfigs = stateHolder._apiConfigs.value
            Log.d(
                TAG,
                "saveApiConfigs: 准备使用 dataSource 保存 ${currentConfigs.size} 个 API 配置..."
            )
            dataSource.saveApiConfigs(currentConfigs)
            Log.i(TAG, "saveApiConfigs: API 配置已通过 dataSource 保存。")
        }
    }

    /** 保存当前的聊天历史列表。 */
    fun saveChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory =
                stateHolder._historicalConversations.value // 从 stateHolder 获取当前要保存的历史
            Log.d(
                TAG,
                "saveChatHistory: 准备使用 dataSource 保存 ${currentHistory.size} 条对话..."
            )
            dataSource.saveChatHistory(currentHistory) // 调用数据源保存
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 dataSource 保存。")
        }
    }

    /** 保存选中的 API 配置的标识符。 */
    fun saveSelectedConfigIdentifier(configId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "saveSelectedConfigIdentifier: 请求 dataSource 保存选中配置ID: $configId")
            dataSource.saveSelectedConfigId(configId)
            Log.i(TAG, "saveSelectedConfigIdentifier: 选中配置ID已通过 dataSource 保存。")
        }
    }

    /** 清除所有 API 配置数据（列表和选中项）。 */
    fun clearAllApiConfigData() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 请求 dataSource 清除API配置并取消选中...")
            dataSource.clearApiConfigs() // 清除配置列表
            dataSource.saveSelectedConfigId(null) // 清除选中项
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 dataSource 清除。")
        }
    }

    /** 清除所有应用数据（如果 dataSource 支持）。 */
    fun clearAllApplicationData() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.w(
                TAG,
                "clearAllApplicationData: 请求 dataSource 清除所有应用数据！"
            ) // 使用警告级别，因为这是个危险操作
            dataSource.clearAllData() // 假设 dataSource 有这个方法
            Log.w(TAG, "clearAllApplicationData: 所有应用数据已通过 dataSource 清除！")
        }
    }
}
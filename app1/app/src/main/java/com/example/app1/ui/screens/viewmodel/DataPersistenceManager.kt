package com.example.app1.ui.screens.viewmodel

import android.util.Log
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender // 确保导入 Sender
import com.example.app1.StateControler.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataPersistenceManager(
    private val dataSource: SharedPreferencesDataSource, // 数据源 (SharedPreferencesDataSource)
    private val stateHolder: ViewModelStateHolder,     // ViewModel 状态持有者
    private val viewModelScope: CoroutineScope         // ViewModel 的协程作用域
) {
    private val TAG = "PersistenceManager" // 日志标签

    /**
     * 加载初始数据，包括 API 配置、聊天历史和最后打开的聊天。
     * @param onLoadingComplete 加载完成后的回调，传递配置和历史是否存在。
     */
    fun loadInitialData(onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { // 在 IO 线程执行耗时操作
            Log.d(TAG, "loadInitialData: 开始加载初始数据...")
            var initialConfigPresent = false
            var initialHistoryPresent = false

            try {
                // --- 加载 API 配置 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadApiConfigs()...")
                val loadedConfigs: List<ApiConfig> = dataSource.loadApiConfigs() // 从数据源加载
                initialConfigPresent = loadedConfigs.isNotEmpty()
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
                    if (selectedConfigFromDataSource == null && loadedConfigs.isNotEmpty()) { // 修正：即使列表不为空，如果ID不匹配也应清除
                        Log.w(
                            TAG,
                            "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。"
                        )
                        dataSource.saveSelectedConfigId(null) // 清除无效ID
                    }
                }

                var finalSelectedConfig = selectedConfigFromDataSource
                // var selectionActuallyChangedInThisLoad = false // 这个变量的逻辑有点复杂且不一定必要，简化

                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    Log.i(
                        TAG,
                        "loadInitialData: 无有效选中配置，默认选择第一个: ID='${finalSelectedConfig.id}', 模型='${finalSelectedConfig.model}'。将保存此选择。"
                    )
                    dataSource.saveSelectedConfigId(finalSelectedConfig.id)
                }
                // 注意：如果 loadedConfigs 为空，finalSelectedConfig 也会是 null，这是正确的

                // --- 加载聊天历史记录 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadChatHistory()...")
                val loadedHistory: List<List<Message>> = dataSource.loadChatHistory() // 从数据源加载
                initialHistoryPresent = loadedHistory.isNotEmpty()
                Log.i(
                    TAG,
                    "loadInitialData: 聊天历史加载完成。数量: ${loadedHistory.size}, initialHistoryPresent: $initialHistoryPresent"
                )

                // --- BEGIN: 加载最后打开的聊天 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadLastOpenChatInternal()...")
                val lastOpenChatMessages: List<Message> = dataSource.loadLastOpenChatInternal()
                Log.i(
                    TAG,
                    "loadInitialData: 最后打开的聊天加载完成。消息数量: ${lastOpenChatMessages.size}"
                )
                // --- END: 加载最后打开的聊天 ---


                // --- 更新 StateHolder (在主线程) ---
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "loadInitialData: 切换到主线程更新 StateHolder...")
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value =
                        finalSelectedConfig // 直接使用处理后的 finalSelectedConfig
                    stateHolder._historicalConversations.value =
                        loadedHistory // 使用 update 可能更安全，但这里直接赋值也行，因为是初始化

                    // --- BEGIN: 更新 StateHolder 中的当前聊天消息 (messages) ---
                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(lastOpenChatMessages.map {
                        // 确保 contentStarted 正确设置
                        it.copy(contentStarted = it.text.isNotBlank() || !it.reasoning.isNullOrBlank())
                    })
                    // 为加载的消息设置动画状态 (假设这些消息动画都已播放)
                    stateHolder.messages.forEach { msg ->
                        if ((msg.sender == Sender.AI || msg.sender == Sender.User) &&
                            (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                        ) {
                            stateHolder.messageAnimationStates[msg.id] = true
                        }
                    }
                    Log.d(
                        TAG,
                        "loadInitialData: StateHolder.messages 已更新为最后打开的聊天。数量: ${stateHolder.messages.size}"
                    )
                    // --- END: 更新 StateHolder 中的当前聊天消息 ---

                    // 重置当前加载的历史索引 (因为我们总是从 lastOpenChat 恢复，而不是历史的第一条)
                    // AppViewModel 的 init 块稍后会根据 messages 和 historicalConversations 设置正确的 loadedHistoryIndex
                    stateHolder._loadedHistoryIndex.value = null
                    Log.d(
                        TAG,
                        "loadInitialData: _loadedHistoryIndex 已在 StateHolder 更新中重置为 null。"
                    )

                    Log.d(
                        TAG,
                        "loadInitialData: StateHolder 更新完成。即将调用 onLoadingComplete。"
                    )
                    onLoadingComplete(initialConfigPresent, initialHistoryPresent)
                }

            } catch (e: Exception) {
                Log.e(TAG, "loadInitialData: 加载初始数据时发生严重错误", e)
                withContext(Dispatchers.Main.immediate) {
                    // 发生错误时，确保状态是可预测的（例如，全空）
                    stateHolder._apiConfigs.value = emptyList()
                    stateHolder._selectedApiConfig.value = null
                    stateHolder._historicalConversations.value = emptyList()
                    stateHolder.messages.clear()
                    stateHolder._loadedHistoryIndex.value = null
                    onLoadingComplete(false, false) // 回调表示加载失败或无数据
                }
            } finally {
                Log.d(TAG, "loadInitialData: 初始数据加载的IO线程任务结束。")
            }
        }
    }

    // --- BEGIN: 添加 saveLastOpenChat 方法 ---
    /**
     * 保存最后打开的聊天内容。
     * @param messages 要保存的消息列表。
     */
    fun saveLastOpenChat(messages: List<Message>) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(
                TAG,
                "saveLastOpenChat: 请求 dataSource 保存最后打开的聊天。消息数量: ${messages.size}"
            )
            // 直接调用 SharedPreferencesDataSource 中的方法
            dataSource.saveLastOpenChatInternal(messages)
            Log.i(TAG, "saveLastOpenChat: 最后打开的聊天已通过 dataSource 保存。")
        }
    }
    // --- END: 添加 saveLastOpenChat 方法 ---

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
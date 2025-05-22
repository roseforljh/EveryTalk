package com.example.everytalk.ui.screens.viewmodel // 请确保包名与你的项目一致

import android.util.Log
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.StateControler.ViewModelStateHolder
import com.example.everytalk.util.convertMarkdownToHtml // ★ 确保这个导入路径正确 ★
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // launch 仅用于 loadInitialData 的顶层启动
import kotlinx.coroutines.withContext

class DataPersistenceManager(
    private val dataSource: SharedPreferencesDataSource, // 数据源 (SharedPreferencesDataSource)
    private val stateHolder: ViewModelStateHolder,     // ViewModel 状态持有者
    private val viewModelScope: CoroutineScope         // ViewModel 的协程作用域, 主要用于 loadInitialData 的启动
) {
    private val TAG = "PersistenceManager" // 日志标签

    /**
     * 加载初始数据，包括 API 配置、聊天历史和最后打开的聊天。
     * @param onLoadingComplete 加载完成后的回调，传递配置和历史是否存在。
     */
    fun loadInitialData(onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit) {
        // loadInitialData 本身不是 suspend，它在内部启动一个协程来做 IO 操作
        viewModelScope.launch(Dispatchers.IO) { // 在 IO 线程执行整个加载流程
            Log.d(TAG, "loadInitialData: 开始加载初始数据 (IO Thread)...")
            var initialConfigPresent = false
            var initialHistoryPresent = false

            try {
                // --- 加载 API 配置 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadApiConfigs()...")
                val loadedConfigs: List<ApiConfig> = dataSource.loadApiConfigs()
                initialConfigPresent = loadedConfigs.isNotEmpty()
                Log.i(
                    TAG,
                    "loadInitialData: API 配置加载完成。数量: ${loadedConfigs.size}, initialConfigPresent: $initialConfigPresent"
                )
                if (initialConfigPresent) {
                    loadedConfigs.forEachIndexed { index, cfg ->
                        Log.d(
                            TAG,
                            "Loaded Config[$index]: ID=${cfg.id.take(4)}, Model=${cfg.model}"
                        )
                    }
                }


                // --- 加载并处理选中的 API 配置 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadSelectedConfigId()...")
                val selectedConfigId: String? = dataSource.loadSelectedConfigId()
                Log.i(TAG, "loadInitialData: 加载到的选中配置ID: '$selectedConfigId'")
                var selectedConfigFromDataSource: ApiConfig? = null
                if (selectedConfigId != null) {
                    selectedConfigFromDataSource = loadedConfigs.find { it.id == selectedConfigId }
                    if (selectedConfigFromDataSource == null && loadedConfigs.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。"
                        )
                        dataSource.saveSelectedConfigId(null) // 清除无效ID
                    }
                }

                var finalSelectedConfig = selectedConfigFromDataSource
                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    Log.i(
                        TAG,
                        "loadInitialData: 无有效选中配置或之前未选中，默认选择第一个: ID='${finalSelectedConfig.id}', 模型='${finalSelectedConfig.model}'。将保存此选择。"
                    )
                    dataSource.saveSelectedConfigId(finalSelectedConfig.id) // 保存这个默认选择
                }
                Log.i(TAG, "loadInitialData: 最终选中的配置: ${finalSelectedConfig?.model ?: "无"}")


                // --- 加载聊天历史记录 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadChatHistory()...")
                val loadedHistory: List<List<Message>> = dataSource.loadChatHistory()
                initialHistoryPresent = loadedHistory.isNotEmpty()
                Log.i(
                    TAG,
                    "loadInitialData: 聊天历史加载完成。数量: ${loadedHistory.size}, initialHistoryPresent: $initialHistoryPresent"
                )

                // --- 加载最后打开的聊天 ---
                Log.d(TAG, "loadInitialData: 调用 dataSource.loadLastOpenChatInternal()...")
                val lastOpenChatMessagesLoaded: List<Message> =
                    dataSource.loadLastOpenChatInternal()
                Log.i(
                    TAG,
                    "loadInitialData: 最后打开的聊天加载完成。消息数量: ${lastOpenChatMessagesLoaded.size}"
                )

                // ★★★ 为 lastOpenChatMessages 预处理 htmlContent 和 contentStarted (仍在 IO 线程) ★★★
                val processedLastOpenChatMessages = if (lastOpenChatMessagesLoaded.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "loadInitialData: 开始为 lastOpenChatMessages 预处理 htmlContent 和 contentStarted..."
                    )
                    lastOpenChatMessagesLoaded.map { message ->
                        val updatedContentStarted =
                            message.text.isNotBlank() || !message.reasoning.isNullOrBlank() || message.isError
                        if (message.sender == Sender.AI && message.text.isNotBlank() && message.htmlContent == null) {
                            Log.d(
                                TAG,
                                "loadInitialData: 预处理 lastOpen: 为消息 ${message.id.take(4)} 生成 htmlContent"
                            )
                            message.copy(
                                htmlContent = convertMarkdownToHtml(message.text),
                                contentStarted = updatedContentStarted
                            )
                        } else {
                            message.copy(
                                contentStarted = updatedContentStarted
                            )
                        }
                    }
                } else {
                    emptyList()
                }
                Log.d(
                    TAG,
                    "loadInitialData: lastOpenChatMessages 的 htmlContent 和 contentStarted 预处理完成。"
                )
                // ★★★ 结束预处理 ★★★

                // --- 更新 StateHolder (在主线程) ---
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "loadInitialData: 切换到主线程更新 StateHolder...")
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value = finalSelectedConfig
                    stateHolder._historicalConversations.value = loadedHistory

                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(processedLastOpenChatMessages)

                    stateHolder.messages.forEach { msg ->
                        if (msg.contentStarted || msg.isError) {
                            stateHolder.messageAnimationStates[msg.id] = true
                        }
                    }
                    Log.d(
                        TAG,
                        "loadInitialData: StateHolder.messages 已更新为最后打开的聊天。数量: ${stateHolder.messages.size}"
                    )

                    stateHolder._loadedHistoryIndex.value = null
                    Log.d(
                        TAG,
                        "loadInitialData: _loadedHistoryIndex 已在 StateHolder 更新中重置为 null。"
                    )

                    Log.d(TAG, "loadInitialData: StateHolder 更新完成。即将调用 onLoadingComplete。")
                    onLoadingComplete(initialConfigPresent, initialHistoryPresent)
                }

            } catch (e: Exception) {
                Log.e(TAG, "loadInitialData: 加载初始数据时发生严重错误", e)
                withContext(Dispatchers.Main.immediate) {
                    // 发生错误时，确保状态是可预测的（例如，全空），并通知回调
                    stateHolder._apiConfigs.value = emptyList()
                    stateHolder._selectedApiConfig.value = null
                    stateHolder._historicalConversations.value = emptyList()
                    stateHolder.messages.clear()
                    stateHolder._loadedHistoryIndex.value = null
                    onLoadingComplete(false, false)
                }
            } finally {
                Log.d(TAG, "loadInitialData: 初始数据加载的IO线程任务结束。")
            }
        }
    }

    suspend fun saveLastOpenChat(messages: List<Message>) {
        withContext(Dispatchers.IO) {
            Log.d(
                TAG,
                "saveLastOpenChat: 请求 dataSource 保存最后打开的聊天 (${messages.size} 条)。"
            )
            dataSource.saveLastOpenChatInternal(messages)
            Log.i(TAG, "saveLastOpenChat: 最后打开的聊天已通过 dataSource 保存。")
        }
    }

    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求 dataSource 清除聊天历史...")
            dataSource.clearChatHistory()
            Log.i(TAG, "clearAllChatHistory: dataSource 已清除聊天历史。")
        }
    }

    suspend fun saveApiConfigs(configsToSave: List<ApiConfig>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个 API 配置到 dataSource...")
            dataSource.saveApiConfigs(configsToSave)
            Log.i(TAG, "saveApiConfigs: API 配置已通过 dataSource 保存。")
        }
    }

    suspend fun saveChatHistory(historyToSave: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveChatHistory: 保存 ${historyToSave.size} 条对话到 dataSource...")
            dataSource.saveChatHistory(historyToSave)
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 dataSource 保存。")
        }
    }

    suspend fun saveSelectedConfigIdentifier(configId: String?) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中配置ID '$configId' 到 dataSource...")
            dataSource.saveSelectedConfigId(configId)
            Log.i(TAG, "saveSelectedConfigIdentifier: 选中配置ID已通过 dataSource 保存。")
        }
    }

    suspend fun clearAllApiConfigData() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 请求 dataSource 清除API配置并取消选中...")
            dataSource.clearApiConfigs()
            dataSource.saveSelectedConfigId(null) // 确保选中的也被清掉
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 dataSource 清除。")
        }
    }
}
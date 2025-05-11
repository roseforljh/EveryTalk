package com.example.app1.ui.screens.viewmodel

import android.util.Log // 引入 Log
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 管理聊天历史操作，如保存、加载、删除和清除。
 */
class HistoryManager(
    private val stateHolder: ViewModelStateHolder,          // ViewModel 状态持有者
    private val persistenceManager: DataPersistenceManager, // 数据持久化管理器
    private val viewModelScope: CoroutineScope              // ViewModel 的协程作用域
) {

    /** 过滤适合保存到历史记录的消息。 */
    private fun filterMessagesForSaving(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            msg.sender != Sender.System && // 排除系统消息
                    !msg.isError && // 排除错误消息
                    // 包含用户消息，或已开始内容、有推理或有文本的 AI 消息
                    (msg.sender == Sender.User || msg.contentStarted || !msg.reasoning.isNullOrBlank() || msg.text.isNotBlank())
        }.toList()
    }

    /**
     * 如果需要，将当前聊天 (`messages`) 保存到 `_historicalConversations`。
     * 更新或添加到历史列表。更新 `_loadedHistoryIndex`。
     * @param forceSave 如果为 true，即使内容可能与现有历史记录重复（当 loadedIndex 为 null 时），也会尝试保存（通常用于编辑后确保更新）。
     * @return 如果历史列表结构被修改（添加/更新），则返回 true。
     */
    fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false): Boolean { // 添加 forceSave 参数
        val currentMessagesSnapshot = stateHolder.messages.toList() // 当前消息的快照
        val messagesToSave =
            filterMessagesForSaving(currentMessagesSnapshot) // 过滤有效消息
        var historyModified = false // 跟踪历史结构是否更改

        Log.d(
            "HistoryManager",
            "准备保存。从 ${currentMessagesSnapshot.size} 条消息中过滤出 ${messagesToSave.size} 条有效消息。forceSave: $forceSave"
        )

        if (messagesToSave.isNotEmpty()) { // 仅当有有效消息时才保存
            var newLoadedIndex: Int? = stateHolder._loadedHistoryIndex.value // 可能的新索引
            var needsPersistence = false // 标记是否需要 SharedPreferences 保存

            // 更新历史对话状态流
            stateHolder._historicalConversations.update { currentHistory ->
                val loadedIndex = stateHolder._loadedHistoryIndex.value // 当前加载的索引
                val mutableHistory = currentHistory.toMutableList() // 用于操作的可变副本

                if (loadedIndex != null && loadedIndex >= 0 && loadedIndex < mutableHistory.size) {
                    // 情况 1: 当前正在查看一个已加载的历史项
                    // 检查内容是否已更改
                    if (mutableHistory[loadedIndex] != messagesToSave || forceSave) { // 如果 forceSave 为 true，即使内容相同也强制更新
                        Log.d(
                            "HistoryManager",
                            "正在更新索引 $loadedIndex 的历史记录，包含 ${messagesToSave.size} 条消息。"
                        )
                        mutableHistory[loadedIndex] = messagesToSave // 更新对话
                        historyModified = true // 列表内容已更改
                        needsPersistence = true // 需要保存到磁盘
                        // 加载的索引保持不变 (newLoadedIndex = loadedIndex)
                    } else {
                        Log.d("HistoryManager", "索引 $loadedIndex 的历史记录未更改，无需更新。")
                        historyModified = false // 列表内容未更改
                        return@update currentHistory // 返回原始列表，不触发状态更新
                    }
                } else {
                    // 情况 2: 当前聊天是新的或加载的索引无效
                    // 检查现有历史记录中是否有重复项
                    val existingIndex =
                        // 如果是强制保存新聊天 (loadedIndex 为 null)，则不检查重复
                        if (forceSave && loadedIndex == null) -1 else mutableHistory.indexOfFirst { it == messagesToSave }

                    if (existingIndex == -1) {
                        // 未找到重复项（或强制保存），作为新的历史条目添加（在开头）
                        Log.d(
                            "HistoryManager",
                            "正在将新对话保存到历史记录 (索引 0)，包含 ${messagesToSave.size} 条消息。"
                        )
                        mutableHistory.add(0, messagesToSave)
                        newLoadedIndex = 0 // 新项目在索引 0
                        historyModified = true // 列表结构已更改
                        needsPersistence = true // 需要保存到磁盘
                    } else {
                        // 找到重复项 (且非强制保存新聊天)
                        Log.d(
                            "HistoryManager",
                            "对话内容与历史索引 $existingIndex 相同。不添加重复项。"
                        )
                        newLoadedIndex =
                            existingIndex // 将加载的索引指向现有的重复项
                        historyModified = false // 列表结构未更改
                        return@update currentHistory // 返回原始列表
                    }
                }
                mutableHistory // 返回修改后的列表
            }

            // 列表更新后，如果加载的索引已更改，则同步加载的索引状态
            if (stateHolder._loadedHistoryIndex.value != newLoadedIndex) {
                stateHolder._loadedHistoryIndex.value = newLoadedIndex
            }

            // 如果数据被修改或添加，则持久化更改
            if (needsPersistence) {
                persistenceManager.saveChatHistory() // 调用持久化层
            }
        } else {
            Log.d("HistoryManager", "没有有效的消息可以保存到历史记录。")
        }
        return historyModified // 返回历史结构是否已更改
    }

    /**
     * 删除指定索引处的历史对话。
     * @param indexToDelete 要删除的历史对话的索引。
     */
    fun deleteConversation(indexToDelete: Int) {
        Log.d("HistoryManager", "请求删除历史索引 $indexToDelete。")
        var deleted = false // 标记是否成功删除
        val currentLoadedIndexBeforeDelete = stateHolder._loadedHistoryIndex.value // 删除前的加载索引

        stateHolder._historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) { // 检查索引有效性
                val mutableHistory = currentHistory.toMutableList()
                mutableHistory.removeAt(indexToDelete) // 从列表中移除
                deleted = true
                Log.d("HistoryManager", "已删除历史索引 $indexToDelete。")

                // 如有必要，调整加载的索引
                if (currentLoadedIndexBeforeDelete == indexToDelete) {
                    // 如果删除的是当前加载的项，则重置加载索引
                    stateHolder._loadedHistoryIndex.value = null
                    Log.d(
                        "HistoryManager",
                        "已删除当前加载的历史 $indexToDelete，重置 loadedHistoryIndex。"
                    )
                } else if (currentLoadedIndexBeforeDelete != null && currentLoadedIndexBeforeDelete > indexToDelete) {
                    // 如果删除的项在当前加载项之前，则加载索引减一
                    stateHolder._loadedHistoryIndex.value = currentLoadedIndexBeforeDelete - 1
                    Log.d(
                        "HistoryManager",
                        "已将 loadedHistoryIndex 递减至 ${stateHolder._loadedHistoryIndex.value}。"
                    )
                }
                mutableHistory // 返回修改后的列表
            } else {
                Log.d("HistoryManager", "无效的删除请求：索引 $indexToDelete 超出范围。")
                currentHistory // 返回原始列表
            }
        }
        if (deleted) { // 如果成功删除
            persistenceManager.saveChatHistory() // 持久化删除操作
            viewModelScope.launch { stateHolder._snackbarMessage.emit("对话已删除") } // 显示提示
        }
    }

    /**
     * 清除所有历史记录。
     */
    fun clearAllHistory() {
        Log.d("HistoryManager", "请求清除所有历史记录。")
        if (stateHolder._historicalConversations.value.isNotEmpty()) { // 如果历史记录不为空
            stateHolder._historicalConversations.value = emptyList() // 清空历史列表
            if (stateHolder._loadedHistoryIndex.value != null) {
                stateHolder._loadedHistoryIndex.value = null // 重置加载索引
                Log.d("HistoryManager", "历史记录已清除，重置 loadedHistoryIndex。")
            }
            persistenceManager.saveChatHistory() // 持久化清除操作
        } else {
            Log.d("HistoryManager", "没有历史记录可以清除。")
        }
    }
}
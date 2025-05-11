package com.example.app1.ui.screens.viewmodel

import android.util.Log
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender
import com.example.app1.StateControler.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理聊天历史操作，如保存、加载、删除和清除。
 */
class HistoryManager(
    private val stateHolder: ViewModelStateHolder,          // ViewModel 状态持有者
    private val persistenceManager: DataPersistenceManager, // 数据持久化管理器
    private val viewModelScope: CoroutineScope,             // ViewModel 的协程作用域
    // 接收一个比较函数，用于比较消息列表的实质内容
    private val compareMessageLists: (List<Message>?, List<Message>?) -> Boolean
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
    fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false): Boolean {
        val currentMessagesSnapshot = stateHolder.messages.toList()
        val messagesToSave = filterMessagesForSaving(currentMessagesSnapshot)
        var historyListModified = false // 跟踪历史列表是否真的被修改（添加/更新）
        var loadedIndexChanged = false // 跟踪 loadedHistoryIndex 是否发生变化

        Log.d(
            "HistoryManager",
            "saveCurrentChatToHistoryIfNeeded: " +
                    "Snapshot size=${currentMessagesSnapshot.size}, " +
                    "Filtered size=${messagesToSave.size}, " +
                    "forceSave=$forceSave, " +
                    "currentLoadedIndex=${stateHolder._loadedHistoryIndex.value}"
        )

        if (messagesToSave.isEmpty() && !forceSave) { // 如果没有有效消息且非强制保存（允许强制保存空列表以清空历史项）
            Log.d("HistoryManager", "没有有效的消息可以保存，且非强制保存空列表，不执行保存。")
            // 如果 loadedIndex 指向一个非空历史，而当前 messages 为空，
            // 且不是 forceSave，我们不应该修改历史。
            // 但如果 forceSave 为 true 且 messagesToSave 为空，我们可能想用空列表更新历史项。
            return false
        }

        // 在协程中更新 StateFlow 和执行持久化
        // 注意：StateFlow 更新应该在主线程，但可以在这里准备数据
        // 这里我们将整个逻辑放在 viewModelScope.launch(Dispatchers.IO) 中，并在需要时切换回 Main

        // viewModelScope.launch(Dispatchers.IO) { // 移到外部调用者决定线程，或保持在IO
        // 对于直接从ViewModel调用的情况，ViewModel的协程作用域通常是合适的。

        var finalNewLoadedIndex: Int? = stateHolder._loadedHistoryIndex.value // 初始化为当前值
        var needsPersistenceSave = false

        stateHolder._historicalConversations.update { currentHistory ->
            val mutableHistory = currentHistory.toMutableList()
            val currentLoadedIdx = stateHolder._loadedHistoryIndex.value // 获取最新的 loadedHistoryIndex

            if (currentLoadedIdx != null && currentLoadedIdx >= 0 && currentLoadedIdx < mutableHistory.size) {
                // 情况 1: 当前正在查看一个已加载的历史项
                val existingChatInHistory = mutableHistory[currentLoadedIdx]
                if (forceSave || !compareMessageLists(messagesToSave, existingChatInHistory)) {
                    Log.d(
                        "HistoryManager",
                        "更新历史索引 $currentLoadedIdx。ForceSave: $forceSave. 内容变化: ${
                            !compareMessageLists(
                                messagesToSave,
                                existingChatInHistory
                            )
                        }"
                    )
                    mutableHistory[currentLoadedIdx] = messagesToSave
                    historyListModified = true
                    needsPersistenceSave = true
                    // finalNewLoadedIndex 保持 currentLoadedIdx
                } else {
                    Log.d("HistoryManager", "历史索引 $currentLoadedIdx 未更改且非强制保存，不更新。")
                    return@update currentHistory // 未修改，返回原列表
                }
            } else {
                // 情况 2: 当前聊天是新的 (loadedHistoryIndex is null) 或加载的索引无效
                if (messagesToSave.isNotEmpty()) { // 只有当有实际内容要保存时才处理新聊天
                    // 检查是否与历史记录中已有的任何一项重复 (除非是强制保存新聊天)
                    val duplicateIndex = if (forceSave && currentLoadedIdx == null) {
                        -1 // 强制保存新的，不检查重复
                    } else {
                        mutableHistory.indexOfFirst { compareMessageLists(it, messagesToSave) }
                    }

                    if (duplicateIndex == -1) {
                        // 不重复，或强制保存：添加为新的历史条目 (在开头)
                        Log.d(
                            "HistoryManager",
                            "添加新对话到历史记录 (索引 0)。消息数: ${messagesToSave.size}"
                        )
                        mutableHistory.add(0, messagesToSave)
                        finalNewLoadedIndex = 0 // 新项目在索引 0
                        historyListModified = true
                        needsPersistenceSave = true
                    } else {
                        // 找到重复项 (且非强制保存新聊天)
                        Log.d(
                            "HistoryManager",
                            "新对话与历史索引 $duplicateIndex 重复。不添加，将 loadedIndex 指向它。"
                        )
                        finalNewLoadedIndex = duplicateIndex // 指向已存在的重复项
                        // historyListModified 保持 false，因为列表结构未变
                        return@update currentHistory // 列表未修改，返回原列表
                    }
                } else {
                    // messagesToSave 为空，并且 loadedHistoryIndex 为 null (全新空聊天)，不保存。
                    Log.d("HistoryManager", "新聊天为空，不添加到历史记录。")
                    return@update currentHistory
                }
            }
            mutableHistory // 返回修改后的列表
        } // end of _historicalConversations.update

        // 更新 loadedHistoryIndex (如果在 update 块内改变了它)
        if (stateHolder._loadedHistoryIndex.value != finalNewLoadedIndex) {
            stateHolder._loadedHistoryIndex.value = finalNewLoadedIndex
            loadedIndexChanged = true
            Log.d("HistoryManager", "LoadedHistoryIndex 更新为: $finalNewLoadedIndex")
        }

        if (needsPersistenceSave) {
            persistenceManager.saveChatHistory()
            Log.d("HistoryManager", "Chat history persisted.")
        }

        // 总是保存最后打开的聊天，即使它没有成为历史记录的一部分或没有修改历史记录
        // （例如，用户打开一个历史，不做修改，然后退出，下次应该还是打开这个历史）
        // 但只有当 messagesToSave 非空时才有意义
        if (messagesToSave.isNotEmpty()) {
            persistenceManager.saveLastOpenChat(messagesToSave)
            Log.d(
                "HistoryManager",
                "Last open chat (filtered) persisted. Size: ${messagesToSave.size}"
            )
        } else if (currentMessagesSnapshot.isNotEmpty()) {
            // 如果过滤后为空，但原始快照不为空 (例如全是系统消息)，
            // 那么“最后打开”的应该是这个原始状态，而不是空的。
            // 但我们通常不保存只含系统消息的聊天。
            // 决定：如果过滤后为空，则认为最后打开的是空的，这会清除 lastOpenChat。
            persistenceManager.saveLastOpenChat(emptyList())
            Log.d("HistoryManager", "Last open chat persisted as empty (filtered was empty).")
        }


        Log.d(
            "HistoryManager",
            "saveCurrentChatToHistoryIfNeeded completed. HistoryListModified: $historyListModified, LoadedIndexChanged: $loadedIndexChanged"
        )
        return historyListModified || loadedIndexChanged // 如果列表或索引有变，都算作“修改”了整体状态
        // } // end of viewModelScope.launch
        // return false // 如果移到协程内，外部同步返回可能不准确
    }


    /**
     * 删除指定索引处的历史对话。
     * @param indexToDelete 要删除的历史对话的索引。
     */
    fun deleteConversation(indexToDelete: Int) {
        // viewModelScope.launch(Dispatchers.IO) { // 同上，线程管理
        Log.d("HistoryManager", "请求删除历史索引 $indexToDelete。")
        var successfullyDeleted = false
        var finalLoadedIndexAfterDelete: Int? = stateHolder._loadedHistoryIndex.value

        stateHolder._historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                mutableHistory.removeAt(indexToDelete)
                successfullyDeleted = true
                Log.d("HistoryManager", "已从列表删除历史索引 $indexToDelete。")

                val currentLoadedIdx = stateHolder._loadedHistoryIndex.value // 获取删除操作前的 loadedIndex
                if (currentLoadedIdx == indexToDelete) {
                    finalLoadedIndexAfterDelete = null // 删除的是当前加载的项
                    Log.d("HistoryManager", "删除的是当前加载项, newLoadedIndex设为null。")
                } else if (currentLoadedIdx != null && currentLoadedIdx > indexToDelete) {
                    finalLoadedIndexAfterDelete = currentLoadedIdx - 1 // 删除的项在当前加载项之前
                    Log.d(
                        "HistoryManager",
                        "删除项在当前加载项之前, newLoadedIndex递减为 $finalLoadedIndexAfterDelete。"
                    )
                }
                // else loadedIndex 不受影响
                mutableHistory
            } else {
                Log.w(
                    "HistoryManager",
                    "无效的删除请求：索引 $indexToDelete 超出范围 ${currentHistory.size}。"
                )
                currentHistory // 返回原始列表
            }
        }

        if (successfullyDeleted) {
            if (stateHolder._loadedHistoryIndex.value != finalLoadedIndexAfterDelete) {
                stateHolder._loadedHistoryIndex.value = finalLoadedIndexAfterDelete
                Log.d(
                    "HistoryManager",
                    "LoadedHistoryIndex 更新为: $finalLoadedIndexAfterDelete (因删除)"
                )
            }
            persistenceManager.saveChatHistory()
        }
        // }
    }

    /**
     * 清除所有历史记录。
     */
    fun clearAllHistory() {
        // viewModelScope.launch(Dispatchers.IO) { // 同上
        Log.d("HistoryManager", "请求清除所有历史记录。")
        if (stateHolder._historicalConversations.value.isNotEmpty()) {
            stateHolder._historicalConversations.value = emptyList()
            if (stateHolder._loadedHistoryIndex.value != null) {
                stateHolder._loadedHistoryIndex.value = null
                Log.d("HistoryManager", "历史记录已清除，loadedHistoryIndex 重置为 null。")
            }
            persistenceManager.saveChatHistory()
            // 清除历史后，也应该清除 "lastOpenChat"
            persistenceManager.saveLastOpenChat(emptyList())
        } else {
            Log.d("HistoryManager", "没有历史记录可以清除。")
        }
        // }
    }
}
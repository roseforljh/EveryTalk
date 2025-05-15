package com.example.app1.ui.screens.viewmodel

import android.util.Log
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender
import com.example.app1.StateControler.ViewModelStateHolder // 更正包名
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val compareMessageLists: (List<Message>?, List<Message>?) -> Boolean
) {

    private fun filterMessagesForSaving(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            (msg.sender != Sender.System || msg.isPlaceholderName) &&
                    !msg.isError &&
                    (msg.sender == Sender.User ||
                            (msg.sender == Sender.AI &&
                                    (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                                    ) ||
                            (msg.sender == Sender.System && msg.isPlaceholderName)
                            )
        }.toList()
    }

    fun findChatInHistory(messagesToFind: List<Message>): Int {
        val filteredMessagesToFind = filterMessagesForSaving(messagesToFind)
        if (filteredMessagesToFind.isEmpty() && messagesToFind.isNotEmpty()) { // 如果原始列表非空但过滤后为空，则不应该匹配任何实质性历史
            return -1
        }
        if (filteredMessagesToFind.isEmpty()) return -1


        return stateHolder._historicalConversations.value.indexOfFirst { historyChat ->
            compareMessageLists(filterMessagesForSaving(historyChat), filteredMessagesToFind)
        }
    }

    suspend fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false): Boolean {
        val currentMessagesSnapshot = stateHolder.messages.toList()
        val messagesToSave = filterMessagesForSaving(currentMessagesSnapshot)
        var historyListModified = false
        var loadedIndexChanged = false

        Log.d(
            "HistoryManagerSM",
            "saveCurrent: snap=${currentMessagesSnapshot.size}, saveSize=${messagesToSave.size}, force=$forceSave, loadedIdx=${stateHolder._loadedHistoryIndex.value}"
        )

        if (messagesToSave.isEmpty() && !forceSave) {
            Log.d(
                "HistoryManagerSM",
                "No valid messages to save, and not forcing save of empty list."
            )
            // 即使不保存到历史列表，也确保“最后打开的聊天”是空的，以实现下次启动为新对话
            withContext(Dispatchers.IO) {
                persistenceManager.saveLastOpenChat(emptyList())
            }
            Log.d(
                "HistoryManagerSM",
                "Last open chat slot cleared (no valid messages to save to history)."
            )
            return false
        }

        var finalNewLoadedIndex: Int? = stateHolder._loadedHistoryIndex.value
        var needsPersistenceSave = false

        stateHolder._historicalConversations.update { currentHistory ->
            val mutableHistory = currentHistory.toMutableList()
            val currentLoadedIdx = stateHolder._loadedHistoryIndex.value

            if (currentLoadedIdx != null && currentLoadedIdx >= 0 && currentLoadedIdx < mutableHistory.size) {
                val existingChatInHistoryFiltered =
                    filterMessagesForSaving(mutableHistory[currentLoadedIdx])
                if (forceSave || !compareMessageLists(
                        messagesToSave,
                        existingChatInHistoryFiltered
                    )
                ) {
                    Log.d(
                        "HistoryManagerSM",
                        "Updating history at index $currentLoadedIdx. Force: $forceSave. Content changed: ${
                            !compareMessageLists(
                                messagesToSave,
                                existingChatInHistoryFiltered
                            )
                        }"
                    )
                    mutableHistory[currentLoadedIdx] = messagesToSave
                    historyListModified = true
                    needsPersistenceSave = true
                } else {
                    Log.d(
                        "HistoryManagerSM",
                        "History index $currentLoadedIdx not changed and not force save."
                    )
                    // 即使历史记录中的当前聊天没有变化，我们仍然要确保“最后打开”为空
                    // （这一步将在下面的 saveLastOpenChat 中统一处理）
                    return@update currentHistory
                }
            } else {
                if (messagesToSave.isNotEmpty() || forceSave) {
                    val duplicateIndex =
                        if (forceSave && currentLoadedIdx == null) -1 else findChatInHistory(
                            messagesToSave
                        ) // findChatInHistory 使用过滤后的 messagesToSave

                    if (duplicateIndex == -1) {
                        Log.d(
                            "HistoryManagerSM",
                            "Adding new conversation to history (index 0). Msg count: ${messagesToSave.size}"
                        )
                        mutableHistory.add(0, messagesToSave)
                        finalNewLoadedIndex = 0
                        historyListModified = true
                        needsPersistenceSave = true
                    } else {
                        Log.d(
                            "HistoryManagerSM",
                            "New conversation is duplicate of history index $duplicateIndex. Pointing loadedIndex to it."
                        )
                        finalNewLoadedIndex = duplicateIndex
                        // 即使是重复的，也要确保“最后打开”为空
                        // （这一步将在下面的 saveLastOpenChat 中统一处理）
                        return@update currentHistory
                    }
                } else {
                    Log.d(
                        "HistoryManagerSM",
                        "New conversation is empty and not force saving, not adding to history."
                    )
                    // 即使不添加到历史，也要确保“最后打开的聊天”是空的
                    // （这一步将在下面的 saveLastOpenChat 中统一处理）
                    return@update currentHistory
                }
            }
            mutableHistory
        }

        if (stateHolder._loadedHistoryIndex.value != finalNewLoadedIndex) {
            stateHolder._loadedHistoryIndex.value = finalNewLoadedIndex
            loadedIndexChanged = true
            Log.d("HistoryManagerSM", "LoadedHistoryIndex updated to: $finalNewLoadedIndex")
        }

        if (needsPersistenceSave) {
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory()
            }
            Log.d("HistoryManagerSM", "Chat history persisted.")
        }

        // --- 核心改动点 ---
        // 无论当前聊天内容是什么，总是将“最后打开的聊天”保存为空列表
        // 这样下次APP启动时，加载的就是一个空对话状态
        withContext(Dispatchers.IO) {
            persistenceManager.saveLastOpenChat(emptyList())
        }
        Log.d(
            "HistoryManagerSM",
            "Last open chat slot explicitly cleared to ensure new chat on next app start."
        )
        // --- 核心改动点结束 ---


        Log.d(
            "HistoryManagerSM",
            "saveIfNeeded completed. Modified: $historyListModified, IdxChanged: $loadedIndexChanged"
        )
        return historyListModified || loadedIndexChanged
    }

    suspend fun deleteConversation(indexToDelete: Int) {
        Log.d("HistoryManagerDEL", "Request to delete history index $indexToDelete.")
        var successfullyDeleted = false
        var finalLoadedIndexAfterDelete: Int? = stateHolder._loadedHistoryIndex.value

        stateHolder._historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                mutableHistory.removeAt(indexToDelete)
                successfullyDeleted = true
                Log.d("HistoryManagerDEL", "Removed history index $indexToDelete from list.")

                val currentLoadedIdx = stateHolder._loadedHistoryIndex.value
                if (currentLoadedIdx == indexToDelete) {
                    finalLoadedIndexAfterDelete = null
                    Log.d(
                        "HistoryManagerDEL",
                        "Deleted current loaded item, newLoadedIndex set to null."
                    )
                } else if (currentLoadedIdx != null && currentLoadedIdx > indexToDelete) {
                    finalLoadedIndexAfterDelete = currentLoadedIdx - 1
                    Log.d(
                        "HistoryManagerDEL",
                        "Deleted item before current, newLoadedIndex decremented to $finalLoadedIndexAfterDelete."
                    )
                }
                mutableHistory
            } else {
                Log.w(
                    "HistoryManagerDEL",
                    "Invalid delete request: index $indexToDelete out of bounds ${currentHistory.size}."
                )
                currentHistory
            }
        }

        if (successfullyDeleted) {
            if (stateHolder._loadedHistoryIndex.value != finalLoadedIndexAfterDelete) {
                stateHolder._loadedHistoryIndex.value = finalLoadedIndexAfterDelete
                Log.d(
                    "HistoryManagerDEL",
                    "LoadedHistoryIndex updated to: $finalLoadedIndexAfterDelete (due to deletion)"
                )
            }
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory()
                // 当删除一个对话后，也应确保“最后打开的聊天”被清空，
                // 除非ViewModel有其他逻辑来决定在删除后加载哪个聊天。
                // 为保持一致性，这里也清空它。
                if (stateHolder._loadedHistoryIndex.value == null) { // 如果删除后当前没有加载任何聊天
                    persistenceManager.saveLastOpenChat(emptyList())
                    Log.d(
                        "HistoryManagerDEL",
                        "Last open chat slot cleared as current loaded chat was deleted or became null."
                    )
                }
            }
        }
    }

    suspend fun clearAllHistory() {
        Log.d("HistoryManagerCLR", "Request to clear all history.")
        if (stateHolder._historicalConversations.value.isNotEmpty() || stateHolder._loadedHistoryIndex.value != null) {
            stateHolder._historicalConversations.value = emptyList()
            stateHolder._loadedHistoryIndex.value = null
            Log.d("HistoryManagerCLR", "History cleared, loadedHistoryIndex reset to null.")
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory() // 保存空的历史列表
                persistenceManager.saveLastOpenChat(emptyList()) // 确保最后打开的也是空的
            }
        } else {
            Log.d("HistoryManagerCLR", "No history to clear.")
        }
    }
}
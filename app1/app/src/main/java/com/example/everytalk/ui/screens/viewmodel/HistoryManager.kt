package com.example.everytalk.ui.screens.viewmodel // 请确保包名与你的项目一致

import android.util.Log
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.StateControler.ViewModelStateHolder // 已更正包名
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val compareMessageLists: (List<Message>?, List<Message>?) -> Boolean
) {

    /**
     * 过滤消息列表，只保留用于保存到历史记录或进行比较的有效消息。
     * - 排除非占位符的系统消息。
     * - 排除错误消息（除非它们是占位符系统消息的一部分，虽然这种情况不典型）。
     * - 用户消息总是保留。
     * - AI消息只有在内容已开始、文本非空或推理非空时才保留。
     * - 占位符系统消息（通常用于聊天标题）总是保留。
     */
    private fun filterMessagesForSaving(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            (msg.sender != Sender.System || msg.isPlaceholderName) && // 非占位符的系统消息不保存
                    !msg.isError && // 一般不保存错误消息，除非产品逻辑需要
                    (msg.sender == Sender.User || // 用户消息
                            (msg.sender == Sender.AI && // AI消息，且有实际内容
                                    (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                                    ) ||
                            (msg.sender == Sender.System && msg.isPlaceholderName) // 占位符系统消息（如标题）
                            )
        }.toList()
    }

    /**
     * 在历史对话列表中查找与给定消息列表匹配的对话。
     * @param messagesToFind 要查找的消息列表（会先经过过滤）。
     * @return 如果找到匹配的对话，则返回其在历史列表中的索引；否则返回 -1。
     */
    fun findChatInHistory(messagesToFind: List<Message>): Int {
        // 首先过滤待查找的消息列表，确保比较的是有效内容
        val filteredMessagesToFind = filterMessagesForSaving(messagesToFind)

        // 如果原始列表非空但过滤后为空（例如，只包含一个空的AI占位符），则不应匹配任何实质性历史
        if (filteredMessagesToFind.isEmpty() && messagesToFind.isNotEmpty()) {
            return -1
        }
        // 如果过滤后列表为空（可能原始列表就是空的，或者只包含无效消息），则不进行查找
        if (filteredMessagesToFind.isEmpty()) return -1


        return stateHolder._historicalConversations.value.indexOfFirst { historyChat ->
            // 对历史记录中的每个对话也进行过滤，然后比较
            compareMessageLists(filterMessagesForSaving(historyChat), filteredMessagesToFind)
        }
    }

    /**
     * 如果当前聊天内容有意义或有变化，则将其保存到历史记录中。
     * 关键行为：无论是否保存到历史列表，此方法执行后，都会将“最后打开的聊天”持久化为空列表，
     * 以确保下次App启动时加载的是一个新会话。
     *
     * @param forceSave 如果为 true，即使当前聊天内容与历史记录中的版本相同，或当前聊天为空，也会强制执行保存/更新操作。
     * @return 如果历史列表被修改或当前加载的历史索引发生变化，则返回 true；否则返回 false。
     */
    suspend fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false): Boolean {
        val currentMessagesSnapshot = stateHolder.messages.toList() // 获取当前UI上消息的快照
        val messagesToSave = filterMessagesForSaving(currentMessagesSnapshot) // 过滤出需要保存的有效消息
        var historyListModified = false // 标记历史对话列表本身是否被修改 (添加/更新)
        var loadedIndexChanged = false  // 标记当前加载的历史对话索引是否改变

        Log.d(
            "HistoryManagerSM",
            "saveCurrent: 快照消息数=${currentMessagesSnapshot.size}, 过滤后待保存数=${messagesToSave.size}, 强制保存=$forceSave, 当前加载索引=${stateHolder._loadedHistoryIndex.value}"
        )

        // 如果过滤后没有有效消息可保存，并且不是强制保存空列表，则不直接修改历史列表。
        // 但“最后打开的聊天”仍会在方法末尾被清空。
        if (messagesToSave.isEmpty() && !forceSave) {
            Log.d(
                "HistoryManagerSM",
                "没有有效的消息可保存到历史记录，且未强制保存空列表。"
            )
            // 注意：即使此处返回，方法末尾的 saveLastOpenChat(emptyList()) 仍会执行（通过后续逻辑保证）
        }

        var finalNewLoadedIndex: Int? = stateHolder._loadedHistoryIndex.value // 预设操作后的加载索引
        var needsPersistenceSaveOfHistoryList = false // 标记是否需要将整个历史列表持久化

        // 更新历史对话列表 (_historicalConversations)
        stateHolder._historicalConversations.update { currentHistory ->
            val mutableHistory = currentHistory.toMutableList()
            val currentLoadedIdx = stateHolder._loadedHistoryIndex.value // 当前实际加载的索引

            // 情况1: 当前正加载着一个历史对话 (currentLoadedIdx 有效)
            if (currentLoadedIdx != null && currentLoadedIdx >= 0 && currentLoadedIdx < mutableHistory.size) {
                val existingChatInHistoryFiltered =
                    filterMessagesForSaving(mutableHistory[currentLoadedIdx]) // 获取历史中该对话的有效消息

                // 如果强制保存，或者当前聊天内容与历史中已加载的对话内容不同
                if (forceSave || !compareMessageLists(
                        messagesToSave,
                        existingChatInHistoryFiltered
                    )
                ) {
                    Log.d(
                        "HistoryManagerSM",
                        "准备更新历史索引 $currentLoadedIdx. 强制: $forceSave. 内容是否改变: ${
                            !compareMessageLists(
                                messagesToSave,
                                existingChatInHistoryFiltered
                            )
                        }"
                    )
                    // 只有当 messagesToSave 非空，或者强制保存时，才用新内容覆盖历史中的条目。
                    // 如果 messagesToSave 为空但 forceSave 为 true，则允许用空列表覆盖（相当于清空该历史条目内容）。
                    if (messagesToSave.isNotEmpty() || forceSave) {
                        mutableHistory[currentLoadedIdx] = messagesToSave
                        historyListModified = true
                        needsPersistenceSaveOfHistoryList = true
                    } else {
                        // 如果 messagesToSave 为空且不是 forceSave，则不应修改历史列表中的这个条目。
                        // 例如：用户清空了当前正在查看的历史聊天，不应自动删除该历史版本。
                        Log.d(
                            "HistoryManagerSM",
                            "尝试用空消息更新历史索引 $currentLoadedIdx (非强制). 历史列表中的该条目未作修改。"
                        )
                    }
                } else {
                    Log.d(
                        "HistoryManagerSM",
                        "历史索引 $currentLoadedIdx 的内容未改变，且非强制保存。"
                    )
                    // 历史列表中的这个条目未变，直接返回当前历史列表。
                    // “最后打开的聊天”仍会在方法末尾被清空。
                    return@update currentHistory
                }
            }
            // 情况2: 当前没有加载历史对话 (currentLoadedIdx 为 null 或无效)
            // 这通常意味着用户正在进行一个全新的对话，或者是一个尚未被识别为历史一部分的对话。
            else {
                // 只有当有实际内容要保存 (messagesToSave 非空)，或者强制保存时，才尝试添加到历史记录。
                if (messagesToSave.isNotEmpty() || forceSave) {
                    // 检查这个新聊天是否已经是历史记录中的一个副本。
                    // 如果是强制保存一个“新”聊天（即 loadedHistoryIndex 为 null 时），则不检查重复，直接添加。
                    val duplicateIndex =
                        if (forceSave && currentLoadedIdx == null) -1 // 强制保存新聊天时不查重
                        else findChatInHistory(messagesToSave) // 否则查找是否重复

                    if (duplicateIndex == -1) { // 不是副本，将这个新聊天添加到历史记录的开头
                        Log.d(
                            "HistoryManagerSM",
                            "添加新对话到历史记录列表的开头 (索引 0). 消息数: ${messagesToSave.size}"
                        )
                        mutableHistory.add(0, messagesToSave)
                        finalNewLoadedIndex = 0 // 新添加的聊天成为当前加载的聊天 (其索引为0)
                        historyListModified = true
                        needsPersistenceSaveOfHistoryList = true
                    } else { // 是副本，更新 loadedHistoryIndex 指向该已存在的副本
                        Log.d(
                            "HistoryManagerSM",
                            "当前对话是历史索引 $duplicateIndex 的副本。将 loadedIndex 指向它。"
                        )
                        finalNewLoadedIndex = duplicateIndex
                        // 历史列表本身未改变，但 loadedIndex 可能改变。
                        // “最后打开的聊天”仍会在方法末尾被清空。
                        // 注意：此处不能直接 return@update currentHistory，因为 finalNewLoadedIndex 可能已改变，后续需要处理。
                    }
                } else {
                    Log.d(
                        "HistoryManagerSM",
                        "当前新对话为空且非强制保存，不添加到历史记录列表。"
                    )
                    // 历史列表本身未变，直接返回当前历史列表。
                    // “最后打开的聊天”仍会在方法末尾被清空。
                    return@update currentHistory
                }
            }
            mutableHistory // 返回修改后的历史列表
        }

        // 如果计算出的 finalNewLoadedIndex 与当前的 stateHolder._loadedHistoryIndex 不同，则更新它
        if (stateHolder._loadedHistoryIndex.value != finalNewLoadedIndex) {
            stateHolder._loadedHistoryIndex.value = finalNewLoadedIndex
            loadedIndexChanged = true
            Log.d("HistoryManagerSM", "LoadedHistoryIndex 更新为: $finalNewLoadedIndex")
        }

        // 如果历史列表内容被修改了 (needsPersistenceSaveOfHistoryList 为 true)，则持久化整个历史记录列表
        if (needsPersistenceSaveOfHistoryList) {
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory() // 保存整个聊天历史列表
            }
            Log.d("HistoryManagerSM", "聊天历史列表已持久化。")
        }

        // --- 核心改动点 ---
        // 无论之前的操作是什么（是否修改了历史列表，是否改变了 loadedIndex），
        // 总是将“最后打开的聊天”在持久化层面设置为空列表。
        // 这是为了确保下次App启动时，总是加载一个全新的、空的对话状态。
        withContext(Dispatchers.IO) {
            persistenceManager.saveLastOpenChat(emptyList())
        }
        Log.d(
            "HistoryManagerSM",
            "“最后打开的聊天”记录已明确清空，确保下次App启动时为新会话。"
        )
        // --- 核心改动点结束 ---


        Log.d(
            "HistoryManagerSM",
            "saveCurrentChatToHistoryIfNeeded 完成。历史列表是否修改: $historyListModified, 加载索引是否改变: $loadedIndexChanged"
        )
        // 返回值指示UI相关的状态（如历史列表视图或当前聊天指示器）是否可能需要更新
        return historyListModified || loadedIndexChanged
    }

    /**
     * 从历史记录中删除指定索引的对话。
     * 关键行为：删除操作后，会将“最后打开的聊天”持久化为空列表。
     *
     * @param indexToDelete 要删除的对话在历史列表中的索引。
     */
    suspend fun deleteConversation(indexToDelete: Int) {
        Log.d("HistoryManagerDEL", "请求删除历史索引 $indexToDelete.")
        var successfullyDeleted = false // 标记是否成功从列表中移除了条目
        var finalLoadedIndexAfterDelete: Int? =
            stateHolder._loadedHistoryIndex.value // 删除操作后，新的加载索引

        stateHolder._historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                mutableHistory.removeAt(indexToDelete) // 从可变列表中移除
                successfullyDeleted = true
                Log.d("HistoryManagerDEL", "已从历史列表中移除索引 $indexToDelete 的对话。")

                val currentLoadedIdx = stateHolder._loadedHistoryIndex.value // 获取删除前的加载索引
                if (currentLoadedIdx == indexToDelete) { // 如果删除的是当前加载的对话
                    finalLoadedIndexAfterDelete = null // 则不再加载任何历史对话
                    Log.d(
                        "HistoryManagerDEL",
                        "删除了当前加载的对话，新的 loadedIndex 设置为 null。"
                    )
                } else if (currentLoadedIdx != null && currentLoadedIdx > indexToDelete) { // 如果删除的对话在当前加载对话之前
                    finalLoadedIndexAfterDelete = currentLoadedIdx - 1 // 当前加载对话的索引需要减1
                    Log.d(
                        "HistoryManagerDEL",
                        "删除了位于当前加载对话之前的条目，新的 loadedIndex 减1为 $finalLoadedIndexAfterDelete."
                    )
                }
                // 如果删除的对话在当前加载对话之后，或者当前没有加载对话，则 loadedIndex 不受影响（除非它就是被删除的那个）
                mutableHistory // 返回修改后的历史列表
            } else {
                Log.w(
                    "HistoryManagerDEL",
                    "无效的删除请求：索引 $indexToDelete 超出历史列表范围 (大小 ${currentHistory.size})."
                )
                currentHistory // 未做修改，返回原列表
            }
        }

        if (successfullyDeleted) {
            // 如果计算出的 finalLoadedIndexAfterDelete 与当前的 stateHolder._loadedHistoryIndex 不同，则更新它
            if (stateHolder._loadedHistoryIndex.value != finalLoadedIndexAfterDelete) {
                stateHolder._loadedHistoryIndex.value = finalLoadedIndexAfterDelete
                Log.d(
                    "HistoryManagerDEL",
                    "因删除操作，LoadedHistoryIndex 更新为: $finalLoadedIndexAfterDelete"
                )
            }
            // 持久化修改后的历史列表，并清空“最后打开的聊天”记录
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory() // 保存更新后的历史列表
                persistenceManager.saveLastOpenChat(emptyList()) // 确保下次启动是新聊天
                Log.d(
                    "HistoryManagerDEL",
                    "聊天历史列表已持久化。“最后打开的聊天”记录已清空。"
                )
            }
        }
    }

    /**
     * 清除所有历史对话记录。
     * 关键行为：操作后，会将“最后打开的聊天”持久化为空列表。
     */
    suspend fun clearAllHistory() {
        Log.d("HistoryManagerCLR", "请求清除所有历史对话。")
        // 只有当确实有历史记录或当前加载了某个历史索引时，才执行操作
        if (stateHolder._historicalConversations.value.isNotEmpty() || stateHolder._loadedHistoryIndex.value != null) {
            stateHolder._historicalConversations.value = emptyList() // 清空内存中的历史列表
            stateHolder._loadedHistoryIndex.value = null // 重置加载索引
            Log.d("HistoryManagerCLR", "内存中的历史已清除，loadedHistoryIndex 重置为 null。")

            // 持久化空的历史列表，并清空“最后打开的聊天”记录
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory() // 保存空的历史列表到持久化存储
                persistenceManager.saveLastOpenChat(emptyList()) // 确保下次启动是新聊天
                Log.d(
                    "HistoryManagerCLR",
                    "持久化的历史列表已清空。“最后打开的聊天”记录已清空。"
                )
            }
        } else {
            Log.d("HistoryManagerCLR", "没有历史记录可清除。")
        }
    }
}
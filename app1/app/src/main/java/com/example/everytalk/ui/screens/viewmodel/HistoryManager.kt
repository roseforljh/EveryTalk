package com.example.everytalk.ui.screens.viewmodel

import android.util.Log
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val compareMessageLists: suspend (List<Message>?, List<Message>?) -> Boolean,
    private val onHistoryModified: () -> Unit
) {
    private val TAG_HM = "HistoryManager"

    private fun filterMessagesForSaving(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.map { msg ->
            // 在保存前，确保消息的最终文本内容被同步
            if (msg.sender == Sender.AI) {
                // 🔥 修复：确保AI消息始终标记为已开始，以便保存
                msg.copy(
                    text = msg.text,
                    contentStarted = true  // 强制设置为true，确保AI消息被保存
                )
            } else {
                msg
            }
        }.filter { msg ->
            (!msg.isError) &&
            (
                (msg.sender == Sender.User) ||
                // 🔥 修复：简化条件，只要是AI消息就保存（除非是错误消息）
                (msg.sender == Sender.AI) ||
                (msg.sender == Sender.System)
            )
        }.toList()
    }

    suspend fun findChatInHistory(messagesToFind: List<Message>, isImageGeneration: Boolean = false): Int = withContext(Dispatchers.Default) {
        val filteredMessagesToFind = filterMessagesForSaving(messagesToFind)
        if (filteredMessagesToFind.isEmpty()) return@withContext -1

        val history = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }

        history.indexOfFirst { historyChat ->
            runBlocking { compareMessageLists(filterMessagesForSaving(historyChat), filteredMessagesToFind) }
        }
    }

    suspend fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false, isImageGeneration: Boolean = false): Boolean {
        val currentMessagesSnapshot = if (isImageGeneration) stateHolder.imageGenerationMessages.toList() else stateHolder.messages.toList()
        
        // 只有文本模式才处理系统提示
        val currentConversationId = if (isImageGeneration) {
            stateHolder._currentImageGenerationConversationId.value
        } else {
            stateHolder._currentConversationId.value
        }
        val currentPrompt = if (!isImageGeneration) {
            stateHolder.systemPrompts[currentConversationId] ?: ""
        } else ""
        val messagesWithPrompt = if (currentPrompt.isNotBlank()) {
            listOf(Message(sender = Sender.System, text = currentPrompt)) + currentMessagesSnapshot
        } else {
            currentMessagesSnapshot
        }
        
        // Final check before saving
        val messagesToSave = filterMessagesForSaving(messagesWithPrompt)
        var historyListModified = false
        var loadedIndexChanged = false

        // 关键修复：确保获取正确模式的索引，避免交叉污染
        val loadedHistoryIndex = if (isImageGeneration) {
            stateHolder._loadedImageGenerationHistoryIndex.value
        } else {
            stateHolder._loadedHistoryIndex.value
        }
        
        // 关键修复：验证当前模式的消息列表是否为空，避免错误的状态判断
        val currentModeHasMessages = if (isImageGeneration) {
            stateHolder.imageGenerationMessages.isNotEmpty()
        } else {
            stateHolder.messages.isNotEmpty()
        }
        
        val isDirty = if (isImageGeneration) stateHolder.isImageConversationDirty.value else stateHolder.isTextConversationDirty.value
        Log.d(
            TAG_HM,
            "saveCurrent: Mode=${if (isImageGeneration) "IMAGE" else "TEXT"}, Snapshot msgs=${currentMessagesSnapshot.size}, Filtered to save=${messagesToSave.size}, Force=$forceSave, isDirty=$isDirty, CurrentLoadedIdx=$loadedHistoryIndex, HasMessages=$currentModeHasMessages"
        )

        if (messagesToSave.isEmpty() && !forceSave && !isImageGeneration) {
            Log.d(
                TAG_HM,
                "No valid messages to save and not in image generation mode. Not saving."
            )
            return false
        }

        var finalNewLoadedIndex: Int? = loadedHistoryIndex
        var needsPersistenceSaveOfHistoryList = false

        val historicalConversations = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations else stateHolder._historicalConversations
        historicalConversations.update { currentHistory ->
            val mutableHistory = currentHistory.toMutableList()
            val currentLoadedIdx = loadedHistoryIndex

            if (currentLoadedIdx != null && currentLoadedIdx >= 0 && currentLoadedIdx < mutableHistory.size) {
                val isDirty = if (isImageGeneration) stateHolder.isImageConversationDirty.value else stateHolder.isTextConversationDirty.value
                if (forceSave || isDirty) {
                    Log.d(
                        TAG_HM,
                        "Updating history index $currentLoadedIdx. Force: $forceSave. isDirty: $isDirty"
                    )
                    // 关键修复：即使强制保存，也绝不能用空列表覆盖一个有效的历史记录
                    if (messagesToSave.isNotEmpty()) {
                        mutableHistory[currentLoadedIdx] = messagesToSave
                        historyListModified = true
                        needsPersistenceSaveOfHistoryList = true
                    } else {
                        Log.d(
                            TAG_HM,
                            "Save is forced but there are no messages to save for index $currentLoadedIdx. Skipping update to prevent data loss."
                        )
                    }
                } else {
                    Log.d(TAG_HM, "History index $currentLoadedIdx content unchanged and not force saving.")
                }
            } else {
                if (messagesToSave.isNotEmpty()) {
                    val duplicateIndex = findChatInHistory(messagesToSave, isImageGeneration)
                    if (duplicateIndex == -1) {
                        Log.d(
                            TAG_HM,
                            "Adding new conversation to start of history. Message count: ${messagesToSave.size}"
                        )
                        mutableHistory.add(0, messagesToSave)
                        finalNewLoadedIndex = 0
                        historyListModified = true
                        needsPersistenceSaveOfHistoryList = true
                    } else {
                        Log.d(
                            TAG_HM,
                            "Current conversation is a duplicate of history index $duplicateIndex. Setting loadedIndex to it."
                        )
                        finalNewLoadedIndex = duplicateIndex
                    }
                } else {
                    Log.d(
                        TAG_HM,
                        "Current new conversation is empty, not adding to history."
                    )
                    return@update currentHistory
                }
            }
            mutableHistory
        }
 
        if (loadedHistoryIndex != finalNewLoadedIndex) {
            if (isImageGeneration) {
                stateHolder._loadedImageGenerationHistoryIndex.value = finalNewLoadedIndex
            } else {
                stateHolder._loadedHistoryIndex.value = finalNewLoadedIndex
            }
            loadedIndexChanged = true
            Log.d(TAG_HM, "LoadedHistoryIndex updated to: $finalNewLoadedIndex")
        }
 
        if (needsPersistenceSaveOfHistoryList) {
            persistenceManager.saveChatHistory(historicalConversations.value, isImageGeneration)
            if (isImageGeneration) {
                stateHolder.isImageConversationDirty.value = false
            } else {
                stateHolder.isTextConversationDirty.value = false
            }
            Log.d(TAG_HM, "Chat history list persisted and dirty flag reset.")
        }
        
        // 参数键迁移（根本修复）：用“会话的稳定键 = 首条消息的ID”，而不是“历史索引”
        // 历史索引会因插入/删除而变化，导致重启后无法按原键取到参数，表现为“回到默认”
        if (!isImageGeneration) {
            val currentId = stateHolder._currentConversationId.value
            // 从“准备保存”的会话内容中取第一条消息ID，作为稳定键
            val stableKeyFromMessages = messagesToSave.firstOrNull()?.id
            if (stableKeyFromMessages != null) {
                val stableId = stableKeyFromMessages
                val currentConfigs = stateHolder.conversationGenerationConfigs.value
                val currentConfigForSession = currentConfigs[currentId]
                if (currentConfigForSession != null) {
                    val newMap = currentConfigs.toMutableMap()
                    newMap[stableId] = currentConfigForSession
                    if (currentId != stableId) {
                        newMap.remove(currentId)
                    }
                    stateHolder.conversationGenerationConfigs.value = newMap
                    // 持久化“稳定键 -> 参数”
                    persistenceManager.saveConversationParameters(newMap)
                    // 切换当前会话ID为稳定键，后续读取与重启后都一致
                    stateHolder._currentConversationId.value = stableId
                    Log.d(TAG_HM, "Migrated parameters from '$currentId' to stable key(firstMessageId) '$stableId' and switched currentConversationId")
                }
            } else {
                // 极端情况：没有消息可用，跳过迁移（空会话本就不应落库）
                Log.d(TAG_HM, "Skip parameter migration: no messages to derive a stable key")
            }
        }

        // 关键修复：如果当前对话未保存到历史记录中，则保存为"last open chat"
        // 这样在切换对话或新建对话后，可以恢复之前的未完成对话
        if (messagesToSave.isNotEmpty()) {
            if (loadedHistoryIndex == null) {
                // 当前对话不在历史记录中，保存为"last open chat"以便后续恢复
                persistenceManager.saveLastOpenChat(messagesToSave, isImageGeneration)
                Log.d(TAG_HM, "Current conversation saved as last open chat for recovery.")
            } else {
                // 当前对话已在历史记录中，清除"last open chat"
                persistenceManager.clearLastOpenChat(isImageGeneration)
                Log.d(TAG_HM, "\"Last open chat\" record has been cleared in persistence.")
            }
        } else if (forceSave) {
            persistenceManager.clearLastOpenChat(isImageGeneration)
            Log.d(TAG_HM, "\"Last open chat\" record has been cleared in persistence.")
        }

        if (historyListModified) {
            onHistoryModified()
        }

        Log.d(
            TAG_HM,
            "saveCurrentChatToHistoryIfNeeded completed. HistoryModified: $historyListModified, LoadedIndexChanged: $loadedIndexChanged"
        )
        return historyListModified || loadedIndexChanged
    }

    suspend fun deleteConversation(indexToDelete: Int, isImageGeneration: Boolean = false) {
        Log.d(TAG_HM, "Requesting to delete history index $indexToDelete.")
        var successfullyDeleted = false
        val historicalConversations = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations else stateHolder._historicalConversations
        val loadedHistoryIndex = if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex else stateHolder._loadedHistoryIndex
        var finalLoadedIndexAfterDelete: Int? = loadedHistoryIndex.value
        var conversationToDelete: List<Message>? = null

        historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                conversationToDelete = mutableHistory[indexToDelete]
                mutableHistory.removeAt(indexToDelete)
                successfullyDeleted = true
                Log.d(TAG_HM, "Removed conversation at index $indexToDelete from memory.")

                val currentLoadedIdx = loadedHistoryIndex.value
                if (currentLoadedIdx == indexToDelete) {
                    finalLoadedIndexAfterDelete = null
                    Log.d(TAG_HM, "Deleted currently loaded conversation. New loadedIndex is null.")
                } else if (currentLoadedIdx != null && currentLoadedIdx > indexToDelete) {
                    finalLoadedIndexAfterDelete = currentLoadedIdx - 1
                    Log.d(
                        TAG_HM,
                        "Deleted conversation before current. New loadedIndex is $finalLoadedIndexAfterDelete."
                    )
                }
                mutableHistory
            } else {
                Log.w(
                    TAG_HM,
                    "Invalid delete request: Index $indexToDelete out of bounds (size ${currentHistory.size})."
                )
                currentHistory
            }
        }

        if (successfullyDeleted) {
            conversationToDelete?.let { conversation ->
                persistenceManager.deleteMediaFilesForMessages(listOf(conversation))
            }
            if (loadedHistoryIndex.value != finalLoadedIndexAfterDelete) {
                loadedHistoryIndex.value = finalLoadedIndexAfterDelete
                Log.d(
                    TAG_HM,
                    "Due to deletion, LoadedHistoryIndex updated to: $finalLoadedIndexAfterDelete"
                )
            }
            persistenceManager.saveChatHistory(historicalConversations.value, isImageGeneration)
            if (finalLoadedIndexAfterDelete == null) {
                persistenceManager.clearLastOpenChat(isImageGeneration)
            }
            // 增强：单条删除后也做一次孤立/缓存清理，确保预览/分享缓存与Coil缓存及时释放
            try {
                persistenceManager.cleanupOrphanedAttachments()
            } catch (e: Exception) {
                Log.w(TAG_HM, "cleanupOrphanedAttachments after delete failed", e)
            }
            Log.d(TAG_HM, "Chat history list persisted after deletion. \"Last open chat\" cleared.")
            onHistoryModified()
        }
    }

    suspend fun clearAllHistory(isImageGeneration: Boolean = false) {
        Log.d(TAG_HM, "Requesting to clear all history.")
        val historyToClear = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations.value else stateHolder._historicalConversations.value
        val loadedHistoryIndex = if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex else stateHolder._loadedHistoryIndex
        if (historyToClear.isNotEmpty() || loadedHistoryIndex.value != null) {
            persistenceManager.deleteMediaFilesForMessages(historyToClear)

            if (isImageGeneration) {
                stateHolder._imageGenerationHistoricalConversations.value = emptyList()
                loadedHistoryIndex.value = null
            } else {
                stateHolder._historicalConversations.value = emptyList()
                loadedHistoryIndex.value = null
            }
            Log.d(TAG_HM, "In-memory history cleared, loadedHistoryIndex reset to null.")

            persistenceManager.saveChatHistory(emptyList(), isImageGeneration)
            persistenceManager.clearLastOpenChat(isImageGeneration)
            
            // 清理所有孤立文件
            persistenceManager.cleanupOrphanedAttachments()
            
            Log.d(TAG_HM, "Persisted history list cleared. \"Last open chat\" cleared.")
        } else {
            Log.d(TAG_HM, "No history to clear.")
        }
    }
}
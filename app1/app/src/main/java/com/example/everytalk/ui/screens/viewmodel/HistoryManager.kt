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
        fun hasValidParts(parts: List<com.example.everytalk.ui.components.MarkdownPart>): Boolean {
            return parts.any { part ->
                when (part) {
                    is com.example.everytalk.ui.components.MarkdownPart.Text -> part.content.isNotBlank()
                    is com.example.everytalk.ui.components.MarkdownPart.CodeBlock -> part.content.isNotBlank()
                    else -> true
                }
            }
        }
        fun hasAiSubstance(msg: Message): Boolean {
            if (msg.sender != Sender.AI) return true
            val hasText = msg.text.isNotBlank()
            val hasReasoning = !msg.reasoning.isNullOrBlank()
            val hasParts = hasValidParts(msg.parts)
            return hasText || hasReasoning || hasParts
        }
        return messagesToFilter
            .filter { msg ->
                !msg.isError && when (msg.sender) {
                    Sender.User, Sender.System -> true
                    Sender.AI -> hasAiSubstance(msg)
                    else -> true
                }
            }
            .toList()
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
            compareMessageLists(filterMessagesForSaving(historyChat), filteredMessagesToFind)
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
                    // 🔧 修复：在 historicalConversations.update 闭包内同步检查重复，避免竞态条件
                    val duplicateIndex = mutableHistory.indexOfFirst { historyChat ->
                        runBlocking { compareMessageLists(filterMessagesForSaving(historyChat), messagesToSave) }
                    }
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
        
        // 参数键迁移（根本修复）：稳定会话键改为“首条有效用户消息ID”，否则回退“非占位System”，再回退“首条消息ID”
        // 避免因系统提示/标题插入到索引0而导致稳定键抖动
        if (!isImageGeneration) {
            val currentId = stateHolder._currentConversationId.value
            val stableKeyFromMessages =
                messagesToSave.firstOrNull { it.sender == Sender.User }?.id
                    ?: messagesToSave.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
                    ?: messagesToSave.firstOrNull()?.id
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
                    Log.d(TAG_HM, "Migrated parameters from '$currentId' to stable key '$stableId' (prefer first user message) and switched currentConversationId")
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
            // 🔧 修复：删除历史项后，重建 systemPrompts 映射，并保证当前加载会话的会话ID稳定
            runCatching {
                val currentHistoryFinal = historicalConversations.value
    
                // 1) 重建 systemPrompts（避免需要重进页面才能恢复）
                stateHolder.systemPrompts.clear()
                currentHistoryFinal.forEach { conversation ->
                    val stableIdForConv =
                        conversation.firstOrNull { it.sender == Sender.User }?.id
                            ?: conversation.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
                            ?: conversation.firstOrNull()?.id
                    val promptForConv =
                        conversation.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.text ?: ""
                    if (stableIdForConv != null) {
                        stateHolder.systemPrompts[stableIdForConv] = promptForConv
                    }
                }
    
                // 2) 若仍存在“已加载的会话”，将 currentConversationId（或图像模式的ID）同步到该会话的稳定键
                if (finalLoadedIndexAfterDelete != null &&
                    finalLoadedIndexAfterDelete >= 0 &&
                    finalLoadedIndexAfterDelete < currentHistoryFinal.size
                ) {
                    val conv = currentHistoryFinal[finalLoadedIndexAfterDelete]
                    val stableIdLoaded =
                        conv.firstOrNull { it.sender == Sender.User }?.id
                            ?: conv.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
                            ?: conv.firstOrNull()?.id
    
                    if (stableIdLoaded != null) {
                        if (isImageGeneration) {
                            stateHolder._currentImageGenerationConversationId.value = stableIdLoaded
                        } else {
                            stateHolder._currentConversationId.value = stableIdLoaded
                        }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG_HM, "Failed to rebuild prompts or adjust conversationId after deletion", e)
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
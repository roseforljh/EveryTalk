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
        return messagesToFilter.filter { msg ->
            (!msg.isError) &&
            (
                (msg.sender == Sender.User) ||
                (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) ||
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
        
        Log.d(
            TAG_HM,
            "saveCurrent: Mode=${if (isImageGeneration) "IMAGE" else "TEXT"}, Snapshot msgs=${currentMessagesSnapshot.size}, Filtered to save=${messagesToSave.size}, Force=$forceSave, CurrentLoadedIdx=$loadedHistoryIndex, HasMessages=$currentModeHasMessages"
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
                val existingChatInHistoryFiltered =
                    filterMessagesForSaving(mutableHistory[currentLoadedIdx])
                val contentChanged = runBlocking {
                    !compareMessageLists(
                        messagesToSave,
                        existingChatInHistoryFiltered
                    )
                }
                if (forceSave || contentChanged) {
                    Log.d(
                        TAG_HM,
                        "Updating history index $currentLoadedIdx. Force: $forceSave. Content changed: $contentChanged"
                    )
                    if (messagesToSave.isNotEmpty() || forceSave) {
                        mutableHistory[currentLoadedIdx] = messagesToSave
                        historyListModified = true
                        needsPersistenceSaveOfHistoryList = true
                    } else {
                        Log.d(
                            TAG_HM,
                            "Attempt to update history index $currentLoadedIdx with empty messages (not forced). No change to this history entry."
                        )
                    }
                } else {
                    Log.d(
                        TAG_HM,
                        "History index $currentLoadedIdx content unchanged and not force saving."
                    )
                    return@update currentHistory
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
            Log.d(TAG_HM, "Chat history list persisted.")
        }

        if (messagesToSave.isNotEmpty() || forceSave) {
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
           conversationToDelete?.let {
               persistenceManager.deleteMediaFilesForMessages(listOf(it))
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
            Log.d(TAG_HM, "Persisted history list cleared. \"Last open chat\" cleared.")
        } else {
            Log.d(TAG_HM, "No history to clear.")
        }
    }
}
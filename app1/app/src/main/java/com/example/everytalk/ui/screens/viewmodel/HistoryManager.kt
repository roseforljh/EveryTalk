package com.example.everytalk.ui.screens.viewmodel

import android.util.Log
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val compareMessageLists: (List<Message>?, List<Message>?) -> Boolean,
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

    suspend fun findChatInHistory(messagesToFind: List<Message>): Int = withContext(Dispatchers.Default) {
        val filteredMessagesToFind = filterMessagesForSaving(messagesToFind)
        if (filteredMessagesToFind.isEmpty() && messagesToFind.isNotEmpty()) {
            return@withContext -1
        }
        if (filteredMessagesToFind.isEmpty()) return@withContext -1

        stateHolder._historicalConversations.value.indexOfFirst { historyChat ->
            compareMessageLists(filterMessagesForSaving(historyChat), filteredMessagesToFind)
        }
    }

    suspend fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false): Boolean {
        val currentMessagesSnapshot = stateHolder.messages.toList()
        val currentPrompt = stateHolder.systemPrompts[stateHolder._currentConversationId.value] ?: ""
        val messagesWithPrompt = if (currentPrompt.isNotBlank()) {
            listOf(Message(sender = Sender.System, text = currentPrompt)) + currentMessagesSnapshot
        } else {
            currentMessagesSnapshot
        }
        val messagesToSave = filterMessagesForSaving(messagesWithPrompt)
        var historyListModified = false
        var loadedIndexChanged = false

        Log.d(
            TAG_HM,
            "saveCurrent: Snapshot msgs=${currentMessagesSnapshot.size}, Filtered to save=${messagesToSave.size}, Force=$forceSave, CurrentLoadedIdx=${stateHolder._loadedHistoryIndex.value}"
        )

        if (messagesToSave.isEmpty() && !forceSave) {
            Log.d(
                TAG_HM,
                "No valid messages to save to history, and not forcing save of empty list."
            )
        }

        var finalNewLoadedIndex: Int? = stateHolder._loadedHistoryIndex.value
        var needsPersistenceSaveOfHistoryList = false

        stateHolder._historicalConversations.update { currentHistory ->
            val mutableHistory = currentHistory.toMutableList()
            val currentLoadedIdx = stateHolder._loadedHistoryIndex.value

            if (currentLoadedIdx != null && currentLoadedIdx >= 0 && currentLoadedIdx < mutableHistory.size) {
                val existingChatInHistoryFiltered =
                    filterMessagesForSaving(mutableHistory[currentLoadedIdx])
                val contentChanged = withContext(Dispatchers.Default) {
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
                if (messagesToSave.isNotEmpty() || forceSave) {
                    val duplicateIndex =
                        if (forceSave && currentLoadedIdx == null) -1 else findChatInHistory(
                            messagesToSave
                        )
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
                        "Current new conversation is empty and not force saving, not adding to history."
                    )
                    return@update currentHistory
                }
            }
            mutableHistory
        }

        if (stateHolder._loadedHistoryIndex.value != finalNewLoadedIndex) {
            stateHolder._loadedHistoryIndex.value = finalNewLoadedIndex
            loadedIndexChanged = true
            Log.d(TAG_HM, "LoadedHistoryIndex updated to: $finalNewLoadedIndex")
        }

        if (needsPersistenceSaveOfHistoryList) {
            persistenceManager.saveChatHistory(stateHolder._historicalConversations.value)
            Log.d(TAG_HM, "Chat history list persisted.")
        }

        persistenceManager.saveLastOpenChat(emptyList())
        Log.d(TAG_HM, "\"Last open chat\" record has been cleared in persistence.")

        if (historyListModified) {
            onHistoryModified()
        }

        Log.d(
            TAG_HM,
            "saveCurrentChatToHistoryIfNeeded completed. HistoryModified: $historyListModified, LoadedIndexChanged: $loadedIndexChanged"
        )
        return historyListModified || loadedIndexChanged
    }

    suspend fun deleteConversation(indexToDelete: Int) {
        Log.d(TAG_HM, "Requesting to delete history index $indexToDelete.")
        var successfullyDeleted = false
        var finalLoadedIndexAfterDelete: Int? = stateHolder._loadedHistoryIndex.value
        var conversationToDelete: List<Message>? = null

        stateHolder._historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                conversationToDelete = mutableHistory[indexToDelete]
                mutableHistory.removeAt(indexToDelete)
                successfullyDeleted = true
                Log.d(TAG_HM, "Removed conversation at index $indexToDelete from memory.")

                val currentLoadedIdx = stateHolder._loadedHistoryIndex.value
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
            if (stateHolder._loadedHistoryIndex.value != finalLoadedIndexAfterDelete) {
                stateHolder._loadedHistoryIndex.value = finalLoadedIndexAfterDelete
                Log.d(
                    TAG_HM,
                    "Due to deletion, LoadedHistoryIndex updated to: $finalLoadedIndexAfterDelete"
                )
            }
            persistenceManager.saveChatHistory(stateHolder._historicalConversations.value)
            persistenceManager.saveLastOpenChat(emptyList())
            Log.d(TAG_HM, "Chat history list persisted after deletion. \"Last open chat\" cleared.")
        }
    }

    suspend fun clearAllHistory() {
        Log.d(TAG_HM, "Requesting to clear all history.")
        val historyToClear = stateHolder._historicalConversations.value
        if (historyToClear.isNotEmpty() || stateHolder._loadedHistoryIndex.value != null) {
            persistenceManager.deleteMediaFilesForMessages(historyToClear)

            stateHolder._historicalConversations.value = emptyList()
            stateHolder._loadedHistoryIndex.value = null
            Log.d(TAG_HM, "In-memory history cleared, loadedHistoryIndex reset to null.")

            persistenceManager.saveChatHistory(stateHolder._historicalConversations.value)
            persistenceManager.saveLastOpenChat(emptyList())
            Log.d(TAG_HM, "Persisted history list cleared. \"Last open chat\" cleared.")
        } else {
            Log.d(TAG_HM, "No history to clear.")
        }
    }
}
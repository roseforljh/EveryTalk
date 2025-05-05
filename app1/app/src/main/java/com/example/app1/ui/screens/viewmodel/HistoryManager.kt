package com.example.app1.ui.screens.viewmodel

import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages chat history operations like saving, loading, deleting, and clearing.
 */
class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val viewModelScope: CoroutineScope
) {

    /** Filters messages suitable for saving to history. */
    private fun filterMessagesForSaving(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            msg.sender != Sender.System && // Exclude system messages
                    !msg.isError && // Exclude error messages
                    // Include User messages, or AI messages that have started content, reasoning, or text
                    (msg.sender == Sender.User || msg.contentStarted || !msg.reasoning.isNullOrBlank() || msg.text.isNotBlank())
        }.toList()
    }

    /**
     * Saves the current chat (`messages`) to `_historicalConversations` if needed.
     * Updates or adds to the history list. Updates `_loadedHistoryIndex`.
     * Returns true if the history list structure was modified (added/updated).
     */
    fun saveCurrentChatToHistoryIfNeeded(): Boolean {
        val currentMessagesSnapshot = stateHolder.messages.toList() // Snapshot of current messages
        val messagesToSave =
            filterMessagesForSaving(currentMessagesSnapshot) // Filter valid messages
        var historyModified = false // Track if history structure changes

        println("HistoryManager: Preparing to save. Filtered ${messagesToSave.size} valid messages from ${currentMessagesSnapshot.size}.")

        if (messagesToSave.isNotEmpty()) { // Only save if there are valid messages
            var newLoadedIndex: Int? = stateHolder._loadedHistoryIndex.value // Potential new index
            var needsPersistence = false // Flag if SharedPreferences save is needed

            // Update the historical conversations state flow
            stateHolder._historicalConversations.update { currentHistory ->
                val loadedIndex = stateHolder._loadedHistoryIndex.value // Current loaded index
                val mutableHistory = currentHistory.toMutableList() // Mutable copy for operations

                if (loadedIndex != null && loadedIndex >= 0 && loadedIndex < mutableHistory.size) {
                    // Case 1: Currently viewing a loaded history item
                    // Check if content has changed
                    if (mutableHistory[loadedIndex] != messagesToSave) {
                        println("HistoryManager: Updating history at index $loadedIndex with ${messagesToSave.size} messages.")
                        mutableHistory[loadedIndex] = messagesToSave // Update the conversation
                        historyModified = true // List content changed
                        needsPersistence = true // Need to save to disk
                        // loaded index remains the same (newLoadedIndex = loadedIndex)
                    } else {
                        println("HistoryManager: History at index $loadedIndex unchanged, no update needed.")
                        historyModified = false // List content did not change
                        return@update currentHistory // Return original list, no state update triggered
                    }
                } else {
                    // Case 2: Current chat is new or loaded index is invalid
                    // Check for duplicates in existing history
                    val existingIndex = mutableHistory.indexOfFirst { it == messagesToSave }
                    if (existingIndex == -1) {
                        // No duplicate found, add as new history entry (at the beginning)
                        println("HistoryManager: Saving new conversation to history (index 0) with ${messagesToSave.size} messages.")
                        mutableHistory.add(0, messagesToSave)
                        newLoadedIndex = 0 // New item is at index 0
                        historyModified = true // List structure changed
                        needsPersistence = true // Need to save to disk
                    } else {
                        // Duplicate found
                        println("HistoryManager: Conversation content identical to history index $existingIndex. Not adding duplicate.")
                        newLoadedIndex =
                            existingIndex // Point loaded index to the existing duplicate
                        historyModified = false // List structure did not change
                        return@update currentHistory // Return original list
                    }
                }
                mutableHistory // Return the modified list
            }

            // After list update, sync the loaded index state if it changed
            if (stateHolder._loadedHistoryIndex.value != newLoadedIndex) {
                stateHolder._loadedHistoryIndex.value = newLoadedIndex
            }

            // If data was modified or added, persist the changes
            if (needsPersistence) {
                persistenceManager.saveChatHistory() // Call persistence layer
            }
        } else {
            println("HistoryManager: No valid messages to save to history.")
        }
        return historyModified // Return whether history structure changed
    }

    fun deleteConversation(indexToDelete: Int) {
        println("HistoryManager: Request to delete history index $indexToDelete.")
        var deleted = false
        val currentLoadedIndexBeforeDelete = stateHolder._loadedHistoryIndex.value

        stateHolder._historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                mutableHistory.removeAt(indexToDelete) // Remove from list
                deleted = true
                println("HistoryManager: Deleted history index $indexToDelete.")

                // Adjust loaded index if necessary
                if (currentLoadedIndexBeforeDelete == indexToDelete) {
                    stateHolder._loadedHistoryIndex.value = null
                    println("HistoryManager: Deleted currently loaded history $indexToDelete, reset loadedHistoryIndex.")
                } else if (currentLoadedIndexBeforeDelete != null && currentLoadedIndexBeforeDelete > indexToDelete) {
                    stateHolder._loadedHistoryIndex.value = currentLoadedIndexBeforeDelete - 1
                    println("HistoryManager: Decremented loadedHistoryIndex to ${stateHolder._loadedHistoryIndex.value}.")
                }
                mutableHistory // Return modified list
            } else {
                println("HistoryManager: Invalid delete request: index $indexToDelete out of bounds.")
                currentHistory // Return original list
            }
        }
        if (deleted) {
            persistenceManager.saveChatHistory() // Persist the deletion
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Conversation deleted") }
        }
    }

    fun clearAllHistory() {
        println("HistoryManager: Request to clear all history.")
        if (stateHolder._historicalConversations.value.isNotEmpty()) {
            stateHolder._historicalConversations.value = emptyList()
            if (stateHolder._loadedHistoryIndex.value != null) {
                stateHolder._loadedHistoryIndex.value = null
                println("HistoryManager: Reset loadedHistoryIndex as history was cleared.")
            }
            persistenceManager.saveChatHistory() // Persist the clearing
            viewModelScope.launch { stateHolder._snackbarMessage.emit("All history cleared") }
        } else {
            println("HistoryManager: No history to clear.")
            viewModelScope.launch { stateHolder._snackbarMessage.emit("No history to clear") }
        }
    }
}
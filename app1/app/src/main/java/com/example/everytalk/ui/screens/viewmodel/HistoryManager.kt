package com.example.everytalk.ui.screens.viewmodel

import android.util.Log
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val compareMessageLists: suspend (List<Message>?, List<Message>?) -> Boolean,
    private val onHistoryModified: () -> Unit,
    private val scope: CoroutineScope
) {
    private val TAG_HM = "HistoryManager"

    // -------- 新增：持久化防抖与串行化 --------
    private val saveRequestChannel = Channel<SaveRequest>(Channel.CONFLATED)
    private var debouncedSaveJob: Job? = null
    private val DEBOUNCE_SAVE_MS = 1800L

    // 去重稳态：最近一次插入的指纹与时间，用于吸收 forceSave + debounce 双触发
    private var lastInsertFingerprint: String? = null
    private var lastInsertAtMs: Long = 0L

    // 生成单条消息的稳定指纹（忽略 id/timestamp/动画状态/占位标题）
    private fun messageFingerprint(msg: Message): String {
        val senderTag = when (msg.sender) {
            Sender.User -> "U"
            Sender.AI -> "A"
            Sender.System -> if (msg.isPlaceholderName) "S_PLACEHOLDER" else "S"
            else -> "O"
        }
        val text = msg.text.trim()
        val reasoning = (msg.reasoning ?: "").trim()
        val hasImages = if (msg.imageUrls.isNullOrEmpty()) 0 else msg.imageUrls!!.size
        val attachmentsSet = msg.attachments.mapNotNull {
            when (it) {
                is com.example.everytalk.models.SelectedMediaItem.ImageFromUri -> it.uri.toString()
                is com.example.everytalk.models.SelectedMediaItem.GenericFile -> it.uri.toString()
                is com.example.everytalk.models.SelectedMediaItem.Audio -> it.data ?: ""
                is com.example.everytalk.models.SelectedMediaItem.ImageFromBitmap -> it.filePath ?: ""
            }
        }.toSet().sorted().joinToString("|")
        return listOf(senderTag, text, reasoning, "img=$hasImages", "att={$attachmentsSet}").joinToString("::")
    }

    // 会话稳定指纹：为判重目的，忽略一切 System 消息（标题/提示均不计入）
    private fun conversationFingerprint(messages: List<Message>): String {
        val filtered = filterMessagesForSaving(messages).filter { it.sender != Sender.System }
        return filtered.joinToString("||") { messageFingerprint(it) }
    }

    init {
        scope.launch(Dispatchers.IO) {
            for (req in saveRequestChannel) {
                if (!isActive) break
                performSave(req)
            }
        }
    }

    private data class SaveRequest(val force: Boolean, val isImageGen: Boolean)

    private suspend fun performSave(req: SaveRequest) {
        saveCurrentChatToHistoryIfNeededInternal(req.force, req.isImageGen)
    }

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
            val hasImages = !msg.imageUrls.isNullOrEmpty()
            return hasText || hasReasoning || hasParts || hasImages
        }
        return messagesToFilter
            .filter { msg ->
                if (msg.isError) return@filter false
                when (msg.sender) {
                    Sender.User -> true
                    Sender.System -> !msg.isPlaceholderName // 排除占位标题，避免去重误判
                    Sender.AI -> hasAiSubstance(msg)
                    else -> true
                }
            }
            .map { it.copy(text = it.text.trim(), reasoning = it.reasoning?.trim()) }
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

    suspend fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false, isImageGeneration: Boolean = false) {
        debouncedSaveJob?.cancel()
        if (forceSave) {
            saveRequestChannel.send(SaveRequest(force = true, isImageGen = isImageGeneration))
        } else {
            debouncedSaveJob = scope.launch {
                delay(DEBOUNCE_SAVE_MS)
                saveRequestChannel.send(SaveRequest(force = false, isImageGen = isImageGeneration))
            }
        }
    }

    private suspend fun saveCurrentChatToHistoryIfNeededInternal(forceSave: Boolean = false, isImageGeneration: Boolean = false): Boolean {
        val currentMessagesSnapshot = if (isImageGeneration) stateHolder.imageGenerationMessages.toList() else stateHolder.messages.toList()
        
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

        val loadedHistoryIndex = if (isImageGeneration) {
            stateHolder._loadedImageGenerationHistoryIndex.value
        } else {
            stateHolder._loadedHistoryIndex.value
        }
        
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
        var addedNewConversation = false
        val newConversationFingerprint = conversationFingerprint(messagesToSave)
        val nowMs = System.currentTimeMillis()

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
                    if (messagesToSave.isNotEmpty()) {
                        mutableHistory[currentLoadedIdx] = messagesToSave
                        historyListModified = true
                        needsPersistenceSaveOfHistoryList = true
                        Log.d(TAG_HM, "Updated existing history at index=$currentLoadedIdx, fp=${newConversationFingerprint.take(64)}")
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
                    // 先与头部会话比较稳定指纹，幂等保护
                    val headFingerprint = if (mutableHistory.isNotEmpty()) conversationFingerprint(mutableHistory.first()) else null
                    if (headFingerprint != null && headFingerprint == newConversationFingerprint) {
                        Log.i(TAG_HM, "Skip insert: head fingerprint equals new conversation (idempotent head guard)")
                        finalNewLoadedIndex = 0
                    } else if (lastInsertFingerprint == newConversationFingerprint && (nowMs - lastInsertAtMs) < 3000L) {
                        Log.i(TAG_HM, "Skip insert: same conversation within 3s window (force+debounce guard)")
                        // 保持 loadedIndex 不变（仍然为空表示新会话未入库）
                    } else {
                        // 先用“无System指纹”快速判重，再回退到深比较
                        var duplicateIndex = mutableHistory.indexOfFirst { historyChat ->
                            conversationFingerprint(historyChat) == newConversationFingerprint
                        }
                        if (duplicateIndex == -1) {
                            duplicateIndex = mutableHistory.indexOfFirst { historyChat ->
                                runBlocking { compareMessageLists(filterMessagesForSaving(historyChat), messagesToSave) }
                            }
                        }
                        if (duplicateIndex == -1) {
                            Log.d(
                                TAG_HM,
                                "Adding new conversation to start of history. Message count: ${messagesToSave.size}, fp=${newConversationFingerprint.take(64)}"
                            )
                            mutableHistory.add(0, messagesToSave)
                            finalNewLoadedIndex = 0
                            historyListModified = true
                            needsPersistenceSaveOfHistoryList = true
                            addedNewConversation = true
                            // 相邻去重兜底（防极端竞态）
                            if (mutableHistory.size >= 2) {
                                val fp0 = conversationFingerprint(mutableHistory[0])
                                val fp1 = conversationFingerprint(mutableHistory[1])
                                if (fp0 == fp1) {
                                    Log.w(TAG_HM, "Adjacent duplicate detected after insert. Removing the second one to dedup.")
                                    mutableHistory.removeAt(1)
                                }
                            }
                        } else {
                            Log.d(
                                TAG_HM,
                                "Current conversation is a duplicate of history index $duplicateIndex. Setting loadedIndex to it."
                            )
                            finalNewLoadedIndex = duplicateIndex
                        }
                    }
                } else {
                    Log.d(
                        TAG_HM,
                        "Current new conversation is empty, not adding to history."
                    )
                    return@update currentHistory
                }
            }
            // 全局去重（按稳定指纹，忽略所有 System），保留首次出现顺序
            val seen = mutableSetOf<String>()
            val deduped = mutableListOf<List<Message>>()
            var removed = 0
            for (conv in mutableHistory) {
                val fp = conversationFingerprint(conv)
                if (fp.isEmpty() || seen.add(fp)) {
                    deduped.add(conv)
                } else {
                    removed++
                }
            }
            if (removed > 0) {
                Log.w(TAG_HM, "Global dedup removed $removed duplicate conversations (fingerprint-based)")
                historyListModified = true
                needsPersistenceSaveOfHistoryList = true
            }
            deduped
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

        // 更新最近一次插入指纹/时间（仅当本次实际新增时）
        if (addedNewConversation) {
            lastInsertFingerprint = newConversationFingerprint
            lastInsertAtMs = nowMs
            Log.d(TAG_HM, "Recorded last insert fingerprint (len=${newConversationFingerprint.length}) at=$nowMs")
        }
        
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
                    persistenceManager.saveConversationParameters(newMap)
                    stateHolder._currentConversationId.value = stableId
                    Log.d(TAG_HM, "Migrated parameters from '$currentId' to stable key '$stableId' (prefer first user message) and switched currentConversationId")
                }
            } else {
                Log.d(TAG_HM, "Skip parameter migration: no messages to derive a stable key")
            }
        }

        if (messagesToSave.isNotEmpty()) {
            if (loadedHistoryIndex == null) {
                persistenceManager.saveLastOpenChat(messagesToSave, isImageGeneration)
                Log.d(TAG_HM, "Current conversation saved as last open chat for recovery.")
            } else {
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
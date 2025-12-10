package com.android.everytalk.ui.screens.viewmodel

import android.util.Log
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
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
                is com.android.everytalk.models.SelectedMediaItem.ImageFromUri -> it.uri.toString()
                is com.android.everytalk.models.SelectedMediaItem.GenericFile -> it.uri.toString()
                is com.android.everytalk.models.SelectedMediaItem.Audio -> it.data ?: ""
                is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap -> it.filePath ?: ""
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
        fun hasValidParts(parts: List<com.android.everytalk.ui.components.MarkdownPart>): Boolean {
            return parts.any { part ->
                when (part) {
                    is com.android.everytalk.ui.components.MarkdownPart.Text -> part.content.isNotBlank()
                    is com.android.everytalk.ui.components.MarkdownPart.CodeBlock -> part.content.isNotBlank()
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
    /**
     * 同步保存当前会话并返回“最终索引”（避免上层读取瞬时旧值导致的重复插入）
     */
    suspend fun saveCurrentChatToHistoryNow(
        forceSave: Boolean = false,
        isImageGeneration: Boolean = false
    ): Int? {
        // 直接调用内部保存逻辑（同步），确保本次保存完成后再读取索引
        saveCurrentChatToHistoryIfNeededInternal(forceSave = forceSave, isImageGeneration = isImageGeneration)
        return if (isImageGeneration) {
            stateHolder._loadedImageGenerationHistoryIndex.value
        } else {
            stateHolder._loadedHistoryIndex.value
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
                    // 先与头部会话比较（指纹 + 深比较）双重护栏，幂等保护更强
                    val headExists = mutableHistory.isNotEmpty()
                    val headFingerprint = if (headExists) conversationFingerprint(mutableHistory.first()) else null
                    val headDeepEqual = if (headExists) {
                        runBlocking {
                            compareMessageLists(
                                filterMessagesForSaving(mutableHistory.first()),
                                messagesToSave
                            )
                        }
                    } else false

                    if ((headFingerprint != null && headFingerprint == newConversationFingerprint) || headDeepEqual) {
                        Log.i(TAG_HM, "Skip insert: head equals new conversation (fingerprint/deep head guard)")
                        finalNewLoadedIndex = 0
                    } else if (lastInsertFingerprint == newConversationFingerprint && (nowMs - lastInsertAtMs) < 3000L) {
                        Log.i(TAG_HM, "Skip insert: same conversation within 3s window (force+debounce guard)")
                        // 保持 loadedIndex 不变（仍然为空表示新会话未入库）
                    } else {
                        // 插入前最后防线：基于现有查找API的精确匹配检索
                        val preInsertFound = runBlocking {
                            try {
                                findChatInHistory(messagesToSave, isImageGeneration)
                            } catch (e: Exception) {
                                Log.w(TAG_HM, "pre-insert findChatInHistory failed: ${e.message}")
                                -1
                            }
                        }
                        if (preInsertFound >= 0) {
                            Log.i(TAG_HM, "Pre-insert duplicate found by findChatInHistory at index=$preInsertFound. Reusing instead of inserting.")
                            finalNewLoadedIndex = preInsertFound
                        } else {
                            // 全量判重：先“无System指纹”快速判重，再回退到深比较
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
            
            // 文本模式下，同步保存会话配置映射
            if (!isImageGeneration) {
                persistenceManager.saveConversationApiConfigIds(stateHolder.conversationApiConfigIds.value)
            }

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
        
        // 迁移会话ID和配置绑定到稳定key
        val currentId = if (isImageGeneration) {
            stateHolder._currentImageGenerationConversationId.value
        } else {
            stateHolder._currentConversationId.value
        }
        
        val stableKeyFromMessages = if (isImageGeneration) {
            // 图像模式：优先使用首条消息ID
            messagesToSave.firstOrNull()?.id
        } else {
            // 文本模式：优先使用首条用户消息ID，其次系统消息ID
            messagesToSave.firstOrNull { it.sender == Sender.User }?.id
                ?: messagesToSave.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
                ?: messagesToSave.firstOrNull()?.id
        }
        
        if (stableKeyFromMessages != null) {
            val stableId = stableKeyFromMessages
            
            // 文本模式：迁移会话生成参数（如果存在）
            if (!isImageGeneration) {
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
                    Log.d(TAG_HM, "Migrated generation parameters from '$currentId' to stable key '$stableId'")
                }
            }
            
            // 修复：统一迁移会话绑定的配置ID（文本模式和图像模式都适用）
            // 这确保即使用户只选择了模型但没有发送消息，配置ID映射也能被正确迁移
            val currentConfigIds = stateHolder.conversationApiConfigIds.value
            if (currentConfigIds.containsKey(currentId) && currentId != stableId) {
                val newConfigIds = currentConfigIds.toMutableMap()
                newConfigIds[stableId] = currentConfigIds[currentId]!!
                newConfigIds.remove(currentId)
                stateHolder.conversationApiConfigIds.value = newConfigIds
                persistenceManager.saveConversationApiConfigIds(newConfigIds)
                Log.d(TAG_HM, "Migrated config binding from '$currentId' to stable key '$stableId' (${if (isImageGeneration) "IMAGE" else "TEXT"} mode)")
            }

            // 迁移系统提示及其相关状态
            if (currentId != stableId) {
                val currentSysPrompt = stateHolder.systemPrompts[currentId]
                if (currentSysPrompt != null) {
                    stateHolder.systemPrompts[stableId] = currentSysPrompt
                    stateHolder.systemPrompts.remove(currentId)
                }
                
                val currentEngaged = stateHolder.systemPromptEngagedState[currentId]
                if (currentEngaged != null) {
                    stateHolder.systemPromptEngagedState[stableId] = currentEngaged
                    stateHolder.systemPromptEngagedState.remove(currentId)
                }
                
                val currentExpanded = stateHolder.systemPromptExpandedState[currentId]
                if (currentExpanded != null) {
                    stateHolder.systemPromptExpandedState[stableId] = currentExpanded
                    stateHolder.systemPromptExpandedState.remove(currentId)
                }
            }

            // 迁移会话滚动状态
            val currentScrollState = stateHolder.conversationScrollStates[currentId]
            if (currentScrollState != null && currentId != stableId) {
                stateHolder.conversationScrollStates[stableId] = currentScrollState
                stateHolder.conversationScrollStates.remove(currentId)
                Log.d(TAG_HM, "Migrated scroll state from '$currentId' to stable key '$stableId'")
            }

            // 切换当前会话ID到稳定key
            if (currentId != stableId) {
                if (isImageGeneration) {
                    stateHolder._currentImageGenerationConversationId.value = stableId
                } else {
                    stateHolder._currentConversationId.value = stableId
                }
                // 触发滚动到底部事件，确保ID切换后，新建的 LazyListState（重置为0）能接收到滚动指令
                // 这修复了“重新生成第一条消息或首条消息变化时，页面跳回顶部”的问题
                stateHolder._scrollToBottomEvent.tryEmit(Unit)
                
                Log.d(TAG_HM, "Switched ${if (isImageGeneration) "imageGenerationConversationId" else "conversationId"} from '$currentId' to stable key '$stableId' and triggered scroll to bottom")
            }
        } else {
            Log.d(TAG_HM, "Skip parameter/config migration: no messages to derive a stable key")
        }

        // 使用“本次保存后的最终索引”决策 last-open，避免首次入库与瞬时旧值导致的双源重复
        if (messagesToSave.isNotEmpty()) {
            if (finalNewLoadedIndex == null) {
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
            // 修复：删除历史项后，重建 systemPrompts 映射，并保证当前加载会话的会话ID稳定
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
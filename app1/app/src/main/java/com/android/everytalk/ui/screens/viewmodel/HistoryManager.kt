package com.android.everytalk.ui.screens.viewmodel
import com.android.everytalk.statecontroller.*

import android.util.Log
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.extractThinkTagContent
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun resolveHistoryExpectedStableConversationId(
    isImageGeneration: Boolean,
    loadedHistoryIndex: Int?,
    currentConversationId: String,
    stableIdFromMessages: String?,
    currentConversationIdInMessages: Boolean = false,
): String? {
    if (
        loadedHistoryIndex != null &&
        currentConversationId.isNotBlank() &&
        (isImageGeneration || currentConversationIdInMessages)
    ) {
        return currentConversationId
    }
    return stableIdFromMessages ?: currentConversationId.takeIf { it.isNotBlank() }
}

class HistoryManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val compareMessageLists: suspend (List<Message>?, List<Message>?) -> Boolean,
    private val onHistoryModified: () -> Unit,
    private val scope: CoroutineScope
) {
    private val TAG_HM = "HistoryManager"

    suspend fun persistMessageImageSource(source: String, messageId: String, index: Int): String? {
        return persistenceManager.persistMessageImageSource(source, messageId, index)
    }

    // -------- 新增：持久化防抖与串行化 --------
    private val historyCommandChannel = Channel<HistoryCommand>(Channel.BUFFERED)
    private var debouncedTextSaveJob: Job? = null
    private var debouncedImageSaveJob: Job? = null
    private val DEBOUNCE_SAVE_MS = 1800L

    // 去重稳态：最近一次插入的指纹与时间，用于吸收 forceSave + debounce 双触发
    private var lastInsertFingerprint: String? = null
    private var lastInsertAtMs: Long = 0L
    private var lastPersistedConversationApiConfigIds: Map<String, String>? = null

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
        val hasImages = if (msg.imageUrls.isNullOrEmpty()) 0 else msg.imageUrls.size
        val attachmentsSet = msg.attachments.mapNotNull {
            when (it) {
                is com.android.everytalk.models.SelectedMediaItem.ImageFromUri -> it.uri.toString()
                is com.android.everytalk.models.SelectedMediaItem.GenericFile -> it.uri.toString()
                is com.android.everytalk.models.SelectedMediaItem.Audio -> it.data
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

    private fun stableConversationId(messages: List<Message>): String? =
        ConversationNameHelper.resolveStableId(messages)

    private fun conversationContainsMessageId(messages: List<Message>, messageId: String?): Boolean {
        if (messageId.isNullOrBlank()) return false
        return messages.any { it.id == messageId }
    }

    private suspend fun persistConversationApiConfigIdsIfChanged() {
        val current = stateHolder.conversationApiConfigIds.value
        if (lastPersistedConversationApiConfigIds != current) {
            persistenceManager.saveConversationApiConfigIds(current)
            lastPersistedConversationApiConfigIds = current.toMap()
        }
    }

    private suspend fun pruneOrphanConversationStateMappings(excludedIds: Set<String> = emptySet()) {
        val canPruneAgainstAllHistory = stateHolder.isConversationStateCleanupReady()
        if (!canPruneAgainstAllHistory && excludedIds.isEmpty()) return
        val retainedIds = buildSet {
            stateHolder._historicalConversations.value.forEach { conversation ->
                stableConversationId(conversation)?.let { add(it) }
            }
            stateHolder._imageGenerationHistoricalConversations.value.forEach { conversation ->
                stableConversationId(conversation)?.let { add(it) }
            }
            stateHolder._currentConversationId.value.takeIf { it.isNotBlank() }?.let { add(it) }
            stateHolder._currentImageGenerationConversationId.value.takeIf { it.isNotBlank() }?.let { add(it) }
        } - excludedIds

        fun shouldRetain(key: String): Boolean = if (canPruneAgainstAllHistory) {
            key in retainedIds
        } else {
            key !in excludedIds
        }
        fun <T> Map<String, T>.retainKnownKeys(): Map<String, T> = filterKeys(::shouldRetain)

        val configIds = stateHolder.conversationApiConfigIds.value
        val prunedConfigIds = stateHolder.conversationApiConfigIds.updateAndGet { current ->
            current.retainKnownKeys()
        }
        if (prunedConfigIds.size != configIds.size) {
            Log.d(TAG_HM, "Pruned ${configIds.size - prunedConfigIds.size} orphan config bindings")
        }

        val toggleStates = stateHolder.conversationFunctionToggleStates.value
        val prunedToggleStates = stateHolder.conversationFunctionToggleStates.updateAndGet { current ->
            current.retainKnownKeys()
        }
        if (prunedToggleStates.size != toggleStates.size) {
            persistenceManager.saveConversationFunctionToggleStates(prunedToggleStates)
            Log.d(TAG_HM, "Pruned ${toggleStates.size - prunedToggleStates.size} orphan function toggle states")
        }

        val generationConfigs = stateHolder.conversationGenerationConfigs.value
        val prunedGenerationConfigs = stateHolder.conversationGenerationConfigs.updateAndGet { current ->
            current.retainKnownKeys()
        }
        if (prunedGenerationConfigs.size != generationConfigs.size) {
            persistenceManager.saveConversationParameters(prunedGenerationConfigs)
            Log.d(TAG_HM, "Pruned ${generationConfigs.size - prunedGenerationConfigs.size} orphan generation configs")
        }

        val systemPromptKeys = stateHolder.systemPrompts.keys.toList()
        systemPromptKeys.filterNot(::shouldRetain).forEach { stateHolder.systemPrompts.remove(it) }
        val systemPromptEngagedKeys = stateHolder.systemPromptEngagedState.keys.toList()
        systemPromptEngagedKeys.filterNot(::shouldRetain).forEach { stateHolder.systemPromptEngagedState.remove(it) }
        val systemPromptExpandedKeys = stateHolder.systemPromptExpandedState.keys.toList()
        systemPromptExpandedKeys.filterNot(::shouldRetain).forEach { stateHolder.systemPromptExpandedState.remove(it) }

        val scrollStateKeys = stateHolder.conversationScrollStates.keys.toList()
        val removedScrollStates = scrollStateKeys.filterNot(::shouldRetain)
        removedScrollStates.forEach { stateHolder.conversationScrollStates.remove(it) }
        if (removedScrollStates.isNotEmpty()) {
            persistenceManager.saveConversationScrollStates(stateHolder.conversationScrollStates.toMap())
            Log.d(TAG_HM, "Pruned ${removedScrollStates.size} orphan scroll states")
        }
    }

    init {
        scope.launch {
            try {
                for (command in historyCommandChannel) {
                    if (!isActive) break
                    try {
                        when (command) {
                            is HistoryCommand.Save -> performSave(command.request)
                            is HistoryCommand.Delete -> deleteConversationInternal(
                                command.indexToDelete,
                                command.isImageGeneration,
                            )
                            is HistoryCommand.PersistDirect -> persistHistoryListDirectlyInternal(
                                command.isImageGeneration,
                            )
                            is HistoryCommand.Clear -> clearAllHistoryInternal(command.isImageGeneration)
                        }
                        command.completion?.complete(Unit)
                    } catch (exception: CancellationException) {
                        command.completion?.completeExceptionally(exception)
                        throw exception
                    } catch (exception: Exception) {
                        command.completion?.completeExceptionally(exception)
                            ?: Log.e(TAG_HM, "Queued history command failed", exception)
                    }
                }
            } finally {
                val exception = CancellationException("History command processor stopped")
                historyCommandChannel.close(exception)
                while (true) {
                    val pending = historyCommandChannel.tryReceive().getOrNull() ?: break
                    pending.completion?.completeExceptionally(exception)
                }
            }
        }
    }

    private sealed interface HistoryCommand {
        val completion: CompletableDeferred<Unit>?

        data class Save(
            val request: SaveRequest,
            override val completion: CompletableDeferred<Unit>? = null,
        ) : HistoryCommand

        data class Delete(
            val indexToDelete: Int,
            val isImageGeneration: Boolean,
            override val completion: CompletableDeferred<Unit>,
        ) : HistoryCommand

        data class PersistDirect(
            val isImageGeneration: Boolean,
            override val completion: CompletableDeferred<Unit>,
        ) : HistoryCommand

        data class Clear(
            val isImageGeneration: Boolean,
            override val completion: CompletableDeferred<Unit>,
        ) : HistoryCommand
    }

    private data class SaveRequest(
        val force: Boolean,
        val isImageGen: Boolean,
        val messagesSnapshot: List<Message>,
        val conversationId: String,
        val loadedHistoryIndex: Int?,
        val isDirty: Boolean,
    )

    private fun buildSaveRequest(force: Boolean, isImageGeneration: Boolean): SaveRequest {
        return SaveRequest(
            force = force,
            isImageGen = isImageGeneration,
            messagesSnapshot = if (isImageGeneration) {
                stateHolder.imageGenerationMessages.toList()
            } else {
                stateHolder.messages.toList()
            },
            conversationId = if (isImageGeneration) {
                stateHolder._currentImageGenerationConversationId.value
            } else {
                stateHolder._currentConversationId.value
            },
            loadedHistoryIndex = if (isImageGeneration) {
                stateHolder._loadedImageGenerationHistoryIndex.value
            } else {
                stateHolder._loadedHistoryIndex.value
            },
            isDirty = if (isImageGeneration) {
                stateHolder.isImageConversationDirty.value
            } else {
                stateHolder.isTextConversationDirty.value
            },
        )
    }

    private suspend fun performSave(req: SaveRequest) {
        saveCurrentChatToHistoryIfNeededInternal(req.force, req.isImageGen, req)
    }

    private fun cancelDebouncedSave(isImageGeneration: Boolean) {
        if (isImageGeneration) {
            debouncedImageSaveJob?.cancel()
            debouncedImageSaveJob = null
        } else {
            debouncedTextSaveJob?.cancel()
            debouncedTextSaveJob = null
        }
    }

    private suspend fun enqueueAndAwait(command: HistoryCommand) {
        historyCommandChannel.send(command)
        command.completion?.await()
    }

    private fun filterMessagesForSaving(messagesToFilter: List<Message>): List<Message> {
        fun isLegacySystemPromptMessage(msg: Message): Boolean {
            return msg.sender == Sender.System && !msg.isPlaceholderName && msg.text.isNotBlank()
        }
        fun stripLeadingLegacySystemPromptMessages(messages: List<Message>): List<Message> {
            return messages.dropWhile { isLegacySystemPromptMessage(it) }
        }
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
        return stripLeadingLegacySystemPromptMessages(messagesToFilter)
            .filter { msg ->
                if (msg.isError) return@filter false
                when (msg.sender) {
                    Sender.User -> true
                    Sender.System -> true // 保留所有 System 消息（包括用户自定义标题）
                    Sender.AI -> hasAiSubstance(msg)
                    else -> true
                }
            }
            .map { msg ->
                val extraction = if (msg.sender == Sender.AI) extractThinkTagContent(msg.text) else null
                if (extraction != null && extraction.changed) {
                    val mergedReasoning = listOfNotNull(msg.reasoning, extraction.reasoning)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                        .ifBlank { null }
                    msg.copy(
                        text = extraction.content.trim(),
                        reasoning = mergedReasoning?.trim(),
                        parts = emptyList(),
                    )
                } else {
                    msg.copy(text = msg.text.trim(), reasoning = msg.reasoning?.trim())
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

    suspend fun saveCurrentChatToHistoryIfNeeded(forceSave: Boolean = false, isImageGeneration: Boolean = false) {
        if (forceSave) {
            cancelDebouncedSave(isImageGeneration)
            historyCommandChannel.send(
                HistoryCommand.Save(buildSaveRequest(force = true, isImageGeneration = isImageGeneration))
            )
        } else {
            cancelDebouncedSave(isImageGeneration)
            val job = scope.launch {
                delay(DEBOUNCE_SAVE_MS)
                historyCommandChannel.send(
                    HistoryCommand.Save(buildSaveRequest(force = false, isImageGeneration = isImageGeneration))
                )
            }
            if (isImageGeneration) {
                debouncedImageSaveJob = job
            } else {
                debouncedTextSaveJob = job
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
        cancelDebouncedSave(isImageGeneration)
        val completion = CompletableDeferred<Unit>()
        enqueueAndAwait(
            HistoryCommand.Save(
                request = buildSaveRequest(forceSave, isImageGeneration),
                completion = completion,
            )
        )
        return if (isImageGeneration) {
            stateHolder._loadedImageGenerationHistoryIndex.value
        } else {
            stateHolder._loadedHistoryIndex.value
        }
    }

    private suspend fun saveCurrentChatToHistoryIfNeededInternal(
        forceSave: Boolean = false,
        isImageGeneration: Boolean = false,
        request: SaveRequest? = null,
    ): Boolean {
        val currentMessagesSnapshot = request?.messagesSnapshot
            ?: if (isImageGeneration) stateHolder.imageGenerationMessages.toList() else stateHolder.messages.toList()
        
        val currentConversationId = request?.conversationId
            ?: if (isImageGeneration) {
                stateHolder._currentImageGenerationConversationId.value
            } else {
                stateHolder._currentConversationId.value
            }
        fun isStillSavingLiveConversation(): Boolean {
            if (request == null) return true
            val latestLiveConversationId = if (isImageGeneration) {
                stateHolder._currentImageGenerationConversationId.value
            } else {
                stateHolder._currentConversationId.value
            }
            val stableIdFromSnapshot = stableConversationId(filterMessagesForSaving(currentMessagesSnapshot))
            return currentConversationId == latestLiveConversationId || stableIdFromSnapshot == latestLiveConversationId
        }

        val messagesToSave = filterMessagesForSaving(currentMessagesSnapshot)
        var historyListModified = false
        var loadedIndexChanged = false

        val loadedHistoryIndex = if (request != null) {
            request.loadedHistoryIndex
        } else if (isImageGeneration) {
            stateHolder._loadedImageGenerationHistoryIndex.value
        } else {
            stateHolder._loadedHistoryIndex.value
        }
        
        val currentModeHasMessages = if (isImageGeneration) {
            stateHolder.imageGenerationMessages.isNotEmpty()
        } else {
            stateHolder.messages.isNotEmpty()
        }
        
        val isDirty = request?.isDirty
            ?: if (isImageGeneration) stateHolder.isImageConversationDirty.value else stateHolder.isTextConversationDirty.value
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
        var conversationToPersist: List<Message>? = null
        var addedNewConversation = false
        val newConversationFingerprint = conversationFingerprint(messagesToSave)
        val nowMs = System.currentTimeMillis()

        val historicalConversations = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations else stateHolder._historicalConversations
        val currentHistory = historicalConversations.value
        val mutableHistory = currentHistory.toMutableList()
        val requestedLoadedIdx = loadedHistoryIndex
        val stableIdFromMessages = stableConversationId(messagesToSave)
        val expectedStableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = isImageGeneration,
            loadedHistoryIndex = requestedLoadedIdx,
            currentConversationId = currentConversationId,
            stableIdFromMessages = stableIdFromMessages,
            currentConversationIdInMessages = messagesToSave.any { it.id == currentConversationId },
        )
        val requestedHistoryFingerprint = requestedLoadedIdx
            ?.takeIf { it >= 0 && it < mutableHistory.size }
            ?.let { conversationFingerprint(mutableHistory[it]) }
        val requestedLooksLikeDraftOfCurrent = !requestedHistoryFingerprint.isNullOrEmpty() &&
            (newConversationFingerprint == requestedHistoryFingerprint ||
                newConversationFingerprint.startsWith("$requestedHistoryFingerprint||"))
        val currentLoadedIdx = if (
            requestedLoadedIdx != null &&
            requestedLoadedIdx >= 0 &&
            requestedLoadedIdx < mutableHistory.size &&
            (
                expectedStableId == null ||
                    stableConversationId(mutableHistory[requestedLoadedIdx]) == expectedStableId ||
                    conversationContainsMessageId(mutableHistory[requestedLoadedIdx], expectedStableId) ||
                    requestedLooksLikeDraftOfCurrent
            )
        ) {
            requestedLoadedIdx
        } else {
            val resolvedIndex = expectedStableId?.let { stableId ->
                mutableHistory.indexOfFirst { stableConversationId(it) == stableId }
            }?.takeIf { it >= 0 }
            if (requestedLoadedIdx != null && resolvedIndex != null && resolvedIndex != requestedLoadedIdx) {
                Log.w(
                    TAG_HM,
                    "Loaded index drift detected. requested=$requestedLoadedIdx, resolved=$resolvedIndex, stableId=$expectedStableId"
                )
            }
            resolvedIndex
        }

        if (currentLoadedIdx != null && currentLoadedIdx in mutableHistory.indices) {
            val existingMessages = filterMessagesForSaving(mutableHistory[currentLoadedIdx])
            val contentChanged = !compareMessageLists(existingMessages, messagesToSave)
            if (messagesToSave.isNotEmpty() && (isDirty || forceSave && contentChanged)) {
                if (contentChanged) {
                    mutableHistory[currentLoadedIdx] = messagesToSave
                    if (currentLoadedIdx > 0) {
                        mutableHistory.add(0, mutableHistory.removeAt(currentLoadedIdx))
                        finalNewLoadedIndex = 0
                    } else {
                        finalNewLoadedIndex = currentLoadedIdx
                    }
                    historyListModified = true
                    Log.d(TAG_HM, "Updated existing history at index=$currentLoadedIdx, fp=${newConversationFingerprint.take(64)}")
                }
                conversationToPersist = messagesToSave
            } else if (!contentChanged) {
                Log.d(TAG_HM, "History index $currentLoadedIdx content unchanged; preserving its original position.")
            } else {
                Log.d(TAG_HM, "History index $currentLoadedIdx changed but is not marked dirty or contains no savable messages.")
            }
        } else if (messagesToSave.isNotEmpty()) {
            val headDeepEqual = mutableHistory.firstOrNull()?.let { head ->
                compareMessageLists(filterMessagesForSaving(head), messagesToSave)
            } ?: false
            val headFingerprint = mutableHistory.firstOrNull()?.let(::conversationFingerprint)

            if (headFingerprint == newConversationFingerprint || headDeepEqual) {
                Log.i(TAG_HM, "Skip insert: head equals new conversation (fingerprint/deep head guard)")
                finalNewLoadedIndex = 0
            } else if (lastInsertFingerprint == newConversationFingerprint && (nowMs - lastInsertAtMs) < 3000L) {
                Log.i(TAG_HM, "Skip insert: same conversation within 3s window (force+debounce guard)")
            } else {
                var duplicateIndex = stableIdFromMessages?.let { stableId ->
                    mutableHistory.indexOfFirst { historyChat -> stableConversationId(historyChat) == stableId }
                } ?: -1
                if (duplicateIndex == -1) {
                    duplicateIndex = mutableHistory.indexOfFirst { historyChat ->
                        conversationFingerprint(historyChat) == newConversationFingerprint
                    }
                }
                if (duplicateIndex == -1) {
                    for ((index, historyChat) in mutableHistory.withIndex()) {
                        if (compareMessageLists(filterMessagesForSaving(historyChat), messagesToSave)) {
                            duplicateIndex = index
                            break
                        }
                    }
                }
                if (duplicateIndex == -1) {
                    mutableHistory.add(0, messagesToSave)
                    finalNewLoadedIndex = 0
                    historyListModified = true
                    conversationToPersist = messagesToSave
                    addedNewConversation = true
                } else {
                    mutableHistory[duplicateIndex] = messagesToSave
                    if (duplicateIndex > 0) {
                        mutableHistory.add(0, mutableHistory.removeAt(duplicateIndex))
                        finalNewLoadedIndex = 0
                    } else {
                        finalNewLoadedIndex = duplicateIndex
                    }
                    historyListModified = true
                    conversationToPersist = messagesToSave
                }
            }
        } else {
            Log.d(TAG_HM, "Current new conversation is empty, not adding to history.")
        }

        val seenStableIds = mutableSetOf<String>()
        val seenFingerprints = mutableSetOf<String>()
        val deduped = mutableListOf<List<Message>>()
        var removed = 0
        for ((index, conversation) in mutableHistory.withIndex()) {
            val stableId = stableConversationId(conversation)
            val fingerprint = conversationFingerprint(conversation)
            val duplicateByStableId = stableId != null && !seenStableIds.add(stableId)
            val duplicateByFingerprint = fingerprint.isNotEmpty() && !seenFingerprints.add(fingerprint)
            if (!duplicateByStableId && !duplicateByFingerprint) {
                deduped += conversation
            } else {
                if (finalNewLoadedIndex == index) {
                    val keptIndex = deduped.indexOfFirst { kept ->
                        (stableId != null && stableConversationId(kept) == stableId) ||
                            (fingerprint.isNotEmpty() && conversationFingerprint(kept) == fingerprint)
                    }
                    finalNewLoadedIndex = keptIndex.takeIf { it >= 0 }
                    if (keptIndex >= 0) deduped[keptIndex] = conversation
                } else if (finalNewLoadedIndex != null && index < finalNewLoadedIndex) {
                    finalNewLoadedIndex -= 1
                }
                removed++
            }
        }
        if (removed > 0) {
            Log.w(TAG_HM, "Global dedup removed $removed duplicate conversations (stableId/fingerprint-based)")
            historyListModified = true
        }
        if (deduped != currentHistory) historicalConversations.value = deduped
        val removedSessionIds = currentHistory.mapNotNull(::stableConversationId).toSet() -
            deduped.mapNotNull(::stableConversationId).toSet()
 
        if (isStillSavingLiveConversation() && loadedHistoryIndex != finalNewLoadedIndex) {
            if (isImageGeneration) {
                stateHolder._loadedImageGenerationHistoryIndex.value = finalNewLoadedIndex
            } else {
                stateHolder._loadedHistoryIndex.value = finalNewLoadedIndex
            }
            loadedIndexChanged = true
            Log.d(TAG_HM, "LoadedHistoryIndex updated to: $finalNewLoadedIndex")
        }
 
        conversationToPersist?.let { conversation ->
            val sessionId = stableConversationId(conversation)
            if (sessionId != null) {
                persistenceManager.saveHistorySession(sessionId, conversation, isImageGeneration)
            }
        }
        removedSessionIds.forEach { sessionId ->
            persistenceManager.deleteHistorySession(sessionId)
        }
        if (conversationToPersist != null) {
            if (isStillSavingLiveConversation()) {
                if (isImageGeneration) {
                    stateHolder.isImageConversationDirty.value = false
                } else {
                    stateHolder.isTextConversationDirty.value = false
                }
            }
            Log.d(TAG_HM, "Dirty history session persisted and dirty flag reset.")
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
        val migrationSourceId = request?.conversationId ?: currentId
        
        val stableKeyFromMessages = if (isImageGeneration || loadedHistoryIndex != null) {
            resolveHistoryExpectedStableConversationId(
                isImageGeneration = isImageGeneration,
                loadedHistoryIndex = loadedHistoryIndex,
                currentConversationId = migrationSourceId,
                stableIdFromMessages = stableConversationId(messagesToSave),
                currentConversationIdInMessages = messagesToSave.any { it.id == migrationSourceId },
            )
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
                val newMap = stateHolder.conversationGenerationConfigs.updateAndGet { current ->
                    val currentConfigForSession = current[migrationSourceId]
                    if (currentConfigForSession == null) {
                        current
                    } else {
                        current.toMutableMap().apply {
                            this[stableId] = currentConfigForSession
                            if (migrationSourceId != stableId) remove(migrationSourceId)
                        }
                    }
                }
                if (newMap != currentConfigs) {
                    persistenceManager.saveConversationParameters(newMap)
                    Log.d(TAG_HM, "Migrated generation parameters from '$migrationSourceId' to stable key '$stableId'")
                }
            }
            
            // 修复：统一迁移会话绑定的配置ID（文本模式和图像模式都适用）
            // 这确保即使用户只选择了模型但没有发送消息，配置ID映射也能被正确迁移
            val currentConfigIds = stateHolder.conversationApiConfigIds.value
            if (currentConfigIds.containsKey(migrationSourceId) && migrationSourceId != stableId) {
                val newConfigIds = stateHolder.conversationApiConfigIds.updateAndGet { current ->
                    val configId = current[migrationSourceId]
                    if (configId == null) {
                        current
                    } else {
                        current.toMutableMap().apply {
                            this[stableId] = configId
                            remove(migrationSourceId)
                        }
                    }
                }
                if (newConfigIds != currentConfigIds) {
                    Log.d(TAG_HM, "Migrated config binding from '$migrationSourceId' to stable key '$stableId' (${if (isImageGeneration) "IMAGE" else "TEXT"} mode)")
                }
            }

            val currentToggleStates = stateHolder.conversationFunctionToggleStates.value
            if (currentToggleStates.containsKey(migrationSourceId) && migrationSourceId != stableId) {
                val newToggleStates = stateHolder.conversationFunctionToggleStates.updateAndGet { current ->
                    val toggleState = current[migrationSourceId]
                    if (toggleState == null) {
                        current
                    } else {
                        current.toMutableMap().apply {
                            this[stableId] = toggleState
                            remove(migrationSourceId)
                        }
                    }
                }
                if (newToggleStates != currentToggleStates) {
                    persistenceManager.saveConversationFunctionToggleStates(newToggleStates)
                    Log.d(TAG_HM, "Migrated function toggles from '$migrationSourceId' to stable key '$stableId'")
                }
            }

            // 迁移系统提示及其相关状态
            if (migrationSourceId != stableId) {
                val currentSysPrompt = stateHolder.systemPrompts[migrationSourceId]
                if (currentSysPrompt != null) {
                    stateHolder.systemPrompts[stableId] = currentSysPrompt
                    stateHolder.systemPrompts.remove(migrationSourceId)
                }
                
                val currentEngaged = stateHolder.systemPromptEngagedState[migrationSourceId]
                if (currentEngaged != null) {
                    stateHolder.systemPromptEngagedState[stableId] = currentEngaged
                    stateHolder.systemPromptEngagedState.remove(migrationSourceId)
                }
                
                val currentExpanded = stateHolder.systemPromptExpandedState[migrationSourceId]
                if (currentExpanded != null) {
                    stateHolder.systemPromptExpandedState[stableId] = currentExpanded
                    stateHolder.systemPromptExpandedState.remove(migrationSourceId)
                }
            }

            // 迁移会话滚动状态
            val currentScrollState = stateHolder.conversationScrollStates[migrationSourceId]
            if (currentScrollState != null && migrationSourceId != stableId) {
                stateHolder.conversationScrollStates[stableId] = currentScrollState
                stateHolder.conversationScrollStates.remove(migrationSourceId)
                Log.d(TAG_HM, "Migrated scroll state from '$migrationSourceId' to stable key '$stableId'")
            }

            // 切换当前会话ID到稳定key
            if (isStillSavingLiveConversation() && currentId != stableId) {
                if (isImageGeneration) {
                    stateHolder._currentImageGenerationConversationId.value = stableId
                } else {
                    stateHolder.setCurrentConversationId(stableId)
                }
                // 不再触发滚动到底部事件，而是依赖 scrollState 的迁移和 ChatScreen 的恢复逻辑。
                // 强制触发 jumpToBottom 会导致“重新回答”时的平滑滚动被覆盖为瞬间跳转。
                // stateHolder._scrollToBottomEvent.tryEmit(Unit)
                
                Log.d(TAG_HM, "Switched ${if (isImageGeneration) "imageGenerationConversationId" else "conversationId"} from '$currentId' to stable key '$stableId'")
            }
        } else {
            Log.d(TAG_HM, "Skip parameter/config migration: no messages to derive a stable key")
        }

        pruneOrphanConversationStateMappings()
        persistConversationApiConfigIdsIfChanged()

        // 使用“本次保存后的最终索引”决策 last-open，避免首次入库与瞬时旧值导致的双源重复
        if (isStillSavingLiveConversation()) {
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
        } else {
            Log.d(TAG_HM, "Skipping live-only persistence side effects for stale save request.")
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
        cancelDebouncedSave(isImageGeneration)
        enqueueAndAwait(
            HistoryCommand.Delete(
                indexToDelete = indexToDelete,
                isImageGeneration = isImageGeneration,
                completion = CompletableDeferred(),
            )
        )
    }

    private suspend fun deleteConversationInternal(indexToDelete: Int, isImageGeneration: Boolean) {
        Log.d(TAG_HM, "Requesting to delete history index $indexToDelete.")
        val historicalConversations = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations else stateHolder._historicalConversations
        val loadedHistoryIndex = if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex else stateHolder._loadedHistoryIndex
        val currentHistory = historicalConversations.value
        if (indexToDelete !in currentHistory.indices) {
            Log.w(TAG_HM, "Invalid delete request: Index $indexToDelete out of bounds (size ${currentHistory.size}).")
            return
        }

        var finalLoadedIndexAfterDelete: Int? = loadedHistoryIndex.value
        val conversationToDelete = currentHistory[indexToDelete]
        val updatedHistory = currentHistory.toMutableList().apply { removeAt(indexToDelete) }
        val currentLoadedIdx = loadedHistoryIndex.value
        if (currentLoadedIdx == indexToDelete) {
            finalLoadedIndexAfterDelete = null
        } else if (currentLoadedIdx != null && currentLoadedIdx > indexToDelete) {
            finalLoadedIndexAfterDelete = currentLoadedIdx - 1
        }

        stableConversationId(conversationToDelete)?.let { sessionId ->
            persistenceManager.deleteHistorySession(sessionId)
        }
        historicalConversations.value = updatedHistory
        if (loadedHistoryIndex.value != finalLoadedIndexAfterDelete) {
            loadedHistoryIndex.value = finalLoadedIndexAfterDelete
        }
        Log.d(TAG_HM, "Removed conversation at index $indexToDelete from memory and persistence.")

        runCatching {
            val currentHistoryFinal = historicalConversations.value

            // 修复：删除历史项后，重建 systemPrompts 映射，并保证当前加载会话的会话ID稳定
            stateHolder.systemPrompts.clear()
            currentHistoryFinal.forEach { conversation ->
                val stableIdForConv = stableConversationId(conversation)
                val promptForConv =
                    conversation.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.text ?: ""
                if (stableIdForConv != null) {
                    stateHolder.systemPrompts[stableIdForConv] = promptForConv
                }
            }

            if (finalLoadedIndexAfterDelete != null && finalLoadedIndexAfterDelete in currentHistoryFinal.indices) {
                stableConversationId(currentHistoryFinal[finalLoadedIndexAfterDelete])?.let { stableIdLoaded ->
                    if (isImageGeneration) {
                        stateHolder._currentImageGenerationConversationId.value = stableIdLoaded
                    } else {
                        stateHolder.setCurrentConversationId(stableIdLoaded)
                    }
                }
            }
        }.onFailure { exception ->
            Log.w(TAG_HM, "Failed to rebuild prompts or adjust conversationId after deletion", exception)
        }
        if (finalLoadedIndexAfterDelete == null) {
            persistenceManager.clearLastOpenChat(isImageGeneration)
        }
        stableConversationId(conversationToDelete)?.let { deletedId ->
            pruneOrphanConversationStateMappings(setOf(deletedId))
            persistConversationApiConfigIdsIfChanged()
        }
        persistenceManager.cleanupOrphanedAttachments()
        onHistoryModified()
    }

    /**
     * 直接持久化当前历史列表（不经过 filterMessagesForSaving）。
     * 用于重命名等场景，确保包含标题消息的完整会话被保存。
     */
    suspend fun persistHistoryListDirectly(isImageGeneration: Boolean = false) {
        cancelDebouncedSave(isImageGeneration)
        enqueueAndAwait(
            HistoryCommand.PersistDirect(
                isImageGeneration = isImageGeneration,
                completion = CompletableDeferred(),
            )
        )
    }

    private suspend fun persistHistoryListDirectlyInternal(isImageGeneration: Boolean) {
        val historicalConversations = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        withContext(Dispatchers.IO) {
            persistenceManager.saveChatHistory(historicalConversations, isImageGeneration)
        }
        Log.d(TAG_HM, "History list persisted directly (${if (isImageGeneration) "IMAGE" else "TEXT"} mode)")
    }

    suspend fun clearAllHistory(isImageGeneration: Boolean = false) {
        cancelDebouncedSave(isImageGeneration)
        enqueueAndAwait(
            HistoryCommand.Clear(
                isImageGeneration = isImageGeneration,
                completion = CompletableDeferred(),
            )
        )
    }

    private suspend fun clearAllHistoryInternal(isImageGeneration: Boolean) {
        Log.d(TAG_HM, "Requesting to clear all history.")
        val historyToClear = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations.value else stateHolder._historicalConversations.value
        val clearedSessionIds = historyToClear.mapNotNull(::stableConversationId).toSet()
        val loadedHistoryIndex = if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex else stateHolder._loadedHistoryIndex
        if (historyToClear.isNotEmpty() || loadedHistoryIndex.value != null) {
            persistenceManager.clearHistoryExplicitly(isImageGeneration)
            if (isImageGeneration) {
                stateHolder._imageGenerationHistoricalConversations.value = emptyList()
                loadedHistoryIndex.value = null
            } else {
                stateHolder._historicalConversations.value = emptyList()
                loadedHistoryIndex.value = null
            }
            Log.d(TAG_HM, "In-memory history cleared, loadedHistoryIndex reset to null.")
            persistenceManager.cleanupOrphanedAttachments(vacuumDatabase = true)
            Log.d(TAG_HM, "Persisted history list cleared. \"Last open chat\" cleared.")
        } else {
            persistenceManager.clearHistoryExplicitly(isImageGeneration)
            Log.d(TAG_HM, "没有可见历史，已按用户操作清除受保护的持久化历史。")
        }
        if (clearedSessionIds.isNotEmpty()) {
            pruneOrphanConversationStateMappings(clearedSessionIds)
            persistConversationApiConfigIdsIfChanged()
        }
    }
}

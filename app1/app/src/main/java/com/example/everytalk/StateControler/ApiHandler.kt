package com.example.everytalk.StateControler

import android.content.Context
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent
import com.example.everytalk.data.DataClass.ApiContentPart
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.model.SelectedMediaItem
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.CancellationException

class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit
) {
    private val jsonParserForError = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BackendErrorContent(val message: String? = null, val code: Int? = null)

    private var currentTextBuilder = StringBuilder()
    private var currentReasoningBuilder = StringBuilder()

    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"


    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX $reason" else "$USER_CANCEL_PREFIX $reason"

        if (jobToCancel?.isActive == true && messageIdBeingCancelled != null) {
            val partialText = currentTextBuilder.toString().trim()
            val partialReasoning =
                currentReasoningBuilder.toString().trim().let { if (it.isNotBlank()) it else null }

            if (partialText.isNotBlank() || partialReasoning != null) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val currentMessage = stateHolder.messages[index]
                        val updatedMessage = currentMessage.copy(
                            text = partialText,
                            reasoning = partialReasoning,
                            contentStarted = currentMessage.contentStarted || partialText.isNotBlank(),
                            isError = false
                        )
                        stateHolder.messages[index] = updatedMessage

                        if (partialText.isNotBlank()) {
                            onAiMessageFullTextChanged(messageIdBeingCancelled, partialText)
                        }
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                    }
                }
            }
        }

        stateHolder._isApiCalling.value = false
        if (!isNewMessageSend && stateHolder._currentStreamingAiMessageId.value == messageIdBeingCancelled) {
            stateHolder._currentStreamingAiMessageId.value = null
        }
        currentTextBuilder.clear()
        currentReasoningBuilder.clear()

        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        val isPlaceholder = msg.sender == Sender.AI && msg.text.isBlank() &&
                                msg.reasoning.isNullOrBlank() && msg.webSearchResults.isNullOrEmpty() &&
                                msg.currentWebSearchStage.isNullOrEmpty() && !msg.contentStarted && !msg.isError
                        if (isPlaceholder) {
                            stateHolder.messages.removeAt(index)
                        }
                    }
                }
            }
        }
        jobToCancel?.takeIf { it.isActive }
            ?.cancel(CancellationException(specificCancelReason))
        stateHolder.apiJob = null
    }

    fun streamChatResponse(
        requestBody: ChatRequest,
        attachmentsToPassToApiClient: List<SelectedMediaItem>,
        applicationContextForApiClient: Context,
        @Suppress("UNUSED_PARAMETER") userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit,
        onRequestFailed: () -> Unit
    ) {
        val contextForLog = when (val lastUserMsg = requestBody.messages.lastOrNull {
            it.role == "user"
        }) {
            is com.example.everytalk.data.DataClass.SimpleTextApiMessage -> lastUserMsg.content
            is com.example.everytalk.data.DataClass.PartsApiMessage -> lastUserMsg.parts
                .filterIsInstance<ApiContentPart.Text>().joinToString(" ") { it.text }

            else -> null
        }?.take(30) ?: "N/A"

        cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true)

        val newAiMessage = Message(text = "", sender = Sender.AI)
        val aiMessageId = newAiMessage.id

        currentTextBuilder.clear()
        currentReasoningBuilder.clear()

        viewModelScope.launch(Dispatchers.Main.immediate) {
            var insertAtIndex = stateHolder.messages.size
            if (afterUserMessageId != null) {
                val userMessageIndex =
                    stateHolder.messages.indexOfFirst { it.id == afterUserMessageId }
                if (userMessageIndex != -1) insertAtIndex = userMessageIndex + 1
            }
            insertAtIndex = insertAtIndex.coerceAtMost(stateHolder.messages.size)
            stateHolder.messages.add(insertAtIndex, newAiMessage)
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap[aiMessageId] = false
            onMessagesProcessed()
        }

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key]
            try {
                ApiClient.streamChatResponse(
                    requestBody,
                    attachmentsToPassToApiClient,
                    applicationContextForApiClient
                )
                    .onStart { }
                    .catch { e ->
                        if (e !is CancellationException) {
                            updateMessageWithError(aiMessageId, e)
                            onRequestFailed()
                        }
                    }
                    .onCompletion { cause ->
                        val targetMsgId = aiMessageId
                        val isThisJobStillTheCurrentOne = stateHolder.apiJob == thisJob

                        if (isThisJobStillTheCurrentOne) {
                            stateHolder._isApiCalling.value = false
                            if (stateHolder._currentStreamingAiMessageId.value == targetMsgId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }
                        if (stateHolder.reasoningCompleteMap[targetMsgId] != true) {
                            stateHolder.reasoningCompleteMap[targetMsgId] = true
                        }

                        val cancellationMessageFromCause =
                            (cause as? CancellationException)?.message
                        val wasCancelledByApiHandler =
                            cancellationMessageFromCause?.startsWith(USER_CANCEL_PREFIX) == true ||
                                    cancellationMessageFromCause?.startsWith(
                                        NEW_STREAM_CANCEL_PREFIX
                                    ) == true

                        if (!wasCancelledByApiHandler) {
                            val finalFullText = currentTextBuilder.toString().trim()
                            if (finalFullText.isNotBlank()) {
                                onAiMessageFullTextChanged(targetMsgId, finalFullText)
                            }
                            if (cause == null || (cause !is CancellationException)) {
                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = cause != null)
                            }
                        }

                        currentTextBuilder.clear()
                        currentReasoningBuilder.clear()

                        viewModelScope.launch {
                            val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                            if (finalIdx != -1) {
                                val msg = stateHolder.messages[finalIdx]
                                if (cause == null && !msg.isError) {
                                    withContext(Dispatchers.Main.immediate) {
                                        val updatedMsg = msg.copy(
                                            text = msg.text.trim(),
                                            reasoning = msg.reasoning?.trim()
                                                .let { if (it.isNullOrBlank()) null else it },
                                            contentStarted = msg.contentStarted || msg.text.isNotBlank()
                                        )
                                        if (updatedMsg != msg) {
                                            stateHolder.messages[finalIdx] = updatedMsg
                                        }
                                        if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                            stateHolder.messageAnimationStates[targetMsgId] = true
                                        }
                                    }
                                } else if (cause is CancellationException) {
                                    if (!wasCancelledByApiHandler) {
                                        val hasMeaningfulContent = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()
                                        if (hasMeaningfulContent) {
                                            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                                        } else if (msg.sender == Sender.AI && !msg.isError) {
                                            stateHolder.messages.removeAt(finalIdx)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .collect { appEvent ->
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                            return@collect
                        }
                        val currentChunkIndex =
                            stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                        if (currentChunkIndex != -1) {
                            withContext(Dispatchers.Default) {
                                processChunk(currentChunkIndex, appEvent, aiMessageId)
                            }
                        } else {
                            thisJob?.cancel(CancellationException("目标消息 $aiMessageId 在收集中途消失"))
                            return@collect
                        }
                    }
            } catch (e: Exception) {
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
                when (e) {
                    is CancellationException -> {}
                    else -> {
                        updateMessageWithError(aiMessageId, e)
                        onRequestFailed()
                    }
                }
            } finally {
                if (stateHolder.apiJob == thisJob) {
                    stateHolder.apiJob = null
                    if (stateHolder._isApiCalling.value && stateHolder._currentStreamingAiMessageId.value == aiMessageId) {
                        stateHolder._isApiCalling.value = false
                        stateHolder._currentStreamingAiMessageId.value = null
                    }
                }
            }
        }
    }

    private fun processChunk(index: Int, appEvent: AppStreamEvent, messageIdForLog: String) {
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            return
        }

        val originalMessage = stateHolder.messages[index]
        var updatedMessage = originalMessage

        when (appEvent.type) {
            "status_update" -> {
                updatedMessage = originalMessage.copy(currentWebSearchStage = appEvent.stage)
            }
            "web_search_results" -> {
                appEvent.results?.let {
                    updatedMessage = originalMessage.copy(webSearchResults = it)
                }
            }
            "reasoning" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    currentReasoningBuilder.append(appEvent.text)
                    if (stateHolder.reasoningCompleteMap[messageIdForLog] != false) {
                        stateHolder.reasoningCompleteMap[messageIdForLog] = false
                    }
                }
            }
            "reasoning_finish" -> {
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                }
            }
            "content" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    currentTextBuilder.append(appEvent.text)
                    if (!originalMessage.contentStarted) {
                        updatedMessage = originalMessage.copy(contentStarted = true)
                        if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                            stateHolder.reasoningCompleteMap[messageIdForLog] = true
                        }
                    }
                }
            }
            "tool_calls_chunk", "google_function_call_request", "finish" -> {
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                }
            }
            "error" -> {
                viewModelScope.launch {
                    updateMessageWithError(
                        messageIdForLog,
                        IOException("SSE Error: ${appEvent.message} (Upstream: ${appEvent.upstreamStatus ?: "N/A"})")
                    )
                }
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                }
            }
        }

        val accumulatedFullText = currentTextBuilder.toString()
        val accumulatedFullReasoning = currentReasoningBuilder.toString().let { if (it.isNotBlank()) it else null }

        if (accumulatedFullText != updatedMessage.text || accumulatedFullReasoning != updatedMessage.reasoning) {
            updatedMessage = updatedMessage.copy(
                text = accumulatedFullText,
                reasoning = accumulatedFullReasoning
            )
        }

        if (updatedMessage != originalMessage) {
            stateHolder.messages[index] = updatedMessage
        }

        if (appEvent.type == "content" && !appEvent.text.isNullOrEmpty()) {
            onAiMessageFullTextChanged(messageIdForLog, accumulatedFullText)
        }
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        currentTextBuilder.clear()
        currentReasoningBuilder.clear()
        withContext(Dispatchers.Main.immediate) {
            val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = stateHolder.messages[idx]
                if (!msg.isError) {
                    val existingContent = (msg.text.takeIf { it.isNotBlank() }
                        ?: msg.reasoning?.takeIf { it.isNotBlank() && msg.text.isBlank() } ?: "")
                    val errorPrefix = if (existingContent.isNotBlank()) "\n\n" else ""
                    val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                        is IOException -> "网络通讯故障: ${error.message ?: "IO 错误"}"
                        else -> "处理时发生错误: ${error.message ?: "未知应用错误"}"
                    }
                    val errorMsg = msg.copy(
                        text = existingContent + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true,
                        reasoning = if (existingContent == msg.reasoning && errorPrefix.isNotBlank()) null else msg.reasoning,
                        currentWebSearchStage = msg.currentWebSearchStage ?: "error_occurred"
                    )
                    stateHolder.messages[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) stateHolder.messageAnimationStates[messageId] =
                        true
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                }
            }
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
            }
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "服务响应错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "服务响应错误 ${response.status.value}: ${
                errorBody.take(150).replace(Regex("<[^>]*>"), "")
            }${if (errorBody.length > 150) "..." else ""}"
        }
    }

    private companion object {
        private const val ERROR_VISUAL_PREFIX = "⚠️ "
    }
}
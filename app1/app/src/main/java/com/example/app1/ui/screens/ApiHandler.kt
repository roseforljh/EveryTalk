package com.example.app1.ui.screens

import android.util.Log
import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.ApiMessage
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.data.network.ApiClient
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException as CoroutineCancellationException


class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager
) {
    private val jsonParserForError = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BackendErrorContent(
        val message: String? = null,
        val code: Int? = null
    )

    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        Log.d(
            "ApiHandlerCancel",
            "Request cancel. Reason: '$reason', NewMsg: $isNewMessageSend, MsgID: $messageIdBeingCancelled, Job: $jobToCancel"
        )

        if (stateHolder._isApiCalling.value) stateHolder._isApiCalling.value = false
        if (stateHolder._currentStreamingAiMessageId.value != null) stateHolder._currentStreamingAiMessageId.value =
            null

        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            stateHolder.messageAnimationStates.remove(messageIdBeingCancelled)

            if (!isNewMessageSend) { // Only consider removing if it's not a new message send (e.g., user explicit cancel)
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        if (msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                            Log.d(
                                "ApiHandlerCancel",
                                "Removing AI placeholder at index $index, ID: $messageIdBeingCancelled (Reason: $reason)"
                            )
                            stateHolder.messages.removeAt(index)
                        } else {
                            Log.d(
                                "ApiHandlerCancel",
                                "Message $messageIdBeingCancelled at $index not an empty placeholder, not removing."
                            )
                        }
                    } else {
                        Log.w(
                            "ApiHandlerCancel",
                            "Message $messageIdBeingCancelled not found for potential removal after cancel."
                        )
                    }
                }
            }
        }
        if (jobToCancel != null && jobToCancel.isActive) {
            Log.d("ApiHandlerCancel", "Cancelling Job $jobToCancel due to: $reason")
            jobToCancel.cancel(CoroutineCancellationException(reason))
        } else {
            Log.d(
                "ApiHandlerCancel",
                "No active job to cancel or job already cancelled for reason: $reason"
            )
        }
    }


    fun streamChatResponse(
        userMessageTextForContext: String, // This is the user text that triggers the AI response
        onMessagesProcessed: () -> Unit
        // targetIndexForAiMessage parameter has been removed
    ) {
        val currentConfig = stateHolder._selectedApiConfig.value
            ?: run {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("Please select or add an API configuration first") }
                return
            }

        // Always consider this a "new message send" for cancellation purposes,
        // as it initiates a new AI stream.
        cancelCurrentApiJob(
            "Starting new stream for context: ${userMessageTextForContext.take(30)}",
            isNewMessageSend = true
        )

        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "API state potentially stale. Forcing reset before new call.")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(CoroutineCancellationException("Force reset for new stream"))
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        val newAiMessage = Message(
            id = UUID.randomUUID().toString(),
            text = "",
            sender = Sender.AI,
            reasoning = null,
            contentStarted = false,
            isError = false,
            timestamp = System.currentTimeMillis()
        )
        val aiMessageId = newAiMessage.id
        val aiMessageInsertionIndex = 0 // Always add to the head of the data list (visual bottom)

        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.messages.add(aiMessageInsertionIndex, newAiMessage)
            Log.d(
                "ApiHandler",
                "New AI message (ID: $aiMessageId) added at index $aiMessageInsertionIndex (visual bottom). List size: ${stateHolder.messages.size}."
            )

            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId)
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            stateHolder.messageAnimationStates.remove(aiMessageId)

            onMessagesProcessed() // Trigger UI callback (e.g., scrolling)
        }

        // --- History Message Construction ---
        val messagesSnapshotForHistory = stateHolder.messages.toList()
        val apiHistoryMessages = mutableListOf<ApiMessage>()
        var historyMessageCount = 0
        val maxHistoryMessages = 20

        for (msg in messagesSnapshotForHistory) {
            if (historyMessageCount >= maxHistoryMessages) break
            if (msg.id == aiMessageId) continue // Skip the current AI placeholder

            val apiMsg: ApiMessage? = when {
                msg.sender == Sender.User && msg.text.isNotBlank() -> ApiMessage(
                    "user",
                    msg.text.trim()
                )

                msg.sender == Sender.AI && (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) && !msg.isError -> ApiMessage(
                    "assistant",
                    msg.text.trim()
                )

                else -> null
            }
            apiMsg?.let {
                apiHistoryMessages.add(it)
                historyMessageCount++
            }
        }
        apiHistoryMessages.reverse() // API usually expects history in chronological order (oldest to newest)

        // Ensure userMessageTextForContext is the last user message in the history for the API
        val lastUserMessageInApiHistory = apiHistoryMessages.lastOrNull { it.role == "user" }
        if (apiHistoryMessages.isEmpty() || lastUserMessageInApiHistory?.content != userMessageTextForContext.trim()) {
            if (apiHistoryMessages.isNotEmpty() && apiHistoryMessages.last().role == "user") {
                apiHistoryMessages[apiHistoryMessages.lastIndex] =
                    ApiMessage("user", userMessageTextForContext.trim())
            } else {
                apiHistoryMessages.add(ApiMessage("user", userMessageTextForContext.trim()))
            }
            while (apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()) {
                apiHistoryMessages.removeAt(0)
            }
        }
        Log.d(
            "ApiHandler",
            "Prepared ${apiHistoryMessages.size} messages for API. Last user ctx: '${
                apiHistoryMessages.lastOrNull { it.role == "user" }?.content?.take(50)
            }'"
        )


        val confirmedConfig: ApiConfig = stateHolder._selectedApiConfig.value ?: run {
            Log.e("ApiHandler", "Selected config became null.")
            viewModelScope.launch { stateHolder._snackbarMessage.emit("API config error.") }
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                val placeholderIndex = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                if (placeholderIndex != -1) {
                    stateHolder.messages.removeAt(placeholderIndex)
                    Log.w("ApiHandler", "Removed AI (ID: $aiMessageId) due to config error.")
                }
            }
            return
        }

        val requestBody = ChatRequest(
            messages = apiHistoryMessages,
            provider = if (confirmedConfig.provider.lowercase() == "google") "google" else "openai",
            apiAddress = confirmedConfig.address,
            apiKey = confirmedConfig.key,
            model = confirmedConfig.model
        )

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job]
            Log.d(
                "ApiHandler",
                "Starting API call job ${thisJob?.toString()?.takeLast(5)} for AI msg $aiMessageId"
            )
            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart { Log.d("ApiHandler", "Flow started for $aiMessageId") }
                    .catch { e ->
                        if (e !is CoroutineCancellationException) {
                            Log.e("ApiHandler", "Flow catch for $aiMessageId: ${e.message}", e)
                            launch(Dispatchers.Main.immediate) {
                                updateMessageWithError(
                                    aiMessageId,
                                    e
                                )
                            }
                        } else {
                            Log.d(
                                "ApiHandler",
                                "Flow catch (CANCELLATION) for $aiMessageId: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val targetMessageIdOnComplete = aiMessageId
                        Log.d(
                            "ApiHandler",
                            "Flow onCompletion for $targetMessageIdOnComplete. Cause: ${cause?.javaClass?.simpleName}"
                        )
                        launch(Dispatchers.Main.immediate) {
                            val isThisTheActiveJobOnComplete =
                                stateHolder.apiJob == thisJob || stateHolder.apiJob == null
                            if (isThisTheActiveJobOnComplete) {
                                stateHolder._isApiCalling.value = false
                                stateHolder.apiJob = null
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageIdOnComplete) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "onCompletion for $targetMessageIdOnComplete ignored, not active job."
                                )
                            }

                            val finalIndexOnComplete =
                                stateHolder.messages.indexOfFirst { it.id == targetMessageIdOnComplete }
                            if (finalIndexOnComplete != -1) {
                                var msg = stateHolder.messages[finalIndexOnComplete]
                                val animationCompleted =
                                    stateHolder.messageAnimationStates[targetMessageIdOnComplete]
                                        ?: false
                                val currentRawText = msg.text
                                val finalTextTrimmed = currentRawText.trim()

                                if (cause == null && !msg.isError) {
                                    if (currentRawText != finalTextTrimmed) {
                                        msg = msg.copy(text = finalTextTrimmed)
                                        stateHolder.messages[finalIndexOnComplete] = msg
                                    }
                                    if (!animationCompleted) stateHolder.messageAnimationStates[targetMessageIdOnComplete] =
                                        true
                                    historyManager.saveCurrentChatToHistoryIfNeeded()
                                } else if (cause != null) {
                                    if (cause is CoroutineCancellationException) {
                                        if (currentRawText != finalTextTrimmed) {
                                            msg = msg.copy(text = finalTextTrimmed)
                                            stateHolder.messages[finalIndexOnComplete] = msg
                                        }
                                        if (!animationCompleted) stateHolder.messageAnimationStates[targetMessageIdOnComplete] =
                                            true
                                    } else if (!msg.isError) {
                                        updateMessageWithError(targetMessageIdOnComplete, cause)
                                    }
                                }
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "Message $targetMessageIdOnComplete not found in onCompletion."
                                )
                            }
                            if (stateHolder._currentStreamingAiMessageId.value == targetMessageIdOnComplete && isThisTheActiveJobOnComplete) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }
                    }
                    .collect { chunk: OpenAiStreamChunk ->
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            Log.w(
                                "ApiHandler",
                                "Collect ignored for $aiMessageId, job/id mismatch."
                            )
                            return@collect
                        }
                        launch(Dispatchers.Main.immediate) {
                            val messageIndexToUpdate =
                                stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                            if (messageIndexToUpdate != -1) {
                                val messageToUpdate = stateHolder.messages[messageIndexToUpdate]
                                if (!messageToUpdate.isError) {
                                    processChunk(
                                        messageIndexToUpdate,
                                        messageToUpdate,
                                        chunk,
                                        aiMessageId
                                    )
                                } else {
                                    Log.w(
                                        "ApiHandler",
                                        "Collect: Message $aiMessageId at $messageIndexToUpdate is error."
                                    )
                                }
                            } else {
                                Log.e("ApiHandler", "Collect: Message $aiMessageId not found.")
                            }
                        }
                    }
            } catch (e: CoroutineCancellationException) {
                Log.d(
                    "ApiHandler",
                    "Outer job ${
                        thisJob?.toString()?.takeLast(5)
                    } cancelled for $aiMessageId: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e(
                    "ApiHandler",
                    "Outer job ${
                        thisJob?.toString()?.takeLast(5)
                    } exception for $aiMessageId: ${e.message}",
                    e
                )
                launch(Dispatchers.Main.immediate) { updateMessageWithError(aiMessageId, e) }
            } finally {
                Log.d(
                    "ApiHandler",
                    "Outer job ${thisJob?.toString()?.takeLast(5)} finally for $aiMessageId."
                )
                if (stateHolder.apiJob == thisJob && (thisJob == null || !thisJob.isActive || thisJob.isCancelled || thisJob.isCompleted)) {
                    launch(Dispatchers.Main.immediate) {
                        Log.w(
                            "ApiHandler",
                            "Manually cleaning API state in finally for job ${
                                thisJob?.toString()?.takeLast(5)
                            } ($aiMessageId)."
                        )
                        stateHolder._isApiCalling.value = false
                        if (stateHolder._currentStreamingAiMessageId.value == aiMessageId) stateHolder._currentStreamingAiMessageId.value =
                            null
                        stateHolder.apiJob = null
                    }
                }
            }
        }
    }

    private fun processChunk(
        index: Int,
        currentMessage: Message,
        chunk: OpenAiStreamChunk,
        messageIdForLog: String
    ) {
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != currentMessage.id) {
            Log.e(
                "ApiHandler",
                "processChunk: Stale index/ID. Index: $index, ListID: ${
                    stateHolder.messages.getOrNull(index)?.id
                }, ExpectedID: ${currentMessage.id}."
            )
            return
        }

        val choice = chunk.choices?.firstOrNull()
        val delta = choice?.delta
        val chunkContent = delta?.content
        val chunkReasoning = delta?.reasoningContent
        var updatedText = currentMessage.text
        var updatedReasoning = currentMessage.reasoning ?: ""
        var needsContentUpdate = false
        var needsReasoningUpdate = false

        if (!chunkContent.isNullOrEmpty()) {
            updatedText += chunkContent
            needsContentUpdate = true
        }
        if (!chunkReasoning.isNullOrEmpty()) {
            updatedReasoning += chunkReasoning
            needsReasoningUpdate = true
        }

        var newContentStarted = currentMessage.contentStarted
        if (!newContentStarted && (updatedText.isNotBlank() || updatedReasoning.isNotBlank())) {
            newContentStarted = true
        }

        if (needsContentUpdate || needsReasoningUpdate || newContentStarted != currentMessage.contentStarted) {
            val updatedMsg = currentMessage.copy(
                text = updatedText,
                reasoning = if (updatedReasoning.isEmpty() && !needsReasoningUpdate && currentMessage.reasoning == null) null else updatedReasoning,
                contentStarted = newContentStarted
            )
            stateHolder.messages[index] = updatedMsg

            if (needsContentUpdate && !chunkReasoning.isNullOrEmpty() && stateHolder.reasoningCompleteMap[currentMessage.id] == true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = false
            } else if (needsContentUpdate && (chunkReasoning.isNullOrEmpty() && choice?.finishReason != "stop") && stateHolder.reasoningCompleteMap[currentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = true
            }
        }

        if (choice?.finishReason == "stop" && delta?.reasoningContent.isNullOrEmpty()) {
            if (stateHolder.reasoningCompleteMap[currentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = true
                Log.d(
                    "ApiHandler",
                    "Marked reasoning complete for ${currentMessage.id} due to finish_reason:stop"
                )
            }
        }
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val msg = stateHolder.messages[idx]
            if (!msg.isError) {
                val errorPrefix = if (msg.text.isNotBlank()) "\n\n" else ""
                val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                    is IOException -> "Network error: ${error.message ?: "IO Error"}"
                    is ResponseException -> parseBackendError(
                        error.response, try {
                            error.response.bodyAsText()
                        } catch (_: Exception) {
                            "(Cannot read error body)"
                        }
                    )

                    else -> "Error: ${error.message ?: "Unknown"}"
                }
                val errorMsg = msg.copy(
                    text = msg.text + errorPrefix + errorTextContent,
                    isError = true, contentStarted = true, reasoning = null
                )
                stateHolder.messages[idx] = errorMsg
                stateHolder.messageAnimationStates[messageId] = true
                Log.e(
                    "ApiHandler",
                    "Updated message $messageId at $idx with error: ${error.message}"
                )
            } else {
                Log.w("ApiHandler", "Message $messageId already error.")
            }
        } else {
            Log.e("ApiHandler", "Failed to find $messageId for error update.")
        }
        if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
            stateHolder._isApiCalling.value = false
            stateHolder._currentStreamingAiMessageId.value = null
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "Backend Error: ${errorJson.message ?: response.status.description} (Status: ${response.status.value}, Code: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "Backend Error ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }
}
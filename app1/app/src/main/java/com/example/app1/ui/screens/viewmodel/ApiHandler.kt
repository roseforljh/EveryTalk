package com.example.app1.ui.screens.viewmodel

import android.util.Log
import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.ApiMessage
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.data.network.ApiClient // 确保 ApiClient 导入正确
import com.example.app1.ui.screens.ERROR_VISUAL_PREFIX // 确保常量导入正确
import com.example.app1.ui.screens.USER_CANCEL_MESSAGE // 确保常量导入正确
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
    private val historyManager: HistoryManager // Assuming HistoryManager constructor is correct now
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
        if (stateHolder.apiJob != null) stateHolder.apiJob = null

        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            stateHolder.messageAnimationStates.remove(messageIdBeingCancelled)
            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    // Find message by ID, as index might shift
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1 && index < stateHolder.messages.size) { // Check index bounds
                        val msg = stateHolder.messages[index] // Safe access after bounds check
                        // Only remove actual AI placeholders on user cancel (not new message send)
                        if (msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                            Log.d(
                                "ApiHandlerCancel",
                                "Removing AI placeholder at index $index, ID: $messageIdBeingCancelled"
                            )
                            // Check again before removing in case of race conditions
                            if (index < stateHolder.messages.size && stateHolder.messages[index].id == messageIdBeingCancelled) {
                                stateHolder.messages.removeAt(index)
                            }
                        } else {
                            Log.d(
                                "ApiHandlerCancel",
                                "Message $messageIdBeingCancelled not an empty placeholder, not removing."
                            )
                        }
                    } else {
                        Log.w(
                            "ApiHandlerCancel",
                            "Message $messageIdBeingCancelled not found for removal."
                        )
                    }
                }
            }
        }
        if (jobToCancel != null && jobToCancel.isActive) {
            Log.d("ApiHandlerCancel", "Cancelling Job $jobToCancel")
            jobToCancel.cancel(CoroutineCancellationException(reason))
        } else {
            Log.d("ApiHandlerCancel", "No active job to cancel or job already cancelled.")
        }
    }


    fun streamChatResponse(
        userMessageText: String, // Keep for context if needed, but primary use is history building
        onMessagesAdded: () -> Unit // Callback for UI notification (e.g., scroll)
    ) {
        val currentConfig = stateHolder._selectedApiConfig.value
            ?: run { viewModelScope.launch { stateHolder._snackbarMessage.emit("Please select or add an API configuration first") }; return }

        // Cancel previous job, marking as a new message send initiation
        cancelCurrentApiJob("Starting new message send", isNewMessageSend = true)

        // Force reset state just in case cancel didn't fully clean up (belt and suspenders)
        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "API state potentially stale. Forcing reset before new call.")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(CoroutineCancellationException("Force reset")) // Cancel again if needed
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        // Create AI loading placeholder message
        val loadingMessage = Message(
            id = UUID.randomUUID().toString(),
            text = "",
            sender = Sender.AI, // Ensure Sender enum is correct
            reasoning = null,
            contentStarted = false,
            isError = false,
            timestamp = System.currentTimeMillis()
        )
        val aiMessageId = loadingMessage.id

        // Add AI placeholder to the START of the list and update state immediately on Main thread
        viewModelScope.launch(Dispatchers.Main.immediate) {
            // --- *** KEY CHANGE: Add placeholder to index 0 for reverseLayout=true *** ---
            stateHolder.messages.add(0, loadingMessage)
            // --- *** KEY CHANGE END *** ---
            Log.d(
                "ApiHandler",
                "AI placeholder message added at index 0. List size: ${stateHolder.messages.size}. ID: $aiMessageId"
            )
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId) // Clear any old state for this ID
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            stateHolder.messageAnimationStates.remove(aiMessageId)
            onMessagesAdded() // Notify UI (e.g., to scroll)
        }

        // Build the history AFTER user message is added (by ViewModel) and BEFORE/separate from AI placeholder add
        // Taking a snapshot ensures we work with a consistent list state for the API call
        val messagesSnapshotForHistory = stateHolder.messages.toList()
        val historyApiMessages = messagesSnapshotForHistory
            .filterNot { it.id == aiMessageId } // Filter out the newly added AI placeholder
            .takeLast(20) // Limit history length
            .mapNotNull { msg ->
                when {
                    msg.sender == Sender.System || msg.isError || (msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted) -> null
                    msg.sender == Sender.User && msg.text.isNotBlank() -> ApiMessage(
                        "user",
                        msg.text.trim()
                    )

                    msg.sender == Sender.AI && msg.text.isNotBlank() -> ApiMessage(
                        "assistant",
                        msg.text.trim()
                    )

                    else -> {
                        Log.w(
                            "ApiHandler",
                            "Unexpected message type in history mapping: ${msg.sender}"
                        ); null
                    }
                }
            }
            // Reverse the history so the API receives it in chronological order (oldest first)
            .reversed() // <<< API usually expects [oldest_user, oldest_ai, ..., latest_user]

        Log.d("ApiHandler", "Prepared ${historyApiMessages.size} messages for API request.")

        // Confirm config again for safety
        val confirmedConfig: ApiConfig = stateHolder._selectedApiConfig.value ?: run {
            Log.e("ApiHandler", "Selected config became null unexpectedly before API call.")
            viewModelScope.launch { stateHolder._snackbarMessage.emit("API config error. Please reselect.") }
            // Clean up the placeholder and state if config disappears
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                val placeholderIndex = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                if (placeholderIndex == 0) { // It should be at index 0 if just added
                    stateHolder.messages.removeAt(placeholderIndex)
                    Log.w("ApiHandler", "Removed placeholder due to config error.")
                }
            }
            return
        }

        // Create request body
        val requestBody = ChatRequest(
            messages = historyApiMessages,
            provider = if (confirmedConfig.provider.lowercase() == "google") "google" else "openai",
            apiAddress = confirmedConfig.address,
            apiKey = confirmedConfig.key,
            model = confirmedConfig.model
        )

        // Start the API call coroutine
        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job]
            Log.d(
                "ApiHandler",
                "Starting API call job ${
                    thisJob?.toString()?.takeLast(5)
                } for AI message $aiMessageId"
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
                                "Flow catch (Cancellation) for $aiMessageId: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val targetMessageId = aiMessageId
                        Log.d(
                            "ApiHandler",
                            "Flow onCompletion for $targetMessageId. Cause: ${cause?.javaClass?.simpleName}"
                        )
                        launch(Dispatchers.Main.immediate) {
                            if (stateHolder.apiJob == thisJob) {
                                Log.d(
                                    "ApiHandler",
                                    "Cleaning API state in onCompletion for job ${
                                        thisJob?.toString()?.takeLast(5)
                                    }"
                                )
                                stateHolder._isApiCalling.value = false
                                stateHolder.apiJob = null
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "onCompletion ignored for job ${
                                        thisJob?.toString()?.takeLast(5)
                                    }, current job is ${
                                        stateHolder.apiJob?.toString()?.takeLast(5)
                                    }"
                                )
                            }

                            // Update final message state
                            val finalIndex =
                                stateHolder.messages.indexOfFirst { it.id == targetMessageId }
                            if (finalIndex != -1 && finalIndex < stateHolder.messages.size) {
                                val msg = stateHolder.messages[finalIndex]
                                if (msg.id == targetMessageId) { // Ensure it's the same message
                                    val animationCompleted =
                                        stateHolder.messageAnimationStates[targetMessageId] ?: false
                                    if (cause == null && !msg.isError && !animationCompleted) {
                                        stateHolder.messageAnimationStates[targetMessageId] = true
                                    }
                                    if (cause == null && !msg.isError) {
                                        historyManager.saveCurrentChatToHistoryIfNeeded()
                                    }
                                    if (cause != null && cause !is CoroutineCancellationException && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                                        if (finalIndex < stateHolder.messages.size && stateHolder.messages[finalIndex].id == targetMessageId) {
                                            Log.d(
                                                "ApiHandler",
                                                "Removing empty placeholder $targetMessageId due to completion error"
                                            )
                                            stateHolder.messages.removeAt(finalIndex)
                                        }
                                    } else if (cause != null && cause !is CoroutineCancellationException && !msg.isError) {
                                        updateMessageWithError(targetMessageId, cause)
                                    } else if (cause is CoroutineCancellationException && !animationCompleted) {
                                        stateHolder.messageAnimationStates[targetMessageId] = true
                                    }
                                }
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "Message $targetMessageId not found in onCompletion."
                                )
                            }
                            if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }
                    }
                    .collect { chunk: OpenAiStreamChunk ->
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            Log.w(
                                "ApiHandler",
                                "Collect ignored for $aiMessageId, current job/id mismatch."
                            )
                            return@collect
                        }

                        launch(Dispatchers.Main.immediate) {
                            // --- *** 重要：现在AI消息应该在索引0 *** ---
                            // 检查索引0是否是我们要更新的消息
                            val messageToUpdate = stateHolder.messages.getOrNull(0)

                            if (messageToUpdate == null || messageToUpdate.id != aiMessageId) {
                                // 如果索引0不是或者列表为空，尝试按ID查找（作为后备，理论上不应发生）
                                val fallbackIndex =
                                    stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                                if (fallbackIndex != -1 && fallbackIndex < stateHolder.messages.size) {
                                    val msg = stateHolder.messages[fallbackIndex]
                                    if (!msg.isError) {
                                        processChunk(fallbackIndex, msg, chunk) // 使用找到的后备索引
                                    } else {
                                        Log.w(
                                            "ApiHandler",
                                            "Collect: Fallback Message $aiMessageId is error."
                                        )
                                    }
                                } else {
                                    Log.w(
                                        "ApiHandler",
                                        "Collect: Message $aiMessageId not found at index 0 or elsewhere."
                                    )
                                }
                                return@launch
                            }

                            // 如果索引0是正确的AI消息且没有错误
                            if (!messageToUpdate.isError) {
                                processChunk(0, messageToUpdate, chunk) // 使用索引0
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "Collect: Message $aiMessageId at index 0 is error."
                                )
                            }
                            // --- *** 重要修改结束 *** ---
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
                    } exception for $aiMessageId before collect: ${e.message}",
                    e
                )
                launch(Dispatchers.Main.immediate) { updateMessageWithError(aiMessageId, e) }
            } finally {
                Log.d(
                    "ApiHandler",
                    "Outer job ${
                        thisJob?.toString()?.takeLast(5)
                    } finally block for $aiMessageId. Job cancelled: ${thisJob?.isCancelled}"
                )
                if (stateHolder.apiJob == thisJob) {
                    if (stateHolder._isApiCalling.value) stateHolder._isApiCalling.value = false
                    if (stateHolder._currentStreamingAiMessageId.value == aiMessageId) stateHolder._currentStreamingAiMessageId.value =
                        null
                    stateHolder.apiJob = null
                }
            }
        }
    }

    // Helper function to process a stream chunk and update the message list
    private fun processChunk(index: Int, currentMessage: Message, chunk: OpenAiStreamChunk) {
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != currentMessage.id) {
            Log.e(
                "ApiHandler",
                "processChunk: Invalid index or ID mismatch. Index: $index, MsgId: ${currentMessage.id}"
            )
            return // Safety check
        }

        val choice = chunk.choices?.firstOrNull()
        val delta = choice?.delta
        val chunkContent = delta?.content
        val chunkReasoning = delta?.reasoningContent
        var updatedMsg = currentMessage
        var needsUpdate = false

        if (!chunkContent.isNullOrEmpty()) {
            updatedMsg = updatedMsg.copy(text = updatedMsg.text + chunkContent)
            needsUpdate = true
            if (!updatedMsg.reasoning.isNullOrBlank() && stateHolder.reasoningCompleteMap[currentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = true
            }
        }
        if (!chunkReasoning.isNullOrEmpty()) {
            updatedMsg = updatedMsg.copy(reasoning = (updatedMsg.reasoning ?: "") + chunkReasoning)
            needsUpdate = true
        }

        if (!updatedMsg.contentStarted && (updatedMsg.text.isNotBlank() || !updatedMsg.reasoning.isNullOrBlank())) {
            updatedMsg = updatedMsg.copy(contentStarted = true)
            needsUpdate = true
            Log.d("ApiHandler", "Set contentStarted true for ${currentMessage.id}")
        }

        if (needsUpdate) {
            // Update the message at the correct index
            stateHolder.messages[index] = updatedMsg
        }
    }


    /**
     * Helper function to update a message in the list with an error.
     */
    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        val errorText = ERROR_VISUAL_PREFIX + when (error) {
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

        // Find message by ID, as index might change
        val idx = stateHolder.messages.indexOfFirst { it.id == messageId }

        if (idx != -1 && idx < stateHolder.messages.size) { // Check index bounds
            val msg = stateHolder.messages[idx] // Safe access
            if (!msg.isError) { // Only update if not already an error
                val errorMsg = msg.copy(
                    text = msg.text + (if (msg.text.isNotBlank()) "\n\n" else "") + errorText,
                    isError = true,
                    contentStarted = true,
                    reasoning = null
                )
                // Check index and ID again before modification
                if (idx < stateHolder.messages.size && stateHolder.messages[idx].id == messageId) {
                    stateHolder.messages[idx] = errorMsg
                    stateHolder.messageAnimationStates[messageId] = true
                    Log.e(
                        "ApiHandler",
                        "Updated message $messageId at index $idx with error: $errorText"
                    )
                } else {
                    Log.w(
                        "ApiHandler",
                        "Index/ID mismatch before error update at index $idx for $messageId"
                    )
                }
            } else {
                Log.w("ApiHandler", "Message $messageId already marked as error.")
            }
        } else {
            Log.e("ApiHandler", "Failed to find message $messageId to update with error.")
        }
        // Ensure API calling state is reset on error
        if (stateHolder._isApiCalling.value) stateHolder._isApiCalling.value = false
        if (stateHolder._currentStreamingAiMessageId.value == messageId) stateHolder._currentStreamingAiMessageId.value =
            null
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
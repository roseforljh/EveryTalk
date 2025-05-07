package com.example.app1.ui.screens.viewmodel // 确保包名正确

import android.util.Log
import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.ApiMessage
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.data.network.ApiClient
import com.example.app1.ui.screens.ERROR_VISUAL_PREFIX
import com.example.app1.ui.screens.USER_CANCEL_MESSAGE
// 不再需要 AnimationProgressState
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
        // Log.d("ApiHandlerCancel", "Request cancel. Reason: '$reason', NewMsg: $isNewMessageSend, MsgID: $messageIdBeingCancelled, Job: $jobToCancel")

        if (stateHolder._isApiCalling.value) stateHolder._isApiCalling.value = false
        if (stateHolder._currentStreamingAiMessageId.value != null) stateHolder._currentStreamingAiMessageId.value =
            null
        if (stateHolder.apiJob != null) stateHolder.apiJob = null

        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            stateHolder.messageAnimationStates.remove(messageIdBeingCancelled) // 清除简单动画状态
            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1 && index < stateHolder.messages.size) {
                        val msg = stateHolder.messages.getOrNull(index)
                        if (msg != null && msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                            if (index < stateHolder.messages.size && stateHolder.messages[index].id == messageIdBeingCancelled) {
                                stateHolder.messages.removeAt(index)
                                if (reason == USER_CANCEL_MESSAGE && index > 0) {
                                    val userMessageIndex = index - 1
                                    if (userMessageIndex < stateHolder.messages.size && stateHolder.messages[userMessageIndex].sender == Sender.User) {
                                        stateHolder.messages.removeAt(userMessageIndex)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (jobToCancel != null && jobToCancel.isActive) {
            jobToCancel.cancel(CoroutineCancellationException(reason))
        }
    }

    fun streamChatResponse(
        userMessageText: String,
        onMessagesAdded: () -> Unit
    ) {
        val currentConfig = stateHolder._selectedApiConfig.value
            ?: run { viewModelScope.launch { stateHolder._snackbarMessage.emit("Please select or add an API configuration first") }; return }
        cancelCurrentApiJob("Starting new message send", isNewMessageSend = true)
        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "API state not fully reset. Forcing reset.")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(CoroutineCancellationException("Force reset before new call"))
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        val userMessage =
            Message(id = UUID.randomUUID().toString(), text = userMessageText, sender = Sender.User)
        val loadingMessage = Message(
            id = UUID.randomUUID().toString(),
            text = "",
            sender = Sender.AI,
            reasoning = null,
            contentStarted = false,
            isError = false
        )
        val aiMessageId = loadingMessage.id

        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.messages.add(userMessage)
            stateHolder._userScrolledAway.value = false
            stateHolder.messages.add(loadingMessage)
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId)
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            stateHolder.messageAnimationStates.remove(aiMessageId) // 确保新消息没有旧的动画状态
            onMessagesAdded()
        }

        val historyApiMessages = stateHolder.messages.toList()
            .filterNot { it.id == aiMessageId }
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

                    else -> null
                }
            }

        val confirmedConfig: ApiConfig =
            stateHolder._selectedApiConfig.value ?: run { /* error handling */ return }
        val requestBody = ChatRequest(
            messages = historyApiMessages,
            provider = if (confirmedConfig.provider.lowercase() == "google") "google" else "openai",
            apiAddress = confirmedConfig.address,
            apiKey = confirmedConfig.key,
            model = confirmedConfig.model
        )

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart { /* Log */ }
                    .catch { e -> // 错误处理
                        if (e !is CoroutineCancellationException) {
                            Log.e("ApiHandler", "Flow catch for $aiMessageId: ${e.message}", e)
                            launch(Dispatchers.Main.immediate) {
                                val errorText = ERROR_VISUAL_PREFIX + when (e) {
                                    is IOException -> "Network error: ${e.message ?: "IO Error"}"
                                    is ResponseException -> parseBackendError(
                                        e.response, try {
                                            e.response.bodyAsText()
                                        } catch (_: Exception) {
                                            "(Cannot read error body)"
                                        }
                                    )

                                    else -> "Stream error: ${e.message ?: "Unknown"}"
                                }
                                val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                                if (idx != -1 && idx < stateHolder.messages.size) {
                                    val msg = stateHolder.messages.getOrNull(idx)
                                    if (msg != null && !msg.isError) {
                                        val errorMsg = msg.copy(
                                            text = msg.text + "\n\n" + errorText,
                                            contentStarted = true,
                                            isError = true,
                                            reasoning = null
                                        )
                                        stateHolder.messages[idx] = errorMsg
                                        stateHolder.messageAnimationStates[aiMessageId] =
                                            true // 错误消息视为动画完成
                                    }
                                }
                            }
                        } else {
                            // Log.d("ApiHandler", "Flow catch (Cancellation) for $aiMessageId: ${e.message}")
                        }
                    }
                    .onCompletion { cause -> // 完成处理
                        val targetMessageId = aiMessageId
                        launch(Dispatchers.Main.immediate) {
                            // Log.d("ApiHandler", "Flow onCompletion for $targetMessageId. Cause: ${cause?.javaClass?.simpleName}")
                            if (stateHolder.apiJob == thisJob) { // 清理全局状态
                                stateHolder._isApiCalling.value = false
                                stateHolder.apiJob = null
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            }

                            val finalIndex =
                                stateHolder.messages.indexOfFirst { it.id == targetMessageId }
                            if (finalIndex != -1 && finalIndex < stateHolder.messages.size) {
                                val msg = stateHolder.messages.getOrNull(finalIndex)
                                if (msg != null && msg.id == targetMessageId) {
                                    val animationCompleted =
                                        stateHolder.messageAnimationStates[targetMessageId] ?: false
                                    if (cause == null && !msg.isError && !animationCompleted) { // 正常完成且未标记完成
                                        stateHolder.messageAnimationStates[targetMessageId] = true
                                    }
                                    if (cause == null && !msg.isError) { // 正常完成时保存历史
                                        historyManager.saveCurrentChatToHistoryIfNeeded()
                                    }
                                    // 处理空占位符的移除（仅在非用户取消或错误时）
                                    if (cause is CoroutineCancellationException && cause.message != USER_CANCEL_MESSAGE && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                                        if (finalIndex < stateHolder.messages.size && stateHolder.messages[finalIndex].id == targetMessageId) {
                                            stateHolder.messages.removeAt(finalIndex)
                                        }
                                    } else if (cause != null && cause !is CoroutineCancellationException && !msg.isError) { // 其他错误
                                        val errorText = ERROR_VISUAL_PREFIX + (cause.message
                                            ?: "Unknown error on completion")
                                        val errorMsg = msg.copy(
                                            text = msg.text + "\n\n" + errorText,
                                            isError = true,
                                            contentStarted = true,
                                            reasoning = null
                                        )
                                        stateHolder.messages[finalIndex] = errorMsg
                                        stateHolder.messageAnimationStates[targetMessageId] =
                                            true // 错误也标记完成
                                    } else if (cause is CoroutineCancellationException && !animationCompleted) {
                                        // 如果因取消而结束，并且动画尚未标记完成，则标记为完成（因为我们不再继续动画）
                                        // 这可以防止 MessageBubble 在下次出现时尝试再次“完成”它
                                        stateHolder.messageAnimationStates[targetMessageId] = true
                                    }
                                }
                            }
                            if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            } // 最终清理
                        }
                    }
                    .collect { chunk: OpenAiStreamChunk -> // 处理数据块
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) return@collect // 检查Job和ID

                        launch(Dispatchers.Main.immediate) {
                            val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                            if (idx == -1 || idx >= stateHolder.messages.size) return@launch
                            val msg = stateHolder.messages.getOrNull(idx)
                            if (msg == null || msg.isError) return@launch

                            val choice = chunk.choices?.firstOrNull();
                            val delta = choice?.delta
                            val chunkContent = delta?.content;
                            val chunkReasoning = delta?.reasoningContent
                            var updatedMsg = msg;
                            var needsMessageListUpdate = false

                            if (!chunkContent.isNullOrEmpty()) {
                                updatedMsg = updatedMsg.copy(text = updatedMsg.text + chunkContent)
                                needsMessageListUpdate = true
                                if (!updatedMsg.reasoning.isNullOrBlank() && stateHolder.reasoningCompleteMap[aiMessageId] != true) {
                                    stateHolder.reasoningCompleteMap[aiMessageId] = true
                                }
                            }
                            if (!chunkReasoning.isNullOrEmpty()) {
                                updatedMsg = updatedMsg.copy(
                                    reasoning = (updatedMsg.reasoning ?: "") + chunkReasoning
                                )
                                needsMessageListUpdate = true
                            }

                            // --- **关键: 更新 contentStarted** ---
                            if (!updatedMsg.contentStarted && (updatedMsg.text.isNotBlank() || !updatedMsg.reasoning.isNullOrBlank())) {
                                updatedMsg = updatedMsg.copy(contentStarted = true)
                                needsMessageListUpdate = true
                                // Log.d("ApiHandler", "Set contentStarted for $aiMessageId")
                            }
                            // --- 结束关键点 ---

                            if (needsMessageListUpdate) {
                                if (idx < stateHolder.messages.size && stateHolder.messages[idx].id == aiMessageId) { // 再次检查
                                    stateHolder.messages[idx] = updatedMsg
                                }
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
                launch(Dispatchers.Main.immediate) {
                    val errorText = ERROR_VISUAL_PREFIX + when (e) {
                        is IOException -> "Network error: ${e.message ?: "IO Error"}"
                        is ResponseException -> parseBackendError(
                            e.response, try {
                                e.response.bodyAsText()
                            } catch (_: Exception) {
                                "(Cannot read error body)"
                            }
                        )

                        else -> "API call error: ${e.message ?: "Unknown"}"
                    }
                    val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                    if (idx != -1 && idx < stateHolder.messages.size) {
                        val msg = stateHolder.messages.getOrNull(idx)
                        if (msg != null && !msg.isError) {
                            val errorMsg = msg.copy(
                                text = msg.text + "\n\n" + errorText,
                                isError = true,
                                contentStarted = true,
                                reasoning = null
                            )
                            stateHolder.messages[idx] = errorMsg
                            stateHolder.messageAnimationStates[aiMessageId] = true // 错误也标记完成
                        }
                    }
                }
            } finally {
                // Log.d("ApiHandler", "Outer job ${thisJob?.toString()?.takeLast(5)} finally block for $aiMessageId")
                if (stateHolder.apiJob == thisJob && thisJob?.isCancelled == true) { // 检查取消状态
                    if (stateHolder._isApiCalling.value) {
                        stateHolder._isApiCalling.value = false
                    }
                    if (stateHolder._currentStreamingAiMessageId.value == aiMessageId) {
                        stateHolder._currentStreamingAiMessageId.value = null
                    }
                    stateHolder.apiJob = null
                }
            }
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson =
                jsonParserForError.decodeFromString<BackendErrorContent>(errorBody); "Backend Error: ${errorJson.message ?: response.status.description} (Status: ${response.status.value}, Code: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "Backend Error ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }
}
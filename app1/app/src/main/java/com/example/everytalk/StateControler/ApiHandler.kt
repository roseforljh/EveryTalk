package com.example.everytalk.StateControler // 你的包名

import android.util.Log
import com.example.everytalk.data.DataClass.ApiConfig // 假设存在
import com.example.everytalk.data.DataClass.ApiMessage // 假设存在
import com.example.everytalk.data.DataClass.ChatRequest // 假设存在
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.OpenAiStreamChunk // 假设存在
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.network.ApiClient // 假设存在
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import io.ktor.client.plugins.ResponseException // 假设存在
import io.ktor.client.statement.HttpResponse // 假设存在
import io.ktor.client.statement.bodyAsText // 假设存在
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

class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager
) {
    private val jsonParserForError = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BackendErrorContent(val message: String? = null, val code: Int? = null)

    private var currentTextBuilder = StringBuilder()
    private var currentReasoningBuilder = StringBuilder()

    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        Log.d(
            "ApiHandlerCancel",
            "请求取消API作业。原因: '$reason', 是否新消息触发: $isNewMessageSend, 取消的消息ID: $messageIdBeingCancelled, 作业: $jobToCancel"
        )

        stateHolder._isApiCalling.value = false
        stateHolder._currentStreamingAiMessageId.value = null

        currentTextBuilder.clear()
        currentReasoningBuilder.clear()

        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            stateHolder.messageAnimationStates.remove(messageIdBeingCancelled)

            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        if (msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                            Log.d(
                                "ApiHandlerCancel",
                                "移除AI占位符于索引 $index, ID: $messageIdBeingCancelled (原因: $reason)"
                            )
                            stateHolder.messages.removeAt(index) // 操作 SnapshotStateList
                        }
                    }
                }
            }
        }
        jobToCancel?.takeIf { it.isActive }
            ?.cancel(java.util.concurrent.CancellationException(reason))
        stateHolder.apiJob = null
    }

    fun streamChatResponse(
        userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit
    ) {
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请先选择或添加一个 API 配置") }
            return
        }

        cancelCurrentApiJob(
            "开始新的流式传输，上下文: '${userMessageTextForContext.take(30)}'",
            isNewMessageSend = true
        )

        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "强制重置API状态以开始新的流。")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(java.util.concurrent.CancellationException("强制重置以进行新的流式传输"))
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        val newAiMessage = Message(text = "", sender = Sender.AI)
        val aiMessageId = newAiMessage.id

        currentTextBuilder.clear()
        currentReasoningBuilder.clear()

        viewModelScope.launch(Dispatchers.Main.immediate) {
            var insertAtIndex = stateHolder.messages.size
            if (afterUserMessageId != null) {
                val userMessageIndex =
                    stateHolder.messages.indexOfFirst { it.id == afterUserMessageId }
                if (userMessageIndex != -1) {
                    insertAtIndex = userMessageIndex + 1
                } else {
                    Log.w(
                        "ApiHandler",
                        "afterUserMessageId $afterUserMessageId 未找到，AI消息将添加到列表末尾。"
                    )
                }
            }
            insertAtIndex = insertAtIndex.coerceAtMost(stateHolder.messages.size)
            stateHolder.messages.add(insertAtIndex, newAiMessage) // 操作 SnapshotStateList
            Log.d(
                "ApiHandler",
                "新的AI消息 (ID: $aiMessageId) 添加到索引 $insertAtIndex. 列表大小: ${stateHolder.messages.size}."
            )

            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId)
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            stateHolder.messageAnimationStates.remove(aiMessageId)
            onMessagesProcessed()
        }

        val messagesSnapshotForHistory =
            stateHolder.messages.toList() // toList() on SnapshotStateList
        val apiHistoryMessages = mutableListOf<ApiMessage>()
        var historyMessageCount = 0
        val maxHistoryMessages = 20
        val relevantHistory =
            messagesSnapshotForHistory.filterNot { it.id == aiMessageId && it.sender == Sender.AI && it.text.isBlank() }

        for (msg in relevantHistory.asReversed().take(maxHistoryMessages * 2)) {
            if (historyMessageCount >= maxHistoryMessages) break
            val apiMsg = when {
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
            apiMsg?.let { apiHistoryMessages.add(0, it); historyMessageCount++ }
        }
        if (apiHistoryMessages.size > maxHistoryMessages) {
            repeat(apiHistoryMessages.size - maxHistoryMessages) { apiHistoryMessages.removeAt(0) }
        }

        val lastUserMsgInApiHistory = apiHistoryMessages.lastOrNull { it.role == "user" }
        if (apiHistoryMessages.isEmpty() || lastUserMsgInApiHistory?.content != userMessageTextForContext.trim()) {
            if (apiHistoryMessages.isNotEmpty() && apiHistoryMessages.last().role == "user") {
                apiHistoryMessages[apiHistoryMessages.lastIndex] =
                    ApiMessage("user", userMessageTextForContext.trim())
            } else {
                apiHistoryMessages.add(ApiMessage("user", userMessageTextForContext.trim()))
            }
            if (apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()) {
                repeat(apiHistoryMessages.size - maxHistoryMessages) { apiHistoryMessages.removeAt(0) }
            }
        }

        val confirmedConfig: ApiConfig = stateHolder._selectedApiConfig.value ?: return
        val requestBody = ChatRequest(
            messages = apiHistoryMessages,
            provider = if (confirmedConfig.provider.equals(
                    "google",
                    ignoreCase = true
                )
            ) "google" else "openai",
            apiAddress = confirmedConfig.address,
            apiKey = confirmedConfig.key,
            model = confirmedConfig.model
        )

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key]
            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart { Log.d("ApiHandler", "流式传输开始，消息ID: $aiMessageId") }
                    .catch { e ->
                        if (e !is java.util.concurrent.CancellationException) {
                            updateMessageWithError(aiMessageId, e)
                        } else {
                            Log.d("ApiHandler", "流式传输被取消，消息ID: $aiMessageId")
                        }
                    }
                    .onCompletion { cause ->
                        val targetMsgId = aiMessageId
                        val isThisJobStillActive = stateHolder.apiJob == thisJob

                        if (isThisJobStillActive) {
                            stateHolder._isApiCalling.value = false
                            if (stateHolder._currentStreamingAiMessageId.value == targetMsgId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }
                        currentTextBuilder.clear()
                        currentReasoningBuilder.clear()

                        val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                        if (finalIdx != -1) {
                            var msg = stateHolder.messages[finalIdx]
                            val finalText = msg.text.trim()
                            val finalReasoning = msg.reasoning?.trim()

                            if (cause == null && !msg.isError) {
                                val updatedMsg = msg.copy(
                                    text = finalText,
                                    reasoning = if (finalReasoning.isNullOrBlank()) null else finalReasoning,
                                    contentStarted = msg.contentStarted || finalText.isNotBlank() || !finalReasoning.isNullOrBlank()
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] =
                                        updatedMsg // 操作 SnapshotStateList
                                }
                                if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                    stateHolder.messageAnimationStates[targetMsgId] = true
                                }
                                historyManager.saveCurrentChatToHistoryIfNeeded()
                            } else if (cause != null && cause !is java.util.concurrent.CancellationException && !msg.isError) {
                                updateMessageWithError(targetMsgId, cause)
                            } else if (cause is java.util.concurrent.CancellationException) {
                                val updatedMsg = msg.copy(
                                    text = finalText,
                                    reasoning = if (finalReasoning.isNullOrBlank()) null else finalReasoning,
                                    contentStarted = msg.contentStarted || finalText.isNotBlank() || !finalReasoning.isNullOrBlank()
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] =
                                        updatedMsg // 操作 SnapshotStateList
                                }
                                if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                    stateHolder.messageAnimationStates[targetMsgId] = true
                                }
                                historyManager.saveCurrentChatToHistoryIfNeeded()
                            }
                        }
                    }
                    .collect { chunk ->
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            Log.w(
                                "ApiHandler",
                                "接收到过时或无效数据块，消息ID: $aiMessageId, 当前流式ID: ${stateHolder._currentStreamingAiMessageId.value}"
                            )
                            thisJob?.cancel(java.util.concurrent.CancellationException("API job changed during collection"))
                            return@collect
                        }
                        val currentChunkIndex =
                            stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                        if (currentChunkIndex != -1) {
                            processChunk(currentChunkIndex, chunk, aiMessageId)
                        } else {
                            Log.e("ApiHandler", "在collect中未找到消息ID $aiMessageId 进行块处理。")
                        }
                    }
            } catch (e: java.util.concurrent.CancellationException) {
                Log.d("ApiHandler", "外部作业 $aiMessageId 被取消: ${e.message}")
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
            } catch (e: Exception) {
                updateMessageWithError(aiMessageId, e)
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
            } finally {
                if (stateHolder.apiJob == thisJob) {
                    stateHolder.apiJob = null
                    if (stateHolder._isApiCalling.value && stateHolder._currentStreamingAiMessageId.value == aiMessageId) {
                        stateHolder._isApiCalling.value = false
                        stateHolder._currentStreamingAiMessageId.value = null
                    }
                }
                Log.d(
                    "ApiHandler",
                    "流处理协程 $aiMessageId (job: $thisJob) 完成/结束。当前apiJob: ${stateHolder.apiJob}"
                )
            }
        }
    }

    private fun processChunk(index: Int, chunk: OpenAiStreamChunk, messageIdForLog: String) {
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            Log.e(
                "ApiHandler",
                "processChunk: 过时的索引或ID。索引: $index, 列表中的ID: ${
                    stateHolder.messages.getOrNull(index)?.id
                }, 期望的ID: $messageIdForLog."
            )
            return
        }

        val actualCurrentMessage = stateHolder.messages[index] // 操作 SnapshotStateList
        val choice = chunk.choices?.firstOrNull()
        val delta = choice?.delta
        val chunkContent = delta?.content
        val chunkReasoning = delta?.reasoningContent
        var contentChanged = false

        if (!chunkContent.isNullOrEmpty()) {
            currentTextBuilder.append(chunkContent)
            contentChanged = true
        }
        if (!chunkReasoning.isNullOrEmpty()) {
            currentReasoningBuilder.append(chunkReasoning)
            contentChanged = true
        }

        var newContentStarted = actualCurrentMessage.contentStarted
        if (!newContentStarted && (currentTextBuilder.isNotEmpty() || currentReasoningBuilder.isNotEmpty())) {
            newContentStarted = true
            contentChanged = true
        }

        if (contentChanged) {
            val updatedText = currentTextBuilder.toString()
            val updatedReasoningStr = currentReasoningBuilder.toString()
            val finalReasoning =
                if (updatedReasoningStr.isEmpty() && actualCurrentMessage.reasoning == null && chunkReasoning.isNullOrEmpty()) null else updatedReasoningStr
            val updatedMsg = actualCurrentMessage.copy(
                text = updatedText,
                reasoning = finalReasoning,
                contentStarted = newContentStarted
            )
            stateHolder.messages[index] = updatedMsg // 操作 SnapshotStateList

            if (chunkContent != null && !chunkReasoning.isNullOrEmpty() && stateHolder.reasoningCompleteMap[actualCurrentMessage.id] == true) {
                stateHolder.reasoningCompleteMap[actualCurrentMessage.id] = false
            } else if (chunkContent != null && (chunkReasoning.isNullOrEmpty() && choice?.finishReason != "stop") && stateHolder.reasoningCompleteMap[actualCurrentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[actualCurrentMessage.id] = true
            }
        }

        if (choice?.finishReason == "stop" && delta?.reasoningContent.isNullOrEmpty()) {
            if (stateHolder.reasoningCompleteMap[actualCurrentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[actualCurrentMessage.id] = true
            }
        }
    }

    private companion object {
        const val ERROR_VISUAL_PREFIX = "⚠️ "
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        currentTextBuilder.clear()
        currentReasoningBuilder.clear()
        withContext(Dispatchers.Main.immediate) {
            val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = stateHolder.messages[idx]
                if (!msg.isError) {
                    val errorPrefix = if (msg.text.isNotBlank()) "\n\n" else ""
                    val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                        is IOException -> "网络错误: ${error.message ?: "IO 错误"}"
                        is ResponseException -> parseBackendError(
                            error.response, try {
                                error.response.bodyAsText()
                            } catch (_: Exception) {
                                "(无法读取错误体)"
                            }
                        )

                        else -> "错误: ${error.message ?: "未知错误"}"
                    }
                    val errorMsg = msg.copy(
                        text = msg.text + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true,
                        reasoning = null
                    )
                    stateHolder.messages[idx] = errorMsg // 操作 SnapshotStateList
                    stateHolder.messageAnimationStates[messageId] = true
                }
            }
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                stateHolder.apiJob?.cancel(java.util.concurrent.CancellationException("流处理中发生错误: ${error.message}"))
            }
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "后端错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "后端错误 ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }
}
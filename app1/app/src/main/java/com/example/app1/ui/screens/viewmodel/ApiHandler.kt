package com.example.app1.ui.screens.viewmodel

import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.ApiMessage
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.data.network.ApiClient
import com.example.app1.ui.screens.ERROR_VISUAL_PREFIX
import com.example.app1.ui.screens.USER_CANCEL_MESSAGE
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers // 引入 Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
// 显式导入 CancellationException 避免歧义
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

    // 保证 user/assistant 严格交错 (此函数不再被 streamChatResponse 调用)
    private fun enforceRoleAlternation(messages: List<ApiMessage>): List<ApiMessage> {
        val result = mutableListOf<ApiMessage>()
        var lastRole: String? = null
        for (msg in messages) {
            if (msg.role == lastRole && (msg.role == "user" || msg.role == "assistant")) {
                continue
            }
            if (msg.role == "user" || msg.role == "assistant") {
                lastRole = msg.role
            }
            result.add(msg)
        }
        if (result.isNotEmpty() && result.last().role != "user") {
            println("WARN (enforceRoleAlternation): History ends with non-user role (${result.last().role})")
        }
        if (result.isNotEmpty() && result.first().role != "user" && result.first().role != "system") {
            println("WARN (enforceRoleAlternation): History starts with non-user/system role (${result.first().role}), removing initial assistant messages.")
            while (result.isNotEmpty() && result.first().role == "assistant") {
                if(result.isNotEmpty()) result.removeAt(0)
            }
        }
        val strictlyAlternating = mutableListOf<ApiMessage>()
        var lastRoleStrict: String? = null
        for (msg in result) {
            if (msg.role == lastRoleStrict && msg.role == "assistant") {
                println("WARN (enforceRoleAlternation): Found consecutive assistant message after final cleanup. Skipping.")
                continue
            }
            if (msg.role == "user" || msg.role == "assistant") {
                lastRoleStrict = msg.role
            }
            strictlyAlternating.add(msg)
        }
        return strictlyAlternating
    }

    // 取消当前 API 任务
    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        println("--- [ApiHandler.cancelCurrentApiJob] Requesting cancellation. Reason: '$reason'. isNewMessageSend: $isNewMessageSend. MessageID: $messageIdBeingCancelled, Job to cancel: $jobToCancel")

        // --- 立即重置 API 调用状态 ---
        if (stateHolder._isApiCalling.value) {
            println("--- [ApiHandler.cancelCurrentApiJob] Immediately setting _isApiCalling = false")
            stateHolder._isApiCalling.value = false
        }
        if (stateHolder._currentStreamingAiMessageId.value != null) {
            println("--- [ApiHandler.cancelCurrentApiJob] Immediately clearing _currentStreamingAiMessageId: ${stateHolder._currentStreamingAiMessageId.value}")
            stateHolder._currentStreamingAiMessageId.value = null
        }
        if (stateHolder.apiJob != null) {
            println("--- [ApiHandler.cancelCurrentApiJob] Immediately clearing internal apiJob reference.")
            stateHolder.apiJob = null
        }

        // --- 清理特定消息的状态 ---
        if (messageIdBeingCancelled != null) {
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            println("--- [ApiHandler.cancelCurrentApiJob] Cleaned up reasoningComplete and expandedReasoning states for message $messageIdBeingCancelled.")

            // --- 消息删除逻辑只在不是发送新消息时执行 ---
            if (!isNewMessageSend) {
                println("--- [ApiHandler.cancelCurrentApiJob] Handling non-send cancellation for message $messageIdBeingCancelled.")
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index = stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1 && index < stateHolder.messages.size) {
                        val msg = stateHolder.messages.getOrNull(index)
                        if (msg != null && msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                            println("--- [ApiHandler.cancelCurrentApiJob] Found empty placeholder AI message (ID: $messageIdBeingCancelled) at index $index. Attempting removal.")
                            if (index < stateHolder.messages.size && stateHolder.messages[index].id == messageIdBeingCancelled) {
                                stateHolder.messages.removeAt(index)
                                println("--- [ApiHandler.cancelCurrentApiJob] Successfully removed empty placeholder message at index $index.")
                                if (reason == USER_CANCEL_MESSAGE && index > 0) {
                                    val userMessageIndex = index - 1
                                    if (userMessageIndex < stateHolder.messages.size && stateHolder.messages[userMessageIndex].sender == Sender.User) {
                                        println("--- [ApiHandler.cancelCurrentApiJob] Attempting to remove preceding user message at index $userMessageIndex.")
                                        stateHolder.messages.removeAt(userMessageIndex)
                                        println("--- [ApiHandler.cancelCurrentApiJob] Successfully removed preceding user message at index $userMessageIndex.")
                                    } else {
                                        println("--- [ApiHandler.cancelCurrentApiJob] Preceding item at index $userMessageIndex was not a user message or index out of bounds, skipping removal.")
                                    }
                                }
                            } else {
                                println("--- [ApiHandler.cancelCurrentApiJob] Index or ID mismatch during removal check ($index vs list size ${stateHolder.messages.size}, ID ${stateHolder.messages.getOrNull(index)?.id} vs $messageIdBeingCancelled), not removing.")
                            }
                        } else {
                            println("--- [ApiHandler.cancelCurrentApiJob] Message (ID: $messageIdBeingCancelled) at index $index is not an empty placeholder suitable for removal. State: textBlank=${msg?.text?.isBlank()}, reasoningBlank=${msg?.reasoning.isNullOrBlank()}, contentStarted=${msg?.contentStarted}, isError=${msg?.isError}. Skipping message removal.")
                        }
                    } else {
                        println("--- [ApiHandler.cancelCurrentApiJob] AI message to check for removal not found (ID: $messageIdBeingCancelled).")
                    }
                }
            } else {
                println("--- [ApiHandler.cancelCurrentApiJob] Cancellation triggered by new message send. Skipping message removal logic.")
            }
        } else {
            println("--- [ApiHandler.cancelCurrentApiJob] No message ID was being cancelled. Skipping state cleanup and removal logic.")
        }

        // --- 取消协程 ---
        if (jobToCancel != null && jobToCancel.isActive) {
            println("--- [ApiHandler.cancelCurrentApiJob] Sending cancellation signal to Job: $jobToCancel")
            jobToCancel.cancel(CoroutineCancellationException(reason))
        } else {
            println("--- [ApiHandler.cancelCurrentApiJob] Job ($jobToCancel) is null or inactive, no cancellation signal sent.")
        }
        println("--- [ApiHandler.cancelCurrentApiJob] Immediate state reset and conditional message removal complete. Cancellation signal sent if job active.")
    }

    // 流式获取聊天响应
    fun streamChatResponse(
        userMessageText: String,
        onMessagesAdded: () -> Unit
    ) {
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Please select or add an API configuration first") }
            return
        }
        cancelCurrentApiJob("Starting new message send", isNewMessageSend = true)

        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            println("ApiHandler: WARNING: API call state not fully reset after cancellation attempt. Forcing reset.")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(CoroutineCancellationException("Force reset before new call"))
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        // 处理历史记录上下文 - 不再在此处清除 loadedHistoryIndex
        val loadedIndexBeforeSend = stateHolder._loadedHistoryIndex.value
        if (loadedIndexBeforeSend != null) {
            println("ApiHandler: Sending message while viewing history index $loadedIndexBeforeSend. Will attempt to update this index upon completion.")
            // stateHolder._loadedHistoryIndex.value = null // <- 确保这行已被删除或注释掉
        }

        // 创建消息对象
        val userMessage = Message(id = UUID.randomUUID().toString(), text = userMessageText, sender = Sender.User)
        val loadingMessage = Message(id = UUID.randomUUID().toString(), text = "", sender = Sender.AI, reasoning = null, contentStarted = false, isError = false)
        val aiMessageId = loadingMessage.id

        // 更新 UI 状态
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.messages.add(userMessage)
            stateHolder._userScrolledAway.value = false
            stateHolder.messages.add(loadingMessage)
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId)
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            onMessagesAdded()
            println("--- [ApiHandler UI Update] Added UserMsg & LoadingMsg(ID:$aiMessageId), set API calling state.")
        }

        // --- **修改点: 简化历史记录构建** ---
        // 直接过滤，不再调用 enforceRoleAlternation
        val historyApiMessages = stateHolder.messages.toList() // 获取当前消息列表快照
            .filterNot { it.id == aiMessageId } // 排除当前的 AI 占位符
            .mapNotNull { msg ->
                when {
                    // 排除系统消息、错误消息、以及没有文本的 AI 消息（包括空的占位符）
                    msg.sender == Sender.System || msg.isError || (msg.sender == Sender.AI && msg.text.isBlank()) -> null
                    // 保留有文本的用户消息
                    msg.sender == Sender.User && msg.text.isNotBlank() -> ApiMessage("user", msg.text.trim())
                    // 保留有文本的 AI 消息 (之前的有效回复)
                    msg.sender == Sender.AI && msg.text.isNotBlank() -> ApiMessage("assistant", msg.text.trim())
                    // 其他情况（如 sender 为 null 等）也排除
                    else -> null
                }
            }
        // 移除了: val historyApiMessages = enforceRoleAlternation(historyApiMessagesRaw)
        println("--- [ApiHandler History] Filtered history size for API request: ${historyApiMessages.size}")
        // --- **End 修改点** ---


        // 确认 API 配置
        val confirmedConfig: ApiConfig = stateHolder._selectedApiConfig.value ?: run {
            stateHolder._isApiCalling.value = false
            stateHolder._currentStreamingAiMessageId.value = null
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                if (idx != -1 && idx < stateHolder.messages.size && stateHolder.messages[idx].id == aiMessageId) {
                    stateHolder.messages.removeAt(idx)
                }
            }
            viewModelScope.launch { stateHolder._snackbarMessage.emit("API config error, cannot send") }
            return
        }

        // 准备请求体
        val providerToSend = when (confirmedConfig.provider.lowercase()) {
            "google" -> "google"
            else -> "openai"
        }
        val requestBody = ChatRequest(
            messages = historyApiMessages,
            provider = providerToSend,
            apiAddress = confirmedConfig.address,
            apiKey = confirmedConfig.key,
            model = confirmedConfig.model
        )
        println("ApiHandler Sending Request: Provider='${requestBody.provider}', Model='${requestBody.model}'")

        // 启动 API 调用协程
        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job]
            println("--- [ApiHandler Coroutine Started] Job: $thisJob, Target AI Message ID: $aiMessageId")

            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart {
                        println("--- [ApiHandler.onStart] Flow collection started for Job $thisJob.")
                    }
                    .catch { e -> // 处理 Flow 处理过程中的异常
                        if (e is CoroutineCancellationException) {
                            println("--- [ApiHandler.catch] Job $thisJob caught CancellationException during flow processing. Reason: ${e.message}. Will be handled by onCompletion.")
                        } else {
                            println("--- [ApiHandler.catch] Non-cancellation exception in flow processing for Job $thisJob: ${e::class.simpleName} - ${e.message}")
                            e.printStackTrace()
                            launch(Dispatchers.Main.immediate) {
                                val errorText = ERROR_VISUAL_PREFIX + when (e) {
                                    is IOException -> "Network connection error during stream: ${e.message ?: "IO Error"}"
                                    else -> "Stream processing error: ${e.message ?: "Unknown error"}"
                                }
                                val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                                if (idx != -1 && idx < stateHolder.messages.size) {
                                    val msg = stateHolder.messages.getOrNull(idx)
                                    if (msg != null && msg.id == aiMessageId && !msg.isError) {
                                        stateHolder.messages[idx] = msg.copy(
                                            text = (msg.text ?: "").let { if (it.isNotBlank()) it + "\n\n" + errorText else errorText },
                                            contentStarted = true, isError = true, reasoning = null
                                        )
                                        stateHolder.reasoningCompleteMap.remove(aiMessageId)
                                        stateHolder.expandedReasoningStates.remove(aiMessageId)
                                    }
                                }
                                stateHolder._snackbarMessage.emit("API request failed during streaming")
                            }
                        }
                    }
                    .onCompletion { cause -> // Flow 完成时调用
                        val completingJob = thisJob
                        val targetMessageId = aiMessageId

                        launch(Dispatchers.Main.immediate) {
                            val reason = when {
                                cause is CoroutineCancellationException && cause.message == USER_CANCEL_MESSAGE -> "User cancellation"
                                cause is CoroutineCancellationException -> "Internal cancellation (${cause.message})"
                                cause != null -> "Error (${cause::class.simpleName}: ${cause.message})"
                                else -> "Normal completion"
                            }
                            println("--- [ApiHandler.onCompletion] Job completing. Job: $completingJob, Target MessageID: $targetMessageId, Reason: $reason")

                            // --- 重置全局 API 状态 ---
                            val currentJobRef = stateHolder.apiJob
                            if (currentJobRef == completingJob) {
                                println("--- [ApiHandler.onCompletion] Global state cleanup: Job matches.")
                                stateHolder._isApiCalling.value = false
                                stateHolder.apiJob = null
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            } else {
                                println("--- [ApiHandler.onCompletion] Completed Job ($completingJob) does NOT match current Job ($currentJobRef).")
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            }
                            // --- END 重置全局 API 状态 ---

                            // --- 处理特定消息的最终状态 ---
                            val finalIndex = stateHolder.messages.indexOfFirst { it.id == targetMessageId }
                            if (finalIndex != -1 && finalIndex < stateHolder.messages.size) {
                                val msg = stateHolder.messages.getOrNull(finalIndex)
                                if (msg != null && msg.id == targetMessageId) {

                                    val wasCancelled = cause is CoroutineCancellationException
                                    val wasUserCancel = wasCancelled && cause?.message == USER_CANCEL_MESSAGE
                                    val hadError = cause != null && !wasCancelled

                                    if (msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError && (wasCancelled && !wasUserCancel || hadError)) {
                                        println("--- [ApiHandler.onCompletion] Removing empty placeholder (ID: $targetMessageId).")
                                        if (finalIndex < stateHolder.messages.size && stateHolder.messages[finalIndex].id == targetMessageId) {
                                            stateHolder.messages.removeAt(finalIndex)
                                        }
                                    }
                                    else if (!msg.isError) {
                                        var updatedMsg = msg.copy(); var modified = false
                                        when {
                                            cause == null -> { // NORMAL COMPLETION
                                                if (stateHolder.reasoningCompleteMap[targetMessageId] != true && !msg.reasoning.isNullOrBlank()) {
                                                    stateHolder.reasoningCompleteMap[targetMessageId] = true
                                                }
                                                println("--- [ApiHandler.onCompletion] Message completed normally.")
                                                historyManager.saveCurrentChatToHistoryIfNeeded() // <--- 保存历史
                                            }
                                            wasUserCancel -> { /* ... */ stateHolder.reasoningCompleteMap.remove(targetMessageId); stateHolder.expandedReasoningStates.remove(targetMessageId); }
                                            wasCancelled && !wasUserCancel -> { /* ... */ stateHolder.reasoningCompleteMap.remove(targetMessageId); stateHolder.expandedReasoningStates.remove(targetMessageId); /* Mark interrupted */ modified = true; updatedMsg = updatedMsg.copy(text = (msg.text ?: "") + " (Interrupted)")} // Example modification
                                            hadError -> { /* ... */ stateHolder.reasoningCompleteMap.remove(targetMessageId); stateHolder.expandedReasoningStates.remove(targetMessageId); /* Mark error */ modified = true; updatedMsg = updatedMsg.copy(text = (msg.text ?: "") + "\n\n[ERROR] " + (cause?.message ?: ""), isError = true, reasoning = null)} // Example modification
                                        }
                                        if (modified) {
                                            val currentIndex = stateHolder.messages.indexOfFirst { it.id == targetMessageId }
                                            if (currentIndex == finalIndex && currentIndex < stateHolder.messages.size && stateHolder.messages[currentIndex].id == targetMessageId) {
                                                stateHolder.messages[finalIndex] = updatedMsg
                                            }
                                        }
                                    }
                                }
                            }
                            // --- END 处理特定消息的最终状态 ---

                            // --- Final check to clear streaming ID ---
                            if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                            println("--- [ApiHandler.onCompletion] Finished onCompletion.")
                        } // END launch for onCompletion
                    } // END onCompletion
                    .collect { chunk: OpenAiStreamChunk -> // Process each chunk
                        // --- 检查状态是否仍然匹配 ---
                        val currentJobRef = stateHolder.apiJob
                        val currentStreamingId = stateHolder._currentStreamingAiMessageId.value
                        if (thisJob != currentJobRef || currentStreamingId != aiMessageId) {
                            return@collect // 忽略这个数据块
                        }
                        // --- END 状态检查 ---

                        // --- 在主线程更新消息状态 ---
                        launch(Dispatchers.Main.immediate) {
                            val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                            if (idx == -1 || idx >= stateHolder.messages.size) { return@launch }
                            val msg = stateHolder.messages.getOrNull(idx)
                            if (msg == null || msg.id != aiMessageId || msg.isError) { return@launch }

                            val choice = chunk.choices?.firstOrNull(); val delta = choice?.delta
                            val chunkContent = delta?.content; val chunkReasoning = delta?.reasoningContent

                            var updatedMsg = msg; var needsUpdate = false
                            val reasoningWasComplete = stateHolder.reasoningCompleteMap[aiMessageId] == true

                            // 更新主文本
                            if (!chunkContent.isNullOrEmpty()) {
                                val newText = (msg.text ?: "") + chunkContent
                                if (msg.text != newText) {
                                    updatedMsg = updatedMsg.copy(text = newText); needsUpdate = true
                                    if (!msg.contentStarted && newText.isNotBlank()) { updatedMsg = updatedMsg.copy(contentStarted = true); }
                                    // 提前标记推理完成
                                    if (!msg.reasoning.isNullOrBlank() && !reasoningWasComplete) {
                                        stateHolder.reasoningCompleteMap[aiMessageId] = true
                                        println("--- [ApiHandler.collect] Marked reasoningComplete=true for $aiMessageId.")
                                    }
                                }
                            }
                            // 更新推理文本
                            if (!chunkReasoning.isNullOrEmpty()) {
                                val newReasoning = (msg.reasoning ?: "") + chunkReasoning
                                if (msg.reasoning != newReasoning) { updatedMsg = updatedMsg.copy(reasoning = newReasoning); needsUpdate = true }
                            }

                            // 更新列表
                            if (needsUpdate) {
                                val currentIndex = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                                if (currentIndex == idx && currentIndex < stateHolder.messages.size && stateHolder.messages[currentIndex].id == aiMessageId) {
                                    stateHolder.messages[idx] = updatedMsg
                                }
                            }
                        } // END launch for collect
                    } // END collect
            } catch (e: CoroutineCancellationException) {
                println("--- [ApiHandler Coroutine Scope] Job $thisJob caught CancellationException during setup/flow.")
            } catch (e: Exception) {
                println("--- [ApiHandler Coroutine Scope] Job $thisJob caught UNEXPECTED exception during setup/execution: ${e::class.simpleName}")
                e.printStackTrace()
                launch(Dispatchers.Main.immediate) {
                    val errorText = ERROR_VISUAL_PREFIX + when (e) {
                        is IOException -> "Network connection error: ${e.message ?: "IO Error"}"
                        is ResponseException -> parseBackendError(e.response, try { e.response.bodyAsText() } catch (_: Exception) { "(Cannot read error body)" })
                        else -> "API call setup error: ${e.message ?: "Unknown error"}"
                    }
                    val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                    if (idx != -1 && idx < stateHolder.messages.size) {
                        val msg = stateHolder.messages.getOrNull(idx)
                        if (msg != null && msg.id == aiMessageId && !msg.isError) {
                            stateHolder.messages[idx] = msg.copy( text = (msg.text ?: "").let { if (it.isNotBlank()) it + "\n\n" + errorText else errorText },
                                isError = true, contentStarted = true, reasoning = null )
                            stateHolder.reasoningCompleteMap.remove(aiMessageId)
                            stateHolder.expandedReasoningStates.remove(aiMessageId)
                        }
                    }
                    stateHolder._snackbarMessage.emit("Failed to initiate API request")
                }
            } finally {
                println("--- [ApiHandler Coroutine Scope] Exiting launch block for Job $thisJob.")
            }
        } // END stateHolder.apiJob = viewModelScope.launch
        println("--- [ApiHandler.streamChatResponse] Launched API coroutine.")
    }

    // 解析后端错误响应
    private fun parseBackendError( response: HttpResponse, errorBody: String ): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "Backend Error: ${errorJson.message ?: response.status.description} (Status: ${response.status.value}, Code: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "Backend Error ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }
}
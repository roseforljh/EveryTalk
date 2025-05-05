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

    // 保证 user/assistant 严格交错
    private fun enforceRoleAlternation(messages: List<ApiMessage>): List<ApiMessage> {
        val result = mutableListOf<ApiMessage>()
        var lastRole: String? = null
        for (msg in messages) {
            if (msg.role == lastRole && (msg.role == "user" || msg.role == "assistant")) {
                continue // 跳过连续的相同角色
            }
            if (msg.role == "user" || msg.role == "assistant") {
                lastRole = msg.role // 更新上一个有效角色
            }
            result.add(msg)
        }
        // 检查并警告历史记录的结尾角色
        if (result.isNotEmpty() && result.last().role != "user") {
            println("WARN: History ends with non-user role (${result.last().role}), may cause issues with some models. Keeping as is for now.")
        }
        // 检查并移除历史记录开头的 assistant 消息
        if (result.isNotEmpty() && result.first().role != "user" && result.first().role != "system") {
            println("WARN: History starts with non-user/system role (${result.first().role}), removing initial assistant messages.")
            while (result.isNotEmpty() && result.first().role == "assistant") {
                // 使用 removeAt(0) 代替 removeFirst() 以兼容旧 API Level
                if(result.isNotEmpty()) result.removeAt(0)
            }
        }
        // 再次检查严格交错
        val strictlyAlternating = mutableListOf<ApiMessage>()
        var lastRoleStrict: String? = null
        for (msg in result) {
            if (msg.role == lastRoleStrict && msg.role == "assistant") {
                println("WARN: Found consecutive assistant message after final cleanup. Skipping.")
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
                                // 如果是用户取消，尝试移除前面的用户消息
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

        // 构造历史消息列表
        val historyApiMessagesRaw = stateHolder.messages.toList().filterNot { it.id == aiMessageId }.mapNotNull { msg ->
            when {
                msg.sender == Sender.System || msg.isError -> null
                msg.sender == Sender.User && msg.text.isNotBlank() -> ApiMessage("user", msg.text.trim())
                msg.sender == Sender.AI && msg.text.isNotBlank() -> ApiMessage("assistant", msg.text.trim())
                else -> null
            }
        }
        val historyApiMessages = enforceRoleAlternation(historyApiMessagesRaw)
        println("--- [ApiHandler History] Filtered & Alternated history size: ${historyApiMessages.size}")

        // 确认 API 配置
        val confirmedConfig: ApiConfig = stateHolder._selectedApiConfig.value ?: run {
            // 处理配置为空的错误
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
            // 可以添加其他 provider 的映射
            else -> "openai" // 默认
        }
        // 传递所有必需参数给 ChatRequest 构造函数
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
                ApiClient.streamChatResponse(requestBody) // 调用网络层获取 Flow
                    .onStart { // Flow 开始时执行
                        println("--- [ApiHandler.onStart] Flow collection started for Job $thisJob.")
                    }
                    .catch { e -> // 处理 Flow 处理过程中的异常
                        if (e is CoroutineCancellationException) {
                            println("--- [ApiHandler.catch] Job $thisJob caught CancellationException during flow processing. Reason: ${e.message}. Will be handled by onCompletion.")
                        } else {
                            // 其他错误（网络、解析等）
                            println("--- [ApiHandler.catch] Non-cancellation exception in flow processing for Job $thisJob: ${e::class.simpleName} - ${e.message}")
                            e.printStackTrace()
                            // 在主线程更新 UI 显示错误
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
                                        println("--- [ApiHandler.catch] Updated AI message (ID: $aiMessageId) at index $idx with stream processing error.")
                                    } else { println("--- [ApiHandler.catch] AI message (ID: $aiMessageId) state invalid at index $idx. Not updating.") }
                                } else { println("--- [ApiHandler.catch] AI message (ID: $aiMessageId) not found in list, cannot update error.") }
                                stateHolder._snackbarMessage.emit("API request failed during streaming")
                                // 让 onCompletion 处理 isApiCalling 等状态
                            }
                        }
                    }
                    .onCompletion { cause -> // Flow 完成时调用 (正常, 错误, 取消)
                        val completingJob = thisJob
                        val targetMessageId = aiMessageId

                        // 在主线程处理完成逻辑
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
                                    println("--- [ApiHandler.onCompletion] Cleared _currentStreamingAiMessageId ($targetMessageId).")
                                }
                            } else {
                                println("--- [ApiHandler.onCompletion] Completed Job ($completingJob) does NOT match current Job ($currentJobRef).")
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                    println("--- [ApiHandler.onCompletion] Clearing _currentStreamingAiMessageId ($targetMessageId) matching completed job.")
                                }
                            }
                            // --- END 重置全局 API 状态 ---

                            // --- 处理特定消息的最终状态 ---
                            val finalIndex = stateHolder.messages.indexOfFirst { it.id == targetMessageId }
                            if (finalIndex != -1 && finalIndex < stateHolder.messages.size) {
                                val msg = stateHolder.messages.getOrNull(finalIndex)
                                if (msg != null && msg.id == targetMessageId) { // 确保消息有效

                                    val wasCancelled = cause is CoroutineCancellationException
                                    val wasUserCancel = wasCancelled && cause?.message == USER_CANCEL_MESSAGE
                                    val hadError = cause != null && !wasCancelled

                                    // Case 1: 移除空的占位符 (如果是非用户取消或错误导致)
                                    if (msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError && (wasCancelled && !wasUserCancel || hadError)) {
                                        println("--- [ApiHandler.onCompletion] Removing empty placeholder (ID: $targetMessageId) at index $finalIndex.")
                                        if (finalIndex < stateHolder.messages.size && stateHolder.messages[finalIndex].id == targetMessageId) {
                                            stateHolder.messages.removeAt(finalIndex)
                                            println("--- [ApiHandler.onCompletion] Successfully removed message.")
                                        } else { println("--- [ApiHandler.onCompletion] Index/ID mismatch during removal check.") }
                                    }
                                    // Case 2: 更新有内容的消息或正常/用户取消结束的消息
                                    else if (!msg.isError) { // 只处理非错误状态的消息
                                        var updatedMsg = msg.copy()
                                        var modified = false

                                        when {
                                            // **** NORMAL COMPLETION ****
                                            cause == null -> {
                                                // Fallback 检查并标记推理完成
                                                if (stateHolder.reasoningCompleteMap[targetMessageId] != true && !msg.reasoning.isNullOrBlank()) {
                                                    stateHolder.reasoningCompleteMap[targetMessageId] = true
                                                    println("--- [ApiHandler.onCompletion Fallback] Marked reasoningComplete=true.")
                                                }
                                                println("--- [ApiHandler.onCompletion] Message completed normally.")
                                                // **** 仅在正常完成时尝试保存历史 ****
                                                historyManager.saveCurrentChatToHistoryIfNeeded()
                                            }
                                            // **** USER CANCELLATION ****
                                            wasUserCancel -> {
                                                println("--- [ApiHandler.onCompletion] User cancellation. Clearing states.")
                                                stateHolder.reasoningCompleteMap.remove(targetMessageId)
                                                stateHolder.expandedReasoningStates.remove(targetMessageId)
                                                // 不保存历史
                                            }
                                            // **** INTERNAL CANCELLATION ****
                                            wasCancelled && !wasUserCancel -> {
                                                val currentText = msg.text ?: ""
                                                if (currentText.isNotBlank() && !currentText.endsWith(" (Interrupted)")) {
                                                    updatedMsg = updatedMsg.copy(text = currentText + " (Interrupted)"); modified = true
                                                } else if (currentText.isBlank()) {
                                                    updatedMsg = updatedMsg.copy(text = "(Interrupted)", contentStarted = true); modified = true
                                                }
                                                if(modified) println("--- [ApiHandler.onCompletion] Marked as Interrupted.")
                                                stateHolder.reasoningCompleteMap.remove(targetMessageId)
                                                stateHolder.expandedReasoningStates.remove(targetMessageId)
                                                // 不保存历史
                                            }
                                            // **** OTHER ERROR ****
                                            hadError -> {
                                                val errorText = ERROR_VISUAL_PREFIX + (cause?.message ?: "Error during completion")
                                                updatedMsg = updatedMsg.copy( text = (msg.text ?: "").let { if (it.isNotBlank()) it + "\n\n" + errorText else errorText },
                                                    contentStarted = true, isError = true, reasoning = null ); modified = true
                                                println("--- [ApiHandler.onCompletion] Marking as Error.")
                                                stateHolder.reasoningCompleteMap.remove(targetMessageId)
                                                stateHolder.expandedReasoningStates.remove(targetMessageId)
                                                // 不保存历史
                                            }
                                        } // End When

                                        // 如果消息状态被修改 (例如添加了 Interrupted 或 Error)，则更新列表
                                        if (modified) {
                                            val currentIndex = stateHolder.messages.indexOfFirst { it.id == targetMessageId }
                                            if (currentIndex == finalIndex && currentIndex < stateHolder.messages.size && stateHolder.messages[currentIndex].id == targetMessageId) {
                                                stateHolder.messages[finalIndex] = updatedMsg
                                                println("--- [ApiHandler.onCompletion] Successfully updated final state of message.")
                                            } else { println("--- [ApiHandler.onCompletion] WARN: Index/ID mismatch during final update.") }
                                        }
                                    } else { println("--- [ApiHandler.onCompletion] Message was already in error state.") } // End if (!msg.isError)
                                } else { println("--- [ApiHandler.onCompletion] WARN: Message invalid at final index.") } // End if msg valid
                            } else { println("--- [ApiHandler.onCompletion] WARN: Message not found at final index.") } // End if finalIndex valid
                            // --- END 处理特定消息的最终状态 ---

                            // --- Final check to clear streaming ID ---
                            if (stateHolder._currentStreamingAiMessageId.value == targetMessageId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                            println("--- [ApiHandler.onCompletion] Finished onCompletion main thread logic for Job $completingJob.")
                        } // END launch(Dispatchers.Main.immediate) for onCompletion
                    } // END onCompletion
                    .collect { chunk: OpenAiStreamChunk -> // 处理流中的每一个数据块
                        // --- 检查状态是否仍然匹配 ---
                        val currentJobRef = stateHolder.apiJob
                        val currentStreamingId = stateHolder._currentStreamingAiMessageId.value
                        if (thisJob != currentJobRef || currentStreamingId != aiMessageId) {
                            println("--- [ApiHandler.collect] WARN: Ignoring chunk due to job/ID mismatch.")
                            if (thisJob != currentJobRef) thisJob?.cancel(CoroutineCancellationException("Stale job detected during collect"))
                            return@collect // 忽略这个数据块
                        }
                        // --- END 状态检查 ---

                        // --- 在主线程更新消息状态 ---
                        launch(Dispatchers.Main.immediate) { // 立即在主线程更新
                            val idx = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                            if (idx == -1 || idx >= stateHolder.messages.size) {
                                println("--- [ApiHandler.collect] WARN: AI message (ID: $aiMessageId) not found in list. Ignoring chunk.")
                                return@launch
                            }

                            val msg = stateHolder.messages.getOrNull(idx)
                            // 检查消息是否存在、ID是否匹配、是否已标记为错误
                            if (msg == null || msg.id != aiMessageId || msg.isError) {
                                println("--- [ApiHandler.collect] WARN: Message (ID: $aiMessageId) state invalid. Ignoring chunk.")
                                return@launch
                            }

                            // --- 解析数据块 ---
                            val choice = chunk.choices?.firstOrNull()
                            val delta = choice?.delta
                            val chunkContent = delta?.content // 主文本内容
                            val chunkReasoning = delta?.reasoningContent // 推理内容
                            val finishReason = choice?.finishReason // 通常为 null 直到最后

                            var updatedMsg = msg // 开始时假设消息不变
                            var needsUpdate = false // 标记是否需要更新列表
                            // 读取当前推理完成状态，避免在同一个 chunk 内重复设置
                            val reasoningWasComplete = stateHolder.reasoningCompleteMap[aiMessageId] == true

                            // 1. 更新主文本内容
                            if (!chunkContent.isNullOrEmpty()) {
                                val newText = (msg.text ?: "") + chunkContent
                                if (msg.text != newText) { // 检查文本是否真的改变了
                                    updatedMsg = updatedMsg.copy(text = newText)
                                    needsUpdate = true
                                    // 如果之前内容未开始，现在开始了，更新标记
                                    if (!msg.contentStarted && newText.isNotBlank()) {
                                        updatedMsg = updatedMsg.copy(contentStarted = true)
                                        println("--- [ApiHandler.collect] Marked contentStarted=true for $aiMessageId")
                                    }
                                    // **** 提前标记推理完成 ****
                                    // 如果之前收到过推理内容，并且这是第一个主内容块，并且推理尚未标记为完成
                                    if (!msg.reasoning.isNullOrBlank() && !reasoningWasComplete) {
                                        stateHolder.reasoningCompleteMap[aiMessageId] = true
                                        println("--- [ApiHandler.collect] Marked reasoningComplete=true for $aiMessageId (received first content chunk).")
                                    }
                                    // **** End ****
                                }
                            }

                            // 2. 更新推理内容
                            if (!chunkReasoning.isNullOrEmpty()) {
                                val newReasoning = (msg.reasoning ?: "") + chunkReasoning
                                if (msg.reasoning != newReasoning) { // 检查推理是否真的改变了
                                    updatedMsg = updatedMsg.copy(reasoning = newReasoning)
                                    needsUpdate = true
                                }
                            }

                            // 3. 如果需要更新，则替换列表中的消息
                            if (needsUpdate) {
                                // 再次确认索引和ID
                                val currentIndex = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                                if (currentIndex == idx && currentIndex < stateHolder.messages.size && stateHolder.messages[currentIndex].id == aiMessageId) {
                                    stateHolder.messages[idx] = updatedMsg
                                    // 可以在这里加 log，但会很频繁
                                    // println("--- [ApiHandler.collect] Updated message $aiMessageId at index $idx")
                                } else {
                                    println("--- [ApiHandler.collect] WARN: Index/ID mismatch during update ($currentIndex vs $idx). Skipping list update.")
                                }
                            }

                            // 4. 检查完成原因 (通常不需要在这里处理，onCompletion 会处理)
                            // if (finishReason != null) { /* ... */ }

                        } // END launch(Dispatchers.Main.immediate) for collect
                    } // END collect
            } catch (e: CoroutineCancellationException) {
                // 这个 catch 块捕获的是 launch 启动或 preparePost/execute 阶段的取消异常
                println("--- [ApiHandler Coroutine Scope] Job $thisJob caught CancellationException during setup/flow. Reason: ${e.message}. onCompletion should handle.")
            } catch (e: Exception) {
                // 这个 catch 块捕获的是 launch 启动或 preparePost/execute 阶段的其他异常
                println("--- [ApiHandler Coroutine Scope] Job $thisJob caught UNEXPECTED exception during setup or execution: ${e::class.simpleName} - ${e.message}")
                e.printStackTrace()
                // 在主线程更新UI
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
                            stateHolder.messages[idx] = msg.copy(
                                text = (msg.text ?: "").let { if (it.isNotBlank()) it + "\n\n" + errorText else errorText },
                                isError = true, contentStarted = true, reasoning = null
                            )
                            stateHolder.reasoningCompleteMap.remove(aiMessageId)
                            stateHolder.expandedReasoningStates.remove(aiMessageId)
                            println("--- [ApiHandler Coroutine Scope] Marked AI message (ID: $aiMessageId) as error due to setup/execution exception.")
                        } else { println("--- [ApiHandler Coroutine Scope] AI message (ID: $aiMessageId) state invalid, skipping marking error.") }
                    } else { println("--- [ApiHandler Coroutine Scope] AI message (ID: $aiMessageId) not found, cannot mark error.") }
                    stateHolder._snackbarMessage.emit("Failed to initiate API request")
                    // 让 onCompletion 处理 isApiCalling 等状态
                }
            } finally {
                // finally 块总会执行
                println("--- [ApiHandler Coroutine Scope] Exiting launch block for Job $thisJob. Final state handled by onCompletion.")
            }
        } // END stateHolder.apiJob = viewModelScope.launch
        println("--- [ApiHandler.streamChatResponse] Launched API coroutine (Job: ${stateHolder.apiJob}) for AI message ID $aiMessageId.")
    }

    // 解析后端错误响应
    private fun parseBackendError(
        response: HttpResponse,
        errorBody: String
    ): String {
        return try {
            // 尝试解析后端可能返回的 JSON 错误结构
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "Backend Error: ${errorJson.message ?: response.status.description} (Status: ${response.status.value}, Code: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            // 如果解析失败，返回原始状态码和部分响应体
            "Backend Error ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }
}
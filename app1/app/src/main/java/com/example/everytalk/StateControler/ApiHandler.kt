package com.example.everytalk.StateControler // 确保包名正确

import android.util.Log
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
// import com.example.everytalk.util.convertMarkdownToHtml // ViewModel 会处理 HTML 生成
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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

    // 为取消原因定义前缀，以便区分
    private val USER_CANCEL_PREFIX = "USER_CANCELLED:"
    private val NEW_STREAM_CANCEL_PREFIX = "NEW_STREAM_INITIATED:"


    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        val specificCancelReason =
            if (isNewMessageSend) "$NEW_STREAM_CANCEL_PREFIX $reason" else "$USER_CANCEL_PREFIX $reason"

        Log.d(
            TAG_API_HANDLER_CANCEL,
            "请求取消API作业。具体原因: '$specificCancelReason', 原始原因: '$reason', 新消息触发: $isNewMessageSend, 取消的消息ID: $messageIdBeingCancelled"
        )

        if (jobToCancel?.isActive == true && messageIdBeingCancelled != null) {
            val partialText = currentTextBuilder.toString().trim() // 获取并trim
            val partialReasoning =
                currentReasoningBuilder.toString().trim().let { if (it.isNotBlank()) it else null }

            if (partialText.isNotBlank() || partialReasoning != null) {
                Log.d(
                    TAG_API_HANDLER_CANCEL,
                    "API作业取消，处理已累积内容 (消息ID: $messageIdBeingCancelled): Text='${
                        partialText.take(50)
                    }', Reasoning='${partialReasoning?.take(50)}'"
                )
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
                            // isInterrupted = true // 可选
                        )
                        stateHolder.messages[index] = updatedMessage

                        if (partialText.isNotBlank()) {
                            onAiMessageFullTextChanged(messageIdBeingCancelled, partialText)
                        }
                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                        Log.d(TAG_API_HANDLER_CANCEL, "用户中断，已更新消息并触发历史保存。")
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
            if (!isNewMessageSend) { // 只在非新消息发送取消时，才考虑移除占位符
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        val isPlaceholder = msg.sender == Sender.AI && msg.text.isBlank() &&
                                msg.reasoning.isNullOrBlank() && msg.webSearchResults.isNullOrEmpty() &&
                                msg.currentWebSearchStage.isNullOrEmpty() && !msg.contentStarted && !msg.isError
                        if (isPlaceholder) {
                            Log.d(
                                TAG_API_HANDLER_CANCEL,
                                "移除AI占位符于索引 $index, ID: $messageIdBeingCancelled (原因: $reason, 非新消息发送)"
                            )
                            stateHolder.messages.removeAt(index)
                        }
                    }
                }
            }
        }
        jobToCancel?.takeIf { it.isActive }
            ?.cancel(CancellationException(specificCancelReason)) // 使用特定原因取消
        stateHolder.apiJob = null
    }

    fun streamChatResponse(
        requestBody: ChatRequest,
        @Suppress("UNUSED_PARAMETER") userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit
    ) {
        val contextForLog =
            requestBody.messages.lastOrNull { it.role == "user" }?.content?.take(30) ?: "N/A"
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
            Log.d(TAG_API_HANDLER, "新的AI消息 (ID: $aiMessageId) 添加到索引 $insertAtIndex.")
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap[aiMessageId] = false
            onMessagesProcessed()
        }

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key]
            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart { Log.d(TAG_API_HANDLER, "流式传输开始，消息ID: $aiMessageId") }
                    .catch { e ->
                        if (e !is CancellationException) {
                            updateMessageWithError(aiMessageId, e)
                        } else {
                            Log.d(
                                TAG_API_HANDLER,
                                "流式传输被取消 (catch)，消息ID: $aiMessageId. 原因: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val targetMsgId = aiMessageId
                        val isThisJobStillTheCurrentOne = stateHolder.apiJob == thisJob
                        Log.d(
                            TAG_API_HANDLER,
                            "流 onCompletion. Cause: $cause, TargetMsgId: $targetMsgId, isThisJobStillTheCurrentOne: $isThisJobStillTheCurrentOne"
                        )

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

                        // 只有在不是由 cancelCurrentApiJob 主动取消的情况下，onCompletion 才负责最终文本处理和保存
                        if (!wasCancelledByApiHandler) {
                            val finalFullText = currentTextBuilder.toString().trim()
                            if (finalFullText.isNotBlank()) {
                                Log.d(
                                    TAG_API_HANDLER,
                                    "onCompletion: 非ApiHandler取消，为最终累积文本触发内容处理 (消息ID: $targetMsgId). 长度: ${finalFullText.length}"
                                )
                                onAiMessageFullTextChanged(targetMsgId, finalFullText)
                            }
                            // 在非主动取消的情况下，如果流正常结束或因错误结束（非取消），也需要保存
                            if (cause == null || (cause !is CancellationException)) {
                                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = cause != null) // 如果是错误，强制保存
                            }
                        }

                        currentTextBuilder.clear()
                        currentReasoningBuilder.clear()

                        val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                        if (finalIdx != -1) {
                            val msg = stateHolder.messages[finalIdx]
                            if (cause == null && !msg.isError) { // 流正常完成
                                val updatedMsg = msg.copy(
                                    text = msg.text.trim(), // 确保最终文本是trim过的
                                    reasoning = msg.reasoning?.trim()
                                        .let { if (it.isNullOrBlank()) null else it },
                                    contentStarted = msg.contentStarted || msg.text.isNotBlank()
                                )
                                if (updatedMsg != msg) stateHolder.messages[finalIdx] = updatedMsg
                                if (stateHolder.messageAnimationStates[targetMsgId] != true) stateHolder.messageAnimationStates[targetMsgId] =
                                    true
                                // 保存已在上面 (if !wasCancelledByApiHandler) 处理
                            } else if (cause != null && cause !is CancellationException && !msg.isError) { // 流因错误完成
                                // updateMessageWithError 内部会处理保存
                            } else if (cause is CancellationException) { // 流被取消
                                Log.d(
                                    TAG_API_HANDLER,
                                    "流被取消 (onCompletion)，消息 $targetMsgId。原因: ${cause.message}"
                                )
                                if (!wasCancelledByApiHandler) { // 如果是其他原因的取消
                                    val hasMeaningfulContent =
                                        msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()
                                    if (hasMeaningfulContent) {
                                        historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                                    } else if (msg.sender == Sender.AI && !msg.isError) {
                                        Log.d(
                                            TAG_API_HANDLER,
                                            "AI消息 $targetMsgId 在 (非ApiHandler) 取消时无内容，移除。"
                                        )
                                        stateHolder.messages.removeAt(finalIdx)
                                    }
                                }
                            }
                        } else {
                            Log.w(
                                TAG_API_HANDLER,
                                "onCompletion: 未找到消息ID $targetMsgId 进行最终更新。"
                            )
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
                            processChunk(currentChunkIndex, appEvent, aiMessageId)
                        } else {
                            thisJob?.cancel(CancellationException("目标消息 $aiMessageId 在收集中途消失"))
                            return@collect
                        }
                    }
            } catch (e: Exception) {
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
                when (e) {
                    is CancellationException -> Log.d(
                        TAG_API_HANDLER,
                        "流处理协程 $aiMessageId 被取消 (outer try-catch): ${e.message}"
                    )

                    else -> {
                        Log.e(
                            TAG_API_HANDLER,
                            "流处理协程 $aiMessageId 发生未捕获的异常: ${e.message}",
                            e
                        )
                        updateMessageWithError(aiMessageId, e)
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
                Log.d(
                    TAG_API_HANDLER,
                    "流处理协程 $aiMessageId (job: $thisJob) 完成/结束 (finally)。当前apiJob: ${stateHolder.apiJob}"
                )
            }
        }
    }

    private fun processChunk(index: Int, appEvent: AppStreamEvent, messageIdForLog: String) {
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            Log.e(
                TAG_API_HANDLER_CHUNK,
                "索引或ID无效或过时。Index: $index, ExpectedID: $messageIdForLog, ActualListID: ${
                    stateHolder.messages.getOrNull(index)?.id
                }"
            )
            return
        }
        val originalMessage = stateHolder.messages[index]
        var newText = originalMessage.text
        var newReasoning = originalMessage.reasoning
        var newContentStarted = originalMessage.contentStarted
        var newWebResults = originalMessage.webSearchResults
        var newWebSearchStage = originalMessage.currentWebSearchStage
        var mainTextChangedInThisChunk = false

        when (appEvent.type) {
            "status_update" -> { /* ... */
            } // 保持不变
            "web_search_results" -> { /* ... */
            } // 保持不变
            "reasoning" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    currentReasoningBuilder.append(appEvent.text)
                    if (stateHolder.reasoningCompleteMap[messageIdForLog] != false) {
                        stateHolder.reasoningCompleteMap[messageIdForLog] = false
                    }
                }
            }

            "reasoning_finish" -> { /* ... */
            } // 保持不变
            "content" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    currentTextBuilder.append(appEvent.text)
                    mainTextChangedInThisChunk = true
                    if (!newContentStarted) {
                        newContentStarted = true
                        if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                            stateHolder.reasoningCompleteMap[messageIdForLog] = true
                        }
                    }
                }
            }

            "tool_calls_chunk", "google_function_call_request" -> { /* ... */
            } // 保持不变
            "finish" -> { /* ... */
            } // 保持不变
            "error" -> { /* ... */
            } // 保持不变
            else -> { /* ... */
            } // 保持不变
        }

        val accumulatedFullText = currentTextBuilder.toString() // 不在此处 trim，让回调或最终处理 trim
        if (newText != accumulatedFullText) {
            newText = accumulatedFullText
        }
        val accumulatedFullReasoning =
            currentReasoningBuilder.toString().let { if (it.isNotBlank()) it else null }
        if (newReasoning != accumulatedFullReasoning) {
            newReasoning = accumulatedFullReasoning
        }
        if (!newContentStarted && newText.isNotBlank() && (stateHolder.reasoningCompleteMap[messageIdForLog] == true || appEvent.type == "content")) {
            newContentStarted = true
        }

        if (newText != originalMessage.text || newReasoning != originalMessage.reasoning ||
            newContentStarted != originalMessage.contentStarted || newWebResults != originalMessage.webSearchResults ||
            newWebSearchStage != originalMessage.currentWebSearchStage
        ) {
            stateHolder.messages[index] = originalMessage.copy(
                text = newText,
                reasoning = newReasoning,
                contentStarted = newContentStarted,
                webSearchResults = newWebResults,
                currentWebSearchStage = newWebSearchStage
            )
            if (mainTextChangedInThisChunk && newText.isNotBlank()) {
                onAiMessageFullTextChanged(messageIdForLog, newText)
            }
        }
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        Log.e(
            TAG_API_HANDLER,
            "updateMessageWithError 为消息 $messageId, 错误: ${error.message}",
            error
        )
        currentTextBuilder.clear()
        currentReasoningBuilder.clear()
        withContext(Dispatchers.Main.immediate) {
            val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = stateHolder.messages[idx]
                if (!msg.isError) {
                    val existingContent = (msg.text.takeIf { it.isNotBlank() }
                        ?: msg.reasoning?.takeIf { it.isNotBlank() && msg.text.isBlank() }
                        ?: "")
                    val errorPrefix = if (existingContent.isNotBlank()) "\n\n" else ""
                    val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                        is IOException -> "网络通讯故障: ${error.message ?: "IO 错误"}"
                        is ResponseException -> parseBackendError(
                            error.response, try {
                                error.response.bodyAsText()
                            } catch (_: Exception) {
                                "(无法读取错误详情)"
                            }
                        )

                        else -> "处理时发生错误: ${error.message ?: "未知应用错误"}"
                    }
                    val errorMsg = msg.copy(
                        text = existingContent + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true,
                        reasoning = if (existingContent == msg.reasoning && errorPrefix.isNotBlank()) null else msg.reasoning, // 如果错误附加到reasoning上，清空
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
        private const val TAG_API_HANDLER = "ApiHandler"
        private const val TAG_API_HANDLER_CANCEL = "ApiHandlerCancel"
        private const val TAG_API_HANDLER_CHUNK = "ApiHandlerChunk"
        private const val ERROR_VISUAL_PREFIX = "⚠️ "
    }
}
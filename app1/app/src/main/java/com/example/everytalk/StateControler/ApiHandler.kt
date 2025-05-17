package com.example.everytalk.StateControler // 你的包名

import android.util.Log
import com.example.everytalk.data.DataClass.ChatRequest // 假设存在，并且是用于网络请求的那个
import com.example.everytalk.data.DataClass.Message // 这是UI层的Message
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

// --- WebSearchResult 导入 ---
// ApiHandler 可能不需要直接知道 WebSearchResult 类型，因为它操作的是 OpenAiStreamChunk 和 Message
// 但如果需要在日志中打印其内容，则可能需要。Message 类已经依赖它了。

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
                        if (msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && msg.webSearchResults.isNullOrEmpty() && !msg.contentStarted && !msg.isError) { // 检查 webSearchResults
                            Log.d(
                                "ApiHandlerCancel",
                                "移除AI占位符于索引 $index, ID: $messageIdBeingCancelled (原因: $reason)"
                            )
                            stateHolder.messages.removeAt(index)
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
        requestBody: ChatRequest,
        @Suppress("UNUSED_PARAMETER")
        userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit
    ) {
        val contextForLog =
            requestBody.messages.lastOrNull { it.role == "user" }?.content?.take(30) ?: "N/A"
        cancelCurrentApiJob(
            "开始新的流式传输，上下文: '$contextForLog'",
            isNewMessageSend = true
        )

        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "强制重置API状态以开始新的流。")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(java.util.concurrent.CancellationException("强制重置以进行新的流式传输"))
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        val newAiMessage = Message(text = "", sender = Sender.AI) // webSearchResults 默认为 null
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
            stateHolder.messages.add(insertAtIndex, newAiMessage)
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

        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key]
            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart { Log.d("ApiHandler", "流式传输开始，消息ID: $aiMessageId") }
                    .catch { e ->
                        if (e !is java.util.concurrent.CancellationException && e !is kotlinx.coroutines.CancellationException) {
                            updateMessageWithError(aiMessageId, e)
                        } else {
                            Log.d(
                                "ApiHandler",
                                "流式传输被取消 (catch)，消息ID: $aiMessageId. 原因: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val targetMsgId = aiMessageId
                        val isThisJobStillTheOne = stateHolder.apiJob == thisJob

                        Log.d(
                            "ApiHandler",
                            "流 onCompletion. Cause: $cause, TargetMsgId: $targetMsgId, isThisJobStillTheOne: $isThisJobStillTheOne"
                        )

                        if (isThisJobStillTheOne) {
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
                                Log.d("ApiHandler", "流正常完成，更新消息 $targetMsgId")
                                val updatedMsg = msg.copy(
                                    text = finalText,
                                    reasoning = if (finalReasoning.isNullOrBlank()) null else finalReasoning,
                                    contentStarted = msg.contentStarted || finalText.isNotBlank() || !finalReasoning.isNullOrBlank() || !msg.webSearchResults.isNullOrEmpty(), // webSearchResults 也算内容开始
                                    webSearchResults = msg.webSearchResults // --- 确保保留 ---
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] = updatedMsg
                                }
                                if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                    stateHolder.messageAnimationStates[targetMsgId] = true
                                }
                                historyManager.saveCurrentChatToHistoryIfNeeded()
                            } else if (cause != null && cause !is java.util.concurrent.CancellationException && cause !is kotlinx.coroutines.CancellationException && !msg.isError) {
                                Log.d("ApiHandler", "流因错误完成，更新消息 $targetMsgId")
                                // updateMessageWithError 内部会处理 webSearchResults，通常错误消息会覆盖它
                                updateMessageWithError(targetMsgId, cause)
                            } else if (cause is java.util.concurrent.CancellationException || cause is kotlinx.coroutines.CancellationException) {
                                Log.d(
                                    "ApiHandler",
                                    "流被取消 (onCompletion)，更新消息 $targetMsgId。当前文本: '$finalText'"
                                )
                                val updatedMsg = msg.copy(
                                    text = finalText,
                                    reasoning = if (finalReasoning.isNullOrBlank()) null else finalReasoning,
                                    contentStarted = msg.contentStarted || finalText.isNotBlank() || !finalReasoning.isNullOrBlank() || !msg.webSearchResults.isNullOrEmpty(), // webSearchResults 也算内容开始
                                    webSearchResults = msg.webSearchResults // --- 确保保留 ---
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] = updatedMsg
                                }
                                if (updatedMsg.text.isNotBlank() || !updatedMsg.reasoning.isNullOrBlank() || !updatedMsg.webSearchResults.isNullOrEmpty()) {
                                    if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                        stateHolder.messageAnimationStates[targetMsgId] = true
                                    }
                                    historyManager.saveCurrentChatToHistoryIfNeeded()
                                } else if (msg.sender == Sender.AI && !msg.contentStarted && msg.webSearchResults.isNullOrEmpty() && !msg.isError) { // 检查 webSearchResults
                                    Log.d(
                                        "ApiHandler",
                                        "AI消息 $targetMsgId 在取消时无内容，将其移除。"
                                    )
                                    stateHolder.messages.removeAt(finalIdx)
                                }
                            }
                        } else {
                            Log.w(
                                "ApiHandler",
                                "onCompletion: 未找到消息ID $targetMsgId 进行最终更新。"
                            )
                        }
                    }
                    .collect { chunk ->
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            Log.w(
                                "ApiHandler",
                                "接收到过时或无效数据块，消息ID: $aiMessageId, 当前流式ID: ${stateHolder._currentStreamingAiMessageId.value}, 当前Job: ${stateHolder.apiJob}, 此块Job: $thisJob"
                            )
                            thisJob?.cancel(java.util.concurrent.CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                            return@collect
                        }

                        val currentChunkIndex =
                            stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                        if (currentChunkIndex != -1) {
                            processChunk(currentChunkIndex, chunk, aiMessageId)
                        } else {
                            Log.e(
                                "ApiHandler",
                                "在collect中未找到消息ID $aiMessageId 进行块处理。列表大小: ${stateHolder.messages.size}"
                            )
                            thisJob?.cancel(java.util.concurrent.CancellationException("目标消息 $aiMessageId 在收集中途消失"))
                            return@collect
                        }
                    }
            } catch (e: java.util.concurrent.CancellationException) {
                Log.d("ApiHandler", "外部作业 (流处理协程 $aiMessageId) 被取消: ${e.message}")
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(
                    "ApiHandler",
                    "外部作业 (流处理协程 $aiMessageId) 被Ktor/coroutine取消: ${e.message}"
                )
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
            } catch (e: Exception) {
                Log.e("ApiHandler", "流处理协程 $aiMessageId 发生未捕获的异常: ${e.message}", e)
                updateMessageWithError(aiMessageId, e)
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
            } finally {
                if (stateHolder.apiJob == thisJob) {
                    stateHolder.apiJob = null
                    if (stateHolder._isApiCalling.value && stateHolder._currentStreamingAiMessageId.value == aiMessageId) {
                        stateHolder._isApiCalling.value = false
                        stateHolder._currentStreamingAiMessageId.value = null
                        Log.d("ApiHandler", "在 finally 中重置了消息 $aiMessageId 的 API 调用状态。")
                    }
                }
                Log.d(
                    "ApiHandler",
                    "流处理协程 $aiMessageId (job: $thisJob) 完成/结束 (finally)。当前apiJob: ${stateHolder.apiJob}"
                )
            }
        }
    }

    private fun processChunk(index: Int, chunk: OpenAiStreamChunk, messageIdForLog: String) {
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            Log.e(
                "ApiHandlerProcessChunk",
                "过时的索引或ID。索引: $index, 列表中的ID: ${stateHolder.messages.getOrNull(index)?.id}, 期望的ID: $messageIdForLog."
            )
            return
        }

        var messageToUpdate = stateHolder.messages[index] // 获取当前消息的最新状态
        var anyChangesInThisChunk = false

        val choice = chunk.choices?.firstOrNull()
        val delta = choice?.delta

        // --- 1. 处理网页搜索结果 (通常只在第一个或早期数据块中出现) ---
        val chunkWebResults = delta?.webSearchResultsInternal // 使用你在 OpenAiDelta 中定义的字段名
        if (!chunkWebResults.isNullOrEmpty() && messageToUpdate.webSearchResults.isNullOrEmpty()) {
            Log.d(
                "ApiHandlerProcessChunk",
                "MsgID: ${messageIdForLog.take(8)}, 收到并设置 webSearchResults: ${chunkWebResults.size} 项"
            )
            messageToUpdate = messageToUpdate.copy(webSearchResults = chunkWebResults)
            anyChangesInThisChunk = true
        }

        // --- 2. 处理文本内容 ---
        val chunkContent = delta?.content
        if (!chunkContent.isNullOrEmpty()) {
            currentTextBuilder.append(chunkContent)
            anyChangesInThisChunk = true // 标记文本内容有变化
        }

        // --- 3. 处理思考过程 ---
        val chunkReasoning = delta?.reasoningContent
        if (!chunkReasoning.isNullOrEmpty()) {
            currentReasoningBuilder.append(chunkReasoning)
            anyChangesInThisChunk = true // 标记思考过程内容有变化
            Log.d(
                "ApiHandlerProcessChunk",
                "MsgID: ${messageIdForLog.take(8)}, 追加到 currentReasoningBuilder: '${
                    currentReasoningBuilder.toString().take(50)
                }'"
            )
        }

        // --- 4. 更新 contentStarted 状态 ---
        var newContentStarted = messageToUpdate.contentStarted
        if (!newContentStarted && (currentTextBuilder.isNotEmpty() || currentReasoningBuilder.isNotEmpty() || !messageToUpdate.webSearchResults.isNullOrEmpty())) {
            newContentStarted = true
            // 如果 newContentStarted 从 false 变为 true，也算作一种改变
            if (!messageToUpdate.contentStarted) {
                anyChangesInThisChunk = true
            }
        }

        // --- 5. 如果有任何变化，则更新消息对象 ---
        if (anyChangesInThisChunk) {
            val updatedText = currentTextBuilder.toString()
            val updatedReasoningStr = currentReasoningBuilder.toString()

            // 这个 finalReasoning 的逻辑需要仔细考虑，确保它能正确处理累积和清空的情况
            val finalReasoning = if (updatedReasoningStr.isNotEmpty()) {
                updatedReasoningStr
            } else if (messageToUpdate.reasoning != null && chunkReasoning == null && choice?.finishReason != "stop" && choice?.finishReason != null /* 只有当有finishReason但不是stop时，才可能保留旧reasoning? 这逻辑有点复杂*/) {
                // 如果当前块没有reasoning，但之前有，并且流未结束，可能需要保留之前的reasoning
                // 但通常 builder 是累积的，所以如果 builder 为空，意味着没有reasoning或已被清空
                messageToUpdate.reasoning // 或者 null，取决于你的业务逻辑
            } else {
                null
            }

            messageToUpdate = messageToUpdate.copy(
                text = updatedText,
                reasoning = finalReasoning, // 使用累积的思考过程
                contentStarted = newContentStarted
                // webSearchResults 已经在前面步骤中通过 messageToUpdate = messageToUpdate.copy(...) 设置了
            )
            stateHolder.messages[index] = messageToUpdate
            Log.d(
                "ApiHandlerProcessChunk",
                "MsgID: ${messageIdForLog.take(8)}, 更新消息. Reasoning: '${
                    messageToUpdate.reasoning?.take(50)
                }', Text: '${messageToUpdate.text.take(50)}', ContentStarted: ${messageToUpdate.contentStarted}, WebResults: ${messageToUpdate.webSearchResults?.size ?: 0}"
            )
        }


        // --- 处理 reasoningCompleteMap (这部分逻辑保持你原有的) ---
        if (anyChangesInThisChunk) { // 只在消息实际更新后才处理 reasoningCompleteMap
            if (!chunkReasoning.isNullOrEmpty() && stateHolder.reasoningCompleteMap[messageToUpdate.id] == true) {
                stateHolder.reasoningCompleteMap[messageToUpdate.id] = false
            } else if (chunkContent != null && chunkReasoning.isNullOrEmpty() && choice?.finishReason != "stop" && stateHolder.reasoningCompleteMap[messageToUpdate.id] != true) {
                // 这段逻辑可能需要调整，确保reasoning真的完成了才设为true
                // stateHolder.reasoningCompleteMap[messageToUpdate.id] = true // 暂时注释，原逻辑可能不完全适配
            }
        }
        if (choice?.finishReason == "stop" && delta?.reasoningContent.isNullOrEmpty()) {
            if (stateHolder.reasoningCompleteMap[messageToUpdate.id] != true) {
                stateHolder.reasoningCompleteMap[messageToUpdate.id] = true
                Log.d("ApiHandler", "消息 ${messageToUpdate.id.take(8)} 推理因 stop 标记完成。")
            }
        }
    }

    private companion object {
        const val ERROR_VISUAL_PREFIX = "⚠️ "
    }

    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        Log.e(
            "ApiHandler",
            "updateMessageWithError 为消息 $messageId, 错误: ${error.message}",
            error
        )
        currentTextBuilder.clear()
        currentReasoningBuilder.clear()

        withContext(Dispatchers.Main.immediate) {
            val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = stateHolder.messages[idx]
                if (!msg.isError) { // 只在第一次出错时附加错误信息
                    val errorPrefix =
                        if (msg.text.isNotBlank() || !msg.webSearchResults.isNullOrEmpty()) "\n\n" else "" // 如果已有内容（包括web结果），则换行
                    val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                        is IOException -> "网络错误: ${error.message ?: "IO 错误"}"
                        is ResponseException -> parseBackendError(
                            error.response, try {
                                error.response.bodyAsText()
                            } catch (_: Exception) {
                                "(无法读取错误体)"
                            }
                        )

                        else -> "处理错误: ${error.message ?: "未知错误"}"
                    }
                    // 当发生错误时，我们通常不保留webSearchResults，因为消息的主体是错误信息
                    val errorMsg = msg.copy(
                        text = msg.text + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true, // 错误消息也算内容开始
                        reasoning = null, // 清除思考过程
                        webSearchResults = msg.webSearchResults // 错误发生前可能已收到搜索结果，可以选择保留或清除
                        // webSearchResults = null // 如果希望错误消息完全取代之前的内容，则设为null
                    )
                    stateHolder.messages[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) {
                        stateHolder.messageAnimationStates[messageId] = true
                    }
                }
            }
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                stateHolder.apiJob?.cancel(java.util.concurrent.CancellationException("流处理中发生错误，已更新UI: ${error.message}"))
                Log.d("ApiHandler", "错误处理后重置了消息 $messageId 的 API 调用状态。")
            }
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "后端错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "后端服务错误 ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }
}
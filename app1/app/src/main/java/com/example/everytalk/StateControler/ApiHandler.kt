package com.example.everytalk.StateControler // 您的包名

import android.util.Log
import com.example.everytalk.data.DataClass.Message // UI层的Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.ChatRequest // 网络请求的 ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent // 新的事件数据类
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse // 仅用于 parseBackendError
import io.ktor.client.statement.bodyAsText // 仅用于 parseBackendError
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
import java.util.concurrent.CancellationException // 显式导入

class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager
) {
    private val jsonParserForError = Json { ignoreUnknownKeys = true } // 用于解析特定错误格式

    @Serializable // 这个内部类保持不变，用于解析特定错误格式
    private data class BackendErrorContent(val message: String? = null, val code: Int? = null)

    // 用于累积当前流式AI消息的文本和思考过程
    private var currentTextBuilder = StringBuilder() // 用于累积 "content" 类型的文本 (最终答案)
    private var currentReasoningBuilder = StringBuilder() // 用于累积 "reasoning" 类型的文本 (思考过程)

    /**
     * 取消当前正在进行的API调用作业。
     * @param reason 取消的原因，用于日志。
     * @param isNewMessageSend 是否因为发送新消息而触发的取消。
     */
    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        Log.d(
            TAG_API_HANDLER_CANCEL, // 使用统一定义的日志标签
            "请求取消API作业。原因: '$reason', 新消息触发: $isNewMessageSend, 取消的消息ID: $messageIdBeingCancelled"
        )

        stateHolder._isApiCalling.value = false // 更新API调用状态
        stateHolder._currentStreamingAiMessageId.value = null // 清除当前流式消息ID

        currentTextBuilder.clear() // 清空累积的最终答案文本
        currentReasoningBuilder.clear() // 清空累积的思考过程文本

        if (messageIdBeingCancelled != null) {
            // 清理与被取消消息相关的状态
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            // stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            // stateHolder.messageAnimationStates.remove(messageIdBeingCancelled)

            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        // 仅当AI消息完全为空（无文本、无思考、无网页结果、无阶段信息、非错误、内容未开始）时才移除
                        if (msg.sender == Sender.AI &&
                            msg.text.isBlank() &&
                            msg.reasoning.isNullOrBlank() &&
                            msg.webSearchResults.isNullOrEmpty() &&
                            msg.currentWebSearchStage.isNullOrEmpty() &&
                            !msg.contentStarted &&
                            !msg.isError
                        ) {
                            Log.d(
                                TAG_API_HANDLER_CANCEL,
                                "移除AI占位符于索引 $index, ID: $messageIdBeingCancelled (原因: $reason)"
                            )
                            stateHolder.messages.removeAt(index)
                        }
                    }
                }
            }
        }
        jobToCancel?.takeIf { it.isActive }
            ?.cancel(CancellationException(reason))
        stateHolder.apiJob = null
    }

    /**
     * 发起聊天请求并处理流式响应。
     */
    fun streamChatResponse(
        requestBody: ChatRequest,
        @Suppress("UNUSED_PARAMETER") userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit
    ) {
        val contextForLog =
            requestBody.messages.lastOrNull { it.role == "user" }?.content?.take(30) ?: "N/A"
        cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true)

        // 创建新的AI消息占位符 (currentWebSearchStage 默认为 null)
        val newAiMessage = Message(text = "", sender = Sender.AI)
        val aiMessageId = newAiMessage.id

        // 清空累积构造器，为新消息做准备
        currentTextBuilder.clear()
        currentReasoningBuilder.clear()

        // 立即在主线程更新UI状态
        viewModelScope.launch(Dispatchers.Main.immediate) {
            var insertAtIndex = stateHolder.messages.size
            if (afterUserMessageId != null) {
                val userMessageIndex =
                    stateHolder.messages.indexOfFirst { it.id == afterUserMessageId }
                if (userMessageIndex != -1) {
                    insertAtIndex = userMessageIndex + 1
                } else {
                    Log.w(
                        TAG_API_HANDLER,
                        "afterUserMessageId $afterUserMessageId 未找到，AI消息将添加到列表末尾。"
                    )
                }
            }
            insertAtIndex = insertAtIndex.coerceAtMost(stateHolder.messages.size)
            stateHolder.messages.add(insertAtIndex, newAiMessage)
            Log.d(
                TAG_API_HANDLER,
                "新的AI消息 (ID: $aiMessageId) 添加到索引 $insertAtIndex. 列表大小: ${stateHolder.messages.size}."
            )

            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap[aiMessageId] = false
            // stateHolder.expandedReasoningStates.remove(aiMessageId)
            // stateHolder.messageAnimationStates.remove(aiMessageId)
            onMessagesProcessed()
        }

        // 启动协程处理网络请求和流式数据
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
                        val isThisJobStillTheOne = stateHolder.apiJob == thisJob
                        Log.d(
                            TAG_API_HANDLER,
                            "流 onCompletion. Cause: $cause, TargetMsgId: $targetMsgId, isThisJobStillTheOne: $isThisJobStillTheOne"
                        )

                        if (isThisJobStillTheOne) {
                            stateHolder._isApiCalling.value = false
                            if (stateHolder._currentStreamingAiMessageId.value == targetMsgId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }

                        if (stateHolder.reasoningCompleteMap[targetMsgId] != true) {
                            stateHolder.reasoningCompleteMap[targetMsgId] = true
                            Log.d(
                                TAG_API_HANDLER,
                                "MsgID: $targetMsgId, reasoningCompleteMap 在 onCompletion 中设置为 true (流结束)"
                            )
                        }

                        currentTextBuilder.clear()
                        currentReasoningBuilder.clear()

                        val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                        if (finalIdx != -1) {
                            val msg = stateHolder.messages[finalIdx]
                            val hasMeaningfulContentOrState = msg.text.isNotBlank() ||
                                    !msg.reasoning.isNullOrBlank() ||
                                    !msg.webSearchResults.isNullOrEmpty() ||
                                    (!msg.currentWebSearchStage.isNullOrEmpty() && msg.currentWebSearchStage != "error_occurred")

                            if (cause == null && !msg.isError) {
                                Log.d(
                                    TAG_API_HANDLER,
                                    "流正常完成，最终消息 $targetMsgId: Text='${msg.text.take(30)}', Stage='${msg.currentWebSearchStage}', ContentStarted=${msg.contentStarted}"
                                )
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
                                historyManager.saveCurrentChatToHistoryIfNeeded()
                            } else if (cause != null && cause !is CancellationException && !msg.isError) {
                                Log.d(TAG_API_HANDLER, "流因错误完成，更新消息 $targetMsgId")
                                updateMessageWithError(targetMsgId, cause)
                            } else if (cause is CancellationException) {
                                Log.d(
                                    TAG_API_HANDLER,
                                    "流被取消 (onCompletion)，消息 $targetMsgId。当前文本: '${
                                        msg.text.take(30)
                                    }', Stage='${msg.currentWebSearchStage}'"
                                )
                                val updatedMsg = msg.copy(
                                    text = msg.text.trim(),
                                    reasoning = msg.reasoning?.trim()
                                        .let { if (it.isNullOrBlank()) null else it },
                                    contentStarted = msg.contentStarted || msg.text.isNotBlank()
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] = updatedMsg
                                }
                                if (hasMeaningfulContentOrState) {
                                    if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                        stateHolder.messageAnimationStates[targetMsgId] = true
                                    }
                                    historyManager.saveCurrentChatToHistoryIfNeeded()
                                } else if (msg.sender == Sender.AI && !msg.isError) {
                                    Log.d(
                                        TAG_API_HANDLER,
                                        "AI消息 $targetMsgId 在取消时无任何内容或阶段，将其移除。"
                                    )
                                    stateHolder.messages.removeAt(finalIdx)
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
                            Log.w(
                                TAG_API_HANDLER,
                                "接收到过时或无效数据块（Job或ID已变），消息ID: $aiMessageId, 当前流ID: ${stateHolder._currentStreamingAiMessageId.value}"
                            )
                            thisJob?.cancel(CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                            return@collect
                        }

                        val currentChunkIndex =
                            stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                        if (currentChunkIndex != -1) {
                            processChunk(
                                currentChunkIndex,
                                appEvent,
                                aiMessageId
                            )
                        } else {
                            Log.e(
                                TAG_API_HANDLER,
                                "在collect中未找到消息ID $aiMessageId 进行块处理。列表大小: ${stateHolder.messages.size}"
                            )
                            thisJob?.cancel(CancellationException("目标消息 $aiMessageId 在收集中途消失"))
                            return@collect
                        }
                    }
            } catch (e: Exception) {
                currentTextBuilder.clear()
                currentReasoningBuilder.clear()
                when (e) {
                    is CancellationException -> {
                        Log.d(
                            TAG_API_HANDLER,
                            "流处理协程 $aiMessageId 被取消 (outer try-catch): ${e.message}"
                        )
                    }

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
                        Log.d(
                            TAG_API_HANDLER,
                            "在 finally 中重置了消息 $aiMessageId 的 API 调用状态。"
                        )
                    }
                }
                Log.d(
                    TAG_API_HANDLER,
                    "流处理协程 $aiMessageId (job: $thisJob) 完成/结束 (finally)。当前apiJob: ${stateHolder.apiJob}"
                )
            }
        }
    }

    /**
     * 处理单个已解析的流式事件 (AppStreamEvent)，更新对应消息的内容。
     * @param index 消息在列表中的索引。
     * @param appEvent 从API收到的已解析为AppStreamEvent的数据块。
     * @param messageIdForLog 用于日志的消息ID。
     */
    private fun processChunk(
        index: Int,
        appEvent: AppStreamEvent,
        messageIdForLog: String
    ) {

        if (appEvent.type == "reasoning") {
            println("收到reasoning块: ${appEvent.text}")
        }
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            Log.e(
                TAG_API_HANDLER_CHUNK,
                "索引或ID无效或过时。索引: $index, 列表ID: ${stateHolder.messages.getOrNull(index)?.id}, 期望ID: $messageIdForLog."
            )
            return
        }

        val originalMessage = stateHolder.messages[index]

        var newText = originalMessage.text
        var newReasoning = originalMessage.reasoning
        var newContentStarted = originalMessage.contentStarted
        var newWebResults = originalMessage.webSearchResults
        var newWebSearchStage = originalMessage.currentWebSearchStage

        when (appEvent.type) {
            "status_update" -> {
                if (appEvent.stage != null && newWebSearchStage != appEvent.stage) {
                    newWebSearchStage = appEvent.stage
                    Log.i(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: ${messageIdForLog.take(8)}, 网页搜索阶段更新为: ${appEvent.stage}"
                    )
                }
            }

            "web_search_results" -> {
                if (appEvent.results != null && newWebResults == null) {
                    if (appEvent.results.isNotEmpty()) {
                        newWebResults = appEvent.results
                        Log.i(
                            TAG_API_HANDLER_CHUNK,
                            "MsgID: ${messageIdForLog.take(8)}, 收到网页搜索结果: ${newWebResults?.size} 项"
                        )
                    }
                }
            }

            "reasoning" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    currentReasoningBuilder.append(appEvent.text)
                    Log.d(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: ${messageIdForLog.take(8)}, 追加思考过程: '${appEvent.text.take(30)}'"
                    )
                    if (stateHolder.reasoningCompleteMap[messageIdForLog] != false) {
                        stateHolder.reasoningCompleteMap[messageIdForLog] = false
                        Log.d(
                            TAG_API_HANDLER_CHUNK,
                            "MsgID: $messageIdForLog, reasoningCompleteMap 设置为 false (收到reasoning事件)"
                        )
                    }
                }
            }

            "content" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    val incoming = appEvent.text

                    // 找到当前累积内容和新来的内容的最长公共前缀
                    val prev = currentTextBuilder.toString()
                    val newSuffix = if (incoming.startsWith(prev)) {
                        // 完全追加后缀增量
                        incoming.removePrefix(prev)
                    } else if (prev.startsWith(incoming)) {
                        // 连续来重复（如缩短）不处理
                        ""
                    } else {
                        // 极大概率是全量内容，直接覆写
                        val diffIndex = prev.zip(incoming).indexOfFirst { (a, b) -> a != b }
                        if (diffIndex > 0) {
                            incoming.substring(diffIndex)
                        } else incoming
                    }
                    if (newSuffix.isNotEmpty()) {
                        currentTextBuilder.append(newSuffix)
                    }

                    // 仅当第一次有text时才设置 contentStarted
                    if (!newContentStarted && currentTextBuilder.isNotEmpty()) {
                        newContentStarted = true
                        Log.i(
                            TAG_API_HANDLER_CHUNK,
                            "MsgID: ${messageIdForLog.take(8)}, 'contentStarted' 因 'content' 事件而变为 true"
                        )
                        if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                            stateHolder.reasoningCompleteMap[messageIdForLog] = true
                            Log.d(
                                TAG_API_HANDLER_CHUNK,
                                "MsgID: $messageIdForLog, reasoningCompleteMap 设置为 true (收到首个content事件)"
                            )
                        }
                    }
                }
            }


            "tool_calls_chunk", "google_function_call_request" -> {
                Log.d(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 收到工具调用类型: ${appEvent.type}, 是否思考步骤: ${appEvent.isReasoningStep}"
                )
                // 如需将工具请求流到 reasoning，可加逻辑。默认前端可忽略。
            }

            "finish" -> {
                Log.i(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 收到 'finish' 事件, 原因: ${appEvent.reason}"
                )
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                    Log.d(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: $messageIdForLog, reasoningCompleteMap 因 'finish' 事件设置为 true"
                    )
                }
            }

            "error" -> {
                Log.e(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 收到流内错误事件: ${appEvent.message}, 上游状态: ${appEvent.upstreamStatus}"
                )
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                    Log.d(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: $messageIdForLog, reasoningCompleteMap 因流内 'error' 事件设置为 true"
                    )
                }
            }

            else -> {
                Log.w(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 未知或未处理的事件类型: ${appEvent.type}"
                )
            }
        }

        val accumulatedText = currentTextBuilder.toString()
        if (newText != accumulatedText) {
            newText = accumulatedText
        }

        val accumulatedReasoning = currentReasoningBuilder.toString()
        val finalAccumulatedReasoning =
            if (accumulatedReasoning.isNotEmpty()) accumulatedReasoning else null
        if (newReasoning != finalAccumulatedReasoning) {
            newReasoning = finalAccumulatedReasoning
        }

        if (!newContentStarted && newText.isNotBlank() && (stateHolder.reasoningCompleteMap[messageIdForLog] == true)) {
            newContentStarted = true
            Log.i(
                TAG_API_HANDLER_CHUNK,
                "MsgID: ${messageIdForLog.take(8)}, 'contentStarted' 在最终检查时（有文本且思考完成）设为 true"
            )
        }

        if (newText != originalMessage.text ||
            newReasoning != originalMessage.reasoning ||
            newContentStarted != originalMessage.contentStarted ||
            newWebResults != originalMessage.webSearchResults ||
            newWebSearchStage != originalMessage.currentWebSearchStage
        ) {
            stateHolder.messages[index] = originalMessage.copy(
                text = newText,
                reasoning = newReasoning,
                contentStarted = newContentStarted,
                webSearchResults = newWebResults,
                currentWebSearchStage = newWebSearchStage
            )
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
                    val errorPrefix =
                        if (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || !msg.webSearchResults.isNullOrEmpty()) "\n\n" else ""
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
                        text = (msg.text.takeIf { it.isNotBlank() }
                            ?: msg.reasoning?.takeIf { it.isNotBlank() && msg.text.isBlank() }
                            ?: "") + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true,
                        reasoning = if (msg.text.isNotBlank()) msg.reasoning else null,
                        currentWebSearchStage = msg.currentWebSearchStage
                            ?: "error_occurred"
                    )
                    stateHolder.messages[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) {
                        stateHolder.messageAnimationStates[messageId] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                }
            }
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                Log.d(TAG_API_HANDLER, "错误处理后重置了消息 $messageId 的 API 调用状态。")
            }
        }
    }

    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "服务响应错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "服务响应错误 ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }

    private companion object {
        private const val TAG_API_HANDLER = "ApiHandler"
        private const val TAG_API_HANDLER_CANCEL = "ApiHandlerCancel"
        private const val TAG_API_HANDLER_CHUNK = "ApiHandlerChunk"
        private const val ERROR_VISUAL_PREFIX = "⚠️ "
    }
}

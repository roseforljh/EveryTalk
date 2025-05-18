package com.example.everytalk.StateControler // 您的包名

import android.util.Log
import com.example.everytalk.data.DataClass.Message // 这是UI层的Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.WebSearchResult // 确保 WebSearchResult 导入
import com.example.everytalk.data.DataClass.ChatRequest // 网络请求的 ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent // << 关键：使用新的事件数据类
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

// import java.util.UUID // Message ID 在 Message 数据类中生成

class ApiHandler(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val historyManager: HistoryManager
) {
    private val jsonParserForError = Json { ignoreUnknownKeys = true } // 用于解析特定错误格式

    @Serializable // 这个内部类保持不变，用于解析特定错误格式
    private data class BackendErrorContent(val message: String? = null, val code: Int? = null)

    // 用于累积当前流式AI消息的文本和思考过程
    private var currentTextBuilder = StringBuilder()
    private var currentReasoningBuilder = StringBuilder()

    /**
     * 取消当前正在进行的API调用作业。
     * @param reason 取消的原因，用于日志。
     * @param isNewMessageSend 是否因为发送新消息而触发的取消。
     */
    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob
        val messageIdBeingCancelled = stateHolder._currentStreamingAiMessageId.value
        Log.d(
            "ApiHandlerCancel",
            "请求取消API作业。原因: '$reason', 新消息触发: $isNewMessageSend, 取消的消息ID: $messageIdBeingCancelled"
        )

        stateHolder._isApiCalling.value = false
        stateHolder._currentStreamingAiMessageId.value = null

        currentTextBuilder.clear() // 清空累积的文本
        currentReasoningBuilder.clear() // 清空累积的思考过程

        if (messageIdBeingCancelled != null) {
            // 清理与被取消消息相关的状态
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            stateHolder.messageAnimationStates.remove(messageIdBeingCancelled)

            if (!isNewMessageSend) { // 如果不是因为新消息发送（此时新消息会取代旧的占位符）
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
                            msg.currentWebSearchStage.isNullOrEmpty() && // << 新增检查
                            !msg.contentStarted &&
                            !msg.isError
                        ) {
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
        stateHolder.apiJob = null // 清理作业引用
    }

    /**
     * 发起聊天请求并处理流式响应。
     */
    fun streamChatResponse(
        requestBody: ChatRequest,
        @Suppress("UNUSED_PARAMETER") // userMessageTextForContext 目前仅用于日志
        userMessageTextForContext: String,
        afterUserMessageId: String?,
        onMessagesProcessed: () -> Unit
    ) {
        val contextForLog =
            requestBody.messages.lastOrNull { it.role == "user" }?.content?.take(30) ?: "N/A"
        cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true)

        // 确保状态已重置
        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "强制重置API状态以开始新的流。")
            // (之前已有相关重置逻辑，此处不再重复，cancelCurrentApiJob已处理)
        }

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
                        "ApiHandler",
                        "afterUserMessageId $afterUserMessageId 未找到，AI消息将添加到列表末尾。"
                    )
                }
            }
            insertAtIndex = insertAtIndex.coerceAtMost(stateHolder.messages.size) // 确保索引有效
            stateHolder.messages.add(insertAtIndex, newAiMessage) // 添加AI消息占位符
            Log.d(
                "ApiHandler",
                "新的AI消息 (ID: $aiMessageId) 添加到索引 $insertAtIndex. 列表大小: ${stateHolder.messages.size}."
            )

            // 更新API调用状态
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId) // 清理可能存在的旧状态
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            stateHolder.messageAnimationStates.remove(aiMessageId)
            onMessagesProcessed() // 执行回调 (例如保存历史记录)
        }

        // 启动协程处理网络请求和流式数据
        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key] // 获取当前协程的Job实例
            try {
                // ApiClient.streamChatResponse 现在返回 Flow<AppStreamEvent>
                ApiClient.streamChatResponse(requestBody)
                    .onStart { Log.d("ApiHandler", "流式传输开始，消息ID: $aiMessageId") }
                    .catch { e -> // 处理流中的异常
                        if (e !is java.util.concurrent.CancellationException && e !is kotlinx.coroutines.CancellationException) {
                            updateMessageWithError(aiMessageId, e) // 用错误信息更新消息UI
                        } else {
                            Log.d(
                                "ApiHandler",
                                "流式传输被取消 (catch)，消息ID: $aiMessageId. 原因: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause -> // 当流完成时
                        val targetMsgId = aiMessageId
                        val isThisJobStillTheOne = stateHolder.apiJob == thisJob
                        Log.d(
                            "ApiHandler",
                            "流 onCompletion. Cause: $cause, TargetMsgId: $targetMsgId, isThisJobStillTheOne: $isThisJobStillTheOne"
                        )

                        if (isThisJobStillTheOne) { // 如果当前VM的apiJob仍然是这个，则重置API调用状态
                            stateHolder._isApiCalling.value = false
                            if (stateHolder._currentStreamingAiMessageId.value == targetMsgId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }
                        // 清理文本构造器
                        currentTextBuilder.clear()
                        currentReasoningBuilder.clear()

                        // 查找并最终更新消息状态
                        val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                        if (finalIdx != -1) {
                            val msg = stateHolder.messages[finalIdx]
                            // 检查是否有任何实际内容（文本、思考、网页结果、或有效的搜索阶段）
                            val hasMeaningfulContentOrState = msg.text.isNotBlank() ||
                                    !msg.reasoning.isNullOrBlank() ||
                                    !msg.webSearchResults.isNullOrEmpty() ||
                                    (!msg.currentWebSearchStage.isNullOrEmpty() && msg.currentWebSearchStage != "error_occurred") // 排除错误阶段

                            if (cause == null && !msg.isError) { // 流正常完成且消息没有错误
                                Log.d(
                                    "ApiHandler",
                                    "流正常完成，最终消息 $targetMsgId: Text='${msg.text.take(30)}', Stage='${msg.currentWebSearchStage}', ContentStarted=${msg.contentStarted}"
                                )
                                // 确保文本和思考过程是trim后的，并且 contentStarted 状态正确
                                val updatedMsg = msg.copy(
                                    text = msg.text.trim(),
                                    reasoning = msg.reasoning?.trim()
                                        .let { if (it.isNullOrBlank()) null else it },
                                    contentStarted = msg.contentStarted || msg.text.isNotBlank() // 如果有文本，则视为内容已开始
                                )
                                if (updatedMsg != msg) { // 仅当消息实际改变时才更新列表中的对象
                                    stateHolder.messages[finalIdx] = updatedMsg
                                }
                                if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                    stateHolder.messageAnimationStates[targetMsgId] = true // 标记动画完成
                                }
                                historyManager.saveCurrentChatToHistoryIfNeeded() // 保存到历史记录
                            } else if (cause != null && cause !is java.util.concurrent.CancellationException && cause !is kotlinx.coroutines.CancellationException && !msg.isError) { // 流因错误完成
                                Log.d("ApiHandler", "流因错误完成，更新消息 $targetMsgId")
                                updateMessageWithError(
                                    targetMsgId,
                                    cause
                                ) // updateMessageWithError 内部会处理保存
                            } else if (cause is java.util.concurrent.CancellationException || cause is kotlinx.coroutines.CancellationException) { // 流被取消
                                Log.d(
                                    "ApiHandler",
                                    "流被取消 (onCompletion)，消息 $targetMsgId。当前文本: '${
                                        msg.text.take(30)
                                    }', Stage='${msg.currentWebSearchStage}'"
                                )
                                val updatedMsg = msg.copy( // 确保取消时状态也是最新的
                                    text = msg.text.trim(),
                                    reasoning = msg.reasoning?.trim()
                                        .let { if (it.isNullOrBlank()) null else it },
                                    contentStarted = msg.contentStarted || msg.text.isNotBlank()
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] = updatedMsg
                                }
                                // 如果取消时已有任何实质内容或有效阶段，则保存并标记动画完成
                                if (hasMeaningfulContentOrState) {
                                    if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                        stateHolder.messageAnimationStates[targetMsgId] = true
                                    }
                                    historyManager.saveCurrentChatToHistoryIfNeeded()
                                } else if (msg.sender == Sender.AI && !msg.isError) { // 如果是完全空的AI占位符 (基于hasMeaningfulContentOrState的判断)
                                    Log.d(
                                        "ApiHandler",
                                        "AI消息 $targetMsgId 在取消时无任何内容或阶段，将其移除。"
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
                    .collect { appEvent -> // << 现在接收的是 AppStreamEvent
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            Log.w(
                                "ApiHandler",
                                "接收到过时或无效数据块（Job或ID已变），消息ID: $aiMessageId, 当前流ID: ${stateHolder._currentStreamingAiMessageId.value}"
                            )
                            thisJob?.cancel(java.util.concurrent.CancellationException("API job 或 streaming ID 已更改，停止收集旧数据块"))
                            return@collect
                        }

                        val currentChunkIndex =
                            stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                        if (currentChunkIndex != -1) {
                            processChunk(
                                currentChunkIndex,
                                appEvent,
                                aiMessageId
                            ) // << 调用修改后的 processChunk
                        } else {
                            Log.e(
                                "ApiHandler",
                                "在collect中未找到消息ID $aiMessageId 进行块处理。列表大小: ${stateHolder.messages.size}"
                            )
                            thisJob?.cancel(java.util.concurrent.CancellationException("目标消息 $aiMessageId 在收集中途消失"))
                            return@collect
                        }
                    }
            } catch (e: Exception) {
                currentTextBuilder.clear() // 确保在各种异常情况下也清空
                currentReasoningBuilder.clear()
                when (e) {
                    is java.util.concurrent.CancellationException, is kotlinx.coroutines.CancellationException -> {
                        Log.d(
                            "ApiHandler",
                            "流处理协程 $aiMessageId 被取消 (outer try-catch): ${e.message}"
                        )
                    }

                    else -> {
                        Log.e(
                            "ApiHandler",
                            "流处理协程 $aiMessageId 发生未捕获的异常: ${e.message}",
                            e
                        )
                        updateMessageWithError(aiMessageId, e) // 用错误信息更新UI
                    }
                }
            } finally {
                // 无论协程如何结束（正常、异常、取消），都会执行
                if (stateHolder.apiJob == thisJob) { // 清理当前 job 相关的状态
                    stateHolder.apiJob = null // 清理作业引用
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
    ) { // << 参数类型已更改为 AppStreamEvent
        // 安全检查
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            Log.e(
                "ApiHandlerProcessChunk",
                "索引或ID无效或过时。索引: $index, 列表ID: ${stateHolder.messages.getOrNull(index)?.id}, 期望ID: $messageIdForLog."
            )
            return
        }

        val originalMessage = stateHolder.messages[index]
        // Log.d("ApiHandlerProcessChunk", "MsgID: ${messageIdForLog.take(8)}, 处理事件: $appEvent") // appEvent 可能包含很多信息，选择性打印

        // 用于构建/更新消息的临时变量，基于原始消息初始化
        // 这些变量将收集本次 chunk 带来的变化 + 之前累积的变化
        var newText = originalMessage.text
        var newReasoning = originalMessage.reasoning
        var newContentStarted = originalMessage.contentStarted
        var newWebResults = originalMessage.webSearchResults
        var newWebSearchStage = originalMessage.currentWebSearchStage

        when (appEvent.type) {
            "status_update" -> {
                if (appEvent.stage != null && newWebSearchStage != appEvent.stage) {
                    newWebSearchStage = appEvent.stage // 更新阶段信息
                    Log.i(
                        "ApiHandlerProcessChunk",
                        "MsgID: ${messageIdForLog.take(8)}, 网页搜索阶段更新为: ${appEvent.stage}"
                    )
                }
            }

            "web_search_results" -> {
                // appEvent.results 已经是 List<WebSearchResult>? 类型 (来自 AppStreamEvent 定义)
                if (appEvent.results != null && newWebResults == null) { // 只设置一次，且不为空
                    if (appEvent.results.isNotEmpty()) {
                        newWebResults = appEvent.results
                        Log.i(
                            "ApiHandlerProcessChunk",
                            "MsgID: ${messageIdForLog.take(8)}, 收到网页搜索结果: ${newWebResults?.size} 项"
                        )
                    }
                }
            }

            "content" -> {
                if (!appEvent.text.isNullOrEmpty()) {
                    currentTextBuilder.append(appEvent.text) // 向 StringBuilder 追加
                    // newText 将在下方从 currentTextBuilder 更新
                    if (!newContentStarted && currentTextBuilder.length > 0) { // 如果主要文本开始出现
                        newContentStarted = true
                        Log.i(
                            "ApiHandlerProcessChunk",
                            "MsgID: ${messageIdForLog.take(8)}, 'contentStarted' 因文本块而变为 true"
                        )
                    }
                }
            }

            "reasoning" -> {
                if (!appEvent.text.isNullOrEmpty()) { // 假设reasoning也通过 AppStreamEvent.text 传递
                    currentReasoningBuilder.append(appEvent.text) //向 StringBuilder 追加
                    // newReasoning 将在下方从 currentReasoningBuilder 更新
                    Log.d(
                        "ApiHandlerProcessChunk",
                        "MsgID: ${messageIdForLog.take(8)}, 追加思考过程: '${appEvent.text.take(30)}'"
                    )

                    // 更新 reasoningCompleteMap (用于控制MessageBubble中临时思考过程UI的显隐)
                    val msgId = messageIdForLog // 使用当前消息ID
                    if (stateHolder.reasoningCompleteMap[msgId] != false) { // 只要不是明确的false (null或true)
                        stateHolder.reasoningCompleteMap[msgId] = false // 收到reasoning，说明未完成
                        Log.d(
                            "ApiHandlerProcessChunk",
                            "MsgID: $msgId, reasoningCompleteMap 设置为 false (收到reasoning)"
                        )
                    }
                }
            }

            "tool_calls_chunk" -> {
                Log.d(
                    "ApiHandlerProcessChunk",
                    "MsgID: ${messageIdForLog.take(8)}, 收到工具调用块 (tool_calls_chunk): ${appEvent.toolCallsData}"
                )
                // TODO: 处理 appEvent.toolCallsData (List<OpenAiToolCall>?)
                // 你可能需要将这些工具调用信息累积或更新到 Message 对象的一个新字段中
                // 例如: var toolCallRequests: MutableList<OpenAiToolCallRequest> = mutableListOf()
            }

            "google_function_call_request" -> {
                Log.d(
                    "ApiHandlerProcessChunk",
                    "MsgID: ${messageIdForLog.take(8)}, 收到Google函数调用请求: name=${appEvent.name}, id=${appEvent.id}"
                )
                // TODO: 处理 appEvent.id, appEvent.name, appEvent.argumentsObj
            }

            "finish" -> {
                Log.i(
                    "ApiHandlerProcessChunk",
                    "MsgID: ${messageIdForLog.take(8)}, 收到 'finish' 事件, 原因: ${appEvent.reason}"
                )
                if (appEvent.reason != null) {
                    val msgId = messageIdForLog
                    if (stateHolder.reasoningCompleteMap[msgId] != true) {
                        stateHolder.reasoningCompleteMap[msgId] = true // 流结束，思考过程标记为完成
                        Log.d(
                            "ApiHandlerProcessChunk",
                            "MsgID: $msgId, reasoningCompleteMap 设置为 true (收到finish)"
                        )
                    }
                    // 如果finish reason是 "tool_calls"，可能需要特殊处理，标记消息有待处理的工具调用
                    // if (appEvent.reason == "tool_calls") { /* ... */ }
                }
            }

            "error" -> {
                // 虽然外层有catch和onCompletion，但如果后端主动在流中发送error类型的事件
                Log.e(
                    "ApiHandlerProcessChunk",
                    "MsgID: ${messageIdForLog.take(8)}, 收到流内错误事件: ${appEvent.message}, 上游状态: ${appEvent.upstreamStatus}"
                )
                // 这里可以考虑是否需要立即将当前Message标记为错误并停止进一步累积文本/思考
                // 例如: newText = (originalMessage.text + "\n\n⚠️ " + (appEvent.message ?: "Stream error")).trim()
                //       newContentStarted = true
                //       viewModelScope.launch { updateMessageWithError(messageIdForLog, RuntimeException(appEvent.message ?: "Unknown stream error")) }
                // 但通常由最外层的catch和onCompletion统一处理错误状态会更简单。
                // 目前主要依赖外层错误处理，这里只做日志记录。
            }

            else -> {
                Log.w(
                    "ApiHandlerProcessChunk",
                    "MsgID: ${messageIdForLog.take(8)}, 未知或未处理的事件类型: ${appEvent.type}"
                )
            }
        }

        // 在所有when分支处理完毕后，从StringBuilder获取最新的累积文本和思考过程
        val accumulatedText = currentTextBuilder.toString()
        if (accumulatedText != newText) { // 仅当累积文本与进入when之前的newText不同时，才更新newText
            newText = accumulatedText
        }
        // 再次检查contentStarted，因为text可能刚被更新
        if (!newContentStarted && newText.isNotEmpty()) {
            newContentStarted = true
            Log.i(
                "ApiHandlerProcessChunk",
                "MsgID: ${messageIdForLog.take(8)}, 'contentStarted' 因累积文本在when后检查而最终变为 true"
            )
        }

        val accumulatedReasoning = currentReasoningBuilder.toString()
        val finalAccumulatedReasoning =
            if (accumulatedReasoning.isNotEmpty()) accumulatedReasoning else null
        // 仅当累积思考过程与进入when之前的newReasoning不同时，才更新newReasoning
        if (finalAccumulatedReasoning != newReasoning) {
            newReasoning = finalAccumulatedReasoning
        }

        // 只有当Message对象的任何受追踪的属性确实发生改变时，才更新列表中的对象
        if (newText != originalMessage.text ||
            newReasoning != originalMessage.reasoning ||
            newContentStarted != originalMessage.contentStarted ||
            newWebResults != originalMessage.webSearchResults || // WebSearchResult 需正确实现 equals
            newWebSearchStage != originalMessage.currentWebSearchStage
        ) {
            stateHolder.messages[index] = originalMessage.copy(
                text = newText,
                reasoning = newReasoning,
                contentStarted = newContentStarted,
                webSearchResults = newWebResults,
                currentWebSearchStage = newWebSearchStage
            )
            Log.d(
                "ApiHandlerProcessChunk",
                "MsgID: ${messageIdForLog.take(8)} 已应用更新. Stage: $newWebSearchStage, CS: $newContentStarted, TextLen: ${newText.length}, ReasonLen: ${newReasoning?.length ?: 0}, WebRes: ${newWebResults?.size ?: 0}"
            )
        }
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
                if (!msg.isError) { // 只在第一次出错时附加/设置错误信息
                    val errorPrefix =
                        if (msg.text.isNotBlank() || !msg.webSearchResults.isNullOrEmpty()) "\n\n" else ""
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
                    val errorMsg = msg.copy(
                        text = msg.text + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true, // 错误也算内容开始
                        reasoning = null, // 出错时清除思考过程
                        currentWebSearchStage = msg.currentWebSearchStage
                            ?: "error_occurred", // 可以选择保留或标记为错误阶段
                        webSearchResults = msg.webSearchResults // 保留已收到的结果
                    )
                    stateHolder.messages[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) {
                        stateHolder.messageAnimationStates[messageId] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) // 错误也是一种最终状态，应该保存
                }
            }
            // 如果出错的消息是当前正在流式处理的消息，则重置API调用状态
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                stateHolder.apiJob?.cancel(java.util.concurrent.CancellationException("流处理中发生错误，已更新UI: ${error.message}"))
                Log.d("ApiHandler", "错误处理后重置了消息 $messageId 的 API 调用状态。")
            }
        }
    }

    // parseBackendError 方法保持不变
    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "后端错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "后端服务错误 ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }

    private companion object {
        const val ERROR_VISUAL_PREFIX = "⚠️ "
    }
}
package com.example.everytalk.StateControler // 您的包名

import android.util.Log
import com.example.everytalk.data.DataClass.Message // UI层的Message
import com.example.everytalk.data.DataClass.Sender
// import com.example.everytalk.data.DataClass.WebSearchResult // WebSearchResult 已在 Message.kt 中定义或单独引入
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
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled) // 清理思考完成状态
            // stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled) // 如果有展开状态也应清理
            // stateHolder.messageAnimationStates.remove(messageIdBeingCancelled) // 动画状态

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
            ?.cancel(CancellationException(reason)) // 使用标准 CancellationException
        stateHolder.apiJob = null // 清理作业引用
    }

    /**
     * 发起聊天请求并处理流式响应。
     */
    fun streamChatResponse(
        requestBody: ChatRequest,
        @Suppress("UNUSED_PARAMETER") // userMessageTextForContext 目前仅用于日志
        userMessageTextForContext: String, // 用户原始输入文本，仅用于日志上下文
        afterUserMessageId: String?, // AI消息应在此用户消息ID之后插入
        onMessagesProcessed: () -> Unit // 消息列表更新后的回调 (例如，保存历史)
    ) {
        val contextForLog =
            requestBody.messages.lastOrNull { it.role == "user" }?.content?.take(30) ?: "N/A"
        cancelCurrentApiJob("开始新的流式传输，上下文: '$contextForLog'", isNewMessageSend = true)

        // 创建新的AI消息占位符 (currentWebSearchStage 默认为 null)
        val newAiMessage = Message(text = "", sender = Sender.AI) // 初始文本为空
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
            insertAtIndex = insertAtIndex.coerceAtMost(stateHolder.messages.size) // 确保索引有效
            stateHolder.messages.add(insertAtIndex, newAiMessage) // 添加AI消息占位符
            Log.d(
                TAG_API_HANDLER,
                "新的AI消息 (ID: $aiMessageId) 添加到索引 $insertAtIndex. 列表大小: ${stateHolder.messages.size}."
            )

            // 更新API调用状态
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap[aiMessageId] = false // 初始时，认为思考过程未完成
            // stateHolder.expandedReasoningStates.remove(aiMessageId) // 如果有展开状态，重置
            // stateHolder.messageAnimationStates.remove(aiMessageId) // 重置动画状态
            onMessagesProcessed() // 执行回调 (例如通知ViewModel保存历史记录)
        }

        // 启动协程处理网络请求和流式数据
        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job.Key] // 获取当前协程的Job实例
            try {
                ApiClient.streamChatResponse(requestBody)
                    .onStart { Log.d(TAG_API_HANDLER, "流式传输开始，消息ID: $aiMessageId") }
                    .catch { e -> // 处理流中的异常
                        if (e !is CancellationException) { // 排除主动取消的异常
                            updateMessageWithError(aiMessageId, e) // 用错误信息更新消息UI
                        } else {
                            Log.d(
                                TAG_API_HANDLER,
                                "流式传输被取消 (catch)，消息ID: $aiMessageId. 原因: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause -> // 当流完成时 (无论正常或异常)
                        val targetMsgId = aiMessageId
                        val isThisJobStillTheOne = stateHolder.apiJob == thisJob
                        Log.d(
                            TAG_API_HANDLER,
                            "流 onCompletion. Cause: $cause, TargetMsgId: $targetMsgId, isThisJobStillTheOne: $isThisJobStillTheOne"
                        )

                        if (isThisJobStillTheOne) { // 如果当前VM的apiJob仍然是这个，则重置API调用状态
                            stateHolder._isApiCalling.value = false
                            if (stateHolder._currentStreamingAiMessageId.value == targetMsgId) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }

                        // 无论流如何结束，都确保如果消息还在，并且其 reasoningCompleteMap 不是 true，则设为 true
                        // 因为流结束意味着思考过程（如果存在且未被 content 事件标记为完成）也必须结束了。
                        if (stateHolder.reasoningCompleteMap[targetMsgId] != true) {
                            stateHolder.reasoningCompleteMap[targetMsgId] = true
                            Log.d(
                                TAG_API_HANDLER,
                                "MsgID: $targetMsgId, reasoningCompleteMap 在 onCompletion 中设置为 true (流结束)"
                            )
                        }

                        // 清理文本构造器
                        currentTextBuilder.clear()
                        currentReasoningBuilder.clear()

                        // 查找并最终更新消息状态
                        val finalIdx = stateHolder.messages.indexOfFirst { it.id == targetMsgId }
                        if (finalIdx != -1) {
                            val msg = stateHolder.messages[finalIdx]
                            val hasMeaningfulContentOrState = msg.text.isNotBlank() ||
                                    !msg.reasoning.isNullOrBlank() ||
                                    !msg.webSearchResults.isNullOrEmpty() ||
                                    (!msg.currentWebSearchStage.isNullOrEmpty() && msg.currentWebSearchStage != "error_occurred")

                            if (cause == null && !msg.isError) { // 流正常完成且消息没有错误
                                Log.d(
                                    TAG_API_HANDLER,
                                    "流正常完成，最终消息 $targetMsgId: Text='${msg.text.take(30)}', Stage='${msg.currentWebSearchStage}', ContentStarted=${msg.contentStarted}"
                                )
                                val updatedMsg = msg.copy(
                                    text = msg.text.trim(), // 确保文本是trim过的
                                    reasoning = msg.reasoning?.trim()
                                        .let { if (it.isNullOrBlank()) null else it },
                                    contentStarted = msg.contentStarted || msg.text.isNotBlank() // 如果有文本，则视为内容已开始
                                )
                                if (updatedMsg != msg) {
                                    stateHolder.messages[finalIdx] = updatedMsg
                                }
                                if (stateHolder.messageAnimationStates[targetMsgId] != true) {
                                    stateHolder.messageAnimationStates[targetMsgId] = true
                                }
                                historyManager.saveCurrentChatToHistoryIfNeeded()
                            } else if (cause != null && cause !is CancellationException && !msg.isError) { // 流因错误完成
                                Log.d(TAG_API_HANDLER, "流因错误完成，更新消息 $targetMsgId")
                                updateMessageWithError(targetMsgId, cause)
                            } else if (cause is CancellationException) { // 流被取消
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
                    .collect { appEvent -> // 现在接收的是 AppStreamEvent
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
                            ) // 调用修改后的 processChunk
                        } else {
                            Log.e(
                                TAG_API_HANDLER,
                                "在collect中未找到消息ID $aiMessageId 进行块处理。列表大小: ${stateHolder.messages.size}"
                            )
                            thisJob?.cancel(CancellationException("目标消息 $aiMessageId 在收集中途消失"))
                            return@collect
                        }
                    }
            } catch (e: Exception) { // 捕获协程启动或流收集过程中的其他异常
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
                // 无论协程如何结束（正常、异常、取消），都会执行
                if (stateHolder.apiJob == thisJob) { // 清理当前 job 相关的状态
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
        // 安全检查
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != messageIdForLog) {
            Log.e(
                TAG_API_HANDLER_CHUNK,
                "索引或ID无效或过时。索引: $index, 列表ID: ${stateHolder.messages.getOrNull(index)?.id}, 期望ID: $messageIdForLog."
            )
            return
        }

        val originalMessage = stateHolder.messages[index]

        var newText = originalMessage.text // 这是 Message.text，用于最终答案
        var newReasoning = originalMessage.reasoning // 这是 Message.reasoning，用于思考过程
        var newContentStarted = originalMessage.contentStarted
        var newWebResults = originalMessage.webSearchResults
        var newWebSearchStage = originalMessage.currentWebSearchStage

        when (appEvent.type) {
            "status_update" -> { // 处理状态更新事件
                if (appEvent.stage != null && newWebSearchStage != appEvent.stage) {
                    newWebSearchStage = appEvent.stage
                    Log.i(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: ${messageIdForLog.take(8)}, 网页搜索阶段更新为: ${appEvent.stage}"
                    )
                }
            }

            "web_search_results" -> { // 处理网页搜索结果事件
                if (appEvent.results != null && newWebResults == null) { // 只设置一次，且不为空
                    if (appEvent.results.isNotEmpty()) {
                        newWebResults = appEvent.results
                        Log.i(
                            TAG_API_HANDLER_CHUNK,
                            "MsgID: ${messageIdForLog.take(8)}, 收到网页搜索结果: ${newWebResults?.size} 项"
                        )
                    }
                }
            }

            "reasoning" -> { // 处理思考过程文本事件
                if (!appEvent.text.isNullOrEmpty()) {
                    currentReasoningBuilder.append(appEvent.text) // 追加到思考过程构造器
                    Log.d(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: ${messageIdForLog.take(8)}, 追加思考过程: '${appEvent.text.take(30)}'"
                    )
                    // 收到 "reasoning" 事件，表示思考过程正在进行或尚未完成
                    if (stateHolder.reasoningCompleteMap[messageIdForLog] != false) {
                        stateHolder.reasoningCompleteMap[messageIdForLog] = false
                        Log.d(
                            TAG_API_HANDLER_CHUNK,
                            "MsgID: $messageIdForLog, reasoningCompleteMap 设置为 false (收到reasoning事件)"
                        )
                    }
                }
            }

            "content" -> { // 处理最终答案文本事件
                if (!appEvent.text.isNullOrEmpty()) {
                    currentTextBuilder.append(appEvent.text) // 追加到最终答案构造器
                    if (!newContentStarted) { // 如果主要内容（最终答案）第一次开始出现
                        newContentStarted = true
                        Log.i(
                            TAG_API_HANDLER_CHUNK,
                            "MsgID: ${messageIdForLog.take(8)}, 'contentStarted' 因 'content' 事件而变为 true"
                        )
                        // 当第一个 'content' 事件到达时，意味着思考过程（如果有的话）已经结束
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

            "tool_calls_chunk", "google_function_call_request" -> { // 处理工具调用事件
                Log.d(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 收到工具调用类型: ${appEvent.type}, 是否思考步骤: ${appEvent.isReasoningStep}"
                )
                // TODO: 根据 appEvent.isReasoningStep 和具体工具调用信息，决定如何更新UI或累积数据。
                // 例如，可以将工具调用请求或其初步指示附加到思考过程或主文本中。
                // 当前后端逻辑主要是在文本流中处理工具调用，前端可能主要消费文本结果。
                // 如果工具调用发生在思考阶段 (isReasoningStep = true)，并且您想在UI中特别展示这一点，
                // 可能需要将相关信息（如“正在使用工具X...”）追加到 currentReasoningBuilder。
                // 如果是最终答案阶段的工具调用，则追加到 currentTextBuilder。
                // 简单的做法是，如果工具调用本身产生了可见文本（由LLM生成），LLM会将其包含在"reasoning"或"content"流中。
            }

            "finish" -> { // 处理流结束事件
                Log.i(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 收到 'finish' 事件, 原因: ${appEvent.reason}"
                )
                // 确保思考过程被标记为完成
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) {
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                    Log.d(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: $messageIdForLog, reasoningCompleteMap 因 'finish' 事件设置为 true"
                    )
                }
                // 如果结束原因是 "tool_calls"，并且这些调用是作为最终步骤，则 contentStarted 可能也应为 true（如果之前没有 content）。
                // 但通常 "tool_calls" 之后会有包含工具结果的 "assistant" 或 "tool" 角色消息，或最终的文本回复。
            }

            "error" -> { // 处理流内错误事件
                Log.e(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 收到流内错误事件: ${appEvent.message}, 上游状态: ${appEvent.upstreamStatus}"
                )
                if (stateHolder.reasoningCompleteMap[messageIdForLog] != true) { // 错误也应终止思考过程
                    stateHolder.reasoningCompleteMap[messageIdForLog] = true
                    Log.d(
                        TAG_API_HANDLER_CHUNK,
                        "MsgID: $messageIdForLog, reasoningCompleteMap 因流内 'error' 事件设置为 true"
                    )
                }
                // 可以在这里触发 updateMessageWithError，或者依赖外层的 catch 和 onCompletion 统一处理。
                // 为了避免重复处理，通常外层统一处理更佳。但如果流内错误需要立即停止文本累积，则可以在此处理。
                // 假设外层会处理，这里主要记录日志。
            }

            else -> {
                Log.w(
                    TAG_API_HANDLER_CHUNK,
                    "MsgID: ${messageIdForLog.take(8)}, 未知或未处理的事件类型: ${appEvent.type}"
                )
            }
        }

        // 从StringBuilder获取最新的累积文本和思考过程
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

        // 再次检查 contentStarted，如果主文本非空但 contentStarted 仍为 false (例如，只有 reasoning 后直接 finish，没有 content 事件)
        // 这种情况下，如果 reasoning 已完成，且主文本非空，则可以认为主文本就是内容。
        if (!newContentStarted && newText.isNotBlank() && (stateHolder.reasoningCompleteMap[messageIdForLog] == true)) {
            newContentStarted = true
            Log.i(
                TAG_API_HANDLER_CHUNK,
                "MsgID: ${messageIdForLog.take(8)}, 'contentStarted' 在最终检查时（有文本且思考完成）设为 true"
            )
        }


        // 只有当Message对象的任何受追踪的属性确实发生改变时，才更新列表中的对象
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
            // （日志可以保持或调整）
        }
    }

    // updateMessageWithError 方法保持不变 (确保注释是中文)
    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        Log.e(
            TAG_API_HANDLER,
            "updateMessageWithError 为消息 $messageId, 错误: ${error.message}",
            error
        )
        currentTextBuilder.clear() // 清空构造器
        currentReasoningBuilder.clear() // 清空构造器

        withContext(Dispatchers.Main.immediate) {
            val idx = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = stateHolder.messages[idx]
                if (!msg.isError) { // 只在第一次出错时附加/设置错误信息
                    val errorPrefix =
                        if (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || !msg.webSearchResults.isNullOrEmpty()) "\n\n" else "" // 如果已有内容，则加换行
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
                            ?: "") + errorPrefix + errorTextContent, // 将错误信息附加到现有文本（或思考过程，如果文本为空）
                        isError = true,
                        contentStarted = true, // 错误消息也视为内容已开始，以便显示
                        reasoning = if (msg.text.isNotBlank()) msg.reasoning else null, // 如果错误附加到主文本，保留思考过程；否则清除
                        currentWebSearchStage = msg.currentWebSearchStage
                            ?: "error_occurred" // 可以标记错误阶段
                    )
                    stateHolder.messages[idx] = errorMsg
                    if (stateHolder.messageAnimationStates[messageId] != true) {
                        stateHolder.messageAnimationStates[messageId] = true // 标记动画完成
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) // 错误也是一种最终状态
                }
            }
            // 如果出错的消息是当前正在流式处理的消息，则重置API调用状态
            if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                // 不需要再次取消 stateHolder.apiJob，因为它会在外层的 catch/finally 中处理
                Log.d(TAG_API_HANDLER, "错误处理后重置了消息 $messageId 的 API 调用状态。")
            }
        }
    }

    // parseBackendError 方法保持不变 (确保注释是中文)
    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "服务响应错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) {
            "服务响应错误 ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}"
        }
    }

    private companion object { // 日志标签
        private const val TAG_API_HANDLER = "ApiHandler" // 主标签
        private const val TAG_API_HANDLER_CANCEL = "ApiHandlerCancel" // 取消操作日志
        private const val TAG_API_HANDLER_CHUNK = "ApiHandlerChunk" // 块处理日志
        private const val ERROR_VISUAL_PREFIX = "⚠️ " // 错误消息的视觉前缀
    }
}
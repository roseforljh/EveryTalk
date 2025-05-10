package com.example.app1.ui.screens

import android.util.Log
import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.ApiMessage
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.data.network.ApiClient
import com.example.app1.ui.screens.viewmodel.HistoryManager
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
    private val stateHolder: ViewModelStateHolder, // ViewModel 状态持有者
    private val viewModelScope: CoroutineScope,    // ViewModel 的协程作用域
    private val historyManager: HistoryManager     // 历史记录管理器
) {
    // 用于解析错误信息的 JSON 解析器，忽略未知键
    private val jsonParserForError = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BackendErrorContent( // 后端错误内容的数据类
        val message: String? = null,
        val code: Int? = null
    )

    /**
     * 取消当前的 API 任务。
     * @param reason 取消的原因。
     * @param isNewMessageSend 是否因为发送新消息而取消（用于区分用户主动取消还是系统因新请求而取消）。
     */
    fun cancelCurrentApiJob(reason: String, isNewMessageSend: Boolean = false) {
        val jobToCancel = stateHolder.apiJob // 获取当前 API 任务
        val messageIdBeingCancelled =
            stateHolder._currentStreamingAiMessageId.value // 获取正在流式传输的 AI 消息 ID
        Log.d(
            "ApiHandlerCancel",
            "请求取消。原因: '$reason', 新消息: $isNewMessageSend, 消息ID: $messageIdBeingCancelled, 任务: $jobToCancel"
        )

        // 重置 API 调用状态
        if (stateHolder._isApiCalling.value) stateHolder._isApiCalling.value = false
        if (stateHolder._currentStreamingAiMessageId.value != null) stateHolder._currentStreamingAiMessageId.value =
            null

        if (messageIdBeingCancelled != null) {
            // 清理与被取消消息相关的状态
            stateHolder.reasoningCompleteMap.remove(messageIdBeingCancelled)
            stateHolder.expandedReasoningStates.remove(messageIdBeingCancelled)
            stateHolder.messageAnimationStates.remove(messageIdBeingCancelled)

            // 如果不是因为发送新消息（例如用户明确取消），则考虑移除占位符
            if (!isNewMessageSend) {
                viewModelScope.launch(Dispatchers.Main.immediate) { // 立即在主线程执行
                    val index =
                        stateHolder.messages.indexOfFirst { it.id == messageIdBeingCancelled }
                    if (index != -1) {
                        val msg = stateHolder.messages[index]
                        // 如果是 AI 发送的空占位符消息，则移除
                        if (msg.sender == Sender.AI && msg.text.isBlank() && msg.reasoning.isNullOrBlank() && !msg.contentStarted && !msg.isError) {
                            Log.d(
                                "ApiHandlerCancel",
                                "正在移除索引 $index 处的 AI 占位符, ID: $messageIdBeingCancelled (原因: $reason)"
                            )
                            stateHolder.messages.removeAt(index)
                        } else {
                            Log.d(
                                "ApiHandlerCancel",
                                "消息 $messageIdBeingCancelled (索引 $index) 不是空的占位符，不移除。"
                            )
                        }
                    } else {
                        Log.w(
                            "ApiHandlerCancel",
                            "取消后未找到消息 $messageIdBeingCancelled 以便进行可能的移除。"
                        )
                    }
                }
            }
        }
        // 如果任务存在且活跃，则取消它
        if (jobToCancel != null && jobToCancel.isActive) {
            Log.d("ApiHandlerCancel", "因原因 '$reason' 取消任务 $jobToCancel")
            jobToCancel.cancel(CoroutineCancellationException(reason))
        } else {
            Log.d(
                "ApiHandlerCancel",
                "没有活动的任务可以取消，或者任务已因原因 '$reason' 被取消。"
            )
        }
    }

    /**
     * 流式传输聊天响应。
     * @param userMessageTextForContext 用于上下文的用户消息文本（触发 AI 响应的文本）。
     * @param onMessagesProcessed 消息处理完成后的回调（例如，UI 滚动）。
     */
    fun streamChatResponse(
        userMessageTextForContext: String,
        onMessagesProcessed: () -> Unit
    ) {
        val currentConfig = stateHolder._selectedApiConfig.value // 获取当前选中的 API 配置
            ?: run { // 如果配置为空
                viewModelScope.launch { stateHolder._snackbarMessage.emit("请先选择或添加一个 API 配置") }
                return
            }

        // 总是将此视为“新消息发送”以进行取消，因为它会启动新的 AI 流。
        cancelCurrentApiJob(
            "为上下文 '${userMessageTextForContext.take(30)}' 启动新流",
            isNewMessageSend = true
        )

        // 如果 API 状态可能过时，强制重置后再进行新调用
        if (stateHolder._isApiCalling.value || stateHolder.apiJob != null || stateHolder._currentStreamingAiMessageId.value != null) {
            Log.w("ApiHandler", "API 状态可能过时。在新的调用前强制重置。")
            stateHolder._isApiCalling.value = false
            stateHolder.apiJob?.cancel(CoroutineCancellationException("为新流强制重置"))
            stateHolder.apiJob = null
            stateHolder._currentStreamingAiMessageId.value = null
        }

        // 创建新的 AI 消息占位符
        val newAiMessage = Message(
            id = UUID.randomUUID().toString(),
            text = "",
            sender = Sender.AI,
            reasoning = null,
            contentStarted = false,
            isError = false,
            timestamp = System.currentTimeMillis()
        )
        val aiMessageId = newAiMessage.id
        val aiMessageInsertionIndex = 0 // 总是添加到数据列表的头部（视觉上是底部）

        viewModelScope.launch(Dispatchers.Main.immediate) { // 立即在主线程执行
            // 添加 AI 消息到列表
            stateHolder.messages.add(aiMessageInsertionIndex, newAiMessage)
            Log.d(
                "ApiHandler",
                "新的 AI 消息 (ID: $aiMessageId) 已添加到索引 $aiMessageInsertionIndex (视觉底部)。列表大小: ${stateHolder.messages.size}."
            )

            // 更新 API 调用状态
            stateHolder._currentStreamingAiMessageId.value = aiMessageId
            stateHolder._isApiCalling.value = true
            stateHolder.reasoningCompleteMap.remove(aiMessageId)
            stateHolder.expandedReasoningStates.remove(aiMessageId)
            stateHolder.messageAnimationStates.remove(aiMessageId)

            onMessagesProcessed() // 触发 UI 回调 (例如，滚动)
        }

        // --- 历史消息构建 ---
        val messagesSnapshotForHistory = stateHolder.messages.toList() // 获取当前消息列表的快照
        val apiHistoryMessages = mutableListOf<ApiMessage>() // 用于 API 请求的历史消息列表
        var historyMessageCount = 0
        val maxHistoryMessages = 20 // 最大历史消息数量

        // 遍历快照，构建 API 历史消息
        for (msg in messagesSnapshotForHistory) {
            if (historyMessageCount >= maxHistoryMessages) break // 达到最大数量则停止
            if (msg.id == aiMessageId) continue // 跳过当前的 AI 占位符消息

            val apiMsg: ApiMessage? = when {
                // 用户消息，且文本不为空
                msg.sender == Sender.User && msg.text.isNotBlank() -> ApiMessage(
                    "user",
                    msg.text.trim()
                )
                // AI 消息，文本不为空或推理不为空，且不是错误消息
                msg.sender == Sender.AI && (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) && !msg.isError -> ApiMessage(
                    "assistant",
                    msg.text.trim()
                )

                else -> null // 其他情况不作为历史消息
            }
            apiMsg?.let {
                apiHistoryMessages.add(it)
                historyMessageCount++
            }
        }
        apiHistoryMessages.reverse() // API 通常期望历史按时间顺序排列（从旧到新）

        // 确保 userMessageTextForContext 是 API 历史中的最后一条用户消息
        val lastUserMessageInApiHistory = apiHistoryMessages.lastOrNull { it.role == "user" }
        if (apiHistoryMessages.isEmpty() || lastUserMessageInApiHistory?.content != userMessageTextForContext.trim()) {
            if (apiHistoryMessages.isNotEmpty() && apiHistoryMessages.last().role == "user") {
                // 如果最后一条是用户消息，但内容不同，则替换它
                apiHistoryMessages[apiHistoryMessages.lastIndex] =
                    ApiMessage("user", userMessageTextForContext.trim())
            } else {
                // 否则，添加新的用户消息
                apiHistoryMessages.add(ApiMessage("user", userMessageTextForContext.trim()))
            }
            // 如果历史消息超过最大数量，则从开头移除旧消息
            while (apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()) {
                apiHistoryMessages.removeAt(0)
            }
        }
        Log.d(
            "ApiHandler",
            "为 API 准备了 ${apiHistoryMessages.size} 条消息。最后的用户上下文: '${
                apiHistoryMessages.lastOrNull { it.role == "user" }?.content?.take(50) // 取前50个字符记录日志
            }'"
        )

        // 再次确认 API 配置
        val confirmedConfig: ApiConfig = stateHolder._selectedApiConfig.value ?: run {
            Log.e("ApiHandler", "选中的配置变为了 null。")
            viewModelScope.launch { stateHolder._snackbarMessage.emit("API 配置错误。") }
            viewModelScope.launch(Dispatchers.Main.immediate) { // 立即在主线程执行
                // 重置 API 状态并移除占位符
                stateHolder._isApiCalling.value = false
                stateHolder._currentStreamingAiMessageId.value = null
                val placeholderIndex = stateHolder.messages.indexOfFirst { it.id == aiMessageId }
                if (placeholderIndex != -1) {
                    stateHolder.messages.removeAt(placeholderIndex)
                    Log.w("ApiHandler", "因配置错误移除了 AI 消息 (ID: $aiMessageId)。")
                }
            }
            return
        }

        // 构建 API 请求体
        val requestBody = ChatRequest(
            messages = apiHistoryMessages,
            provider = if (confirmedConfig.provider.lowercase() == "google") "google" else "openai", // 根据配置选择提供商
            apiAddress = confirmedConfig.address,
            apiKey = confirmedConfig.key,
            model = confirmedConfig.model
        )

        // 启动 API 调用协程任务
        stateHolder.apiJob = viewModelScope.launch {
            val thisJob = coroutineContext[Job] // 获取当前协程任务的引用
            Log.d(
                "ApiHandler",
                "为 AI 消息 $aiMessageId 启动 API 调用任务 ${
                    thisJob?.toString()?.takeLast(5)
                }" // 日志记录任务的最后5位标识
            )
            try {
                ApiClient.streamChatResponse(requestBody) // 调用 API 客户端进行流式响应
                    .onStart { Log.d("ApiHandler", "流开始处理 $aiMessageId") } // 流开始时的回调
                    .catch { e -> // 捕获流中的异常
                        if (e !is CoroutineCancellationException) { // 如果不是协程取消异常
                            Log.e("ApiHandler", "流捕获到 $aiMessageId 的异常: ${e.message}", e)
                            launch(Dispatchers.Main.immediate) { // 立即在主线程更新 UI
                                updateMessageWithError(
                                    aiMessageId,
                                    e
                                )
                            }
                        } else { // 如果是协程取消异常
                            Log.d(
                                "ApiHandler",
                                "流捕获到 $aiMessageId 的取消异常: ${e.message}"
                            )
                        }
                    }
                    .onCompletion { cause -> // 流完成时的回调（无论成功、失败还是取消）
                        val targetMessageIdOnComplete = aiMessageId // 记录完成时对应的消息ID
                        Log.d(
                            "ApiHandler",
                            "流完成处理 $targetMessageIdOnComplete。原因: ${cause?.javaClass?.simpleName}" // 记录完成原因
                        )
                        launch(Dispatchers.Main.immediate) { // 立即在主线程执行
                            // 检查当前任务是否仍是活跃的 API 任务
                            val isThisTheActiveJobOnComplete =
                                stateHolder.apiJob == thisJob || stateHolder.apiJob == null
                            if (isThisTheActiveJobOnComplete) {
                                // 如果是活跃任务，则重置 API 调用状态
                                stateHolder._isApiCalling.value = false
                                stateHolder.apiJob = null
                                if (stateHolder._currentStreamingAiMessageId.value == targetMessageIdOnComplete) {
                                    stateHolder._currentStreamingAiMessageId.value = null
                                }
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "消息 $targetMessageIdOnComplete 的 onCompletion 被忽略，不是活动任务。"
                                )
                            }

                            // 处理消息的最终状态
                            val finalIndexOnComplete =
                                stateHolder.messages.indexOfFirst { it.id == targetMessageIdOnComplete }
                            if (finalIndexOnComplete != -1) {
                                var msg = stateHolder.messages[finalIndexOnComplete]
                                val animationCompleted =
                                    stateHolder.messageAnimationStates[targetMessageIdOnComplete]
                                        ?: false // 获取动画完成状态
                                val currentRawText = msg.text
                                val finalTextTrimmed = currentRawText.trim() // 去除文本前后空格

                                if (cause == null && !msg.isError) { // 如果没有错误且正常完成
                                    if (currentRawText != finalTextTrimmed) { // 如果文本有变化（例如去除了末尾空格）
                                        msg = msg.copy(text = finalTextTrimmed)
                                        stateHolder.messages[finalIndexOnComplete] = msg
                                    }
                                    if (!animationCompleted) stateHolder.messageAnimationStates[targetMessageIdOnComplete] =
                                        true // 标记动画完成
                                    historyManager.saveCurrentChatToHistoryIfNeeded() // 保存聊天记录
                                } else if (cause != null) { // 如果有异常或取消
                                    if (cause is CoroutineCancellationException) { // 如果是取消
                                        if (currentRawText != finalTextTrimmed) {
                                            msg = msg.copy(text = finalTextTrimmed)
                                            stateHolder.messages[finalIndexOnComplete] = msg
                                        }
                                        if (!animationCompleted) stateHolder.messageAnimationStates[targetMessageIdOnComplete] =
                                            true // 标记动画完成 (即使取消，也认为动画结束)
                                    } else if (!msg.isError) { // 如果是其他错误，且消息尚未标记为错误
                                        updateMessageWithError(
                                            targetMessageIdOnComplete,
                                            cause
                                        ) // 更新消息为错误状态
                                    }
                                }
                            } else {
                                Log.w(
                                    "ApiHandler",
                                    "在 onCompletion 中未找到消息 $targetMessageIdOnComplete。"
                                )
                            }
                            // 再次确保如果当前流式ID与完成的ID一致，则清空
                            if (stateHolder._currentStreamingAiMessageId.value == targetMessageIdOnComplete && isThisTheActiveJobOnComplete) {
                                stateHolder._currentStreamingAiMessageId.value = null
                            }
                        }
                    }
                    .collect { chunk: OpenAiStreamChunk -> // 收集流式响应的每个数据块
                        // 如果当前任务或流式消息 ID 已更改，则忽略此数据块
                        if (stateHolder.apiJob != thisJob || stateHolder._currentStreamingAiMessageId.value != aiMessageId) {
                            Log.w(
                                "ApiHandler",
                                "为 $aiMessageId 收集到的数据块被忽略，任务/ID 不匹配。"
                            )
                            return@collect // 停止处理此数据块
                        }
                        launch(Dispatchers.Main.immediate) { // 立即在主线程更新 UI
                            val messageIndexToUpdate =
                                stateHolder.messages.indexOfFirst { it.id == aiMessageId } // 找到要更新的消息索引
                            if (messageIndexToUpdate != -1) {
                                val messageToUpdate = stateHolder.messages[messageIndexToUpdate]
                                if (!messageToUpdate.isError) { // 如果消息不是错误状态
                                    processChunk( // 处理数据块
                                        messageIndexToUpdate,
                                        messageToUpdate,
                                        chunk,
                                        aiMessageId
                                    )
                                } else {
                                    Log.w(
                                        "ApiHandler",
                                        "收集: 消息 $aiMessageId (索引 $messageIndexToUpdate) 是错误状态。"
                                    )
                                }
                            } else {
                                Log.e("ApiHandler", "收集: 未找到消息 $aiMessageId。")
                            }
                        }
                    }
            } catch (e: CoroutineCancellationException) { // 捕获外部任务的取消异常
                Log.d(
                    "ApiHandler",
                    "外部任务 ${
                        thisJob?.toString()?.takeLast(5) // 日志记录任务的最后5位标识
                    } 因 $aiMessageId 被取消: ${e.message}"
                )
            } catch (e: Exception) { // 捕获外部任务的其他异常
                Log.e(
                    "ApiHandler",
                    "外部任务 ${
                        thisJob?.toString()?.takeLast(5) // 日志记录任务的最后5位标识
                    } 处理 $aiMessageId 时发生异常: ${e.message}",
                    e
                )
                launch(Dispatchers.Main.immediate) {
                    updateMessageWithError(
                        aiMessageId,
                        e
                    )
                } // 更新消息为错误状态
            } finally { // 外部任务的 finally 块
                Log.d(
                    "ApiHandler",
                    "外部任务 ${thisJob?.toString()?.takeLast(5)} 的 finally 块，处理 $aiMessageId。"
                )
                // 如果当前 API 任务是此任务，并且此任务已结束（非活跃、已取消或已完成）
                // 则手动清理 API 状态，以防 onCompletion 未正确触发或被覆盖
                if (stateHolder.apiJob == thisJob && (thisJob == null || !thisJob.isActive || thisJob.isCancelled || thisJob.isCompleted)) {
                    launch(Dispatchers.Main.immediate) {
                        Log.w(
                            "ApiHandler",
                            "在 finally 块中为任务 ${
                                thisJob?.toString()?.takeLast(5)
                            } ($aiMessageId) 手动清理 API 状态。"
                        )
                        stateHolder._isApiCalling.value = false
                        if (stateHolder._currentStreamingAiMessageId.value == aiMessageId) stateHolder._currentStreamingAiMessageId.value =
                            null
                        stateHolder.apiJob = null
                    }
                }
            }
        }
    }

    /**
     * 处理从 API 返回的单个数据块。
     * @param index 消息在列表中的索引。
     * @param currentMessage 当前的消息对象。
     * @param chunk API 返回的数据块。
     * @param messageIdForLog 用于日志记录的消息 ID。
     */
    private fun processChunk(
        index: Int,
        currentMessage: Message,
        chunk: OpenAiStreamChunk,
        messageIdForLog: String // 主要用于日志，确保在异步回调中我们知道在处理哪个消息
    ) {
        // 防御性检查：确保索引和消息 ID 仍然有效
        if (index < 0 || index >= stateHolder.messages.size || stateHolder.messages[index].id != currentMessage.id) {
            Log.e(
                "ApiHandler",
                "processChunk: 索引/ID 过时。索引: $index, 列表ID: ${
                    stateHolder.messages.getOrNull(index)?.id
                }, 期望ID: ${currentMessage.id}."
            )
            return
        }

        val choice = chunk.choices?.firstOrNull() // 获取第一个选项
        val delta = choice?.delta // 获取增量内容
        val chunkContent = delta?.content // 获取文本内容
        val chunkReasoning = delta?.reasoningContent // 获取推理内容
        var updatedText = currentMessage.text // 当前文本
        var updatedReasoning = currentMessage.reasoning ?: "" // 当前推理，如果为null则为空字符串
        var needsContentUpdate = false // 标记文本是否需要更新
        var needsReasoningUpdate = false // 标记推理是否需要更新

        // 如果数据块包含文本内容，则追加
        if (!chunkContent.isNullOrEmpty()) {
            updatedText += chunkContent
            needsContentUpdate = true
        }
        // 如果数据块包含推理内容，则追加
        if (!chunkReasoning.isNullOrEmpty()) {
            updatedReasoning += chunkReasoning
            needsReasoningUpdate = true
        }

        // 检查内容是否已开始（用于UI判断是否显示AI消息）
        var newContentStarted = currentMessage.contentStarted
        if (!newContentStarted && (updatedText.isNotBlank() || updatedReasoning.isNotBlank())) {
            newContentStarted = true
        }

        // 如果文本、推理或内容开始状态有任何更新
        if (needsContentUpdate || needsReasoningUpdate || newContentStarted != currentMessage.contentStarted) {
            val updatedMsg = currentMessage.copy(
                text = updatedText,
                // 如果推理为空，且不是因为本次chunk没有推理内容（即原本就没有推理），则保持为null
                reasoning = if (updatedReasoning.isEmpty() && !needsReasoningUpdate && currentMessage.reasoning == null) null else updatedReasoning,
                contentStarted = newContentStarted
            )
            stateHolder.messages[index] = updatedMsg // 更新消息列表中的消息

            // 更新推理完成状态的逻辑
            // 如果本次有内容更新，且推理内容不为空，并且之前推理已标记为完成，则重置为未完成（因为推理可能在继续）
            if (needsContentUpdate && !chunkReasoning.isNullOrEmpty() && stateHolder.reasoningCompleteMap[currentMessage.id] == true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = false
                // 如果本次有内容更新，但推理内容为空，且完成原因不是 "stop"，并且之前推理未标记为完成，则标记为完成（假设文本流开始后推理就结束了）
            } else if (needsContentUpdate && (chunkReasoning.isNullOrEmpty() && choice?.finishReason != "stop") && stateHolder.reasoningCompleteMap[currentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = true
            }
        }

        // 如果 API 返回的完成原因是 "stop"，且当前数据块没有推理内容，则标记推理已完成
        if (choice?.finishReason == "stop" && delta?.reasoningContent.isNullOrEmpty()) {
            if (stateHolder.reasoningCompleteMap[currentMessage.id] != true) {
                stateHolder.reasoningCompleteMap[currentMessage.id] = true
                Log.d(
                    "ApiHandler",
                    "因 finish_reason:stop 将消息 ${currentMessage.id} 的推理标记为完成"
                )
            }
        }
    }

    // 错误信息在UI上显示的前缀
    private companion object {
        const val ERROR_VISUAL_PREFIX = "⚠️ " // 使用 Emoji 作为视觉提示
    }

    /**
     * 使用错误信息更新指定 ID 的消息。
     * @param messageId 要更新的消息的 ID。
     * @param error 发生的 Throwable 错误。
     */
    private suspend fun updateMessageWithError(messageId: String, error: Throwable) {
        val idx = stateHolder.messages.indexOfFirst { it.id == messageId } // 查找消息索引
        if (idx != -1) {
            val msg = stateHolder.messages[idx]
            if (!msg.isError) { // 如果消息尚未标记为错误
                val errorPrefix = if (msg.text.isNotBlank()) "\n\n" else "" // 如果已有文本，则加换行符
                // 构建错误文本内容
                val errorTextContent = ERROR_VISUAL_PREFIX + when (error) {
                    is IOException -> "网络错误: ${error.message ?: "IO 错误"}"
                    is ResponseException -> parseBackendError( // 如果是 Ktor 的响应异常
                        error.response, try {
                            error.response.bodyAsText() // 尝试读取响应体作为文本
                        } catch (_: Exception) {
                            "(无法读取错误体)"
                        }
                    )

                    else -> "错误: ${error.message ?: "未知错误"}"
                }
                // 创建新的错误消息对象
                val errorMsg = msg.copy(
                    text = msg.text + errorPrefix + errorTextContent, // 追加错误信息
                    isError = true, contentStarted = true, reasoning = null // 标记为错误，内容已开始，清空推理
                )
                stateHolder.messages[idx] = errorMsg // 更新消息列表
                stateHolder.messageAnimationStates[messageId] = true // 标记动画完成（错误也算完成）
                Log.e(
                    "ApiHandler",
                    "已将索引 $idx 处的消ID息 $messageId 更新为错误状态: ${error.message}"
                )
            } else {
                Log.w("ApiHandler", "消息 $messageId 已经是错误状态。")
            }
        } else {
            Log.e("ApiHandler", "未能找到消息 $messageId 以更新错误状态。")
        }
        // 如果当前流式消息是出错的消息，并且 API 仍在调用中，则重置 API 状态
        if (stateHolder._currentStreamingAiMessageId.value == messageId && stateHolder._isApiCalling.value) {
            stateHolder._isApiCalling.value = false
            stateHolder._currentStreamingAiMessageId.value = null
        }
    }

    /**
     * 解析来自后端的错误响应。
     * @param response Ktor HTTP 响应对象。
     * @param errorBody 错误响应体字符串。
     * @return 解析后的错误消息字符串。
     */
    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        return try {
            // 尝试将错误体解析为 BackendErrorContent 数据类
            val errorJson = jsonParserForError.decodeFromString<BackendErrorContent>(errorBody)
            "后端错误: ${errorJson.message ?: response.status.description} (状态码: ${response.status.value}, 内部代码: ${errorJson.code ?: "N/A"})"
        } catch (e: Exception) { // 如果解析失败，则返回原始错误信息的一部分
            "后端错误 ${response.status.value}: ${errorBody.take(150)}${if (errorBody.length > 150) "..." else ""}" // 最多取150个字符
        }
    }
}